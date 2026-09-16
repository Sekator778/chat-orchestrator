package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.BotPersona;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.service.cache.BotPersonaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Covers resolveStyle (metadata.style parsing — both entity-JSON-string and
 * YAML-Map shapes, and graceful fallback to defaults on anything missing or
 * invalid) and the localised buildPersonaSystemPrompt carrier scaffold.
 */
class PersonaServiceImplTest {

    private ResourceLoader resourceLoader;
    private BotInstanceProvider botInstanceProvider;
    private BotPersonaRepository botPersonaRepository;
    private BotPersonaCache botPersonaCache;
    private PersonaServiceImpl service;

    @BeforeEach
    void setUp() {
        resourceLoader = Mockito.mock(ResourceLoader.class);
        botInstanceProvider = Mockito.mock(BotInstanceProvider.class);
        botPersonaRepository = Mockito.mock(BotPersonaRepository.class);
        botPersonaCache = Mockito.mock(BotPersonaCache.class);
        Mockito.when(botInstanceProvider.getInstanceId()).thenReturn("bot-default");
        service = new PersonaServiceImpl(resourceLoader, botInstanceProvider, botPersonaRepository, botPersonaCache);
    }

    private BotPersona entity(String botId, String lang, String name, String metadataJson) {
        BotPersona p = new BotPersona();
        p.setBotId(botId);
        p.setLanguage(lang);
        p.setName(name);
        p.setDescription("");
        p.setBehavior("");
        p.setTraits("");
        p.setLimitations("");
        p.setMetadata(metadataJson);
        return p;
    }

    // --- resolveStyle: metadata as a JSON string on the entity ---------------

    @Test
    void resolveStyleParsesMetadataJsonString() {
        String metadata = "{\"style\":{"
                + "\"max_sentences\":3,"
                + "\"emoji\":\"often\","
                + "\"lowercase_start\":true,"
                + "\"drop_final_period\":false,"
                + "\"skip_probability\":0.25,"
                + "\"catchphrases\":[\"ну такое\",\"по факту\"],"
                + "\"interests\":[\"крипта\",\"макро\"],"
                + "\"timezone\":\"Europe/Berlin\"}}";
        Mockito.when(botPersonaCache.get("bot1", "en")).thenReturn(Optional.of(entity("bot1", "en", "Nova", metadata)));

        PersonaStyle style = service.resolveStyle("bot1", "en");

        assertThat(style.maxSentences()).isEqualTo(3);
        assertThat(style.emoji()).isEqualTo(PersonaStyle.EmojiUsage.OFTEN);
        assertThat(style.lowercaseStart()).isTrue();
        assertThat(style.dropFinalPeriod()).isFalse();
        assertThat(style.skipProbability()).isEqualTo(0.25);
        assertThat(style.catchphrases()).containsExactly("ну такое", "по факту");
        assertThat(style.interests()).containsExactly("крипта", "макро");
        assertThat(style.timezone()).isEqualTo("Europe/Berlin");
    }

    // --- resolveStyle: metadata as a native Map (YAML fallback path) --------

    @Test
    void resolveStyleParsesMetadataAsMapFromYamlResource() {
        Mockito.when(botPersonaCache.get("bot2", "en")).thenReturn(Optional.empty());
        String yaml = """
                bot-persona:
                  name: "Nova"
                  description: ""
                  behavior:
                    - "test"
                  personality:
                    traits:
                      - calm
                  limitations: []
                  metadata:
                    style:
                      max_sentences: 1
                      emoji: none
                      catchphrases:
                        - "test phrase"
                """;
        Resource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
        Mockito.when(resourceLoader.getResource("classpath:persona/bot-persona-bot2-en.yml")).thenReturn(resource);

        PersonaStyle style = service.resolveStyle("bot2", "en");

        assertThat(style.maxSentences()).isEqualTo(1);
        assertThat(style.emoji()).isEqualTo(PersonaStyle.EmojiUsage.NONE);
        assertThat(style.catchphrases()).containsExactly("test phrase");
    }

    // --- resolveStyle: missing/invalid -> defaults, never throws -------------

    @Test
    void resolveStyleFallsBackToDefaultsWhenMetadataMissing() {
        Mockito.when(botPersonaCache.get("bot3", "en")).thenReturn(Optional.of(entity("bot3", "en", "Nova", null)));

        PersonaStyle style = service.resolveStyle("bot3", "en");

        assertThat(style).isEqualTo(PersonaStyle.defaults());
    }

    @Test
    void resolveStyleFallsBackToDefaultsWhenMetadataIsNotValidJson() {
        Mockito.when(botPersonaCache.get("bot4", "en")).thenReturn(Optional.of(entity("bot4", "en", "Nova", "not json at all")));

        PersonaStyle style = service.resolveStyle("bot4", "en");

        assertThat(style).isEqualTo(PersonaStyle.defaults());
    }

    @Test
    void resolveStyleFallsBackToDefaultsWhenStyleKeyIsNotAMap() {
        Mockito.when(botPersonaCache.get("bot5", "en"))
                .thenReturn(Optional.of(entity("bot5", "en", "Nova", "{\"style\":\"not-a-map\"}")));

        PersonaStyle style = service.resolveStyle("bot5", "en");

        assertThat(style).isEqualTo(PersonaStyle.defaults());
    }

    @Test
    void resolveStyleUsesFieldDefaultsWhenOneFieldHasTheWrongType() {
        // max_sentences carries a garbage type; the rest of the style block is still honored.
        String metadata = "{\"style\":{\"max_sentences\":\"lots\",\"emoji\":\"often\"}}";
        Mockito.when(botPersonaCache.get("bot6", "en")).thenReturn(Optional.of(entity("bot6", "en", "Nova", metadata)));

        PersonaStyle style = service.resolveStyle("bot6", "en");

        assertThat(style.maxSentences()).isEqualTo(PersonaStyle.defaults().maxSentences());
        assertThat(style.emoji()).isEqualTo(PersonaStyle.EmojiUsage.OFTEN);
    }

    @Test
    void resolveStyleNeverThrowsWhenPersonaNotFoundAnywhere() {
        Mockito.when(botPersonaCache.get(any(), any())).thenReturn(Optional.empty());
        Mockito.when(resourceLoader.getResource(any())).thenReturn(new NonExistentResource());

        PersonaStyle style = service.resolveStyle("unknown-bot", "en");

        assertThat(style).isEqualTo(PersonaStyle.defaults());
    }

    // --- buildPersonaSystemPrompt: localised carrier scaffold -----------------

    @Test
    void buildPersonaSystemPromptEnUsesEnglishCarrierSentences() {
        Mockito.when(botPersonaCache.get("bot7", "en")).thenReturn(Optional.of(entity("bot7", "en", "Nova", null)));

        String prompt = service.buildPersonaSystemPrompt(null, "en", "bot7");

        assertThat(prompt).contains("You are Nova.");
        assertThat(prompt).contains("IMPORTANT:");
        assertThat(prompt).doesNotContain("ВАЖНО");
    }

    @Test
    void buildPersonaSystemPromptRuUsesRussianCarrierSentences() {
        Mockito.when(botPersonaCache.get("bot8", "ru")).thenReturn(Optional.of(entity("bot8", "ru", "Нова", null)));

        String prompt = service.buildPersonaSystemPrompt(null, "ru", "bot8");

        assertThat(prompt).contains("Ты Нова.");
        assertThat(prompt).contains("ВАЖНО:");
        assertThat(prompt).doesNotContain("IMPORTANT");
    }

    @Test
    void buildPersonaSystemPromptAppendsBasePromptWhenGiven() {
        Mockito.when(botPersonaCache.get("bot9", "en")).thenReturn(Optional.of(entity("bot9", "en", "Nova", null)));

        String prompt = service.buildPersonaSystemPrompt("Chat-specific instructions.", "en", "bot9");

        assertThat(prompt).contains("Chat-specific instructions.");
    }

    /** A Resource that reports it does not exist, for the "nothing found anywhere" path. */
    private static final class NonExistentResource extends ByteArrayResource {
        NonExistentResource() {
            super(new byte[0]);
        }

        @Override
        public boolean exists() {
            return false;
        }
    }
}
