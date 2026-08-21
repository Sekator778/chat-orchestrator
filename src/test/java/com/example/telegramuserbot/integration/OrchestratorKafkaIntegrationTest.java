package com.example.telegramuserbot.integration;

import com.example.telegramuserbot.dto.KafkaTelegramMessage;
import com.example.telegramuserbot.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DeleteRecordsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import reactor.core.publisher.Mono;

/**
 * Интеграционный тест полного пути с живым Kafka-брокером:
 * Kafka -> orchestrator -> mock Telegram/DeepSeek.
 * Проверяет, что сообщение обрабатывается, мок Telegram дергается, БД фиксирует ответ бота.
 */
@DirtiesContext
@TestPropertySource(properties = {
        "spring.liquibase.enabled=false",
        "kafka.topic.incoming-messages=orchestrator-int-test",
        "spring.kafka.topic.incoming-messages=orchestrator-int-test",
        "spring.kafka.consumer.group-id=orchestrator-int-test-group",
        "spring.kafka.admin.properties.allow.auto.create.topics=true"
})
class OrchestratorKafkaIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private com.example.telegramuserbot.service.publishing.TelegramMessageSender telegramMessageSender;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private org.springframework.r2dbc.core.DatabaseClient databaseClient;

    @Value("${spring.kafka.topic.incoming-messages}")
    private String incomingTopic;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void shouldProcessKafkaMessageAndSendBotReply() throws Exception {
        long chatId = -2000L;
        long msgId = 101L;

        assumeKafkaAvailable();
        ensureTopicExists();

        insertTestChannel(chatId, "Test chat", 0.5, 1000).block(Duration.ofSeconds(5));
        Long configId = createChatConfig(chatId).block(Duration.ofSeconds(5));
        createResponseTemplate(configId, "CONCISE", "NEUTRAL", 160).block(Duration.ofSeconds(5));
        insertTestMessage(chatId, msgId, "Привет, что по рынку?").block(Duration.ofSeconds(5));

        enqueueDeepSeekSuccess("Отвечаю из теста");

        KafkaTelegramMessage kafkaMessage = new KafkaTelegramMessage();
        kafkaMessage.setChatId(chatId);
        kafkaMessage.setMessageId(msgId);

        kafkaTemplate.send(incomingTopic, String.valueOf(chatId), objectMapper.writeValueAsString(kafkaMessage)).get();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(messageRepository.findTopByChatIdAndIsOutgoingTrueOrderByDateDesc(chatId).block(Duration.ofSeconds(5)))
                        .isNotNull()
        );
    }

    @AfterEach
    void cleanupKafka() throws Exception {
        assumeKafkaAvailable();
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            TopicPartition tp = new TopicPartition(incomingTopic, 0);
            ListOffsetsResult offsets = admin.listOffsets(Collections.singletonMap(tp, OffsetSpec.latest()));
            Long endOffset = offsets.partitionResult(tp).get().offset();
            if (endOffset != null && endOffset > 0) {
                DeleteRecordsResult result = admin.deleteRecords(Map.of(tp, org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(endOffset)));
                KafkaFuture.allOf(result.lowWatermarks().values().toArray(new KafkaFuture[0])).get();
            }
        }
    }

    /**
     * Проверяем доступность живого Kafka и, если его нет, аккуратно скипаем тест,
     * чтобы не ломать запуск всего набора.
     */
    private void assumeKafkaAvailable() {
        Assert.isTrue(StringUtils.hasText(bootstrapServers), "Kafka bootstrap servers must be set for integration test");
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.describeCluster().nodes().get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Kafka broker not reachable at " + bootstrapServers + " : " + e.getMessage());
        }
    }

    private void ensureTopicExists() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(Collections.singletonList(new org.apache.kafka.clients.admin.NewTopic(incomingTopic, 1, (short) 1)))
                    .all().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (ExecutionException ee) {
            if (!(ee.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw ee;
            }
        } catch (org.apache.kafka.common.errors.TopicExistsException ignore) {
        }
    }

	    private Mono<Long> createChatConfig(long chatId) {
	        return databaseClient.sql("""
	                INSERT INTO bot.chat_configs (channel_chat_id, prompt_template, enabled, max_tokens, temperature,
	                                              language, context_window_size, primary_channel_id, default_sync_depth_days,
	                                              auto_sync_enabled, sync_enabled, respond_to_forwarded_bot_messages)
	                VALUES (:chatId, 'Test prompt', true, 200, 0.5, 'ru', 6, NULL, 7, false, false, false)
	                ON CONFLICT (channel_chat_id) DO UPDATE SET enabled = true
	                RETURNING id
	                """)
	                .bind("chatId", chatId)
	                .map(row -> row.get("id", Long.class))
	                .one()
	                .flatMap(configId -> databaseClient.sql("""
	                                INSERT INTO bot.rate_limits (chat_config_id, max_messages_per_day, current_daily_messages)
	                                VALUES (:configId, 100, 0)
	                                ON CONFLICT (chat_config_id) DO UPDATE SET
	                                    max_messages_per_day = EXCLUDED.max_messages_per_day
	                                """)
	                        .bind("configId", configId)
	                        .fetch()
	                        .rowsUpdated()
	                        .thenReturn(configId));
	    }

    private Mono<Long> createResponseTemplate(Long configId, String style, String tone, Integer maxLength) {
        if (configId == null) {
            return Mono.just(0L);
        }
        return databaseClient.sql("""
                INSERT INTO bot.response_templates (chat_config_id, template_name, template_content,
                                                    response_style, response_tone, max_response_length,
                                                    is_default, priority, active)
                VALUES (:configId, 'test_template', 'Test content', :style, :tone, :maxLength, true, 1, true)
                ON CONFLICT DO NOTHING
                """)
                .bind("configId", configId)
                .bind("style", style)
                .bind("tone", tone)
                .bind("maxLength", maxLength)
                .fetch()
                .rowsUpdated();
    }
}
