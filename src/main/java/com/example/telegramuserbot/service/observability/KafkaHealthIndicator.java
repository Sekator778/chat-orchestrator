package com.example.telegramuserbot.service.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;

/**
 * Readiness probe for the broker. A Kafka outage halts the entire reply path —
 * incoming messages stop being consumed — and nothing in the health endpoint said
 * so: the app answers UP on disk, ping and the database while doing no work at all.
 * <p>
 * Readiness, not liveness: restarting the app does not bring a broker back, and
 * on this stand it would cost the Telegram session.
 */
@Component("kafka")
@ConditionalOnProperty(name = "management.health.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaHealthIndicator implements ReactiveHealthIndicator {

    /**
     * Seam for tests: the real probe talks to a broker, the test probe does not.
     */
    @FunctionalInterface
    interface ClusterProbe {
        /** @return number of brokers reachable within the timeout */
        int nodeCount() throws Exception;
    }

    private final ClusterProbe probe;
    private final AdminClient ownedClient;
    private final String bootstrapServers;

    public KafkaHealthIndicator(
            KafkaAdmin kafkaAdmin,
            @Value("${spring.kafka.bootstrap-servers:unset}") String bootstrapServers,
            @Value("${management.health.kafka.timeout-ms:2000}") int timeoutMs) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        this.ownedClient = AdminClient.create(config);
        this.bootstrapServers = bootstrapServers;
        this.probe = () -> ownedClient
                .describeCluster(new DescribeClusterOptions().timeoutMs(timeoutMs))
                .nodes()
                .get()
                .size();
    }

    KafkaHealthIndicator(ClusterProbe probe, String bootstrapServers) {
        this.probe = probe;
        this.ownedClient = null;
        this.bootstrapServers = bootstrapServers;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(probe::nodeCount)
                // AdminClient is blocking; keep it off the event loop.
                .subscribeOn(Schedulers.boundedElastic())
                .map(nodes -> nodes > 0
                        ? Health.up()
                            .withDetail("bootstrapServers", bootstrapServers)
                            .withDetail("nodes", nodes)
                            .build()
                        : Health.down()
                            .withDetail("bootstrapServers", bootstrapServers)
                            .withDetail("error", "cluster reported zero brokers")
                            .build())
                .onErrorResume(error -> Mono.just(Health.down()
                        .withDetail("bootstrapServers", bootstrapServers)
                        .withDetail("error", error.getClass().getSimpleName()
                                + ": " + String.valueOf(error.getMessage()))
                        .build()));
    }

    @PreDestroy
    void close() {
        if (ownedClient != null) {
            ownedClient.close();
        }
    }
}
