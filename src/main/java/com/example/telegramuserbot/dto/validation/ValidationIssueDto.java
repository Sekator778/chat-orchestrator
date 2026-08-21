package com.example.telegramuserbot.dto.validation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single validation issue found during configuration validation.
 * Mirrors the frontend DependencyIssue interface.
 */
public record ValidationIssueDto(
        @JsonProperty("type") IssueType type,
        @JsonProperty("severity") IssueSeverity severity,
        @JsonProperty("message") String message,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("field") String field,
        @JsonProperty("suggestion") String suggestion,
        @JsonProperty("related_entity_id") String relatedEntityId
) {
    /**
     * Issue type enumeration
     */
    public enum IssueType {
        @JsonProperty("missing")
        MISSING,
        @JsonProperty("incomplete")
        INCOMPLETE,
        @JsonProperty("warning")
        WARNING,
        @JsonProperty("suggestion")
        SUGGESTION
    }

    /**
     * Issue severity enumeration
     */
    public enum IssueSeverity {
        @JsonProperty("error")
        ERROR,
        @JsonProperty("warning")
        WARNING,
        @JsonProperty("info")
        INFO
    }

    /**
     * Creates an error-level missing field issue
     */
    public static ValidationIssueDto missing(String entityType, String field, String message, String suggestion) {
        return new ValidationIssueDto(
                IssueType.MISSING,
                IssueSeverity.ERROR,
                message,
                entityType,
                field,
                suggestion,
                null
        );
    }

    /**
     * Creates a warning-level incomplete configuration issue
     */
    public static ValidationIssueDto incomplete(String entityType, String field, String message, String suggestion) {
        return new ValidationIssueDto(
                IssueType.INCOMPLETE,
                IssueSeverity.WARNING,
                message,
                entityType,
                field,
                suggestion,
                null
        );
    }

    /**
     * Creates a warning issue
     */
    public static ValidationIssueDto warning(String entityType, String field, String message, String suggestion) {
        return new ValidationIssueDto(
                IssueType.WARNING,
                IssueSeverity.WARNING,
                message,
                entityType,
                field,
                suggestion,
                null
        );
    }

    /**
     * Creates an info-level suggestion
     */
    public static ValidationIssueDto suggestion(String entityType, String field, String message, String suggestion) {
        return new ValidationIssueDto(
                IssueType.SUGGESTION,
                IssueSeverity.INFO,
                message,
                entityType,
                field,
                suggestion,
                null
        );
    }
}
