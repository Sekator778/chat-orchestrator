package com.example.telegramuserbot.service.validation;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.domain.DigestPersonaStyle;
import com.example.telegramuserbot.domain.ResponseFormat;
import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.domain.TriggerType;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.dto.validation.*;
import com.example.telegramuserbot.service.config.ConfigurationService;
import com.example.telegramuserbot.service.digest.DigestPersonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ConfigValidationServiceImpl.
 * Uses mocks for dependencies to test validation logic in isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigValidationServiceImpl should")
class ConfigValidationServiceImplTest {

    @Mock
    private ConfigurationService configurationService;

    @Mock
    private DigestPersonaService digestPersonaService;

    private ConfigValidationService validationService;

    @BeforeEach
    void setUp() {
        validationService = new ConfigValidationServiceImpl(configurationService, digestPersonaService);
    }

    @Test
    @DisplayName("return valid result for fully configured chat")
    void shouldReturnValidResultForFullyConfiguredChat() {
        Long channelId = -1001234567890L;
        EnhancedChatConfigDto config = createFullyConfiguredConfig(channelId);
        when(configurationService.getEnhancedConfig(channelId)).thenReturn(Mono.just(config));
        StepVerifier.create(validationService.validateChannel(channelId))
                .assertNext(result -> {
                    assertThat(result.valid()).isTrue();
                    assertThat(result.entityType()).isEqualTo("chatConfig");
                    assertThat(result.issues()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("report missing LLM parameters when enabled but not configured")
    void shouldReportMissingLlmParametersWhenEnabledButNotConfigured() {
        Long channelId = -1001234567890L;
        EnhancedChatConfigDto config = createConfigWithoutLlmParams(channelId);
        when(configurationService.getEnhancedConfig(channelId)).thenReturn(Mono.just(config));
        StepVerifier.create(validationService.validateChannel(channelId))
                .assertNext(result -> {
                    assertThat(result.valid()).isFalse();
                    assertThat(result.issues()).anyMatch(i ->
                            i.field() != null && i.field().equals("llm_parameters") &&
                                    i.severity() == ValidationIssueDto.IssueSeverity.ERROR
                    );
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("report missing triggers when enabled but none defined")
    void shouldReportMissingTriggersWhenEnabledButNoneDefined() {
        Long channelId = -1001234567890L;
        EnhancedChatConfigDto config = createConfigWithoutTriggers(channelId);
        when(configurationService.getEnhancedConfig(channelId)).thenReturn(Mono.just(config));
        StepVerifier.create(validationService.validateChannel(channelId))
                .assertNext(result -> {
                    assertThat(result.issues()).anyMatch(i ->
                            i.field() != null && i.field().equals("trigger_conditions") &&
                                    i.severity() == ValidationIssueDto.IssueSeverity.WARNING
                    );
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("report not found error when channel config does not exist")
    void shouldReportNotFoundErrorWhenChannelConfigDoesNotExist() {
        Long channelId = -1001234567890L;
        when(configurationService.getEnhancedConfig(channelId)).thenReturn(Mono.empty());
        StepVerifier.create(validationService.validateChannel(channelId))
                .assertNext(result -> {
                    assertThat(result.valid()).isFalse();
                    assertThat(result.issues()).anyMatch(i ->
                            i.type() == ValidationIssueDto.IssueType.MISSING &&
                                    i.message().contains("not found")
                    );
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("validate multiple channels in single request")
    void shouldValidateMultipleChannelsInSingleRequest() {
        Long channelId1 = -1001234567890L;
        Long channelId2 = -1001234567891L;
        when(configurationService.getEnhancedConfig(channelId1))
                .thenReturn(Mono.just(createFullyConfiguredConfig(channelId1)));
        when(configurationService.getEnhancedConfig(channelId2))
                .thenReturn(Mono.just(createConfigWithoutLlmParams(channelId2)));
        ConfigValidationRequestDto request = new ConfigValidationRequestDto(
                List.of(channelId1, channelId2),
                false,
                false
        );
        StepVerifier.create(validationService.validate(request))
                .assertNext(response -> {
                    assertThat(response.entityResults()).hasSizeGreaterThanOrEqualTo(5);
                    assertThat(response.valid()).isFalse();
                    assertThat(response.totalIssues()).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("validate digest persona with missing required fields")
    void shouldValidateDigestPersonaWithMissingRequiredFields() {
        Long personaId = 1L;
        DigestPersona invalidPersona = createInvalidDigestPersona(personaId);
        when(digestPersonaService.findById(personaId)).thenReturn(Mono.just(invalidPersona));
        StepVerifier.create(validationService.validateDigestPersona(personaId))
                .assertNext(result -> {
                    assertThat(result.valid()).isFalse();
                    assertThat(result.entityType()).isEqualTo("digestPersona");
                    assertThat(result.issues()).anyMatch(i ->
                            i.field() != null && i.field().equals("scheduleCron")
                    );
                    assertThat(result.issues()).anyMatch(i ->
                            i.field() != null && i.field().equals("targetChannelId")
                    );
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("return valid result for disabled chat config without LLM params")
    void shouldReturnValidResultForDisabledChatConfigWithoutLlmParams() {
        Long channelId = -1001234567890L;
        EnhancedChatConfigDto config = createDisabledConfigWithoutLlmParams(channelId);
        when(configurationService.getEnhancedConfig(channelId)).thenReturn(Mono.just(config));
        StepVerifier.create(validationService.validateChannel(channelId))
                .assertNext(result -> {
                    boolean hasLlmError = result.issues().stream()
                            .anyMatch(i -> i.field() != null && i.field().equals("llm_parameters") &&
                                    i.severity() == ValidationIssueDto.IssueSeverity.ERROR);
                    assertThat(hasLlmError).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("include digest personas when flag is set")
    void shouldIncludeDigestPersonasWhenFlagIsSet() {
        Long channelId = -1001234567890L;
        when(configurationService.getEnhancedConfig(channelId))
                .thenReturn(Mono.just(createFullyConfiguredConfig(channelId)));
        DigestPersona validPersona = createValidDigestPersona(1L);
        when(digestPersonaService.findAll()).thenReturn(Flux.just(validPersona));
        ConfigValidationRequestDto request = new ConfigValidationRequestDto(
                List.of(channelId),
                true,
                false
        );
        StepVerifier.create(validationService.validate(request))
                .assertNext(response -> {
                    assertThat(response.entityResults()).containsKey("digestPersona-1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("report warning for temperature out of range")
    void shouldReportWarningForTemperatureOutOfRange() {
        Long channelId = -1001234567890L;
        EnhancedChatConfigDto config = createConfigWithInvalidTemperature(channelId);
        when(configurationService.getEnhancedConfig(channelId)).thenReturn(Mono.just(config));
        ConfigValidationRequestDto request = new ConfigValidationRequestDto(
                List.of(channelId),
                false,
                false
        );
        StepVerifier.create(validationService.validate(request))
                .assertNext(response -> {
                    var llmResult = response.entityResults().get("llmParams-" + channelId);
                    assertThat(llmResult).isNotNull();
                    assertThat(llmResult.issues()).anyMatch(i ->
                            i.field() != null && i.field().equals("temperature") &&
                                    i.severity() == ValidationIssueDto.IssueSeverity.WARNING
                    );
                })
                .verifyComplete();
    }

    private EnhancedChatConfigDto createFullyConfiguredConfig(Long channelId) {
        return new EnhancedChatConfigDto(
                1L, channelId, "Test Channel",
                "You are a helpful assistant", true, false,
                30, true, "en", null, null, 10, false, 0, true, 1000, 0.7,
                List.of(createResponseTemplate()),
                List.of(createTriggerCondition()),
                createContextSettings(),
                createLlmParameters(),
                createRateLimits(),
                List.of()
        );
    }

    private EnhancedChatConfigDto createConfigWithoutLlmParams(Long channelId) {
        return new EnhancedChatConfigDto(
                1L, channelId, "Test Channel",
                "You are a helpful assistant", true, false,
                30, true, "en", null, null, 10, false, 0, true, 1000, 0.7,
                List.of(createResponseTemplate()),
                List.of(createTriggerCondition()),
                createContextSettings(),
                null,
                createRateLimits(),
                List.of()
        );
    }

    private EnhancedChatConfigDto createConfigWithoutTriggers(Long channelId) {
        return new EnhancedChatConfigDto(
                1L, channelId, "Test Channel",
                "You are a helpful assistant", true, false,
                30, true, "en", null, null, 10, false, 0, true, 1000, 0.7,
                List.of(createResponseTemplate()),
                List.of(),
                createContextSettings(),
                createLlmParameters(),
                createRateLimits(),
                List.of()
        );
    }

    private EnhancedChatConfigDto createDisabledConfigWithoutLlmParams(Long channelId) {
        return new EnhancedChatConfigDto(
                1L, channelId, "Test Channel",
                null, false, false,
                30, true, "en", null, null, 10, false, 0, true, 1000, 0.7,
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of()
        );
    }

    private EnhancedChatConfigDto createConfigWithInvalidTemperature(Long channelId) {
        return new EnhancedChatConfigDto(
                1L, channelId, "Test Channel",
                "You are a helpful assistant", true, false,
                30, true, "en", null, null, 10, false, 0, true, 1000, 0.7,
                List.of(createResponseTemplate()),
                List.of(createTriggerCondition()),
                createContextSettings(),
                createLlmParametersWithInvalidTemperature(),
                createRateLimits(),
                List.of()
        );
    }

    private ResponseTemplateDto createResponseTemplate() {
        return new ResponseTemplateDto(
                1L, 1L, "default", "You are a helpful assistant",
                ResponseStyle.ADAPTIVE, ResponseTone.FRIENDLY, 1000, true, 1, true
        );
    }

    private TriggerConditionDto createTriggerCondition() {
        return new TriggerConditionDto(
                1L, 1L, "keyword-trigger", TriggerType.KEYWORD_MATCH,
                "hello,help", false, 0, 100, null, null, "1,2,3,4,5,6,7", 0, 1, true
        );
    }

    private ContextSettingsDto createContextSettings() {
        return new ContextSettingsDto(
                1L, 1L, 50, 24, true, true, false, 2000, true
        );
    }

    private LlmParametersDto createLlmParameters() {
        return new LlmParametersDto(
                1L, 1L, "deepseek-chat", 0.7, 1000, 1.0, 0.0, 0.0, null, null, ResponseFormat.TEXT
        );
    }

    private LlmParametersDto createLlmParametersWithInvalidTemperature() {
        return new LlmParametersDto(
                1L, 1L, "deepseek-chat", 2.5, 1000, 1.0, 0.0, 0.0, null, null, ResponseFormat.TEXT
        );
    }

    private RateLimitsDto createRateLimits() {
        return new RateLimitsDto(
                1L, 1L, null, 20, 100, 0, 50000, 0, 60, 3, 60, false
        );
    }

    private DigestPersona createInvalidDigestPersona(Long id) {
        DigestPersona persona = new DigestPersona();
        persona.setId(id);
        persona.setName("Test Persona");
        persona.setEnabled(true);
        persona.setScheduleCron(null);
        persona.setTargetChannelId(null);
        persona.setBotId(null);
        return persona;
    }

    private DigestPersona createValidDigestPersona(Long id) {
        DigestPersona persona = new DigestPersona();
        persona.setId(id);
        persona.setName("Valid Persona");
        persona.setEnabled(true);
        persona.setScheduleCron("0 0 9 * * *");
        persona.setTargetChannelId(-1001234567890L);
        persona.setBotId(123456789L);
        persona.setPersonaStyle(DigestPersonaStyle.PROFESSIONAL.name());
        return persona;
    }
}
