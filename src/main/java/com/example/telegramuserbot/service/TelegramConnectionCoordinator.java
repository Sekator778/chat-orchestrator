package com.example.telegramuserbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates Telegram API access between Java TDLib and Python Telethon clients
 * to prevent connection conflicts when both try to use the same session simultaneously.
 *
 * Uses a semaphore-based read/write guard that is safe across different scheduler threads.
 */
@Service
public final class TelegramConnectionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TelegramConnectionCoordinator.class);

    private final Semaphore exclusiveSemaphore = new Semaphore(1, true);
    private final AtomicInteger activeReaders = new AtomicInteger(0);
    private final Object readerMutex = new Object();

    /**
     * Executes a "writer" task (like a Python script) with an exclusive lock.
     * Any attempts to read from the repository will be blocked until this Mono completes.
     */
    public <T> Mono<T> executeAsWriter(Mono<T> writerExecution) {
        return Mono.usingWhen(
                acquireWriterPermit(),
                ignored -> writerExecution,
                ignored -> releaseWriterPermit()
        ).doOnError(error -> log.error("Error during writer execution with coordination", error));
    }

    /**
     * Guards a "reader" task (like a repository call) with a shared lock.
     * Allows multiple readers to execute concurrently, but blocks if a writer holds the lock.
     */
    public <T> Mono<T> guardMonoAsReader(Mono<T> source) {
        return Mono.usingWhen(
                acquireReaderPermit(),
                ignored -> source,
                ignored -> releaseReaderPermit()
        );
    }

    /**
     * Guards a "reader" Flux with a shared lock.
     */
    public <T> Flux<T> guardFluxAsReader(Flux<T> source) {
        return Flux.usingWhen(
                acquireReaderPermit(),
                ignored -> source,
                ignored -> releaseReaderPermit()
        );
    }

    private Mono<Boolean> acquireWriterPermit() {
        return Mono.fromCallable(() -> {
                    log.info("🐍 Writer process starting. Waiting for exclusive access to ChannelRepository...");
                    acquireUninterruptibly(exclusiveSemaphore);
                    log.info("✅ Exclusive access granted. Repository readers are now blocked.");
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> releaseWriterPermit() {
        return Mono.fromRunnable(() -> {
                    exclusiveSemaphore.release();
                    log.info("🔑 Writer process finished. Exclusive access released.");
                })
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Boolean> acquireReaderPermit() {
        return Mono.fromCallable(() -> {
                    boolean needExclusive = false;
                    synchronized (readerMutex) {
                        int current = activeReaders.getAndIncrement();
                        if (current == 0) {
                            needExclusive = true;
                        }
                        log.debug("Reader registered. Active readers: {}", current + 1);
                    }

                    if (needExclusive) {
                        log.debug("First reader acquiring exclusive semaphore for shared access.");
                        try {
                            acquireUninterruptibly(exclusiveSemaphore);
                        } catch (RuntimeException ex) {
                            synchronized (readerMutex) {
                                int updated = activeReaders.decrementAndGet();
                                if (updated < 0) {
                                    activeReaders.set(0);
                                }
                            }
                            throw ex;
                        }
                    }

                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> releaseReaderPermit() {
        return Mono.fromRunnable(() -> {
                    boolean releaseExclusive = false;
                    synchronized (readerMutex) {
                        int remaining = activeReaders.decrementAndGet();
                        if (remaining <= 0) {
                            activeReaders.set(0);
                            releaseExclusive = true;
                        }
                        log.debug("Reader finished. Remaining readers: {}", Math.max(remaining, 0));
                    }

                    if (releaseExclusive) {
                        exclusiveSemaphore.release();
                        log.debug("Last reader released shared access semaphore.");
                    }
                })
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void acquireUninterruptibly(Semaphore semaphore) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    semaphore.acquire();
                    return;
                } catch (InterruptedException ex) {
                    interrupted = true;
                    log.warn("Semaphore acquisition interrupted, retrying...");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
