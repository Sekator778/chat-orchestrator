package com.example.telegramuserbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive WebFilter that protects {@code /api/admin/**} and {@code /api/python/**}
 * endpoints with a shared API-key header ({@code X-Admin-Key}).
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If {@code admin.api.key} is blank/unset (the default) → <strong>pass-through no-op</strong>;
 *       the running staging stand is completely unaffected.</li>
 *   <li>If the key IS set → requests to the protected paths must carry the header
 *       {@code X-Admin-Key} whose value equals the configured key; otherwise the
 *       filter short-circuits with {@code 401 UNAUTHORIZED}.</li>
 *   <li>All other paths (including {@code /actuator/**}) are never touched.</li>
 * </ul>
 *
 * <p>Activate on a real deployment by setting the environment variable
 * {@code ADMIN_API_KEY} to a secret value (min 32 chars recommended).
 */
@Component
public class AdminApiKeyFilter implements WebFilter {

    private static final String HEADER_NAME = "X-Admin-Key";

    private final String adminApiKey;

    public AdminApiKeyFilter(@Value("${admin.api.key:}") String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // If no key is configured, behave as a pass-through (safe default for staging).
        if (adminApiKey == null || adminApiKey.isBlank()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/admin/") && !path.startsWith("/api/python/")) {
            return chain.filter(exchange);
        }

        String provided = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);
        if (adminApiKey.equals(provided)) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
