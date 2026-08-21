package com.example.telegramuserbot.service.orchestration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация оркестратора: точка расширения, чтобы не плодить @Service на helper-классы.
 */
@Configuration
public class OrchestratorConfig {

    @Bean
    @ConditionalOnMissingBean
    public ResponsePostProcessor responsePostProcessor() {
        return new ResponsePostProcessor();
    }
}

