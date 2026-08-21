package com.example.telegramuserbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupInfoLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    @Autowired(required = false)
    private BuildProperties buildProperties;

    @Autowired
    private Environment env;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String[] active = env.getActiveProfiles();
        String profiles = String.join(", ", active.length > 0 ? active : env.getDefaultProfiles());
        String version = buildProperties != null ? buildProperties.getVersion() : "unknown";
        String appName = env.getProperty("spring.application.name", "");
        log.info("Started [{}] | profiles: [{}] | version: {}", appName, profiles, version);
    }
}
