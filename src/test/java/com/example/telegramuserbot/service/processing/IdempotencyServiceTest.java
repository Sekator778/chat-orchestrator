package com.example.telegramuserbot.service.processing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the cross-account dedup primitive: exactly ONE caller may
 * claim a key, even under the concurrent race that the shared-chat bug exposed
 * (two persona clients ingesting the same physical message on different threads).
 *
 * FR-001: first claim wins, repeat claims are duplicates.
 * FR-002: under N concurrent claims of the same key, exactly one succeeds.
 */
class IdempotencyServiceTest {

    @Test
    void firstClaimWinsRepeatIsDuplicate() {
        IdempotencyService service = new IdempotencyService();
        assertThat(service.checkAndSet("content:-100:42:abc")).isTrue();
        assertThat(service.checkAndSet("content:-100:42:abc")).isFalse();
    }

    @Test
    void exactlyOneWinnerUnderConcurrentRace() throws Exception {
        IdempotencyService service = new IdempotencyService();
        String key = "content:-1001234567890:1000000001:samehash";
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger winners = new AtomicInteger();
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    if (service.checkAndSet(key)) {
                        winners.incrementAndGet();
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(winners.get()).isEqualTo(1);
    }
}
