package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.humanization.PersonaStyle;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Builds the final system prompt for LLM interactions.
 *
 * <p>Resolves the persona identity and its writing-habit style, then hands them
 * together with the request to {@link PersonaPromptComposer}, which renders the
 * whole thing as a natural-language brief — a living persona's instructions, not
 * a JSON config dump.
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
 * @see PersonaPromptComposer
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final PersonaService personaService;
    private final PersonaPromptComposer composer;

    /**
     * Creates a new PromptBuilder with required dependencies.
     *
     * @param personaService service for resolving the persona identity and its style
     * @param composer natural-language prompt composer
     */
    public PromptBuilder(PersonaService personaService, PersonaPromptComposer composer) {
        this.personaService = personaService;
        this.composer = composer;
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
     *     .speakerContext(speakers)
     *     .pendingResponses(pending)
     *     .build();
     *
     * String prompt = promptBuilder.buildEnhancedPrompt(request);
     * }</pre>
     *
     * @param request the prompt request containing all parameters
     * @return the assembled system prompt as natural-language plain text
     * @see EnhancedPromptRequest
     */
    public String buildEnhancedPrompt(EnhancedPromptRequest request) {
        ChatConfig chatConfig = request.chatConfig();
        LlmSpeakerContext speakerContext = request.speakerContext();
        String languageHint = Optional.ofNullable(chatConfig)
                .map(ChatConfig::getLanguage)
                .filter(lang -> !lang.isBlank())
                .orElse(request.fallbackLanguage());
        String botId = speakerContext != null ? speakerContext.botInstanceId() : null;

        String identityBlock = personaService.buildPersonaSystemPrompt(null, languageHint, botId);
        PersonaStyle style = personaService.resolveStyle(botId, languageHint);
        String prompt = composer.compose(request, identityBlock, style);

        if (log.isDebugEnabled()) {
            log.debug(
                    "[PromptBuilder] system prompt built (len={}, chatConfigId={}, templateId={}, lang={}, botId={})",
                    prompt.length(),
                    chatConfig != null ? chatConfig.getId() : null,
                    request.template() != null ? request.template().getId() : null,
                    languageHint,
                    botId
            );
        }
        return prompt;
    }
}
