package com.example.telegramuserbot.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Value("${deepseek.apiUrl}")
    private String deepSeekBaseUrl;

    @Value("${deepseek.apiKey}")
    private String deepSeekApiKey;

    @Value("${deepseek.requestTimeoutSeconds:30}")
    private int requestTimeoutSeconds;

    @Bean
    public WebClient deepSeekWebClient(WebClient.Builder builder) {
        // Налаштовуємо HttpClient з тайм-аутами
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, requestTimeoutSeconds * 1000)
                .responseTimeout(Duration.ofSeconds(requestTimeoutSeconds)) // Таймаут очікування відповіді
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(requestTimeoutSeconds, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(requestTimeoutSeconds, TimeUnit.SECONDS)));

        // Визначаємо базовий URL та хедери за замовчуванням
        // Важливо: DeepSeek API може не мати "базового" URL, якщо шлях завжди повний.
        // Якщо apiUrl - це повний шлях, то baseUrl тут не потрібен,
        // але можна задати хедери за замовчуванням.
        // Припустимо, що apiUrl - це ПОВНИЙ шлях до ендпоінту /chat/completions
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient)) // Застосовуємо налаштування таймаутів
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + deepSeekApiKey)
                .build();
    }
}