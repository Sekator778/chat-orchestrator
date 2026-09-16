package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.service.llm.conversation.ConversationFormatter;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import com.example.telegramuserbot.service.orchestration.dto.ResponseDirectives;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedList;
import java.util.List;

@Component
public class LlmMessageBuilder {

    private final PromptBuilder promptBuilder;
    private final ConversationFormatter conversationFormatter;
    private final TelegramSelfUserIdResolver selfUserIdResolver;
    private final PendingResponseCoordinator pendingResponseCoordinator;
    private final ReplyKnowledgeService replyKnowledgeService;

    public LlmMessageBuilder(PromptBuilder promptBuilder,
                             ConversationFormatter conversationFormatter,
                             TelegramSelfUserIdResolver selfUserIdResolver,
                             PendingResponseCoordinator pendingResponseCoordinator,
                             ReplyKnowledgeService replyKnowledgeService) {
        this.promptBuilder = promptBuilder;
        this.conversationFormatter = conversationFormatter;
        this.selfUserIdResolver = selfUserIdResolver;
        this.pendingResponseCoordinator = pendingResponseCoordinator;
        this.replyKnowledgeService = replyKnowledgeService;
    }

    /**
     * Builds LLM API messages (2-arg overload; delegates with null directives for byte-identical behavior).
     */
    public Mono<List<ApiMessage>> buildApiMessagesWithSystem(long chatId,
                                                             ContextCollector.ConversationContext context,
                                                             BotContextResolver.ResolvedConfig cfg,
                                                             String pipelineLabel) {
        return buildApiMessagesWithSystem(chatId, context, cfg, pipelineLabel, null);
    }

    /**
     * Builds LLM API messages, optionally applying shaping directives to the system prompt.
     * When directives is null, output is byte-identical to the 4-arg overload.
     *
     * @param directives nullable shaping directives from the decision engine
     */
    public Mono<List<ApiMessage>> buildApiMessagesWithSystem(long chatId,
                                                             ContextCollector.ConversationContext context,
                                                             BotContextResolver.ResolvedConfig cfg,
                                                             String pipelineLabel,
                                                             ResponseDirectives directives) {
        String botInstanceId = cfg != null ? cfg.botInstanceId() : null;
        Mono<Long> selfUserIdMono = selfUserIdResolver.resolveSelfUserId(botInstanceId).defaultIfEmpty(0L);

        Mono<List<PendingResponse>> pendingMono = pendingResponseCoordinator.loadPendingContext(chatId,
                context != null && context.triggeringMessage() != null ? context.triggeringMessage().getMessageId() : null,
                botInstanceId);

        ConversationFormatter.FormatResult fallback = conversationFormatter.format(
                context.contextMessages(),
                context.triggeringMessage(),
                botInstanceId,
                null
        );

        Mono<ConversationFormatter.FormatResult> conversationMono = selfUserIdMono
                .map(selfUserId -> selfUserId != null && selfUserId != 0L
                        ? conversationFormatter.format(
                        context.contextMessages(),
                        context.triggeringMessage(),
                        botInstanceId,
                        selfUserId
                )
                        : fallback)
                .onErrorReturn(fallback);

        String chatText = context != null && context.triggeringMessage() != null
                ? (context.triggeringMessage().getContent() != null && !context.triggeringMessage().getContent().isBlank()
                        ? context.triggeringMessage().getContent()
                        : context.triggeringMessage().getCaption())
                : null;
        Mono<String> knowledgeMono = replyKnowledgeService.buildKnowledgeBlock(chatText).defaultIfEmpty("");

        return Mono.zip(conversationMono, pendingMono, knowledgeMono)
                .map(tuple -> {
                    ConversationFormatter.FormatResult conversation = tuple.getT1();
                    List<PendingResponse> pending = tuple.getT2();
                    String knowledge = tuple.getT3();
                    LinkedList<ApiMessage> finalMessages = new LinkedList<>(conversation.messages());
                    LlmSpeakerContext speakers = conversation.speakerContext();
                    EnhancedPromptRequest promptRequest = EnhancedPromptRequest.builder()
                            .template(cfg.template())
                            .chatConfig(cfg.config())
                            .rateLimits(cfg.rateLimits())
                            .llmParameters(cfg.llmParameters())
                            .fallbackPrompt("Respond naturally with context.")
                            .fallbackLanguage(cfg.config() != null ? cfg.config().getLanguage() : "auto")
                            .speakerContext(speakers)
                            .pendingResponses(pending)
                            .directives(directives)
                            .knowledgeBlock(knowledge.isBlank() ? null : knowledge)
                            .build();
                    finalMessages.addFirst(new ApiMessage("system", promptBuilder.buildEnhancedPrompt(promptRequest)));
                    return finalMessages;
                });
    }
}
