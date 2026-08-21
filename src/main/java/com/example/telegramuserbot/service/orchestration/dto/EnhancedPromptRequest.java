package com.example.telegramuserbot.service.orchestration.dto;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;

import java.util.List;
import java.util.Objects;



/**
 * Immutable request object encapsulating all parameters for building an enhanced system prompt.
 * Replaces 8 overloaded buildEnhancedPrompt methods with a single, type-safe builder pattern.
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
 * @param template          response template with style and tone settings
 * @param chatConfig        chat-specific configuration
 * @param rateLimits        rate limiting configuration
 * @param llmParameters     LLM-specific parameters (model, temperature, etc.)
 * @param fallbackPrompt    default prompt if chatConfig.promptTemplate is empty
 * @param fallbackLanguage  default language if chatConfig.language is empty
 * @param user              user for personalization (nullable)
 * @param speakerContext    conversation participant context (nullable)
 * @param pendingResponses  list of pending responses to avoid duplication (nullable)
 * @param directives        decision-gate shaping directives (nullable — null means use template defaults)
 * @param knowledgeBlock    background-knowledge bullet list from the shared intelligence base (nullable — null means omit the section)
 */
public record EnhancedPromptRequest(
        ResponseTemplate template,
        ChatConfig chatConfig,
        RateLimits rateLimits,
        LlmParameters llmParameters,
        String fallbackPrompt,
        String fallbackLanguage,
        User user,
        LlmSpeakerContext speakerContext,
        List<PendingResponse> pendingResponses,
        ResponseDirectives directives,
        String knowledgeBlock
) {

    /**
     * Creates a new builder instance.
     *
     * @return new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing EnhancedPromptRequest instances.
     * Provides fluent API for optional parameters with sensible defaults.
     */
    public static final class Builder {
        private ResponseTemplate template;
        private ChatConfig chatConfig;
        private RateLimits rateLimits;
        private LlmParameters llmParameters;
        private String fallbackPrompt;
        private String fallbackLanguage;
        private User user;
        private LlmSpeakerContext speakerContext;
        private List<PendingResponse> pendingResponses;
        private ResponseDirectives directives;
        private String knowledgeBlock;

        private Builder() {
        }

        /**
         * Sets the response template.
         *
         * @param template response template with style and tone
         * @return this builder
         */
        public Builder template(ResponseTemplate template) {
            this.template = template;
            return this;
        }

        /**
         * Sets the chat configuration.
         *
         * @param chatConfig chat-specific configuration
         * @return this builder
         */
        public Builder chatConfig(ChatConfig chatConfig) {
            this.chatConfig = chatConfig;
            return this;
        }

        /**
         * Sets the rate limits configuration.
         *
         * @param rateLimits rate limiting settings
         * @return this builder
         */
        public Builder rateLimits(RateLimits rateLimits) {
            this.rateLimits = rateLimits;
            return this;
        }

        /**
         * Sets the LLM parameters.
         *
         * @param llmParameters LLM-specific settings
         * @return this builder
         */
        public Builder llmParameters(LlmParameters llmParameters) {
            this.llmParameters = llmParameters;
            return this;
        }

        /**
         * Sets the fallback prompt used when chatConfig.promptTemplate is empty.
         *
         * @param fallbackPrompt default prompt text
         * @return this builder
         */
        public Builder fallbackPrompt(String fallbackPrompt) {
            this.fallbackPrompt = fallbackPrompt;
            return this;
        }

        /**
         * Sets the fallback language used when chatConfig.language is empty.
         *
         * @param fallbackLanguage default language code (e.g., "auto", "en", "ru")
         * @return this builder
         */
        public Builder fallbackLanguage(String fallbackLanguage) {
            this.fallbackLanguage = fallbackLanguage;
            return this;
        }

        /**
         * Sets the user for personalization.
         *
         * @param user user entity (nullable)
         * @return this builder
         */
        public Builder user(User user) {
            this.user = user;
            return this;
        }

        /**
         * Sets the speaker context for conversation participant identification.
         *
         * @param speakerContext conversation speaker metadata (nullable)
         * @return this builder
         */
        public Builder speakerContext(LlmSpeakerContext speakerContext) {
            this.speakerContext = speakerContext;
            return this;
        }

        /**
         * Sets the list of pending responses to avoid duplication.
         *
         * @param pendingResponses list of pending responses (nullable)
         * @return this builder
         */
        public Builder pendingResponses(List<PendingResponse> pendingResponses) {
            this.pendingResponses = pendingResponses;
            return this;
        }

        /**
         * Sets the decision-gate shaping directives (nullable).
         * Null means use template defaults (byte-identical prompt behavior).
         *
         * @param directives decision-gate directives (nullable)
         * @return this builder
         */
        public Builder directives(ResponseDirectives directives) {
            this.directives = directives;
            return this;
        }

        /**
         * Sets the background-knowledge bullet list (nullable).
         * Null or blank means the knowledge section is omitted entirely.
         *
         * @param knowledgeBlock formatted knowledge items (nullable)
         * @return this builder
         */
        public Builder knowledgeBlock(String knowledgeBlock) {
            this.knowledgeBlock = knowledgeBlock;
            return this;
        }

        /**
         * Builds the immutable EnhancedPromptRequest.
         *
         * @return new EnhancedPromptRequest instance
         * @throws NullPointerException if fallbackPrompt or fallbackLanguage is null
         */
        public EnhancedPromptRequest build() {
            Objects.requireNonNull(fallbackPrompt, "fallbackPrompt must not be null");
            Objects.requireNonNull(fallbackLanguage, "fallbackLanguage must not be null");
            return new EnhancedPromptRequest(
                    template,
                    chatConfig,
                    rateLimits,
                    llmParameters,
                    fallbackPrompt,
                    fallbackLanguage,
                    user,
                    speakerContext,
                    pendingResponses,
                    directives,
                    knowledgeBlock
            );
        }
    }
}
