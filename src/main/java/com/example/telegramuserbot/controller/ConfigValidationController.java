package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.validation.ConfigValidationRequestDto;
import com.example.telegramuserbot.dto.validation.ConfigValidationResponseDto;
import com.example.telegramuserbot.dto.validation.EntityValidationResultDto;
import com.example.telegramuserbot.service.validation.ConfigValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * REST controller for configuration validation endpoints.
 * Provides server-side validation of chat configurations, digest personas,
 * and related entities used by the Interactive Configuration Constructor.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/config/validate")
@Tag(name = "Configuration Validation", description = "Validate chat configurations and related entities")
public class ConfigValidationController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private static final Logger log = LoggerFactory.getLogger(ConfigValidationController.class);

    private final ConfigValidationService validationService;

    public ConfigValidationController(ConfigValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping
    @Operation(
            summary = "Validate multiple configurations",
            description = "Validates configurations for specified channels and optionally digest/bot personas"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Validation completed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "500", description = "Internal server error during validation")
    })
    public Mono<ResponseEntity<ConfigValidationResponseDto>> validateConfigurations(
            @Valid @RequestBody ConfigValidationRequestDto request) {
        uiLog.info("UI:validateConfigurations channels={} includeDigest={} includeBotPersonas={}",
                request.channelIds().size(),
                request.includeDigestPersonas(),
                request.includeBotPersonas());
        return validationService.validate(request)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Validation failed", e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    @GetMapping("/channel/{channelId}")
    @Operation(
            summary = "Validate single channel configuration",
            description = "Validates a single channel configuration including LLM params, triggers, and related settings"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Validation completed successfully"),
            @ApiResponse(responseCode = "404", description = "Channel not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error during validation")
    })
    public Mono<ResponseEntity<EntityValidationResultDto>> validateChannel(
            @Parameter(description = "Channel ID (TDLib format)") @PathVariable Long channelId) {
        uiLog.info("UI:validateChannel channelId={}", channelId);
        return validationService.validateChannel(channelId)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Channel validation failed for channelId={}", channelId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    @GetMapping("/digest-persona/{personaId}")
    @Operation(
            summary = "Validate digest persona configuration",
            description = "Validates a digest persona including schedule, target channel, and bot settings"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Validation completed successfully"),
            @ApiResponse(responseCode = "404", description = "Persona not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error during validation")
    })
    public Mono<ResponseEntity<EntityValidationResultDto>> validateDigestPersona(
            @Parameter(description = "Digest persona ID") @PathVariable Long personaId) {
        uiLog.info("UI:validateDigestPersona personaId={}", personaId);
        return validationService.validateDigestPersona(personaId)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Digest persona validation failed for personaId={}", personaId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    @GetMapping("/preview/{channelId}")
    @Operation(
            summary = "Preview configuration changes impact",
            description = "Shows what will happen if the current configuration is activated"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Preview generated successfully"),
            @ApiResponse(responseCode = "404", description = "Channel not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public Mono<ResponseEntity<EntityValidationResultDto>> previewConfiguration(
            @Parameter(description = "Channel ID (TDLib format)") @PathVariable Long channelId) {
        uiLog.info("UI:previewConfiguration channelId={}", channelId);
        return validationService.validateChannel(channelId)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Configuration preview failed for channelId={}", channelId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }
}
