package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.BotPersonaDto;
import com.example.telegramuserbot.dto.PersonaBundleSummaryDto;
import com.example.telegramuserbot.service.humanization.PersonaAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/persona")
@Tag(name = "Persona Management", description = "CRUD for bot personas")
public class PersonaController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private final PersonaAdminService personaAdminService;

    public PersonaController(PersonaAdminService personaAdminService) {
        this.personaAdminService = personaAdminService;
    }

    @GetMapping
    @Operation(summary = "List persona bundles", description = "List bot IDs and languages")
    public Flux<PersonaBundleSummaryDto> listBundles() {
        uiLog.info("UI:persona:bundles");
        return personaAdminService.listBundles();
    }

    @GetMapping("/{botId}")
    @Operation(summary = "List personas for bot", description = "Return personas by bot ID")
    public Flux<BotPersonaDto> listByBot(@PathVariable String botId) {
        uiLog.info("UI:persona:list botId={}", botId);
        return personaAdminService.list(botId);
    }

    @GetMapping("/{botId}/{lang}")
    @Operation(summary = "Get persona by bot and language", description = "Return persona for given bot and language")
    public Mono<ResponseEntity<BotPersonaDto>> get(@PathVariable String botId, @PathVariable String lang) {
        uiLog.info("UI:persona:get botId={} lang={}", botId, lang);
        return personaAdminService.get(botId, lang)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{botId}/{lang}")
    @Operation(summary = "Upsert persona", description = "Create or update persona for language")
    public Mono<ResponseEntity<BotPersonaDto>> upsert(
            @PathVariable String botId,
            @PathVariable String lang,
            @RequestBody BotPersonaDto dto) {
        uiLog.info("UI:persona:upsert botId={} lang={} name={}", botId, lang, dto.name());
        return personaAdminService.upsert(botId, lang, dto)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    uiLog.warn("UI:persona:upsert error botId={} lang={} msg={}", botId, lang, e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().build());
                });
    }
}
