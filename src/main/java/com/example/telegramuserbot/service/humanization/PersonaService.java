package com.example.telegramuserbot.service.humanization;

/**
 * Service for managing bot persona - who the bot pretends to be
 */
public interface PersonaService {
    
    /**
     * Name of one persona.
     * <p>
     * botId is required for the same reason it is on
     * {@link #buildPersonaSystemPrompt}: several personas are served from one
     * process, and an answer given without one is the answer for whichever
     * persona was loaded at startup. A blank botId falls back to that persona
     * explicitly rather than by accident.
     */
    String getBotName(String botId);
    
    /**
     * Full identity description of one persona.
     */
    String getBotIdentity(String botId);
    
    /**
     * Response for "who are you" questions, in one persona's voice.
     */
    String getAboutSelfResponse(String botId);
    
    /**
     * Response for photo requests, in one persona's voice.
     */
    String getPhotoRefusalResponse(String botId);
    
    /**
     * Response about capabilities, in one persona's voice.
     */
    String getCapabilitiesResponse(String botId);
    
    /**
     * Build the persona-enhanced system prompt for one bot persona.
     * <p>
     * botId is required: with several personas fanned out from a single process,
     * a prompt built without one is a prompt built for whichever persona happened
     * to be loaded last.
     */
    String buildPersonaSystemPrompt(String basePrompt, String languageHint, String botId);
    
    /**
     * Persona-appropriate canned response for a specific question type,
     * in the voice of the persona that is about to answer.
     */
    String getPersonaResponse(String userQuestion, String botId);
}
