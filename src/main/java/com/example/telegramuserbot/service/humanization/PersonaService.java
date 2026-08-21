package com.example.telegramuserbot.service.humanization;

/**
 * Service for managing bot persona - who the bot pretends to be
 */
public interface PersonaService {
    
    /**
     * Get bot's name
     */
    String getBotName();
    
    /**
     * Get bot's full identity description
     */
    String getBotIdentity();
    
    /**
     * Get response for "who are you" questions
     */
    String getAboutSelfResponse();
    
    /**
     * Get response for photo requests
     */
    String getPhotoRefusalResponse();
    
    /**
     * Get response about bot capabilities
     */
    String getCapabilitiesResponse();
    
    /**
     * Build persona-enhanced system prompt
     */
    String buildPersonaSystemPrompt(String basePrompt);

    /**
     * Build persona-enhanced system prompt with language-specific persona file override.
     */
    String buildPersonaSystemPrompt(String basePrompt, String languageHint);

    /**
     * Build persona-enhanced system prompt for a specific bot persona id.
     * <p>
     * Default implementation keeps backward compatibility and ignores botId.
     */
    default String buildPersonaSystemPrompt(String basePrompt, String languageHint, String botId) {
        return buildPersonaSystemPrompt(basePrompt, languageHint);
    }
    
    /**
     * Get persona-appropriate response for specific question type
     */
    String getPersonaResponse(String userQuestion);
}
