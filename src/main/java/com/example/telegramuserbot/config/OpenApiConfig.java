package com.example.telegramuserbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnHttpEnabled
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI(
            // Можна впровадити властивості з application.yml для версії/опису
            // @Value("${application.description}") String appDescription,
            // @Value("${application.version}") String appVersion
    ) {
        return new OpenAPI()
                .info(new Info()
                        .title("Telegram UserBot Admin API")
                        // .version(appVersion)
                        .version("1.0.0")
                        // .description(appDescription)
                        .description("API для адміністрування конфігурацій чатів Telegram UserBot")
                        .termsOfService("http://swagger.io/terms/") // Замініть на реальні умови
                        .license(new License().name("Apache 2.0").url("http://springdoc.org"))); // Замініть на вашу ліцензію
    }
}
