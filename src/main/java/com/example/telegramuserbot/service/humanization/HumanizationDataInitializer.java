package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.repository.ResponseVariationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Initializes the humanization system with default response variations.
 * Creates templates for anti-detection and natural conversation flow.
 */
@Component
@Order(100) // Run early during startup
public class HumanizationDataInitializer {
    
    private static final Logger log = LoggerFactory.getLogger(HumanizationDataInitializer.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);
    
    private final ResponseVariationRepository responseVariationRepository;
    
    private volatile boolean initialized = false;

    public HumanizationDataInitializer(ResponseVariationRepository responseVariationRepository) {
        this.responseVariationRepository = responseVariationRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeOnStartup() {
        if (initialized) {
            log.debug("Humanization data already initialized, skipping");
            return;
        }
        
        try {
            // Check if we need to initialize
            long existingCount = blockCount(responseVariationRepository.count());
            long botDenialCount = blockCount(responseVariationRepository.countByIntentTypeAndEnabledTrue(ResponseIntent.BOT_DENIAL));
            
            log.info(">>> STARTUP: Humanization system check - Total variations: {}, Bot denial variations: {}", 
                    existingCount, botDenialCount);
            
            if (existingCount == 0 || botDenialCount == 0) {
                log.info(">>> STARTUP: Initializing humanization response variations...");
                initializeResponseVariations();
                
                // Verify initialization
                long newCount = blockCount(responseVariationRepository.count());
                long newBotDenialCount = blockCount(responseVariationRepository.countByIntentTypeAndEnabledTrue(ResponseIntent.BOT_DENIAL));
                
                log.info("<<< STARTUP: Humanization response variations initialized successfully");
                log.info("<<< STARTUP: Total variations: {}, Bot denial variations: {}", newCount, newBotDenialCount);
                
                if (newBotDenialCount == 0) {
                    log.error("!!! STARTUP CRITICAL: Bot denial variations still not found after initialization!");
                    throw new RuntimeException("Failed to initialize critical bot denial variations");
                }
            } else {
                log.info(">>> STARTUP: Response variations already exist, validating critical variations...");
                validateCriticalVariations();
            }
            
            initialized = true;
        } catch (Exception e) {
            log.error("!!! STARTUP ERROR: Failed to initialize humanization data: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize humanization system", e);
        }
    }
    
    /**
     * Manual initialization method for testing or forced re-initialization
     */
    public void forceInitialization() {
        log.info(">>> MANUAL: Force initializing humanization response variations...");
        
        // Clear existing data
        long existingCount = blockCount(responseVariationRepository.count());
        if (existingCount > 0) {
            log.info(">>> MANUAL: Clearing {} existing response variations", existingCount);
            blockDeleteAll();
        }
        
        // Initialize fresh data
        initializeResponseVariations();
        initialized = true;
        
        long newCount = blockCount(responseVariationRepository.count());
        long botDenialCount = blockCount(responseVariationRepository.countByIntentTypeAndEnabledTrue(ResponseIntent.BOT_DENIAL));
        
        log.info("<<< MANUAL: Force initialization complete - Total: {}, Bot denial: {}", newCount, botDenialCount);
    }
    
    /**
     * Validate that critical variations exist
     */
    private void validateCriticalVariations() {
        List<ResponseIntent> criticalIntents = List.of(
                ResponseIntent.BOT_DENIAL,
                ResponseIntent.DEFLECTION,
                ResponseIntent.GREETING,
                ResponseIntent.ACKNOWLEDGMENT
        );
        
        for (ResponseIntent intent : criticalIntents) {
            long count = blockCount(responseVariationRepository.countByIntentTypeAndEnabledTrue(intent));
            if (count == 0) {
                log.warn("!!! STARTUP WARNING: No variations found for critical intent: {}", intent);
            } else {
                log.debug(">>> STARTUP: Found {} variations for intent: {}", count, intent);
            }
        }
    }

    private void initializeResponseVariations() {
        // Bot denial responses (CRITICAL for anti-detection)
        createBotDenialVariations();
        
        // Greeting variations
        createGreetingVariations();
        
        // Acknowledgment variations
        createAcknowledgmentVariations();
        
        // Clarification variations
        createClarificationVariations();
        
        // Deflection variations
        createDeflectionVariations();
        
        // Empathy variations
        createEmpathyVariations();
        
        // Humor variations
        createHumorVariations();
        
        // Thinking/processing variations
        createThinkingVariations();
        
        // Casual chat variations
        createCasualChatVariations();
    }

    private void createBotDenialVariations() {
        List<ResponseVariation> botDenials = List.of(
                // Confusion-based deflections
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Дивне питання! А чому ти запитуєш?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Хм, а я схожий на бота? 😄", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Що за дивні питання! Я просто намагаюся допомогти)", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "А ти що, перевіряєш мене? 😏", CommunicationStyle.HUMOROUS),
                
                // Counter-questioning strategies
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "А сам як думаєш? Я на бота схожий?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Хаха, що це за перевірка? Ти детектив якийсь? 🕵️", CommunicationStyle.HUMOROUS),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Чого це раптом таке питання? Щось підозріле помітив?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "А що тебе на таку думку навело? Занадто розумний? 😎", CommunicationStyle.HUMOROUS),
                
                // Personal touch deflections
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Ну ти і смішний) Просто люблю допомагати людям", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Та ну тебе! Може, просто добре налаштований сьогодні", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Хм, а ти часто такі питання задаєш? 😅", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "А що, якась особливість помітила? Розкажи", CommunicationStyle.CASUAL),
                
                // Casual dismissals
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Та ну тебе! Просто багато читаю, от і здаюся розумним", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Хм, мабуть, дуже формально пишу? Спробую простіше", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Ну що ти! Може, просто настрій хороший сьогодні", CommunicationStyle.FRIENDLY),
                
                // Enhanced anti-detection responses
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "А ти що, тестуєш мене? 😄 Нормальний я, звичайний", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Хм, дивне питання... А чому це тебе цікавить?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Та ну тебе! Просто люблю розмовляти з людьми", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "А ти сам що думаєш? Я на бота схожий?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.BOT_DENIAL, "Хаха, що це за перевірка? Ти детектив якийсь? 🕵️", CommunicationStyle.HUMOROUS)
        );
        
        botDenials.forEach(variation -> {
            variation.setWeight(15); // High priority for bot denial
            variation.setFormalityLevel(2); // Casual formality
            variation.setEmotionalTone("defensive");
        });
        
        blockSaveAll(botDenials);
        log.info("Created {} bot denial variations", botDenials.size());
    }

    private void createGreetingVariations() {
        List<ResponseVariation> greetings = List.of(
                new ResponseVariation(ResponseIntent.GREETING, "Привіт! 👋", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.GREETING, "Вітаю!", CommunicationStyle.FORMAL),
                new ResponseVariation(ResponseIntent.GREETING, "Привіт! Як справи?", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.GREETING, "Доброго дня!", CommunicationStyle.FORMAL),
                new ResponseVariation(ResponseIntent.GREETING, "Привіт! Що нового?", CommunicationStyle.CASUAL)
        );
        
        greetings.forEach(variation -> {
            variation.setWeight(10);
            variation.setFormalityLevel(3);
        });
        
        blockSaveAll(greetings);
    }

    private void createAcknowledgmentVariations() {
        List<ResponseVariation> acknowledgments = List.of(
                new ResponseVariation(ResponseIntent.ACKNOWLEDGMENT, "Зрозуміло", CommunicationStyle.NEUTRAL),
                new ResponseVariation(ResponseIntent.ACKNOWLEDGMENT, "Так, зрозуміло", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.ACKNOWLEDGMENT, "Окей", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.ACKNOWLEDGMENT, "Добре", CommunicationStyle.NEUTRAL),
                new ResponseVariation(ResponseIntent.ACKNOWLEDGMENT, "Зрозуміло, дякую", CommunicationStyle.FORMAL)
        );
        
        acknowledgments.forEach(variation -> {
            variation.setWeight(8);
            variation.setFormalityLevel(3);
        });
        
        blockSaveAll(acknowledgments);
    }

    private void createClarificationVariations() {
        List<ResponseVariation> clarifications = List.of(
                new ResponseVariation(ResponseIntent.CLARIFICATION, "Можеш пояснити детальніше?", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.CLARIFICATION, "Що саме маєш на увазі?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.CLARIFICATION, "Будь ласка, уточни", CommunicationStyle.FORMAL),
                new ResponseVariation(ResponseIntent.CLARIFICATION, "Не зовсім зрозуміло, можеш розширити?", CommunicationStyle.FRIENDLY)
        );
        
        clarifications.forEach(variation -> {
            variation.setWeight(12);
            variation.setFormalityLevel(3);
        });
        
        blockSaveAll(clarifications);
    }

    private void createDeflectionVariations() {
        List<ResponseVariation> deflections = List.of(
                new ResponseVariation(ResponseIntent.DEFLECTION, "Хм, не зовсім зрозуміло що ти маєш на увазі", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.DEFLECTION, "Дивне питання... А чому це тебе цікавить?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.DEFLECTION, "А ти сам що думаєш з цього приводу?", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.DEFLECTION, "Цікаво... Розкажи більше про свої думки", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.DEFLECTION, "Хм, а чому ти так думаєш?", CommunicationStyle.CASUAL)
        );
        
        deflections.forEach(variation -> {
            variation.setWeight(14); // High priority for deflection
            variation.setFormalityLevel(2);
            variation.setEmotionalTone("confused");
        });
        
        blockSaveAll(deflections);
    }

    private void createEmpathyVariations() {
        List<ResponseVariation> empathy = List.of(
                new ResponseVariation(ResponseIntent.EMPATHY, "Розумію, це дійсно складно", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.EMPATHY, "Співчуваю, це неприємно", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.EMPATHY, "Це дійсно важко, розумію", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.EMPATHY, "Співчуваю твоїй ситуації", CommunicationStyle.FRIENDLY)
        );
        
        empathy.forEach(variation -> {
            variation.setWeight(10);
            variation.setFormalityLevel(3);
            variation.setEmotionalTone("empathetic");
        });
        
        blockSaveAll(empathy);
    }

    private void createHumorVariations() {
        List<ResponseVariation> humor = List.of(
                new ResponseVariation(ResponseIntent.HUMOR, "Хаха, смішно 😄", CommunicationStyle.HUMOROUS),
                new ResponseVariation(ResponseIntent.HUMOR, "Ну ти і смішний! 😂", CommunicationStyle.HUMOROUS),
                new ResponseVariation(ResponseIntent.HUMOR, "Хороший жарт! 😆", CommunicationStyle.HUMOROUS),
                new ResponseVariation(ResponseIntent.HUMOR, "Ну що ти! 😅", CommunicationStyle.HUMOROUS)
        );
        
        humor.forEach(variation -> {
            variation.setWeight(8);
            variation.setFormalityLevel(2);
            variation.setEmotionalTone("amused");
        });
        
        blockSaveAll(humor);
    }

    private void createThinkingVariations() {
        List<ResponseVariation> thinking = List.of(
                new ResponseVariation(ResponseIntent.THINKING, "Хм... Дай подумати", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.THINKING, "Секунду...", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.THINKING, "Хвилинку...", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.THINKING, "Так...", CommunicationStyle.CASUAL)
        );
        
        thinking.forEach(variation -> {
            variation.setWeight(6);
            variation.setFormalityLevel(2);
            variation.setEmotionalTone("thoughtful");
        });
        
        blockSaveAll(thinking);
    }

    private void createCasualChatVariations() {
        List<ResponseVariation> casualChat = List.of(
                new ResponseVariation(ResponseIntent.CASUAL_CHAT, "Ну то як справи?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.CASUAL_CHAT, "Що нового?", CommunicationStyle.CASUAL),
                new ResponseVariation(ResponseIntent.CASUAL_CHAT, "Як день проходить?", CommunicationStyle.FRIENDLY),
                new ResponseVariation(ResponseIntent.CASUAL_CHAT, "Що цікавого?", CommunicationStyle.CASUAL)
        );
        
        casualChat.forEach(variation -> {
            variation.setWeight(7);
            variation.setFormalityLevel(2);
            variation.setEmotionalTone("casual");
        });
        
        blockSaveAll(casualChat);
    }

    private long blockCount(Mono<Long> source) {
        return source.blockOptional(BLOCK_TIMEOUT).orElse(0L);
    }

    private void blockDeleteAll() {
        responseVariationRepository.deleteAll().block(BLOCK_TIMEOUT);
    }

    private void blockSaveAll(List<ResponseVariation> variations) {
        if (variations == null || variations.isEmpty()) {
            return;
        }
        responseVariationRepository.saveAll(variations).collectList().block(BLOCK_TIMEOUT);
    }
}
