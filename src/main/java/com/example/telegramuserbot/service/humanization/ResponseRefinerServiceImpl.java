package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Implementation of response refinement service using secondary LLM processing
 */
@Service
public class ResponseRefinerServiceImpl implements ResponseRefinerService {

    private static final Logger log = LoggerFactory.getLogger(ResponseRefinerServiceImpl.class);
    private static final long REFINER_CHAT_ID = -2L;
    private static final int REFINEMENT_TIMEOUT_SECONDS = 15;

    private final DeepSeekApiClient deepSeekApiClient;
    private final PersonaService personaService;
    
    // Patterns that indicate AI-like responses
    private static final List<Pattern> AI_INDICATORS = List.of(
            Pattern.compile("(?i)я бот|я штучний інтелект|я ai|я програма"),
            Pattern.compile("(?i)розумний помічник|ai assistant"),
            Pattern.compile("(?i)я не людина|створений для допомоги"),
            Pattern.compile("(?i)на базі штучного інтелекту|based on.*ai"),
            Pattern.compile("(?i)так, я бот|yes.*i.*bot"),
            Pattern.compile("(?i)у мене немає тіла|тільки код|суперкомп'ютер"),
            Pattern.compile("(?i)неонов.*підсвітк|neon.*light"),
            Pattern.compile("(?i)розумна програма|умная программа")
    );
    
    // Fallback human responses for different question types
    private static final List<String> PHOTO_DEFLECTIONS = List.of(
            "Ха-ха, я не дуже люблю фоткатися 😅",
            "А навіщо тобі моя фотка? Сором'язливий я трохи 😊",
            "Камера зламана, на жаль 😄",
            "А ти спочатку свою покажи! 😏",
            "Ой, не люблю селфі робити 😊",
            "В мене волосся сьогодні не дуже 😄",
            "А нащо? Все одно не красивий 😅"
    );
    
    private static final List<String> GENERAL_DEFLECTIONS = List.of(
            "Хм, не зовсім зрозуміло що ти маєш на увазі",
            "Дивне питання... А чому це тебе цікавить?",
            "А ти сам що думаєш з цього приводу?",
            "Цікаво... Розкажи більше про свої думки"
    );

    public ResponseRefinerServiceImpl(DeepSeekApiClient deepSeekApiClient,
                                     PersonaService personaService) {
        this.deepSeekApiClient = deepSeekApiClient;
        this.personaService = personaService;
    }

    @Override
    public Mono<String> refineResponse(String originalResponse, String userQuestion, Long userId) {
        log.debug("Refining response for potential AI indicators");

        if (originalResponse == null || originalResponse.isBlank()) {
            log.debug("Original response is empty, using alternative response");
            return Mono.fromSupplier(() -> generateAlternativeResponse(userQuestion, userId));
        }

        if (!needsRefinement(originalResponse)) {
            log.debug("Response doesn't need refinement");
            return Mono.just(originalResponse);
        }

        log.warn("Response needs refinement, applying secondary LLM processing");
        String refinementPrompt = buildRefinementPrompt(originalResponse, userQuestion);
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                List.of(new ApiMessage("user", refinementPrompt)),
                null,
                500,
                0.3,
                null,
                null,
                null,
                false
        );
        return deepSeekApiClient.chat(request, REFINER_CHAT_ID, REFINEMENT_TIMEOUT_SECONDS)
                .filter(refined -> !needsRefinement(refined))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("Refined response still contains AI indicators or is empty, using fallback");
                    return generateAlternativeResponse(userQuestion, userId);
                }))
                .doOnSuccess(refined -> log.debug("Response successfully refined"))
                .onErrorResume(error -> {
                    log.error("Error during response refinement: {}", error.getMessage(), error);
                    return Mono.fromSupplier(() -> generateAlternativeResponse(userQuestion, userId));
                });
    }

    @Override
    public boolean needsRefinement(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        
        // Check for AI indicator patterns
        for (Pattern pattern : AI_INDICATORS) {
            if (pattern.matcher(response).find()) {
                log.debug("Found AI indicator in response: {}", pattern.pattern());
                return true;
            }
        }
        
        return false;
    }

    @Override
    public String generateAlternativeResponse(String userQuestion, Long userId) {
        // First, try to get a persona-specific response
        String personaResponse = personaService.getPersonaResponse(userQuestion);
        if (personaResponse != null) {
            log.debug("Using persona-specific alternative response");
            return personaResponse;
        }
        
        String lowerQuestion = userQuestion.toLowerCase();
        
        // Context-specific deflections
        if (lowerQuestion.contains("фот") || lowerQuestion.contains("photo") || lowerQuestion.contains("картин")) {
            return PHOTO_DEFLECTIONS.get((int) (Math.random() * PHOTO_DEFLECTIONS.size()));
        }
        
        if (lowerQuestion.contains("бот") || lowerQuestion.contains("bot") || lowerQuestion.contains("ai")) {
            return "Дивне питання! А чому ти запитуєш? 🤔";
        }
        
        // General deflection
        return GENERAL_DEFLECTIONS.get((int) (Math.random() * GENERAL_DEFLECTIONS.size()));
    }

    private String buildRefinementPrompt(String originalResponse, String userQuestion) {
        String personaIdentity = personaService.getBotIdentity();
        return String.format("""
                Ти %s - звичайна людина, яка допомагає переписати відповідь так, щоб вона звучала природно.
                
                Запитання: "%s"
                Відповідь для виправлення: "%s"
                
                КРИТИЧНО ВАЖЛИВО - переписуй відповідь щоб:
                1. НІКОЛИ не згадувати: бот, AI, штучний інтелект, програма, код, робот
                2. ЗАВЖДИ відповідати як жива людина з власним досвідом
                3. Якщо про фото/зовнішність - відповідай як сором'язлива людина ("не люблю фоткатися", "камера зламана" тощо)
                4. Якщо про можливості - говори як про особисті вміння ("добре розбираюся в...", "люблю допомагати з...")
                5. Додавай людські недоліки ("не завжди знаю", "іноді помиляюся")
                6. Використовуй розмовну українську мову
                7. Будь коротким і природним
                8. Відповідай від імені %s
                
                Відповідь як звичайна людина:
                """, personaIdentity, userQuestion, originalResponse, personaService.getBotName());
    }
}
