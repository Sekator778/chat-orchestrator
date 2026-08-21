# Asynchronous & Throughput Audit

## Overview
- **Date:** 2025-02-14
- **Analyst:** Automated review (Codex / GPT-5)
- **Scope:** Reactive ingestion (TDLib listener), Kafka producer/consumer, humanization & LLM orchestration, persistence, media handling.
- **Goal:** Surface blockers/bottlenecks that prevent enterprise-grade throughput and resilience.

Key risks cluster around *blocking calls inside reactive flows*, *lack of concurrency governance for Kafka & TDLib ingestion*, and *idempotency/accounting strategies that can throttle sustained load*. The sections below detail the highest-impact issues and remediation paths.

---

## Critical Findings

1. **Blocking LLM refinement on event threads**  
   - `src/main/java/com/example/telegramuserbot/service/humanization/ResponseRefinerServiceImpl.java:91-118` invokes `responseMono.block()` on the caller thread.  
   - **Impact:** Every refinement call ties up the Kafka listener thread or bounded elastic worker until DeepSeek responds, nullifying the reactive architecture and risking consumer stalls.  
   - **Action:** Return `Mono<String>` from the refiner, reuse the existing WebClient pipeline, and let callers `flatMap` it. If synchronous fallback is required, wrap in `Mono.defer(...).subscribeOn(Schedulers.boundedElastic())` with a timeout.

2. **Reactive → blocking conversions in personalization & context assembly**  
   - `PersonalizationService.analyzeUserStyle()` (`src/.../PersonalizationService.java:48-70`) and nearly every helper in `MessageContextService` (`src/.../MessageContextService.java:53-155`) call `blockOptional` / `collectList().blockOptional`.  
   - **Impact:** Per-message processing blocks a thread for each DB fetch, eliminating the benefits of R2DBC under load. Large chats will saturate the bounded-elastic pool and back up Kafka partitions.  
   - **Action:** Rewrite these methods to stay reactive: compose monos/fluxes, return `Mono<UserCommunicationProfile>` / `Mono<ConversationContext>`, and remove manual blocking. Cache immutable config (e.g., context settings) to avoid repeated round-trips.

3. **Cross-channel filter and DeepSeek service still block**  
   - `CrossChannelResponseFilter.shouldProcess()` (`src/.../CrossChannelResponseFilter.java:43-100`) blocks for config/message lookups.  
   - `DeepSeekService` fetch helpers (`src/.../DeepSeekService.java:207-239`) call repositories inside `Mono.fromCallable(...blockOptional...)`.  
   - **Impact:** Even though most callers shift onto `Schedulers.boundedElastic()`, heavy load will exhaust that scheduler and slow message throughput.  
   - **Action:** Promote the filter API to `Mono<Boolean>` and refactor DeepSeek helpers to pure reactive chains so they play nicely with Reactor’s back-pressure and reuse connection pools efficiently.

4. **Kafka listener executes JSON parsing & pipeline on consumer thread**  
   - In `KafkaMessageConsumerService.handleIncomingMessage()` (`src/.../KafkaMessageConsumerService.java:67-90`), deserialization uses `Mono.fromCallable(objectMapper::readValue)` without `subscribeOn`, keeping the work on the Kafka consumer thread.  
   - **Impact:** Large payloads or back-to-back records can stall the poll loop, triggering rebalancestorms.  
   - **Action:** Move the chain behind `subscribeOn(Schedulers.boundedElastic())` (or a dedicated scheduler) and consider using `KafkaListener`’s native async/`Mono` return capability to avoid manual `subscribe`.

5. **Idempotency cache never releases keys**  
   - `IdempotencyService` (`src/.../IdempotencyService.java:16-38`) stores keys for 15 minutes but never invalidates them on success.  
   - **Impact:** Under burst traffic, the cache balloons and prevents legitimate replays (e.g., DLQ re-drives) inside the TTL window.  
   - **Action:** Invalidate keys once a message reaches an acknowledged terminal state, and configure TTL to align with business SLAs. Consider a bounded, metrics-backed structure (e.g., Caffeine with `maximumSize`) to protect heap.

6. **Artificial 50 ms gate before DB verification**  
   - `verifyMessageExists()` (`src/.../KafkaMessageConsumerService.java:105-126`) begins with `Mono.delay(Duration.ofMillis(50))`.  
   - **Impact:** Adds a floor of ~50 ms per message before orchestration even starts, capping single-partition throughput at ~20 msg/s.  
   - **Action:** Replace with an outbox (transactional publish) or conditional retry that only delays when the first lookup misses. Use `retryWhen` backoff without the unconditional delay to lift throughput.

7. **TDLib listener monopolises callback threads**  
   - `handleNewMessage()` (`src/.../TelegramListenerService.java:100-120`) subscribes immediately, so command parsing, persistence, and Kafka publish all execute on TDLib’s callback thread.  
   - **Impact:** Long-running handlers block new updates, creating backlog in TDLib and risking disconnects.  
   - **Action:** Add `.publishOn(Schedulers.boundedElastic())` (or a dedicated scheduler) right after the duplicate check to delegate heavy work off the TDLib thread pool.

8. **Kafka concurrency and resiliency knobs unset**  
   - `KafkaConsumerConfig` (`src/.../config/KafkaConsumerConfig.java:22-38`) uses defaults: concurrency=1, no error handler, no `idleBetweenPolls`.  
   - **Impact:** Single-threaded consumption per listener and fragile error handling; any poison message will stall processing.  
   - **Action:** Tune `factory.setConcurrency(partitionCount)` (or configure via properties), set a `DefaultErrorHandler` with DLQ/backoff, and externalise consumer `max.poll.interval`, `max.poll.records`, and fetch sizes for high-throughput scenarios.

---

## High-Priority Recommendations

- **Refactor blocking services to reactive:** Start with personalization and context assembly because every outbound reply depends on them. Introduce repository helper methods that return `Mono`/`Flux`, reuse them across orchestrators, and drop `block*` calls entirely.  
- **Dedicate schedulers for heavy tasks:** Create named bounded elastic pools for (a) LLM/HTTP calls, (b) DB fallbacks, (c) media downloads. Configure sizes via application properties for environment-specific tuning.  
- **Kafka tuning & observability:**  
  - Configure listener concurrency and partition assignments explicitly.  
  - Add consumer lag metrics, processing latency histograms, and ack timing logs (Micrometer).  
  - Enable `spring.kafka.listener.monitor-interval` to catch slow consumer warnings early.  
- **Resilience playbook:** Implement DLQ or retry topic for terminal failures instead of reprocessing the same record indefinitely. Combine with idempotency key invalidation to avoid cache build-up.  
- **Outbox / transactional pattern:** Relying on a fixed delay to wait for DB commits is brittle. Adopt a transactional outbox or use `@TransactionalEventListener` so Kafka produce happens after persistence succeeds without guesswork delays.

---

## Additional Improvement Ideas

- **Media executor sizing:** The fixed `mediaTaskExecutor` of 5 threads (`src/.../config/BotConfig.java:117-133`) is conservative. Expose pool size + queue thresholds via configuration to keep downloads from blocking message ingestion.  
- **Command pipeline timeouts:** Wrap command handling/humanization in explicit timeouts so a slow persona calculation cannot stall the listener chain.  
- **Caching config lookups:** Chat configuration and context settings are read for almost every message. Introduce `CacheMono` / `ReactiveCache` with invalidation hooks to remove redundant round-trips.

---

## Suggested Metrics & Alerts

| Metric | Purpose | Tooling |
| --- | --- | --- |
| Kafka consumer lag + processing duration | Detect backlogs / slow handlers | Micrometer `Timer` + KafkaExporter |
| TDLib update handling duration | Ensure listener offload works | `Timer` around `handleNewMessage` |
| Reactor scheduler saturation | Spot bounded elastic exhaustion | `Schedulers.enableMetrics()` + JFR |
| Cache size / eviction for idempotency | Prevent memory bloat | Caffeine metrics |

---

## Next Steps Checklist

1. Remove `block()`/`blockOptional()` usages in hot paths; add unit tests to enforce reactive style.  
2. Introduce dedicated schedulers and wire them via configuration.  
3. Reconfigure Kafka consumer concurrency, add DLQ/error handler, and document SLA targets.  
4. Replace the DB wait delay with a transactional outbox or smarter retry.  
5. Instrument the pipeline end-to-end and establish alert thresholds before scaling load.

