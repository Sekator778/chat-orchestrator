package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.proactive.PersonaProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin endpoint to force an immediate config-cache refresh without waiting
 * for the scheduled TTL cycles (AppSettings ≈20 min, SyncEnabledChatsCache ≈1 h).
 *
 * <p>POST /api/admin/cache/refresh
 *
 * <p>Protected by the existing {@link com.example.telegramuserbot.config.AdminApiKeyFilter}
 * which gates all {@code /api/admin/**} paths — no additional auth wiring needed.
 */
@RestController
@RequestMapping("/api/admin/cache")
public class AdminCacheController {

    private final AppSettingsService appSettingsService;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final PersonaProfileService personaProfileService;

    public AdminCacheController(AppSettingsService appSettingsService,
                                SyncEnabledChatsCache syncEnabledChatsCache,
                                PersonaProfileService personaProfileService) {
        this.appSettingsService    = appSettingsService;
        this.syncEnabledChatsCache = syncEnabledChatsCache;
        this.personaProfileService = personaProfileService;
    }

    /**
     * Forces an immediate reload of {@code bot.app_settings}, clears the
     * {@code SyncEnabledChatsCache}, and evicts the {@code PersonaProfileService} vector
     * cache so that edited persona rows trigger re-embedding on the next proactive tick.
     *
     * @return {@code {"refreshed": true}} on success
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh() {
        syncEnabledChatsCache.invalidateAll();
        personaProfileService.invalidateAll();
        return appSettingsService.refreshFromDatabase()
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of("refreshed", true)));
    }
}
