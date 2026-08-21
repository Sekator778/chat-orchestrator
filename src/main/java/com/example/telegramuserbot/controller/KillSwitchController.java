package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.service.safety.OutboundKillSwitch;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Owner control plane: the emergency stop for ALL outbound Telegram traffic
 * of every persona. The flip is durable (bot.runtime_flags) and takes effect
 * immediately in-process.
 */
@RestController
@RequestMapping("/api/admin/kill-switch")
public class KillSwitchController {

    private final OutboundKillSwitch killSwitch;

    public KillSwitchController(OutboundKillSwitch killSwitch) {
        this.killSwitch = killSwitch;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> status() {
        return Mono.just(ResponseEntity.ok(Map.of("active", killSwitch.isActive())));
    }

    @PostMapping("/activate")
    public Mono<ResponseEntity<Map<String, Object>>> activate() {
        return killSwitch.set(true)
                .map(active -> ResponseEntity.ok(Map.of("active", active, "message", "All outbound Telegram traffic is SUPPRESSED")));
    }

    @PostMapping("/deactivate")
    public Mono<ResponseEntity<Map<String, Object>>> deactivate() {
        return killSwitch.set(false)
                .map(active -> ResponseEntity.ok(Map.of("active", active, "message", "Outbound Telegram traffic is allowed")));
    }
}
