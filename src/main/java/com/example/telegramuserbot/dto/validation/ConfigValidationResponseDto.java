package com.example.telegramuserbot.dto.validation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for configuration validation results.
 */
public record ConfigValidationResponseDto(
        @JsonProperty("valid") boolean valid,
        @JsonProperty("total_issues") int totalIssues,
        @JsonProperty("error_count") int errorCount,
        @JsonProperty("warning_count") int warningCount,
        @JsonProperty("info_count") int infoCount,
        @JsonProperty("entity_results") Map<String, EntityValidationResultDto> entityResults,
        @JsonProperty("summary") ValidationSummaryDto summary,
        @JsonProperty("validated_at") Instant validatedAt
) {
    /**
     * Creates a response from entity results
     */
    public static ConfigValidationResponseDto fromResults(Map<String, EntityValidationResultDto> entityResults) {
        List<ValidationIssueDto> allIssues = entityResults.values().stream()
                .flatMap(r -> r.issues().stream())
                .toList();
        int errorCount = (int) allIssues.stream()
                .filter(i -> i.severity() == ValidationIssueDto.IssueSeverity.ERROR)
                .count();
        int warningCount = (int) allIssues.stream()
                .filter(i -> i.severity() == ValidationIssueDto.IssueSeverity.WARNING)
                .count();
        int infoCount = (int) allIssues.stream()
                .filter(i -> i.severity() == ValidationIssueDto.IssueSeverity.INFO)
                .count();
        boolean valid = errorCount == 0;
        ValidationSummaryDto summary = ValidationSummaryDto.fromEntityResults(entityResults);
        return new ConfigValidationResponseDto(
                valid,
                allIssues.size(),
                errorCount,
                warningCount,
                infoCount,
                entityResults,
                summary,
                Instant.now()
        );
    }
}
