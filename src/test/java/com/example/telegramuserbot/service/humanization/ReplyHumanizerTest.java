package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.service.humanization.ReplyHumanizer.Humanized;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ReplyHumanizer — each numbered step from the class contract,
 * across ru/uk/en, plus the "normal reply passes through unchanged" guarantee.
 */
class ReplyHumanizerTest {

    /** A style with every probabilistic/decorative knob disabled, for deterministic assertions. */
    private static final PersonaStyle QUIET_STYLE =
            new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, false, false, 0.0, List.of(), List.of(), null);

    /** RandomGenerator whose nextDouble() always returns a fixed value. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    private static ReplyHumanizer humanizer(double randomValue) {
        return new ReplyHumanizer(fixed(randomValue));
    }

    // --- (1) null/blank -> skip ---

    @Test
    void nullTextIsSkipped() {
        Humanized result = humanizer(0.9).humanize(null, "ru", QUIET_STYLE);
        assertThat(result.skip()).isTrue();
        assertThat(result.aiTell()).isFalse();
    }

    @Test
    void blankTextIsSkipped() {
        Humanized result = humanizer(0.9).humanize("   \n  ", "ru", QUIET_STYLE);
        assertThat(result.skip()).isTrue();
    }

    // --- (2) silence token ---

    @Test
    void bracketedSkipTokenIsSkipped() {
        assertThat(humanizer(0.9).humanize("[SKIP]", "ru", QUIET_STYLE).skip()).isTrue();
    }

    @Test
    void bareSkipTokenIsSkipped() {
        assertThat(humanizer(0.9).humanize("SKIP", "en", QUIET_STYLE).skip()).isTrue();
    }

    @Test
    void lowercaseBracketedSkipTokenIsSkipped() {
        assertThat(humanizer(0.9).humanize("[skip]", "uk", QUIET_STYLE).skip()).isTrue();
    }

    @Test
    void skipTokenAsFirstLineIsSkippedEvenWithMoreContentAfter() {
        Humanized result = humanizer(0.9).humanize("[SKIP]\nignored trailing content", "ru", QUIET_STYLE);
        assertThat(result.skip()).isTrue();
    }

    // --- (3) speaker prefix stripping ---

    @Test
    void stripsAssistantRolePrefix() {
        Humanized result = humanizer(0.9).humanize("ASSISTANT: Привет, как дела?", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Привет, как дела?");
    }

    @Test
    void stripsNumberedSpeakerPrefixWithHandle() {
        Humanized result = humanizer(0.9).humanize("P1 (@ivan): Согласен", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Согласен");
    }

    // --- (4) wrapping quote unwrap ---

    @Test
    void unwrapsGuillemets() {
        Humanized result = humanizer(0.9).humanize("«Привет, друг»", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Привет, друг");
    }

    @Test
    void unwrapsCurlyQuotes() {
        Humanized result = humanizer(0.9).humanize("“Hello there”", "en", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Hello there");
    }

    @Test
    void unwrapsStraightQuotes() {
        Humanized result = humanizer(0.9).humanize("\"Hello there\"", "en", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Hello there");
    }

    // --- (5) markdown -> plain + list marker collapse ---

    @Test
    void stripsMarkdownAndCollapsesBulletList() {
        Humanized result = humanizer(0.9).humanize(
                "**Список дел:**\n- первое дело\n- второе дело без точки", "ru", QUIET_STYLE);
        assertThat(result.text()).doesNotContain("**").doesNotContain("- ");
        assertThat(result.text()).contains("Список дел:");
        assertThat(result.text()).contains("первое дело. второе дело без точки.");
    }

    // --- (6) assistant-isms: leading fillers ---

    @Test
    void removesRussianLeadingFillerWithOwnPunctuation() {
        Humanized result = humanizer(0.9).humanize("Конечно! Вот твой ответ.", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Вот твой ответ.");
    }

    @Test
    void removesRussianLeadingFillerFollowedByComma() {
        Humanized result = humanizer(0.9).humanize("Хороший вопрос, вот что я думаю.", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("вот что я думаю.");
    }

    // --- (6) assistant-isms: trailing offers, per language ---

    @Test
    void removesRussianLeadingAndTrailingAssistantIsms() {
        Humanized result = humanizer(0.9).humanize(
                "Вот твой ответ. Если у вас есть ещё вопросы, обращайтесь!", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Вот твой ответ.");
    }

    @Test
    void removesUkrainianLeadingAndTrailingAssistantIsms() {
        Humanized result = humanizer(0.9).humanize(
                "Звісно! Ось відповідь. Якщо будуть питання, пишіть.", "uk", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Ось відповідь.");
    }

    @Test
    void removesEnglishLeadingAndTrailingAssistantIsms() {
        Humanized result = humanizer(0.9).humanize(
                "Sure! Here's the answer. Let me know if you need more.", "en", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Here's the answer.");
    }

    // --- (7) aiTell detection: short-circuits, text unchanged from step 6 ---

    @Test
    void detectsRussianAiTellAndStopsFurtherProcessing() {
        Humanized result = humanizer(0.9).humanize(
                "Слушай, вообще я бот, если честно.", "ru", QUIET_STYLE);
        assertThat(result.aiTell()).isTrue();
        assertThat(result.skip()).isFalse();
        assertThat(result.text()).isEqualTo("Слушай, вообще я бот, если честно.");
    }

    @Test
    void detectsUkrainianAiTell() {
        Humanized result = humanizer(0.9).humanize(
                "Чесно, я штучний інтелект і це нормально.", "uk", QUIET_STYLE);
        assertThat(result.aiTell()).isTrue();
    }

    @Test
    void detectsEnglishAiTell() {
        Humanized result = humanizer(0.9).humanize(
                "To be clear, I am an AI assistant here to help.", "en", QUIET_STYLE);
        assertThat(result.aiTell()).isTrue();
    }

    @Test
    void autoLanguageHintChecksAllThreeAiTellLists() {
        Humanized result = humanizer(0.9).humanize("as an AI I cannot do that", "auto", QUIET_STYLE);
        assertThat(result.aiTell()).isTrue();
    }

    // --- (8) dash moderation ---

    @Test
    void keepsFirstEmDashAndReplacesLaterOnesWithComma() {
        Humanized result = humanizer(0.9).humanize(
                "Идея такая — сначала одно — потом другое — и третье.", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Идея такая — сначала одно, потом другое, и третье.");
    }

    @Test
    void singleEmDashIsLeftAlone() {
        Humanized result = humanizer(0.9).humanize("Идея такая — простая.", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Идея такая — простая.");
    }

    // --- (9) style: emoji usage ---

    @Test
    void emojiNoneRemovesAllEmoji() {
        Humanized result = humanizer(0.9).humanize("Привет 😀 как дела 🎉", "ru", QUIET_STYLE);
        assertThat(result.text()).doesNotContain("😀").doesNotContain("🎉");
    }

    @Test
    void emojiRareKeepsAtMostOne() {
        PersonaStyle rare = new PersonaStyle(2, PersonaStyle.EmojiUsage.RARE, false, false, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.9).humanize("Круто 😀 очень круто 🎉", "ru", rare);
        long emojiCount = result.text().codePoints().filter(cp -> cp == 0x1F600 || cp == 0x1F389).count();
        assertThat(emojiCount).isEqualTo(1);
    }

    @Test
    void emojiOftenKeepsAtMostThree() {
        PersonaStyle often = new PersonaStyle(2, PersonaStyle.EmojiUsage.OFTEN, false, false, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.9).humanize("😀🎉😀🎉😀", "ru", often);
        long emojiCount = result.text().codePoints().filter(cp -> cp == 0x1F600 || cp == 0x1F389).count();
        assertThat(emojiCount).isEqualTo(3);
    }

    // --- (9) style: lowercaseStart ---

    @Test
    void lowercaseStartAppliesWhenRandomTriggersAndWordIsLongEnough() {
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, true, false, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.0).humanize("Привет, как дела?", "ru", style);
        assertThat(result.text()).isEqualTo("привет, как дела?");
    }

    @Test
    void lowercaseStartDoesNotApplyWhenRandomDoesNotTrigger() {
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, true, false, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.9).humanize("Привет, как дела?", "ru", style);
        assertThat(result.text()).isEqualTo("Привет, как дела?");
    }

    @Test
    void lowercaseStartSkipsShortLeadingWord() {
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, true, false, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.0).humanize("Я думаю, что да.", "ru", style);
        assertThat(result.text()).startsWith("Я ");
    }

    // --- (9) style: dropFinalPeriod ---

    @Test
    void dropFinalPeriodAppliesWhenRandomTriggers() {
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, false, true, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.0).humanize("Согласен.", "ru", style);
        assertThat(result.text()).isEqualTo("Согласен");
    }

    @Test
    void dropFinalPeriodDoesNotApplyWhenRandomDoesNotTrigger() {
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, false, true, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.9).humanize("Согласен.", "ru", style);
        assertThat(result.text()).isEqualTo("Согласен.");
    }

    @Test
    void dropFinalPeriodNeverTouchesEllipsis() {
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, false, true, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.0).humanize("Хм...", "ru", style);
        assertThat(result.text()).isEqualTo("Хм...");
    }

    @Test
    void dropFinalPeriodNeverAppliesBeyond200Chars() {
        String longSentence = "а".repeat(201) + ".";
        PersonaStyle style = new PersonaStyle(2, PersonaStyle.EmojiUsage.NONE, false, true, 0.0, List.of(), List.of(), null);
        Humanized result = humanizer(0.0).humanize(longSentence, "ru", style);
        assertThat(result.text()).endsWith(".");
    }

    // --- (10) trim / collapse / blank-after-processing -> skip ---

    @Test
    void collapsesTripleNewlinesToDouble() {
        Humanized result = humanizer(0.9).humanize("Первое.\n\n\n\nВторое.", "ru", QUIET_STYLE);
        assertThat(result.text()).isEqualTo("Первое.\n\nВторое.");
    }

    @Test
    void becomingBlankAfterProcessingIsSkipped() {
        Humanized result = humanizer(0.9).humanize("Конечно!", "ru", QUIET_STYLE);
        assertThat(result.skip()).isTrue();
        assertThat(result.text()).isEmpty();
    }

    // --- pass-through guarantee ---

    @Test
    void normalRussianReplyPassesThroughUnchangedApartFromTrimming() {
        String raw = "  Хорошая новость, встречаемся завтра в шесть.  \n";
        // defaults(): dropFinalPeriod=true but this fixed random (0.9 >= 0.6 threshold) never triggers it.
        Humanized result = humanizer(0.9).humanize(raw, "ru", PersonaStyle.defaults());
        assertThat(result.skip()).isFalse();
        assertThat(result.aiTell()).isFalse();
        assertThat(result.text()).isEqualTo(raw.trim());
    }

    @Test
    void skipTokenFollowedByAnExplanationIsStillSilence() {
        ReplyHumanizer.Humanized result = humanizer(0.99).humanize("[SKIP] — мне тут нечего добавить", "ru", PersonaStyle.defaults());

        assertThat(result.skip()).isTrue();
        assertThat(result.text()).isEmpty();
    }

    @Test
    void skipLookalikeInsideAWordIsNotSilence() {
        ReplyHumanizer.Humanized result = humanizer(0.99).humanize("[skip]ped the meeting, sorry", "en", PersonaStyle.defaults());

        assertThat(result.skip()).isFalse();
    }
}
