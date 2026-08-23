package com.example.telegramuserbot.service.observability;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The readiness indicators exist because a Qdrant, TEI or Kafka outage used to be
 * invisible: the app answered UP on disk, ping and the database while semantic
 * ranking silently degraded and, without Kafka, the reply path did no work at all.
 * <p>
 * What matters in these tests is the failure direction. An indicator that throws,
 * hangs, or reports UP when the dependency is gone is worse than none at all.
 */
class ReadinessHealthIndicatorTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    @Test
    @DisplayName("qdrant: UP on a healthy readyz, and it is readyz that gets asked")
    void qdrantUp() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200));

        Health health = new QdrantHealthIndicator(WebClient.builder(), baseUrl(), 2000)
                .health().block();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/readyz");
    }

    @Test
    @DisplayName("qdrant: DOWN when the store answers with an error, with the reason attached")
    void qdrantDownOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(503));

        Health health = new QdrantHealthIndicator(WebClient.builder(), baseUrl(), 2000)
                .health().block();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("qdrant: DOWN rather than an exception when nothing is listening")
    void qdrantDownWhenUnreachable() throws IOException {
        String dead = baseUrl();
        server.shutdown();

        Health health = new QdrantHealthIndicator(WebClient.builder(), dead, 2000)
                .health().block();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("embeddings: UP on a healthy /health")
    void embeddingsUp() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200));

        Health health = new EmbeddingsHealthIndicator(WebClient.builder(), baseUrl(), 2000)
                .health().block();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/health");
    }

    @Test
    @DisplayName("a slow dependency reports DOWN instead of hanging the endpoint")
    void slowDependencyTimesOut() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(5, TimeUnit.SECONDS));

        long started = System.currentTimeMillis();
        Health health = new EmbeddingsHealthIndicator(WebClient.builder(), baseUrl(), 300)
                .health().block();
        long elapsed = System.currentTimeMillis() - started;

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(elapsed).as("the probe must give up on its own timeout").isLessThan(3000);
    }

    @Test
    @DisplayName("kafka: UP when brokers answer, DOWN on zero brokers or a failure")
    void kafkaStatuses() {
        Health up = new KafkaHealthIndicator(() -> 3, "localhost:9092").health().block();
        assertThat(up).isNotNull();
        assertThat(up.getStatus()).isEqualTo(Status.UP);
        assertThat(up.getDetails()).containsEntry("nodes", 3);

        Health empty = new KafkaHealthIndicator(() -> 0, "localhost:9092").health().block();
        assertThat(empty).isNotNull();
        assertThat(empty.getStatus()).isEqualTo(Status.DOWN);

        Health failed = new KafkaHealthIndicator(() -> {
            throw new IllegalStateException("no broker");
        }, "localhost:9092").health().block();
        assertThat(failed).isNotNull();
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails().get("error").toString()).contains("no broker");
    }
}
