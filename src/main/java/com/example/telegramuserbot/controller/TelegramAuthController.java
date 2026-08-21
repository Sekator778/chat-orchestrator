package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.service.auth.TelegramAuthCodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Owner-facing endpoint to feed Telegram login codes/2FA passwords to an
 * account that is logging in headless (no container console access).
 */
@RestController
@RequestMapping("/api/admin/auth")
public class TelegramAuthController {

    private final TelegramAuthCodeService authCodeService;

    public TelegramAuthController(TelegramAuthCodeService authCodeService) {
        this.authCodeService = authCodeService;
    }

    @PostMapping("/{botId}/code")
    public Mono<ResponseEntity<Map<String, Object>>> submitCode(@PathVariable String botId,
                                                                @RequestBody Map<String, String> body) {
        String value = body.get("code");
        if (value == null || value.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "missing 'code'")));
        }
        String kind = body.getOrDefault("kind", TelegramAuthCodeService.KIND_CODE);
        return authCodeService.submit(botId, kind, value)
                .thenReturn(ResponseEntity.ok(Map.of("submitted", true, "botId", botId, "kind", kind)));
    }
}
