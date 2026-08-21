package com.example.telegramuserbot.integration;

import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import com.example.telegramuserbot.service.llm.dto.ResponseChoice;
import com.example.telegramuserbot.service.llm.dto.ResponseMessage;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.Init;
import it.tdlight.jni.TdApi;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unified base integration test class providing:
 * - Live PostgreSQL database (unit_db)
 * - Real Kafka broker (localhost:9092)
 * - MockWebServer for DeepSeek API
 * - Database cleanup utilities
 * - Common test infrastructure
 * <p>
 * Adheres to Elegant Objects principles:
 * - No static methods (except lifecycle hooks)
 * - Immutable where possible
 * - Clear single responsibility
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.liquibase.enabled=true",
                "startup.sync.enabled=false",
                "spring.kafka.consumer.auto-offset-reset=earliest"
        }
)
@ActiveProfiles("test")
@Import(BaseIntegrationTest.TestConfig.class)
public abstract class BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BaseIntegrationTest.class);
    private static final Set<String> TABLE_EXCLUSIONS = Set.of("databasechangelog", "databasechangeloglock");

    // --- MockWebServer for DeepSeek API ---
    protected static MockWebServer mockDeepSeekServer;

    @BeforeAll
    static void setUpMockServer() throws Exception {
        Init.init();
        mockDeepSeekServer = new MockWebServer();
        try {
            mockDeepSeekServer.start();
            log.info("MockWebServer started for DeepSeek API mocking");
        } catch (IOException ex) {
            log.warn("MockWebServer could not start ({}). DeepSeek HTTP mocks disabled for tests.", ex.getMessage());
            mockDeepSeekServer = null;
        }
    }

    @AfterAll
    static void tearDownMockServer() throws IOException {
        if (mockDeepSeekServer != null) {
            mockDeepSeekServer.shutdown();
            log.info("MockWebServer shut down");
        }
    }

    /**
     * Dynamically configure properties for tests
     */
    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        // Kafka: Point to locally running broker
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");

        // DeepSeek: Use mock server
        registry.add("deepseek.apiUrl", () -> {
            if (mockDeepSeekServer != null) {
                return mockDeepSeekServer.url("/chat/completions").toString();
            }
            return "http://127.0.0.1:0/deepseek-disabled";
        });
    }

    // --- Injected Dependencies ---
    @Autowired
    protected DatabaseClient databaseClient;

    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected Environment environment;

    /**
     * Clean database before each test to ensure isolation
     */
    @BeforeEach
    void cleanDatabase() {
        purgeSchemas();
    }

    /**
     * Clean database after each test as well to guarantee consistency
     */
    @AfterEach
    void cleanDatabaseAfter() {
        purgeSchemas();
    }

    /**
     * Cleans bot schema tables in correct order (respecting foreign keys)
     */
    protected Mono<Void> cleanBotSchema() {
        return truncateSchema("bot");
    }

    /**
     * Cleans tgscan schema tables in correct order (respecting foreign keys)
     */
    protected Mono<Void> cleanTgscanSchema() {
        // Explicit order to respect foreign key constraints:
        // 1. posted (has FKs to events and post_subscriptions)
        // 2. events (referenced by posted)
        // 3. post_subscriptions (referenced by posted)
        // 4. Everything else
        return truncateSchemaTables("tgscan", List.of(
                "posted",              // Child table first
                "alerts",              // No FK dependencies
                "events",              // Referenced by posted
                "post_subscriptions",  // Referenced by posted
                "messages",
                "channels",
                "channel_candidates",
                "agg_top_messages_daily",
                "run_log"
        ));
    }

    /**
     * Inserts test channel into tgscan.channels table
     */
    protected Mono<Long> insertTestChannel(long chatId, String title, double weight, long subscribers) {
        return databaseClient.sql(
                        """
                                INSERT INTO tgscan.channels (id, title, weight, subscribers, first_seen, last_seen)
                                VALUES (:chatId, :title, :weight, :subscribers, NOW(), NOW())
                                ON CONFLICT (id) DO UPDATE SET
                                    title = EXCLUDED.title,
                                    weight = EXCLUDED.weight,
                                    subscribers = EXCLUDED.subscribers
                                RETURNING id
                                """
                )
                .bind("chatId", chatId)
                .bind("title", title)
                .bind("weight", weight)
                .bind("subscribers", subscribers)
                .map(row -> row.get("id", Long.class))
                .one();
    }

    /**
     * Inserts test message into bot.messages table
     */
    protected Mono<Long> insertTestMessage(long chatId, long messageId, String content) {
        return databaseClient.sql(
                        """
                                INSERT INTO bot.messages (chat_id, message_id, content, date, is_outgoing, message_type, created_at)
                                VALUES (:chatId, :messageId, :content, NOW(), false, 'USER_MESSAGE', NOW())
                                ON CONFLICT (chat_id, message_id) DO NOTHING
                                RETURNING id
                                """
                )
                .bind("chatId", chatId)
                .bind("messageId", messageId)
                .bind("content", content)
                .map(row -> row.get("id", Long.class))
                .one()
                .switchIfEmpty(Mono.just(-1L)); // Return -1 if conflict (already exists)
    }

    /**
     * Counts messages in bot.messages table
     */
    protected Mono<Long> countBotMessages() {
        return databaseClient.sql("SELECT COUNT(*) FROM bot.messages")
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * Counts messages in tgscan.messages table
     */
    protected Mono<Long> countTgscanMessages() {
        return databaseClient.sql("SELECT COUNT(*) FROM tgscan.messages")
                .map(row -> row.get(0, Long.class))
                .one();
    }

    protected Connection openJdbcConnection() throws SQLException {
        String url = Objects.requireNonNull(environment.getProperty("spring.liquibase.url"), "spring.liquibase.url must be set");
        String user = Objects.requireNonNull(environment.getProperty("spring.liquibase.user"), "spring.liquibase.user must be set");
        String password = Objects.requireNonNull(environment.getProperty("spring.liquibase.password"), "spring.liquibase.password must be set");
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Enqueues successful DeepSeek API response to mock server
     */
    protected void enqueueDeepSeekSuccess(String responseContent) throws Exception {
        if (mockDeepSeekServer == null) {
            log.warn("MockWebServer not available, skipping enqueueDeepSeekSuccess");
            return;
        }

        ResponseMessage responseMessage = new ResponseMessage("assistant", responseContent);
        ResponseChoice choice = new ResponseChoice(0, responseMessage);
        DeepSeekChatResponse successResponse = new DeepSeekChatResponse(
                "id-123",
                List.of(choice),
                System.currentTimeMillis() / 1000,
                "deepseek-test",
                "chat.completion",
                null
        );

        mockDeepSeekServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(successResponse))
                .addHeader("Content-Type", "application/json"));

        log.debug("Enqueued DeepSeek success response: {}", responseContent);
    }

    /**
     * Enqueues error response from DeepSeek API
     */
    protected void enqueueDeepSeekError(int code, String errorJsonBody) {
        if (mockDeepSeekServer == null) {
            log.warn("MockWebServer not available, skipping enqueueDeepSeekError");
            return;
        }

        mockDeepSeekServer.enqueue(new MockResponse()
                .setResponseCode(code)
                .setBody(errorJsonBody)
                .addHeader("Content-Type", "application/json"));

        log.debug("Enqueued DeepSeek error response: {} - {}", code, errorJsonBody);
    }

    private void purgeSchemas() {
        cleanBotSchema().block(Duration.ofSeconds(10));
        cleanTgscanSchema().block(Duration.ofSeconds(10));
    }

    private Mono<Void> truncateSchema(String schema) {
        return databaseClient.sql("""
                        SELECT table_name
                          FROM information_schema.tables
                         WHERE table_schema = :schema
                           AND table_type = 'BASE TABLE'
                        """)
                .bind("schema", schema)
                .map((row, metadata) -> row.get("table_name", String.class))
                .all()
                .filter(table -> table != null && !TABLE_EXCLUSIONS.contains(table))
                .collectList()
                .flatMap(tables -> {
                    if (tables.isEmpty()) {
                        log.debug("No tables found to truncate for schema {}", schema);
                        return Mono.empty();
                    }

                    String joined = tables.stream()
                            .map(table -> schema + "." + "\"" + table + "\"")
                            .collect(Collectors.joining(", "));

                    String sql = "TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE";
                    log.debug("Executing: {}", sql);
                    return databaseClient.sql(sql).fetch().rowsUpdated().then();
                });
    }

    /**
     * Truncates tables in specified order to respect foreign key constraints
     */
    private Mono<Void> truncateSchemaTables(String schema, List<String> tableOrder) {
        if (tableOrder.isEmpty()) {
            return Mono.empty();
        }

        String joined = tableOrder.stream()
                .map(table -> schema + "." + "\"" + table + "\"")
                .collect(Collectors.joining(", "));

        String sql = "TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE";
        log.debug("Executing: {}", sql);
        return databaseClient.sql(sql).fetch().rowsUpdated().then();
    }

    /**
     * Test configuration for integration tests.
     * Provides mock beans for all Telegram-related services to prevent real API calls.
     * Uses @Primary to ensure these mocks override any production beans.
     */
    @TestConfiguration
    static class TestConfig {

        /**
         * Provides mock TelegramMessageSender that never sends real messages.
         * Returns successful empty response for all send() calls.
         */
        @Bean
        @Primary
        public TelegramMessageSender mockTelegramMessageSender() {
            log.info("Mock TelegramMessageSender created - all Telegram sends will be blocked");
            return new TelegramMessageSender() {
                @Override
                public Mono<TdApi.Message> send(Long chatId, String text) {
                    return Mono.just(dummyMessage(chatId));
                }

                @Override
                public Mono<TdApi.Message> send(Long chatId, Long replyToMessageId, String text) {
                    return Mono.just(dummyMessage(chatId));
                }

                @Override
                public Mono<TdApi.Message> send(String botId, Long chatId, String text) {
                    return Mono.just(dummyMessage(chatId));
                }

                @Override
                public Mono<TdApi.Message> send(String botId, Long chatId, Long replyToMessageId, String text) {
                    return Mono.just(dummyMessage(chatId));
                }

                @Override
                public boolean isBackingOff(String botId) {
                    return false;
                }

                private TdApi.Message dummyMessage(Long chatId) {
                    TdApi.Message message = new TdApi.Message();
                    message.id = 999_999L;
                    message.chatId = chatId != null ? chatId : 0L;
                    message.date = (int) (System.currentTimeMillis() / 1000);
                    message.content = new TdApi.MessageText(new TdApi.FormattedText("Mock message", null), null, null);
                    return message;
                }
            };
        }

        /**
         * Stub TelegramClientFacade to avoid any TDLib calls during tests.
         * Always returns a dummy success message for send() operations.
         */
        @Bean
        @Primary
        public com.example.telegramuserbot.telegram.TelegramClientFacade mockTelegramClientFacade() {
            return new com.example.telegramuserbot.telegram.TelegramClientFacade() {
                @Override
                public <T extends TdApi.Object> java.util.concurrent.CompletableFuture<T> send(TdApi.Function<T> function) {
                    return java.util.concurrent.CompletableFuture.completedFuture(buildResult(function));
                }

                @Override
                public <T extends TdApi.Object> void send(TdApi.Function<T> function, it.tdlight.client.GenericResultHandler<T> handler) {
                    handler.onResult((it.tdlight.client.Result<T>) it.tdlight.client.Result.of(buildResult(function)));
                }

                @Override
                public <T extends TdApi.Update> void addUpdateHandler(Class<T> type, it.tdlight.client.GenericUpdateHandler<? super T> handler) {
                    // no-op
                }

                @Override
                public void addUpdatesHandler(it.tdlight.client.GenericUpdateHandler<TdApi.Update> handler) {
                    // no-op
                }

                @Override
                public void addUpdateExceptionHandler(it.tdlight.ExceptionHandler handler) {
                    // no-op
                }

                @Override
                public void addDefaultExceptionHandler(it.tdlight.ExceptionHandler handler) {
                    // no-op
                }

                @Override
                public void addCommandHandler(String command, it.tdlight.client.CommandHandler handler) {
                    // no-op
                }

                private TdApi.Message dummyMessage() {
                    TdApi.Message message = new TdApi.Message();
                    message.id = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
                    message.chatId = 0L;
                    message.date = (int) (System.currentTimeMillis() / 1000);
                    message.content = new TdApi.MessageText(new TdApi.FormattedText("Mock TDLib message", null), null, null);
                    return message;
                }

                private <T extends TdApi.Object> T buildResult(TdApi.Function<T> function) {
                    TdApi.Message msg = dummyMessage();
                    if (function instanceof TdApi.SendMessage sendMessage) {
                        msg.chatId = sendMessage.chatId;
                        msg.id = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
                        msg.content = new TdApi.MessageText(new TdApi.FormattedText("Mock TDLib message", null), null, null);
                    } else if (function instanceof TdApi.SendChatAction sendChatAction) {
                        msg.chatId = sendChatAction.chatId;
                    }
                    return (T) msg;
                }
            };
        }
    }
}
