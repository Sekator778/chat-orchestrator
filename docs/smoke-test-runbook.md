# Smoke Test Runbook — telegram-userbot

## Overview

The smoke harness is a self-contained, ephemeral test that verifies the full
application stack (Spring Boot + PostgreSQL + Redpanda + Liquibase) starts up
correctly in a headless environment — without a real Telegram session or live
LLM credentials.

One command from the repo root produces `PASS` (exit 0) or `FAIL` (non-zero).

## Prerequisites

| Tool     | Minimum version | Verify with              |
|----------|-----------------|--------------------------|
| Docker   | 24.0+           | `docker --version`       |
| Docker Compose V2 | 2.20+    | `docker compose version` |

No Java, Maven, or JDK is required on the host — all build steps run inside
Docker.

Outbound internet access is required on the **first** run only (to pull base
Docker images). Subsequent runs on the same host operate with cached images.

## Invocation

From the repository root:

```bash
./scripts/smoke-test.sh
```

No arguments are required and no environment variables need to be set before
invocation. All credentials for the smoke infrastructure are fixed dummy values
defined within the Compose file.

### Optional overrides

The following environment variables can be set to tune the harness:

| Variable                  | Default                        | Description                          |
|---------------------------|--------------------------------|--------------------------------------|
| `SMOKE_HEALTH_URL`        | `http://localhost:8099/...`     | Actuator health endpoint URL         |
| `SMOKE_POLL_INTERVAL`     | `5`                            | Seconds between health poll attempts |
| `SMOKE_HEALTH_TIMEOUT`    | `120`                          | Max seconds to wait for UP           |

Example:

```bash
SMOKE_HEALTH_TIMEOUT=180 ./scripts/smoke-test.sh
```

## What is checked

The script runs five sequential steps:

| Step | Check                                               | On failure             |
|------|-----------------------------------------------------|------------------------|
| 1    | Build the Docker image                              | exit 1 (build error)   |
| 2    | Start PostgreSQL, Redpanda, and the app             | exit 1 (startup error) |
| 3    | Poll `GET /actuator/health` until `{"status":"UP"}` | exit 1 (timeout/error) |
| 4    | Assert `bot.databasechangelog` has >=1 row          | exit 1 (no migrations) |
| 5    | Print `PASS` and exit 0                             | —                      |

Teardown runs **unconditionally** via a `trap` handler: `docker compose down
--volumes --remove-orphans` fires on PASS, FAIL, SIGINT, or SIGTERM.

## Interpreting output

### PASS

```
[2026-06-03 12:00:01] BUILD: Building smoke Docker image...
[2026-06-03 12:03:15] BUILD: Success
[2026-06-03 12:03:16] START: Starting smoke stack...
[2026-06-03 12:03:30] HEALTH: SUCCESS — {"status":"UP"}
[2026-06-03 12:03:31] MIGRATIONS: SUCCESS — 42 migration(s) applied in bot.databasechangelog
[2026-06-03 12:03:31] RESULT: All smoke assertions passed
[2026-06-03 12:03:31] TEARDOWN: ...
PASS
```

### FAIL

```
[2026-06-03 12:00:01] BUILD: Building smoke Docker image...
[2026-06-03 12:03:15] BUILD: Success
[2026-06-03 12:03:16] START: Starting smoke stack...
[2026-06-03 12:05:16] HEALTH: TIMEOUT after 120s — health endpoint did not return UP
[2026-06-03 12:05:16] HEALTH: FAIL — printing application logs for diagnosis
[2026-06-03 12:05:17] TEARDOWN: ...
FAIL
```

On FAIL, the script prints the last 80 lines of the application container logs
for diagnosis. Common causes:

- **Context initialization error** — check for `IllegalStateException` or
  missing bean errors in the log.
- **Kafka broker unreachable** — verify Redpanda is running and the
  `KAFKA_BOOTSTRAP_SERVERS` env var is set correctly.
- **Liquibase migration failure** — check for SQL errors in the log.
- **Timeout before health UP** — the app may need more time; increase
  `SMOKE_HEALTH_TIMEOUT` or check `StartupOrchestrator` logs for extended waits.

## Performance expectations

| Scenario          | Expected wall-clock | Bound     |
|-------------------|---------------------|-----------|
| Warm (images cached) | Under 5 minutes   | NFR-01 |
| Cold (first pull)    | Under 15 minutes  | NFR-02 |

On a warm host, the dominant time is the Maven build (downloads dependencies,
compiles, packages). Subsequent runs on the same host with unchanged source
benefit from Docker layer caching for the `mvn dependency:go-offline` layer.

## Extending the smoke profile

The smoke profile is defined in `src/main/resources/application-smoke.yml`.

To add a new feature-flag override:

1. Open `application-smoke.yml`
2. Add the property set to `false`, following the existing dotted-key format:

   ```yaml
   my-new-feature:
     enabled: false
   ```

3. Update the contract test in
   `src/test/java/com/example/telegramuserbot/config/ApplicationSmokeProfileContractTest.java`
   to assert the new flag value.

To stub a new external dependency:

1. If it has an existing interface, add a no-op implementation (following the
   `NoOpTelegramClientFacade` pattern).
2. Register it in `SmokeTelegramClientConfig` (gated by the appropriate
   `@ConditionalOnProperty`).

## Smoke profile activated features

The following are set to `false` in the smoke profile to ensure headless
operation:

- `telegram.client.enabled` — prevents `TelegramClientConfig` from throwing
- `startup.sync.enabled`
- `scheduler.chat-discovery.enabled`
- `events.watcher.enabled`
- `events.publisher.enabled`
- `python.scheduler.enabled`
- `pipeline.observability.enabled`
- `pending-response.scheduler.enabled`
- `digest.scheduler.enabled`
- `proactive.engagement.enabled`

## Comparison: smoke vs. test profile

| Aspect               | `smoke` (this feature)      | `test` (existing)               |
|----------------------|-----------------------------|---------------------------------|
| Profile file         | `src/main/resources/`       | `src/test/resources/`           |
| Spring profile name  | `smoke`                     | `test`                          |
| Database             | Ephemeral Docker PostgreSQL | `unit_db` (locally assumed)     |
| Kafka broker         | Redpanda in Docker          | Embedded via `@DynamicPropertySource` |
| Classpath            | Runtime (deployable image)  | Test only                       |
| Activation           | `SPRING_PROFILES_ACTIVE=smoke` | `@ActiveProfiles("test")`    |

## Security note

The smoke harness uses only dummy credentials (`smoke-placeholder`,
`smoke-password`). No real API keys, Telegram tokens, or phone numbers are
committed. If you add a new credential placeholder, use a value that is
recognizably fake (following the existing convention) and update the contract
test (`AC-08`).

## Known limitations

- **SIGKILL (`kill -9`) cannot be trapped.** A hard-killed run may leave
  containers or volumes. The next invocation's `docker compose down
  --remove-orphans` cleanup at the start (via `trap` on `EXIT`) reclaims them.
- **Linux/amd64 only.** `Dockerfile.smoke` builds the TDLight natives with the
  `linux_amd64_gnu_ssl3` classifier (glibc + OpenSSL 3, matching the
  `temurin:21-jre-jammy` runtime) — override `--build-arg TD_LIGHT_CLASSIFIER=…`
  for a different base (e.g. `_gnu_ssl1` for OpenSSL 1.1). The bare `linux_amd64`
  is **not** a published classifier and fails the build. arm64 is not supported.
- **Redpanda, not Apache Kafka.** The smoke stack uses Redpanda as a
  Kafka-wire-protocol broker. It verifies context wiring against a
  Kafka-compatible endpoint, not production Kafka itself.

## Rollback

The smoke harness is entirely additive:

1. Delete `application-smoke.yml`, `SmokeTelegramClientConfig.java`,
   `NoOpTelegramClientFacade.java`, and the `docker/smoke/`, `scripts/`, and
   `docs/` artifacts.
2. Revert the short-circuit branch in
   `StartupOrchestrator.waitForTdLibReadiness()`.

No database migration, no config flip on running production, and no change to
`application.yml` or any non-smoke code path.
