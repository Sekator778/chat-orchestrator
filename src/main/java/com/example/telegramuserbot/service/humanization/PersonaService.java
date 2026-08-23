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
     * Build the persona-enhanced system prompt for one bot persona.
     * <p>
     * botId is required: with several personas fanned out from a single process,
     * a prompt built without one is a prompt built for whichever persona happened
     * to be loaded last.
     */
    String buildPersonaSystemPrompt(String basePrompt, String languageHint, String botId);
    
    /**
     * Get persona-appropriate response for specific question type
     */
    String getPersonaResponse(String userQuestion);
}
