package com.example.telegramuserbot.config;

import com.example.telegramuserbot.telegram.TelegramClientFacade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Gated configuration that provides a no-op {@link TelegramClientFacade} bean
 * when the smoke profile is active ({@code telegram.client.enabled=false}).
 *
 * <p>This configuration is the exact inverse of {@code TelegramClientConfig}
 * (which is gated {@code havingValue="true", matchIfMissing=true}). The two are
 * mutually exclusive: exactly one of them is active per Spring profile,
 * determined by the single source-of-truth property {@code telegram.client.enabled}.</p>
 *
 * <p>When active, it registers a {@code @Primary @Bean} of type
 * {@link NoOpTelegramClientFacade} that satisfies the {@code TelegramClientFacade}
 * dependency (required by e.g. {@code BotInfoService}) without a real TDLib client.
 * All method calls return dummy success results; handler registrations are no-ops.</p>
 *
 * <p>This configuration is NOT active under any non-smoke profile because
 * those profiles either leave {@code telegram.client.enabled} absent (defaults to
 * {@code true} via {@code matchIfMissing}) or explicitly set it to {@code true}.</p>
 *
 * @see TelegramClientConfig the real-client counterpart
 * @see NoOpTelegramClientFacade the no-op implementation
 */
@Configuration
@ConditionalOnProperty(name = "telegram.client.enabled", havingValue = "false")
public class SmokeTelegramClientConfig {

    @Bean
    @Primary
    public TelegramClientFacade noOpTelegramClientFacade() {
        return new NoOpTelegramClientFacade();
    }
}
