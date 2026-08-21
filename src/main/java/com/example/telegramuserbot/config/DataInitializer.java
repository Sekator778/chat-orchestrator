package com.example.telegramuserbot.config;

import com.example.telegramuserbot.service.humanization.PersonaServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final PersonaServiceImpl personaService;

    public DataInitializer(PersonaServiceImpl personaService) {
        this.personaService = personaService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info(">>> Application Started. Initializing Persona Data...");
        personaService.loadPersona();
    }
}