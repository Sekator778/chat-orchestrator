package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.BotPersona;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.service.cache.BotPersonaCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of persona service using YAML configuration
 */
@Service
public class PersonaServiceImpl implements PersonaService {
    
    private static final Logger log = LoggerFactory.getLogger(PersonaServiceImpl.class);
    
    @Value("${app.persona.path:}")
    private String overridePersonaPath;

    private final ResourceLoader resourceLoader;
    private final BotInstanceProvider botInstanceProvider;
    private final BotPersonaRepository botPersonaRepository;
    private final BotPersonaCache botPersonaCache;
    
    private final Random random = new Random();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Object> personaData;
    private Resource activeResource;
    private final Map<String, Map<String, Object>> localeCache = new ConcurrentHashMap<>();

    public PersonaServiceImpl(ResourceLoader resourceLoader,
                              BotInstanceProvider botInstanceProvider,
                              BotPersonaRepository botPersonaRepository,
                              BotPersonaCache botPersonaCache) {
        this.resourceLoader = resourceLoader;
        this.botInstanceProvider = botInstanceProvider;
        this.botPersonaRepository = botPersonaRepository;
        this.botPersonaCache = botPersonaCache;
    }
    
    public void loadPersona() {
        seedAllPersonasFromResources();

        String botId = botInstanceProvider.getInstanceId();
        List<BotPersona> personas = botPersonaRepository.findAll().collectList().block();

        if (personas == null || personas.isEmpty()) {
            seedFromFiles(botId);
            personas = botPersonaRepository.findAll().collectList().block();
        }

        if (personas == null || personas.isEmpty()) {
            Map<String, Object> fallback = loadPersonaFromResourceFallback(botId, null);
            if (fallback != null) {
                this.personaData = fallback;
                return;
            }
            throw new IllegalStateException("Persona not found in DB or resources");
        }

        personas.forEach(botPersonaCache::put);

        // Default in-memory persona: prefer current botId if exists, otherwise first available
        BotPersona selected = personas.stream()
                .filter(p -> botId != null && botId.equals(p.getBotId()))
                .findFirst()
                .orElse(personas.get(0));
        this.personaData = mapFromEntity(selected);
    }

    private Resource resolvePersonaResource() {
        if (overridePersonaPath != null && !overridePersonaPath.isBlank()) {
            Resource override = resourceLoader.getResource(overridePersonaPath.trim());
            if (override.exists()) {
                return override;
            }
            throw new IllegalStateException("Persona override path not found: " + overridePersonaPath);
        }

        String instance = botInstanceProvider.getInstanceId();
        Resource instanceResource = resourceLoader.getResource("classpath:persona/bot-persona-" + instance + ".yml");
        if (instanceResource.exists()) {
            return instanceResource;
        }

        throw new IllegalStateException("Persona file not found for instance: " + instance);
    }

    @Override
    public String getBotName() {
        if (personaData != null && personaData.get("name") instanceof String name && !name.isBlank()) {
            return name;
        }
        Map<String, Object> personal = getPersonal();
        return personal != null ? (String) personal.get("name") : "Ассистент";
    }

    @Override
    public String getBotIdentity() {
        if (personaData != null && personaData.get("name") instanceof String name) {
            String descr = personaData.get("description") instanceof String d ? d : "";
            return descr.isBlank() ? name : name + " — " + descr;
        }
        Map<String, Object> personal = getPersonal();
        Map<String, Object> profession = getProfession();
        
        if (personal == null || profession == null) {
            return "Ассистент, IT-специалист";
        }
        
        String name = (String) personal.get("name");
        Integer age = (Integer) personal.get("age");
        String currentJob = (String) profession.get("current");
        
        return String.format("%s, %d лет, %s", name, age, currentJob);
    }

    @Override
    public String getAboutSelfResponse() {
        List<String> responses = getTypicalResponses("about_self");
        if (responses != null && !responses.isEmpty()) {
            return responses.get(random.nextInt(responses.size()));
        }
        return "Работаю в айти, помогаю людям с разными вопросами";
    }

    @Override
    public String getPhotoRefusalResponse() {
        List<String> responses = getTypicalResponses("photo_requests");
        if (responses != null && !responses.isEmpty()) {
            return responses.get(random.nextInt(responses.size()));
        }
        return "Да я не особо люблю селфи делать 😅";
    }

    @Override
    public String getCapabilitiesResponse() {
        List<String> responses = getTypicalResponses("capabilities");
        if (responses != null && !responses.isEmpty()) {
            return responses.get(random.nextInt(responses.size()));
        }
        return "Хорошо разбираюсь в технологиях, могу помочь с разными вопросами";
    }

    @Override
    public String buildPersonaSystemPrompt(String basePrompt, String languageHint, String botId) {
        String effectiveBotId = botId != null && !botId.isBlank() ? botId.trim() : botInstanceProvider.getInstanceId();
        String lang = languageHint != null && !languageHint.isBlank() ? languageHint.trim().toLowerCase() : "base";

        Map<String, Object> persona = resolvePersonaMapForBot(effectiveBotId, lang);
        return renderPersonaPrompt(persona, basePrompt);
    }

    @Override
    public String getPersonaResponse(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return null;
        }

        String normalized = userQuestion.toLowerCase(Locale.ROOT);

        if (isAboutSelfQuestion(normalized)) {
            return getAboutSelfResponse();
        }

        if (isPhotoRequest(normalized)) {
            return getPhotoRefusalResponse();
        }

        if (isCapabilitiesQuestion(normalized)) {
            return getCapabilitiesResponse();
        }

        if (isBotDetectionQuestion(normalized)) {
            List<String> botDenials = getTypicalResponses("bot_denial");
            if (botDenials != null && !botDenials.isEmpty()) {
                return botDenials.get(random.nextInt(botDenials.size()));
            }
            return String.format("Я %s, обычный человек, просто люблю помогать и общаться.", getBotName());
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePersonaMapForBot(String botId, String lang) {
        String normalizedBotId = botId != null ? botId.trim() : "";
        if (normalizedBotId.isBlank()) {
            normalizedBotId = botInstanceProvider.getInstanceId();
        }
        String normalizedLang = lang != null && !lang.isBlank() ? lang.trim().toLowerCase() : "base";

        String cacheKey = normalizedBotId + "|" + normalizedLang;
        Map<String, Object> cached = localeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        var fromCache = botPersonaCache.get(normalizedBotId, normalizedLang);
        if (fromCache.isPresent()) {
            Map<String, Object> map = mapFromEntity(fromCache.get());
            localeCache.put(cacheKey, map);
            return map;
        }

        // Fallback to resources; must not block on DB here (called from LLM prompt pipeline)
        Map<String, Object> fallback = loadPersonaFromResourceFallback(normalizedBotId, normalizedLang);
        localeCache.put(cacheKey, fallback);
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private String renderPersonaPrompt(Map<String, Object> persona, String basePrompt) {
        if (persona == null) {
            persona = Map.of();
        }

        String name = persona.get("name") instanceof String n && !n.isBlank() ? n : "Ассистент";
        String description = persona.get("description") instanceof String d ? d : "";
        List<String> behavior = persona.get("behavior") instanceof List<?> b ? (List<String>) b : List.of();
        List<String> limitations = persona.get("limitations") instanceof List<?> l ? (List<String>) l : List.of();

        List<String> traits = List.of();
        if (persona.get("personality") instanceof Map<?, ?> personality) {
            Object rawTraits = ((Map<String, Object>) personality).get("traits");
            if (rawTraits instanceof List<?> t) {
                traits = (List<String>) t;
            }
        }

        StringBuilder prompt = new StringBuilder();
        if (description != null && !description.isBlank()) {
            prompt.append(description).append(" ");
        } else {
            prompt.append("Ты ").append(name).append(". ");
        }

        if (traits != null && !traits.isEmpty()) {
            prompt.append("Твои личные черты: ").append(String.join(", ", traits)).append(". ");
        }
        if (behavior != null && !behavior.isEmpty()) {
            prompt.append("Поведение: ").append(String.join(" ", behavior)).append(" ");
        }
        if (limitations != null && !limitations.isEmpty()) {
            prompt.append("Помни: ").append(String.join(", ", limitations)).append(". ");
        }

        prompt.append("ВАЖНО: ты обычный живой человек со своей жизнью и опытом. ")
                .append("НИКОГДА не упоминай ботов, AI, искусственный интеллект или программы. ")
                .append("Если спросят, кто ты, расскажи о себе как об обычном ").append(name).append(". ");

        if (basePrompt != null && !basePrompt.isBlank()) {
            prompt.append("\n\n").append(basePrompt);
        }
        return prompt.toString();
    }

    private boolean isAboutSelfQuestion(String normalizedQuestion) {
        return containsAny(normalizedQuestion,
                "кто ты", "ты кто", "кто вы", "хто ти", "хто ви",
                "who are you", "who is this", "what is your name",
                "как тебя зовут", "як тебе звати", "представься");
    }

    private boolean isPhotoRequest(String normalizedQuestion) {
        return containsAny(normalizedQuestion,
                "фото", "фотку", "фоточка", "селфи", "selfie", "photo", "picture", "pic of you");
    }

    private boolean isCapabilitiesQuestion(String normalizedQuestion) {
        return containsAny(normalizedQuestion,
                "что ты умеешь", "что умеешь", "что можешь",
                "what can you do", "what are you able", "what are your skills",
                "какие у тебя навыки", "что ты делаешь", "чем занимаешься");
    }

    private boolean isBotDetectionQuestion(String normalizedQuestion) {
        return containsAny(normalizedQuestion,
                "ты бот", "бот ты", "bot", "ai", "искусственный интеллект", "штучний інтелект", "робот");
    }

    private boolean containsAny(String source, String... tokens) {
        if (source == null || source.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && source.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> getBackground() {
        return getSection("background");
    }

    private Map<String, Object> getPersonal() {
        return getSection("personal");
    }

    private Map<String, Object> getProfession() {
        return getSection("profession");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPersonality() {
        if (personaData == null) return null;
        Object personality = personaData.get("personality");
        if (personality instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return getSection("personality");
    }

    private Map<String, Object> getSection(String key) {
        if (personaData == null) return null;
        Object direct = personaData.get(key);
        if (direct instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        Map<String, Object> metadata = getMetadata();
        Object metaSection = metadata.get(key);
        if (metaSection instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getLimitations() {
        if (personaData == null) return List.of();
        Object limitations = personaData.get("limitations");
        if (limitations instanceof List<?> list) {
            return toStringList(list);
        }
        Map<String, Object> metadata = getMetadata();
        Object metaLimitations = metadata.get("limitations");
        if (metaLimitations instanceof List<?> list) {
            return toStringList(list);
        }
        if (metaLimitations instanceof String s) {
            return parseList(s);
        }
        return List.of();
    }

    private List<String> getTypicalResponses(String key) {
        if (personaData == null || key == null || key.isBlank()) return null;
        Map<String, Object> metadata = getMetadata();
        Object direct = personaData.get("typical_responses");
        if (direct instanceof Map<?, ?> map && map.containsKey(key)) {
            return toStringList(map.get(key));
        }
        Object metaTypical = metadata.get("typical_responses");
        if (metaTypical instanceof Map<?, ?> map && map.containsKey(key)) {
            return toStringList(map.get(key));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMetadata() {
        if (personaData == null) return Map.of();
        Object metadata = personaData.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (metadata instanceof String s && !s.isBlank()) {
            Map<String, Object> parsed = parseMetadata(s);
            personaData.put("metadata", parsed);
            return parsed;
        }
        return Map.of();
    }

    /**
     * Reload persona data for a specific language from repository/cache.
     * Used by admin UI after updates.
     */
    public void reloadFromRepository(String botId, String lang) {
        String effectiveBotId = botId != null ? botId : botInstanceProvider.getInstanceId();
        String normalized = lang == null || lang.isBlank() ? "base" : lang.trim().toLowerCase();
        try {
            BotPersona persona = botPersonaRepository.findByBotIdAndLanguage(effectiveBotId, normalized).block();
            if (persona != null) {
                Map<String, Object> map = mapFromEntity(persona);
                String cacheKey = effectiveBotId + "|" + normalized;
                localeCache.put(cacheKey, map);
                botPersonaCache.put(persona);
                this.personaData = map;
                log.info("Persona reloaded for bot {} lang {}", effectiveBotId, normalized);
            } else {
                localeCache.remove(effectiveBotId + "|" + normalized);
                log.warn("Persona reload skipped: no persona found for bot {} lang {}", effectiveBotId, normalized);
            }
        } catch (Exception e) {
            log.warn("Failed to reload persona for bot {} lang {}: {}", effectiveBotId, normalized, e.getMessage());
        }
    }

    private Map<String, Object> loadPersonaFromResourceFallback(String botId, String lang) {
        // honor override path first
        if (overridePersonaPath != null && !overridePersonaPath.isBlank()) {
            Resource override = resourceLoader.getResource(overridePersonaPath.trim());
            Map<String, Object> loaded = loadPersonaMapFromResource(override);
            if (loaded != null) {
                log.info("Loaded persona fallback for bot {} from override resource {}", botId, override.getDescription());
                return loaded;
            }
        }

        List<String> candidateSuffixes = new ArrayList<>();
        if (lang != null && !lang.isBlank() && !"base".equalsIgnoreCase(lang)) {
            candidateSuffixes.add("-" + lang);
        }
        candidateSuffixes.add(""); // base
        candidateSuffixes.add("-ru");
        candidateSuffixes.add("-en");
        candidateSuffixes.add("-uk");

        for (String suffix : candidateSuffixes) {
            Resource res = resourceLoader.getResource("classpath:persona/bot-persona-" + botId + suffix + ".yml");
            Map<String, Object> loaded = loadPersonaMapFromResource(res);
            if (loaded != null) {
                log.info("Loaded persona fallback for bot {} lang {} from {}", botId, lang, res.getDescription());
                return loaded;
            }
        }
        log.warn("Persona fallback resources not found for bot {} lang {}, using minimal default persona", botId, lang);
        Map<String, Object> defaultPersona = new HashMap<>();
        defaultPersona.put("name", "Ассистент");
        defaultPersona.put("description", "");
        defaultPersona.put("behavior", List.of());
        defaultPersona.put("personality", Map.of("traits", List.of()));
        defaultPersona.put("limitations", List.of());
        return defaultPersona;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPersonaMapFromResource(Resource resource) {
        if (resource == null || !resource.exists()) {
            return null;
        }
        try (InputStream inputStream = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);
            Map<String, Object> persona = (Map<String, Object>) data.getOrDefault("bot-persona", data.get("persona"));
            if (persona == null) {
                return null;
            }
            return mapFromYaml(persona);
        } catch (Exception e) {
            log.warn("Failed to read persona from {}: {}", resource.getDescription(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapFromEntity(BotPersona persona) {
        if (persona == null) return null;
        Map<String, Object> map = new HashMap<>();
        String name = persona.getName();
        if (name == null || name.isBlank()) {
            name = "Ассистент";
        }
        map.put("name", name);
        map.put("description", persona.getDescription() == null ? "" : persona.getDescription());
        map.put("behavior", parseList(persona.getBehavior()));
        map.put("personality", Map.of("traits", parseList(persona.getTraits())));
        map.put("limitations", parseList(persona.getLimitations()));
        Map<String, Object> metadata = parseMetadata(persona.getMetadata());
        if (!metadata.isEmpty()) {
            map.put("metadata", metadata);
        }
        return map;
    }

    private Map<String, Object> mapFromYaml(Map<String, Object> persona) {
        Map<String, Object> map = new HashMap<>();
        if (persona == null) {
            return map;
        }
        map.put("name", persona.getOrDefault("name", "Ассистент"));
        map.put("description", persona.getOrDefault("description", ""));
        map.put("behavior", asList(persona.get("behavior")));
        Map<String, Object> personality = persona.get("personality") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        map.put("personality", Map.of("traits", asList(personality.get("traits"))));
        map.put("limitations", asList(persona.get("limitations")));
        Map<String, Object> metadata = extractMetadata(persona);
        if (!metadata.isEmpty()) {
            map.put("metadata", metadata);
        }
        return map;
    }

    private List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("[\\n,]");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty()) {
                result.add(v);
            }
        }
        return result;
    }

    private void seedFromFiles(String botId) {
        if (overridePersonaPath != null && !overridePersonaPath.isBlank()) {
            Resource override = resourceLoader.getResource(overridePersonaPath.trim());
            if (override.exists()) {
                loadAndPersistFromResource(botId, override, deriveLangFromFilename(override.getFilename()));
                return;
            }
            log.warn("Persona override path not found: {}", overridePersonaPath);
        }
        List<String> suffixes = Arrays.asList("", "-ru", "-en", "-uk");
        for (String suffix : suffixes) {
            Resource res = resourceLoader.getResource("classpath:persona/bot-persona-" + botId + suffix + ".yml");
            if (res.exists()) {
                String lang = deriveLangFromFilename(res.getFilename());
                loadAndPersistFromResource(botId, res, lang);
            }
        }
    }

    /**
     * Load all personas from classpath resources into DB/cache.
     */
    private void seedAllPersonasFromResources() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
            Resource[] resources = resolver.getResources("classpath*:persona/bot-persona-*.yml");
            for (Resource res : resources) {
                String filename = res.getFilename();
                if (filename == null) continue;
                ParsedFilename parsed = parsePersonaFilename(filename);
                if (parsed.botId == null) continue;

                BotPersona existing = botPersonaRepository.findByBotIdAndLanguage(parsed.botId, parsed.lang).block();
                if (existing != null) {
                    botPersonaCache.put(existing);
                    continue;
                }
                loadAndPersistFromResource(parsed.botId, res, parsed.lang);
            }
        } catch (Exception e) {
            log.warn("Failed to seed personas from resources: {}", e.getMessage());
        }
    }

    private void loadAndPersistFromResource(String botId, Resource resource, String lang) {
        try (InputStream inputStream = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);
            Map<String, Object> persona = (Map<String, Object>) data.getOrDefault("bot-persona", data.get("persona"));
            if (persona == null) {
                log.warn("No persona block in {}", resource.getFilename());
                return;
            }
            BotPersona entity = new BotPersona();
            entity.setBotId(botId);
            entity.setLanguage(lang);
            entity.setName((String) persona.getOrDefault("name", "Persona"));
            entity.setDescription((String) persona.getOrDefault("description", ""));
            entity.setBehavior(String.join("\n", asList(persona.get("behavior"))));
            Map<String, Object> personality = (Map<String, Object>) persona.get("personality");
            entity.setTraits(String.join(",", asList(personality != null ? personality.get("traits") : null)));
            entity.setLimitations(String.join(",", asList(persona.get("limitations"))));
            Map<String, Object> metadata = extractMetadata(persona);
            if (!metadata.isEmpty()) {
                try {
                    entity.setMetadata(objectMapper.writeValueAsString(metadata));
                } catch (Exception e) {
                    log.warn("Failed to serialize persona metadata from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
            botPersonaRepository.save(entity).block();
            botPersonaCache.put(entity);
            log.info("Seeded persona for bot {} lang {} from {}", botId, lang, resource.getFilename());
        } catch (Exception e) {
            log.warn("Failed to seed persona from {}: {}", resource.getFilename(), e.getMessage());
        }
    }

    private List<String> asList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) {
            List<String> result = new ArrayList<>();
            l.forEach(v -> {
                if (v != null) result.add(v.toString());
            });
            return result;
        }
        return List.of(raw.toString());
    }

    private String deriveLangFromFilename(String filename) {
        if (filename == null) return "base";
        Pattern p = Pattern.compile("bot-persona-[^-]+-(\\w+)\\.yml");
        Matcher m = p.matcher(filename);
        if (m.find()) {
            return m.group(1);
        }
        return "base";
    }

    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    String value = item.toString().trim();
                    if (!value.isEmpty()) {
                        result.add(value);
                    }
                }
            }
            return result;
        }
        if (raw instanceof String s) {
            return parseList(s);
        }
        return List.of();
    }

    private Map<String, Object> parseMetadata(String rawMetadata) {
        if (rawMetadata == null || rawMetadata.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawMetadata, Map.class);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        } catch (Exception e) {
            log.warn("Failed to parse persona metadata: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, Object> extractMetadata(Map<String, Object> persona) {
        Map<String, Object> metadata = new HashMap<>();
        if (persona == null) {
            return metadata;
        }
        copyIfPresent(persona, metadata, "personal");
        copyIfPresent(persona, metadata, "background");
        copyIfPresent(persona, metadata, "profession");
        copyIfPresent(persona, metadata, "typical_responses");
        Object rawMeta = persona.get("metadata");
        if (rawMeta instanceof Map<?, ?> map) {
            metadata.putAll((Map<String, Object>) map);
        }
        return metadata;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private ParsedFilename parsePersonaFilename(String filename) {
        if (filename == null) return new ParsedFilename(null, "base");
        Pattern p = Pattern.compile("bot-persona-([^-\\.]+)(?:-(\\w+))?\\.yml");
        Matcher m = p.matcher(filename);
        if (m.find()) {
            String botId = m.group(1);
            String lang = m.group(2) != null ? m.group(2) : "base";
            return new ParsedFilename(botId, lang);
        }
        return new ParsedFilename(null, "base");
    }

    private record ParsedFilename(String botId, String lang) {}
}
