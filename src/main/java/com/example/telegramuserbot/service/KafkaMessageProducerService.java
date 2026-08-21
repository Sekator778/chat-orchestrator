package com.example.telegramuserbot.service;

import com.example.telegramuserbot.dto.KafkaTelegramMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class KafkaMessageProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topicName;

    public KafkaMessageProducerService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topic.incoming-messages}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topicName = topicName;
    }

    public Mono<SendResult<String, String>> sendNewMessageNotification(long chatId, long messageId) {
        return Mono.fromCallable(() -> {
                    try {
                        KafkaTelegramMessage payload = new KafkaTelegramMessage(chatId, messageId);
                        return objectMapper.writeValueAsString(payload);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to serialize Kafka message", e);
                    }
                })
                .flatMap(jsonPayload -> {
                    String key = String.valueOf(chatId);
                    log.info("Sending message to Kafka topic '{}': key={}, payload={}", topicName, key, jsonPayload);
                    return Mono.fromFuture(kafkaTemplate.send(topicName, key, jsonPayload));
                })
                .doOnSuccess(sendResult -> log.debug("Successfully sent message to Kafka topic '{}'. Partition: {}, Offset: {}",
                        topicName, sendResult.getRecordMetadata().partition(), sendResult.getRecordMetadata().offset()))
                .doOnError(e -> log.error("Error sending message to Kafka topic '{}' for chatId={}, messageId={}",
                        topicName, chatId, messageId, e));
    }
}
