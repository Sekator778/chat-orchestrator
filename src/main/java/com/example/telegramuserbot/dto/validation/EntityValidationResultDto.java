package com.example.telegramuserbot.dto.validation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Validation result for a single entity.
 */
public record EntityValidationResultDto(
        @JsonProperty("entity_id") String entityId,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("valid") boolean valid,
        @JsonProperty("issues") List<ValidationIssueDto> issues,
        @JsonProperty("suggested_status") String suggestedStatus
) {
    /**
     * Creates a valid result with no issues
     */
    public static EntityValidationResultDto valid(String entityId, String entityType) {
        return new EntityValidationResultDto(entityId, entityType, true, List.of(), "configured");
    }

    /**
     * Creates a result from a list of issues
     */
    public static EntityValidationResultDto fromIssues(String entityId, String entityType, List<ValidationIssueDto> issues) {
        boolean valid = issues.stream()
                .noneMatch(i -> i.severity() == ValidationIssueDto.IssueSeverity.ERROR);
        String status = determineSuggestedStatus(issues);
        return new EntityValidationResultDto(entityId, entityType, valid, issues, status);
    }

    private static String determineSuggestedStatus(List<ValidationIssueDto> issues) {
        boolean hasErrors = issues.stream()
                .anyMatch(i -> i.severity() == ValidationIssueDto.IssueSeverity.ERROR);
        boolean hasWarnings = issues.stream()
                .anyMatch(i -> i.severity() == ValidationIssueDto.IssueSeverity.WARNING);
        boolean hasIncomplete = issues.stream()
                .anyMatch(i -> i.type() == ValidationIssueDto.IssueType.INCOMPLETE);
        if (hasErrors) {
            return "partial";
        }
        if (hasWarnings) {
            return "warning";
        }
        if (hasIncomplete) {
            return "partial";
        }
        return "configured";
    }
}
