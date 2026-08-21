package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.ResponseFormat;
import com.example.telegramuserbot.service.UserService;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Builds the final system prompt for LLM interactions.
 *
 * <p>Assembles prompt components: persona, behavior template (ResponseTemplate),
 * language/style settings, and base prompt. Supports user preferences integration.
 *
 * <p>Recommended usage with builder pattern:
 * <pre>{@code
 * EnhancedPromptRequest request = EnhancedPromptRequest.builder()
 *     .template(template)
 *     .chatConfig(config)
 *     .fallbackPrompt("Respond naturally.")
 *     .fallbackLanguage("auto")
 *     .build();
 *
 * String prompt = promptBuilder.buildEnhancedPrompt(request);
 * }</pre>
 *
 * @see EnhancedPromptRequest
 * @see PromptJsonSerializer
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final PersonaService personaService;
    private final UserService userService;
    private final PromptJsonSerializer serializer;

    /**
     * Creates a new PromptBuilder with required dependencies.
     *
     * @param personaService service for building persona prompts
     * @param userService service for user personalization
     * @param serializer JSON serialization service for prompt components
     */
    public PromptBuilder(PersonaService personaService,
                         UserService userService,
                         PromptJsonSerializer serializer) {
        this.personaService = personaService;
        this.userService = userService;
        this.serializer = serializer;
    }

    /**
     * Builds an enhanced system prompt using the request builder pattern.
     *
     * <p>This is the preferred method for building prompts. It uses {@link EnhancedPromptRequest}
     * to encapsulate all parameters, providing a cleaner API than the multiple overloaded methods.
     *
     * <p>Usage example:
     * <pre>{@code
     * EnhancedPromptRequest request = EnhancedPromptRequest.builder()
     *     .template(template)
     *     .chatConfig(config)
     *     .rateLimits(limits)
     *     .llmParameters(llmParams)
     *     .fallbackPrompt("Respond naturally.")
     *     .fallbackLanguage("auto")
     *     .user(user)
     *     .speakerContext(speakers)
     *     .pendingResponses(pending)
     *     .build();
     *
     * String prompt = promptBuilder.buildEnhancedPrompt(request);
     * }</pre>
     *
     * @param request the prompt request containing all parameters
     * @return the assembled system prompt as a JSON string
     * @see EnhancedPromptRequest
     */
    public String buildEnhancedPrompt(EnhancedPromptRequest request) {
        ChatConfig chatConfig = request.chatConfig();
        LlmParameters llmParameters = request.llmParameters();
        LlmSpeakerContext speakerContext = request.speakerContext();
        String basePrompt = Optional.ofNullable(chatConfig)
                .map(ChatConfig::getPromptTemplate)
                .filter(p -> p != null && !p.isBlank())
                .orElse(request.fallbackPrompt());
        String languageHint = Optional.ofNullable(chatConfig)
                .map(ChatConfig::getLanguage)
                .filter(lang -> !lang.isBlank())
                .orElse(request.fallbackLanguage());
        String personaBlock = personaService.buildPersonaSystemPrompt(
                null,
                languageHint,
                speakerContext != null ? speakerContext.botInstanceId() : null
        );
        String userPersonalization = request.user() != null ? userService.buildPersonalizedPrompt(request.user(), "") : "";
        ResponseFormat responseFormat = Optional.ofNullable(llmParameters)
                .map(LlmParameters::getResponseFormat)
                .orElse(ResponseFormat.TEXT);
        StringBuilder builder = new StringBuilder();
        builder.append("{\n")
                .append(serializer.personaJson(personaBlock))
                .append(serializer.chatConfigJson(chatConfig, request.rateLimits(), languageHint, basePrompt))
                .append(serializer.llmParametersJson(llmParameters))
                .append(serializer.responseTemplateJson(request.template(), responseFormat, request.directives()))
                .append(serializer.responseRulesJson(languageHint, responseFormat))
                .append(serializer.speakerContextJson(speakerContext))
                .append(serializer.knowledgeJson(request.knowledgeBlock()))
                .append(serializer.pendingResponsesJson(request.pendingResponses()))
                .append(serializer.userPersonalizationJson(userPersonalization))
                .append("}");
        String prompt = builder.toString();
        if (log.isDebugEnabled()) {
            log.debug(
                    "[PromptBuilder] system prompt built (len={}, chatConfigId={}, templateId={}, lang={}, userPresent={})",
                    prompt.length(),
                    chatConfig != null ? chatConfig.getId() : null,
                    request.template() != null ? request.template().getId() : null,
                    languageHint,
                    request.user() != null
            );
        }
        return prompt;
    }
}
