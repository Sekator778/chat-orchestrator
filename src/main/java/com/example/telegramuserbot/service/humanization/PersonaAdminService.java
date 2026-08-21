package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.domain.BotPersona;
import com.example.telegramuserbot.dto.BotPersonaDto;
import com.example.telegramuserbot.dto.PersonaBundleSummaryDto;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.service.cache.BotPersonaCache;
import com.example.telegramuserbot.service.proactive.PersonaProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PersonaAdminService {

    private static final Logger log = LoggerFactory.getLogger(PersonaAdminService.class);

    private final BotPersonaRepository botPersonaRepository;
    private final BotPersonaCache botPersonaCache;
    private final PersonaServiceImpl personaServiceImpl;
    private final ObjectMapper objectMapper;
    private final PersonaProfileService personaProfileService;

    public PersonaAdminService(BotPersonaRepository botPersonaRepository,
                               BotPersonaCache botPersonaCache,
                               PersonaServiceImpl personaServiceImpl,
                               ObjectMapper objectMapper,
                               PersonaProfileService personaProfileService) {
        this.botPersonaRepository = botPersonaRepository;
        this.botPersonaCache = botPersonaCache;
        this.personaServiceImpl = personaServiceImpl;
        this.objectMapper = objectMapper;
        this.personaProfileService = personaProfileService;
    }

    public Flux<PersonaBundleSummaryDto> listBundles() {
        return botPersonaRepository.findDistinctBotIds()
                .flatMap(botId -> botPersonaRepository.findByBotId(botId).collectList()
                        .map(list -> PersonaBundleSummaryDto.from(botId, list)));
    }

    public Flux<BotPersonaDto> list(String botId) {
        return botPersonaRepository.findByBotId(botId)
                .map(this::toDto);
    }

    public Mono<BotPersonaDto> get(String botId, String language) {
        return botPersonaRepository.findByBotIdAndLanguage(botId, normalizeLang(language))
                .map(this::toDto);
    }

    public Mono<BotPersonaDto> upsert(String botId, String language, BotPersonaDto dto) {
        String lang = normalizeLang(language);
        return botPersonaRepository.findByBotIdAndLanguage(botId, lang)
                .defaultIfEmpty(new BotPersona())
                .flatMap(entity -> {
                    entity.setBotId(botId);
                    entity.setLanguage(lang);
                    entity.setName(dto.name() == null ? "Persona" : dto.name());
                    entity.setDescription(dto.description() == null ? "" : dto.description());
                    entity.setBehavior(String.join("\n", dto.behavior() != null ? dto.behavior() : List.of()));
                    entity.setTraits(String.join(",", dto.traits() != null ? dto.traits() : List.of()));
                    entity.setLimitations(String.join("\n", dto.limitations() != null ? dto.limitations() : List.of()));
                    if (dto.metadata() != null && !dto.metadata().isEmpty()) {
                        try {
                            entity.setMetadata(objectMapper.writeValueAsString(dto.metadata()));
                        } catch (Exception e) {
                            return Mono.error(new IllegalArgumentException("Некорректный JSON metadata: " + e.getMessage(), e));
                        }
                    } else {
                        entity.setMetadata(null);
                    }
                    entity.setUpdatedAt(Instant.now());
                    return botPersonaRepository.save(entity);
                })
                .map(saved -> {
                    botPersonaCache.put(saved);
                    personaServiceImpl.reloadFromRepository(botId, lang);
                    personaProfileService.invalidate(botId);
                    log.info("Persona updated: botId={} lang={} name={}", botId, lang, saved.getName());
                    return toDto(saved);
                });
    }

    private String normalizeLang(String lang) {
        return lang == null || lang.isBlank() ? "base" : lang.trim().toLowerCase();
    }

    private BotPersonaDto toDto(BotPersona entity) {
        Map<String, Object> meta = Map.of();
        if (entity.getMetadata() != null && !entity.getMetadata().isBlank()) {
            try {
                meta = objectMapper.readValue(entity.getMetadata(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse persona metadata for {} {}: {}", entity.getBotId(), entity.getLanguage(), e.getMessage());
            }
        }
        return BotPersonaDto.fromEntity(entity, meta);
    }
}
