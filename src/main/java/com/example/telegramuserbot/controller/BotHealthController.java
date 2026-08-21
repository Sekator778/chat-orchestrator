package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.dto.BotHealthResponse;
import com.example.telegramuserbot.service.BotHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Health endpoint reporting the status of every configured bot instance.
 *
 * <p>GET /api/bots/health
 */
@RestController
@RequestMapping("/api/bots")
public final class BotHealthController {

    private final BotHealthService botHealthService;

    public BotHealthController(BotHealthService botHealthService) {
        this.botHealthService = botHealthService;
    }

    /**
     * Returns the aggregate health snapshot for all configured bots.
     * The snapshot is built lazily on subscription via {@code fromSupplier}.
     */
    @GetMapping("/health")
    public Mono<BotHealthResponse> health() {
        return Mono.fromSupplier(botHealthService::getBotHealth);
    }
}
