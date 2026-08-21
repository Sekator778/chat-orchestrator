package com.example.telegramuserbot.config; // или ваш пакет для конфигураций

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerConfig {

    /**
     * Переопределяет стандартный бин ConcurrentKafkaListenerContainerFactory,
     * чтобы установить режим ручного подтверждения (AckMode.MANUAL).
     * Это необходимо для того, чтобы мы могли получать объект Acknowledgment
     * в наших @KafkaListener методах и контролировать, когда сообщение
     * считается обработанным.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // --- ГЛАВНОЕ ИЗМЕНЕНИЕ ---
        // Устанавливаем режим ручного подтверждения.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // Вы можете добавить сюда и другие настройки, если потребуется
        // Например, установить обработчик ошибок:
        // factory.setCommonErrorHandler(new DefaultErrorHandler());

        return factory;
    }
    
    // Этот бин обычно создается автоматически, но для ясности можно определить его явно.
    // Он будет использовать настройки из вашего application.yml (bootstrap-servers и т.д.)
    @Bean
    public ConsumerFactory<String, String> consumerFactory(KafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(null));
    }
}