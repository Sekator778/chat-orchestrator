package com.example.telegramuserbot.dto.validation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Summary of validation results across all entities.
 */
public record ValidationSummaryDto(
        @JsonProperty("channels_validated") int channelsValidated,
        @JsonProperty("configs_valid") int configsValid,
        @JsonProperty("configs_with_errors") int configsWithErrors,
        @JsonProperty("configs_with_warnings") int configsWithWarnings,
        @JsonProperty("missing_llm_params") int missingLlmParams,
        @JsonProperty("missing_triggers") int missingTriggers,
        @JsonProperty("digest_personas_validated") int digestPersonasValidated,
        @JsonProperty("digest_personas_valid") int digestPersonasValid
) {
    /**
     * Creates a summary from entity validation results
     */
    public static ValidationSummaryDto fromEntityResults(Map<String, EntityValidationResultDto> results) {
        int channelsValidated = 0;
        int configsValid = 0;
        int configsWithErrors = 0;
        int configsWithWarnings = 0;
        int missingLlmParams = 0;
        int missingTriggers = 0;
        int digestPersonasValidated = 0;
        int digestPersonasValid = 0;
        for (Map.Entry<String, EntityValidationResultDto> entry : results.entrySet()) {
            EntityValidationResultDto result = entry.getValue();
            String entityType = result.entityType();
            if ("chatConfig".equals(entityType)) {
                channelsValidated++;
                if (result.valid()) {
                    configsValid++;
                } else {
                    boolean hasError = result.issues().stream()
                            .anyMatch(i -> i.severity() == ValidationIssueDto.IssueSeverity.ERROR);
                    if (hasError) {
                        configsWithErrors++;
                    } else {
                        configsWithWarnings++;
                    }
                }
                boolean missingLlm = result.issues().stream()
                        .anyMatch(i -> "llm_parameters".equals(i.field()));
                if (missingLlm) {
                    missingLlmParams++;
                }
                boolean noTriggers = result.issues().stream()
                        .anyMatch(i -> "trigger_conditions".equals(i.field()));
                if (noTriggers) {
                    missingTriggers++;
                }
            } else if ("digestPersona".equals(entityType)) {
                digestPersonasValidated++;
                if (result.valid()) {
                    digestPersonasValid++;
                }
            }
        }
        return new ValidationSummaryDto(
                channelsValidated,
                configsValid,
                configsWithErrors,
                configsWithWarnings,
                missingLlmParams,
                missingTriggers,
                digestPersonasValidated,
                digestPersonasValid
        );
    }
}
