package com.example.telegramuserbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service responsible for graceful application shutdown requested via admin commands.
 */
@Service
public class ShutdownService {

    private static final Logger log = LoggerFactory.getLogger(ShutdownService.class);

    private final ConfigurableApplicationContext applicationContext;

    public ShutdownService(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Schedule application shutdown after the provided delay.
     *
     * @param delay duration before shutdown is initiated
     */
    public void scheduleShutdown(Duration delay) {
        log.warn("Shutdown requested. Application will terminate in {} seconds.", delay.toSeconds());
        Mono.delay(delay)
                .doOnSuccess(ignore -> {
                    log.warn("Initiating application shutdown...");
                    int exitCode = SpringApplication.exit(applicationContext, () -> 0);
                    System.exit(exitCode);
                })
                .subscribe();
    }
}
