package com.example.telegramuserbot.util;

import com.example.telegramuserbot.dto.ChatConfigDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationUtilsTest {

    // ---------------------------------------------------------------------------
    // createDefaultConfig
    // ---------------------------------------------------------------------------

    // FR-001 / AC-001: channelId equals the supplied chatId
    @Test
    void createDefaultConfigShouldSetChannelIdToChatId() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(12345L);
        assertThat(config.channelId()).isEqualTo(12345L);
    }

    // FR-001 / AC-001: contextWindowSize is 10
    @Test
    void createDefaultConfigShouldSetContextWindowSizeToTen() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.contextWindowSize()).isEqualTo(10);
    }

    // FR-001 / AC-001: id is null
    @Test
    void createDefaultConfigShouldHaveNullId() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.id()).isNull();
    }

    // FR-001 / AC-001: enabled is false
    @Test
    void createDefaultConfigShouldHaveEnabledFalse() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.enabled()).isFalse();
    }

    // FR-001 / AC-001: multiStageEnabled is false
    @Test
    void createDefaultConfigShouldHaveMultiStageEnabledFalse() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.multiStageEnabled()).isFalse();
    }

    // FR-001 / AC-001: autoSyncEnabled is false
    @Test
    void createDefaultConfigShouldHaveAutoSyncEnabledFalse() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.autoSyncEnabled()).isFalse();
    }

    // FR-001 / AC-001: respondToForwardedBotMessages is false
    @Test
    void createDefaultConfigShouldHaveRespondToForwardedBotMessagesFalse() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.respondToForwardedBotMessages()).isFalse();
    }

    // FR-001 / AC-001: syncEnabled is false
    @Test
    void createDefaultConfigShouldHaveSyncEnabledFalse() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.syncEnabled()).isFalse();
    }

    // FR-001 / AC-001: nullable fields (language, primaryChannelId, primaryChannelCheckedAt,
    //                   defaultSyncDepthDays, maxTokens, temperature) are all null
    @Test
    void createDefaultConfigShouldHaveNullNullableFields() {
        ChatConfigDto config = ConfigurationUtils.createDefaultConfig(1L);
        assertThat(config.language()).isNull();
        assertThat(config.primaryChannelId()).isNull();
        assertThat(config.primaryChannelCheckedAt()).isNull();
        assertThat(config.defaultSyncDepthDays()).isNull();
        assertThat(config.maxTokens()).isNull();
        assertThat(config.temperature()).isNull();
    }

    // ---------------------------------------------------------------------------
    // copyConfigWithPrompt
    // ---------------------------------------------------------------------------

    private ChatConfigDto sampleConfig() {
        return new ChatConfigDto(
                42L,                          // id
                999L,                         // channelId
                "My Channel",                 // channelTitle
                "Original prompt",            // promptTemplate
                true,                         // enabled
                true,                         // multiStageEnabled
                30,                           // defaultSyncDepthDays
                Boolean.TRUE,                 // autoSyncEnabled
                "en",                         // language
                111L,                         // primaryChannelId
                Instant.parse("2024-01-01T00:00:00Z"), // primaryChannelCheckedAt
                20,                           // contextWindowSize
                Boolean.FALSE,                // respondToForwardedBotMessages
                true,                         // syncEnabled
                500,                          // maxTokens
                0.7                           // temperature
        );
    }

    // FR-001 / AC-001: copyConfigWithPrompt replaces only promptTemplate
    @Test
    void copyConfigWithPromptShouldReplaceOnlyPromptTemplate() {
        ChatConfigDto original = sampleConfig();
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithPrompt(original, "New prompt");

        assertThat(copy.promptTemplate()).isEqualTo("New prompt");
        // All other fields must be unchanged
        assertThat(copy.id()).isEqualTo(original.id());
        assertThat(copy.channelId()).isEqualTo(original.channelId());
        assertThat(copy.channelTitle()).isEqualTo(original.channelTitle());
        assertThat(copy.enabled()).isEqualTo(original.enabled());
        assertThat(copy.multiStageEnabled()).isEqualTo(original.multiStageEnabled());
        assertThat(copy.defaultSyncDepthDays()).isEqualTo(original.defaultSyncDepthDays());
        assertThat(copy.autoSyncEnabled()).isEqualTo(original.autoSyncEnabled());
        assertThat(copy.language()).isEqualTo(original.language());
        assertThat(copy.primaryChannelId()).isEqualTo(original.primaryChannelId());
        assertThat(copy.primaryChannelCheckedAt()).isEqualTo(original.primaryChannelCheckedAt());
        assertThat(copy.contextWindowSize()).isEqualTo(original.contextWindowSize());
        assertThat(copy.respondToForwardedBotMessages()).isEqualTo(original.respondToForwardedBotMessages());
        assertThat(copy.syncEnabled()).isEqualTo(original.syncEnabled());
        assertThat(copy.maxTokens()).isEqualTo(original.maxTokens());
        assertThat(copy.temperature()).isEqualTo(original.temperature());
    }

    // FR-001 / AC-001: copyConfigWithPrompt accepts an empty string as the new prompt
    @Test
    void copyConfigWithPromptShouldAcceptEmptyStringAsNewPrompt() {
        ChatConfigDto original = sampleConfig();
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithPrompt(original, "");

        assertThat(copy.promptTemplate()).isEmpty();
        assertThat(copy.channelId()).isEqualTo(original.channelId());
    }

    // ---------------------------------------------------------------------------
    // copyConfigWithEnabled — defaultSyncDepthDays branch
    // ---------------------------------------------------------------------------

    // FR-001 / AC-001: when original.defaultSyncDepthDays is null and enabling -> 100
    @Test
    void copyConfigWithEnabledShouldSetDefaultSyncDepthDaysToHundredWhenNullAndEnabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", false, false,
                null, // defaultSyncDepthDays = null
                null, null, null, null, 10, null, false, null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, true);

        assertThat(copy.defaultSyncDepthDays()).isEqualTo(100);
    }

    // FR-001 / AC-001: when original.defaultSyncDepthDays is null and disabling -> null
    @Test
    void copyConfigWithEnabledShouldLeaveDefaultSyncDepthDaysNullWhenNullAndDisabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", true, false,
                null, // defaultSyncDepthDays = null
                null, null, null, null, 10, null, true, null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, false);

        assertThat(copy.defaultSyncDepthDays()).isNull();
    }

    // FR-001 / AC-001: when original.defaultSyncDepthDays is already set, it is preserved when enabling
    @Test
    void copyConfigWithEnabledShouldPreserveExistingDefaultSyncDepthDaysWhenEnabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", false, false,
                7, // defaultSyncDepthDays already set
                null, null, null, null, 10, null, false, null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, true);

        assertThat(copy.defaultSyncDepthDays()).isEqualTo(7);
    }

    // FR-001 / AC-001: when original.defaultSyncDepthDays is already set, it is preserved when disabling
    @Test
    void copyConfigWithEnabledShouldPreserveExistingDefaultSyncDepthDaysWhenDisabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", true, false,
                7, // defaultSyncDepthDays already set
                null, null, null, null, 10, null, true, null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, false);

        assertThat(copy.defaultSyncDepthDays()).isEqualTo(7);
    }

    // ---------------------------------------------------------------------------
    // copyConfigWithEnabled — syncEnabled branch
    // ---------------------------------------------------------------------------

    // FR-001 / AC-001: enabling forces syncEnabled true even when original had syncEnabled = false
    @Test
    void copyConfigWithEnabledShouldForceSyncEnabledTrueWhenEnabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", false, false,
                null, null, null, null, null, 10, null,
                false, // syncEnabled = false
                null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, true);

        assertThat(copy.syncEnabled()).isTrue();
    }

    // FR-001 / AC-001: disabling preserves original syncEnabled = false
    @Test
    void copyConfigWithEnabledShouldPreserveSyncEnabledFalseWhenDisabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", true, false,
                null, null, null, null, null, 10, null,
                false, // syncEnabled = false
                null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, false);

        assertThat(copy.syncEnabled()).isFalse();
    }

    // FR-001 / AC-001: disabling preserves original syncEnabled = true
    @Test
    void copyConfigWithEnabledShouldPreserveSyncEnabledTrueWhenDisabling() {
        ChatConfigDto original = new ChatConfigDto(
                null, 1L, "", "", true, false,
                null, null, null, null, null, 10, null,
                true, // syncEnabled = true
                null, null
        );
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, false);

        assertThat(copy.syncEnabled()).isTrue();
    }

    // FR-001 / AC-001: copyConfigWithEnabled sets enabled field correctly
    @Test
    void copyConfigWithEnabledShouldSetEnabledField() {
        ChatConfigDto original = sampleConfig();
        ChatConfigDto enabled = ConfigurationUtils.copyConfigWithEnabled(original, true);
        ChatConfigDto disabled = ConfigurationUtils.copyConfigWithEnabled(original, false);

        assertThat(enabled.enabled()).isTrue();
        assertThat(disabled.enabled()).isFalse();
    }

    // FR-001 / AC-001: copyConfigWithEnabled preserves all non-derived fields
    @Test
    void copyConfigWithEnabledShouldPreserveAllOtherFields() {
        ChatConfigDto original = sampleConfig();
        ChatConfigDto copy = ConfigurationUtils.copyConfigWithEnabled(original, false);

        assertThat(copy.id()).isEqualTo(original.id());
        assertThat(copy.channelId()).isEqualTo(original.channelId());
        assertThat(copy.channelTitle()).isEqualTo(original.channelTitle());
        assertThat(copy.promptTemplate()).isEqualTo(original.promptTemplate());
        assertThat(copy.multiStageEnabled()).isEqualTo(original.multiStageEnabled());
        assertThat(copy.autoSyncEnabled()).isEqualTo(original.autoSyncEnabled());
        assertThat(copy.language()).isEqualTo(original.language());
        assertThat(copy.primaryChannelId()).isEqualTo(original.primaryChannelId());
        assertThat(copy.primaryChannelCheckedAt()).isEqualTo(original.primaryChannelCheckedAt());
        assertThat(copy.contextWindowSize()).isEqualTo(original.contextWindowSize());
        assertThat(copy.respondToForwardedBotMessages()).isEqualTo(original.respondToForwardedBotMessages());
        assertThat(copy.maxTokens()).isEqualTo(original.maxTokens());
        assertThat(copy.temperature()).isEqualTo(original.temperature());
    }

    // ---------------------------------------------------------------------------
    // formatLimitDisplay
    // ---------------------------------------------------------------------------

    // FR-001 / AC-001: null input returns 'Без ліміту'
    @Test
    void formatLimitDisplayShouldReturnUkrainianLabelForNull() {
        assertThat(ConfigurationUtils.formatLimitDisplay(null)).isEqualTo("Без ліміту");
    }

    // FR-001 / AC-001: non-null input returns String.valueOf of that integer
    @Test
    void formatLimitDisplayShouldReturnStringValueForNonNull() {
        assertThat(ConfigurationUtils.formatLimitDisplay(42)).isEqualTo("42");
        assertThat(ConfigurationUtils.formatLimitDisplay(0)).isEqualTo("0");
    }

    // ---------------------------------------------------------------------------
    // formatLimitStatusMessage
    // ---------------------------------------------------------------------------

    // FR-001 / AC-001: null input returns 'знято (без ліміту)'
    @Test
    void formatLimitStatusMessageShouldReturnRemovedLabelForNull() {
        assertThat(ConfigurationUtils.formatLimitStatusMessage(null)).isEqualTo("знято (без ліміту)");
    }

    // FR-001 / AC-001: non-null input returns 'встановлено на ' + value
    @Test
    void formatLimitStatusMessageShouldReturnSetLabelForNonNull() {
        assertThat(ConfigurationUtils.formatLimitStatusMessage(100)).isEqualTo("встановлено на 100");
        assertThat(ConfigurationUtils.formatLimitStatusMessage(0)).isEqualTo("встановлено на 0");
    }
}
