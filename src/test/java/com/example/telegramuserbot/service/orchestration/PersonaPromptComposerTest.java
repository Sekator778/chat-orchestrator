package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.PendingResponseStatus;
import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.service.humanization.PersonaStyle;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PersonaPromptComposer renders the whole system prompt as natural-language
 * plain text. These tests check the localised scaffold (ru/uk/en/auto), that
 * every style knob shows up only when it is actually set, and that optional
 * sections (knowledge, pending drafts, speakers) appear only when present.
 */
class PersonaPromptComposerTest {

    // Fixed "now": Wednesday 2026-09-16T10:15:30Z
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-16T10:15:30Z"), ZoneId.of("UTC"));

    private final PersonaPromptComposer composer = new PersonaPromptComposer(FIXED_CLOCK);

    private EnhancedPromptRequest.Builder baseRequest(String fallbackLanguage) {
        return EnhancedPromptRequest.builder()
                .fallbackPrompt("Respond naturally.")
                .fallbackLanguage(fallbackLanguage);
    }

    @Test
    void neverProducesJsonArtifacts() {
        String prompt = composer.compose(baseRequest("ru").build(), "Ты Нова.", PersonaStyle.defaults());

        assertThat(prompt).doesNotContain("{");
        assertThat(prompt).doesNotContain("\"chat_config_id\"");
        assertThat(prompt).doesNotContain("chat_config_id");
    }

    @Test
    void ruScaffoldCarriesRussianLanguageRuleAndSkipToken() {
        String prompt = composer.compose(baseRequest("ru").build(), "Ты Нова.", PersonaStyle.defaults());

        assertThat(prompt).contains("Пиши только на русском языке");
        assertThat(prompt).contains("[SKIP]");
    }

    @Test
    void ukScaffoldCarriesUkrainianLanguageRuleAndSkipToken() {
        String prompt = composer.compose(baseRequest("uk").build(), "Ти Нова.", PersonaStyle.defaults());

        assertThat(prompt).contains("Пиши лише українською мовою");
        assertThat(prompt).contains("[SKIP]");
    }

    @Test
    void enScaffoldCarriesEnglishLanguageRuleAndSkipToken() {
        String prompt = composer.compose(baseRequest("en").build(), "You are Nova.", PersonaStyle.defaults());

        assertThat(prompt).contains("Write only in English");
        assertThat(prompt).contains("[SKIP]");
    }

    @Test
    void autoScaffoldIsEnglishWithMirrorLanguageRule() {
        String prompt = composer.compose(baseRequest("auto").build(), "You are Nova.", PersonaStyle.defaults());

        assertThat(prompt).contains("Answer in the language of the message you are replying to");
        assertThat(prompt).contains("How people write here");
        assertThat(prompt).contains("[SKIP]");
    }

    @Test
    void styleLinesAppearOnlyWhenSet() {
        PersonaStyle withExtras = new PersonaStyle(3, PersonaStyle.EmojiUsage.OFTEN, true, true, 0.2,
                List.of("ну такое"), List.of("крипта"), null);
        String withExtrasPrompt = composer.compose(baseRequest("en").build(), "You are Nova.", withExtras);
        assertThat(withExtrasPrompt).contains("sometimes starts a message in lowercase");
        assertThat(withExtrasPrompt).contains("you like saying: \"ну такое\"");
        assertThat(withExtrasPrompt).contains("you follow: крипта");

        String defaultsPrompt = composer.compose(baseRequest("en").build(), "You are Nova.", PersonaStyle.defaults());
        assertThat(defaultsPrompt).doesNotContain("sometimes starts a message in lowercase");
        assertThat(defaultsPrompt).doesNotContain("you like saying:");
        assertThat(defaultsPrompt).doesNotContain("you follow:");
    }

    @Test
    void nowLineUsesPersonaTimezoneWhenSet() {
        PersonaStyle berlinStyle = new PersonaStyle(2, PersonaStyle.EmojiUsage.RARE, false, true, 0.0,
                List.of(), List.of(), "Europe/Berlin");
        String prompt = composer.compose(baseRequest("en").build(), "You are Nova.", berlinStyle);

        assertThat(prompt).contains("Europe/Berlin");
    }

    @Test
    void nowLineFallsBackToClockZoneWhenTimezoneUnset() {
        String prompt = composer.compose(baseRequest("en").build(), "You are Nova.", PersonaStyle.defaults());

        assertThat(prompt).contains("(UTC)");
    }

    @Test
    void knowledgeSectionAppearsOnlyWhenPresent() {
        String withoutKnowledge = composer.compose(baseRequest("en").build(), "You are Nova.", PersonaStyle.defaults());
        assertThat(withoutKnowledge).doesNotContain("What you might know");

        EnhancedPromptRequest withKnowledge = baseRequest("en").knowledgeBlock("BTC hit a new high\nSome other item").build();
        String prompt = composer.compose(withKnowledge, "You are Nova.", PersonaStyle.defaults());
        assertThat(prompt).contains("What you might know");
        assertThat(prompt).contains("BTC hit a new high");
    }

    @Test
    void pendingDraftsSectionAppearsOnlyWhenPresent() {
        String withoutPending = composer.compose(baseRequest("en").build(), "You are Nova.", PersonaStyle.defaults());
        assertThat(withoutPending).doesNotContain("Earlier drafts");

        PendingResponse pending = new PendingResponse();
        pending.setStatus(PendingResponseStatus.PENDING);
        pending.setPreparedResponse("Draft text here");
        EnhancedPromptRequest withPending = baseRequest("en").pendingResponses(List.of(pending)).build();
        String prompt = composer.compose(withPending, "You are Nova.", PersonaStyle.defaults());
        assertThat(prompt).contains("Earlier drafts");
        assertThat(prompt).contains("Draft text here");
    }

    @Test
    void speakersLegendAppearsOnlyWhenSpeakerContextPresent() {
        String withoutSpeakers = composer.compose(baseRequest("en").build(), "You are Nova.", PersonaStyle.defaults());
        assertThat(withoutSpeakers).doesNotContain("ME:");

        LlmSpeakerContext speakerContext = new LlmSpeakerContext("bot-1", 1L, List.of(
                new LlmSpeakerContext.Participant("P1", 100L, "user1", "John", "Doe", "John Doe")
        ));
        EnhancedPromptRequest withSpeakers = baseRequest("en").speakerContext(speakerContext).build();
        String prompt = composer.compose(withSpeakers, "You are Nova.", PersonaStyle.defaults());
        assertThat(prompt).contains("ME:");
        assertThat(prompt).contains("P1 — John Doe");
    }

    @Test
    void chatBlockUsesChatConfigPromptTemplateOverFallback() {
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setPromptTemplate("This is a crypto-trading chat.");
        EnhancedPromptRequest request = baseRequest("en").chatConfig(chatConfig).build();

        String prompt = composer.compose(request, "You are Nova.", PersonaStyle.defaults());

        assertThat(prompt).contains("This is a crypto-trading chat.");
    }

    @Test
    void chatBlockIncludesStyleToneAndLengthCapFromTemplate() {
        ResponseTemplate template = new ResponseTemplate();
        String prompt = composer.compose(baseRequest("en").template(template).build(), "You are Nova.", PersonaStyle.defaults());

        assertThat(prompt).contains("Style:");
        assertThat(prompt).contains("tone:");
        assertThat(prompt).contains("characters");
    }

    @Test
    void identityBlockIsIncludedVerbatim() {
        String prompt = composer.compose(baseRequest("en").build(), "You are Nova, a crypto enthusiast.", PersonaStyle.defaults());

        assertThat(prompt).contains("You are Nova, a crypto enthusiast.");
    }

    // --- "how people write here" brevity clause follows style.maxSentences ---

    private PersonaStyle styleWithMaxSentences(int maxSentences) {
        return new PersonaStyle(maxSentences, PersonaStyle.EmojiUsage.RARE, false, true, 0.0, List.of(), List.of(), null);
    }

    @Test
    void brevityClauseIsOneShortSentenceWhenMaxSentencesIsOne() {
        String prompt = composer.compose(baseRequest("ru").build(), "Ты Нова.", styleWithMaxSentences(1));

        assertThat(prompt).contains("Обычно одно короткое предложение");
    }

    @Test
    void brevityClauseIsOneOrTwoWhenMaxSentencesIsTwo() {
        String prompt = composer.compose(baseRequest("ru").build(), "Ты Нова.", styleWithMaxSentences(2));

        assertThat(prompt).contains("Обычно одно-два коротких предложения");
    }

    @Test
    void brevityClauseCapsAtMaxSentencesWhenGreaterThanTwo() {
        // PersonaStyle clamps maxSentences to [1,4], so 4 is the highest reachable value.
        String prompt = composer.compose(baseRequest("ru").build(), "Ты Нова.", styleWithMaxSentences(4));

        assertThat(prompt).contains("Обычно не больше 4 коротких предложений");
    }

    // --- style/tone words are localized, not the English enum descriptions ---

    @Test
    void concreteCasualStyleAndToneAreLocalizedInARussianChat() {
        ResponseTemplate template = new ResponseTemplate();
        template.setResponseStyle(ResponseStyle.CONCISE);
        template.setResponseTone(ResponseTone.CASUAL);

        String prompt = composer.compose(baseRequest("ru").template(template).build(), "Ты Нова.", PersonaStyle.defaults());

        assertThat(prompt).contains("коротко и по делу");
        assertThat(prompt).contains("непринуждённый");
        // Never the raw English enum descriptions leaking into a Russian prompt.
        assertThat(prompt).doesNotContain("Brief and to-the-point");
        assertThat(prompt).doesNotContain("Relaxed and informal");
    }
}
