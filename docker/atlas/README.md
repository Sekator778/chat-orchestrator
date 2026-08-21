# atlas stand (macOS / Apple Silicon)

The local stand on the Mac. Infrastructure runs in containers, the application
runs **natively** as a plain JVM against it — there is no app container here.

| Piece | Where | Note |
|---|---|---|
| Postgres 16 | `tg-orch-postgres`, `127.0.0.1:5433` | db `news_aggregator_db`, user/password `staging` — the staging names, so the winbox `pg_dump` restores without renames |
| Redpanda (Kafka wire) | `tg-orch-redpanda`, `127.0.0.1:9094` | single node, `--mode dev-container`, advertises `localhost:9094` for the host JVM |
| Qdrant | `tg-orch-qdrant`, `127.0.0.1:6335` | |
| Embeddings (TEI, `BAAI/bge-m3`) | `127.0.0.1:8087` | **not in this stack** — already running natively on this machine, shared with the other local stands |
| App | host JVM, `127.0.0.1:8099` | JDK 21, TDLight classifier `macos_arm64` |

Ports are shifted off the ones other local stacks on this machine hold
(1522/9092, 6333, 6343).

## Commands

```bash
docker/atlas/bin/orchstack.sh up            # containers, wait until healthy
docker/atlas/bin/orchstack.sh app start     # build if needed, then run the jar
docker/atlas/bin/orchstack.sh app start --build   # force a rebuild first
docker/atlas/bin/orchstack.sh status        # containers + app health
docker/atlas/bin/orchstack.sh logs [svc]    # follow container logs
docker/atlas/bin/orchstack.sh sql           # psql as the application user
docker/atlas/bin/orchstack.sh app stop
docker/atlas/bin/orchstack.sh down          # containers gone, volumes kept
docker/atlas/bin/orchstack.sh nuke          # + delete volumes (typed confirmation)
```

App logs: `logs/app.log` (previous run kept as `logs/app.log.prev`).
Build log: `logs/build.log`. Pidfile: `.pids/app.pid`.

## Configuration

`cp .env.atlas.example .env.atlas` and edit — `.env.atlas` is gitignored and is
the only file `app start` reads. It ships dummy Telegram/DeepSeek values and
`SPRING_PROFILES_ACTIVE=smoke`, which boots the app headless (TDLight client off)
so the stand is usable before any real secret exists.

## Building by hand

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-oracle \
  mvn -s docker/atlas/settings-atlas.xml -Dtdlight.native.classifier=macos_arm64 verify
```

`-s docker/atlas/settings-atlas.xml` is mandatory: the machine-wide
`~/.m2/settings.xml` is offline and mirrors every repository id to Central, which
hides the `mchv` repository the TDLight artifacts come from.

## Still to restore from winbox

1. **Database.** `pg_dump` the staging DB on the WSL host, then load it here:
   `pg_dump -Fc -U staging news_aggregator_db > orch.dump` → copy over →
   `docker exec -i tg-orch-postgres pg_restore -U staging -d news_aggregator_db --clean --if-exists < orch.dump`.
   Same database and role names on both sides, so nothing needs remapping.
   Run it against a stand that has never booted the app, or `nuke` first — the
   dump carries its own `databasechangelog` and must not merge with a Liquibase
   run of its own.
2. **TDLight sessions.** Copy `${STAGING_DATA_DIR}/sessions/<botId>/` per account
   from the WSL host to a stable directory **outside this repo**, point each
   `TELEGRAM_ACCOUNTS_<n>_SESSIONSDIRECTORY` in `.env.atlas` at it, fill in the
   real `APIID`/`APIHASH`/`PHONENUMBER` and `DEEPSEEK_API_KEY`, and switch
   `SPRING_PROFILES_ACTIVE` to `staging`. Without that tree every account needs a
   fresh phone-code and 2FA login. Never delete it; back it up with `tar czf`.
