# atlas stand (macOS / Apple Silicon)

The local stand on the Mac. Infrastructure runs in containers, the application
runs **natively** as a plain JVM against it — there is no app container here.

| Piece | Where | Note |
|---|---|---|
| Postgres 16 | `tg-orch-postgres`, `127.0.0.1:5433` | db `news_aggregator_db`, user/password `staging` — the staging names, so the winbox `pg_dump` restores without renames |
| Redpanda (Kafka wire) | `tg-orch-redpanda`, `127.0.0.1:9094` | single node, `--mode dev-container`, advertises `localhost:9094` for the host JVM, `--smp 1` (one Seastar reactor thread — keeps idle CPU low on a shared dev machine) |
| Qdrant | `tg-orch-qdrant`, `127.0.0.1:6335` | |
| Embeddings (TEI, `BAAI/bge-m3`) | `127.0.0.1:8087` | **not in this stack** — already running natively on this machine, shared with the other local stands |
| App | host JVM, `127.0.0.1:8099` | JDK 21, TDLight classifier `macos_arm64` |

Ports are shifted off the ones other local stacks on this machine hold
(1522/9092, 6333, 6343).

## Commands

```bash
docker/atlas/bin/orchstack.sh up            # containers, wait until healthy
docker/atlas/bin/orchstack.sh app start     # update from main if newer, then run
docker/atlas/bin/orchstack.sh app start --no-update  # run what is already here
docker/atlas/bin/orchstack.sh app start --build      # build this working copy
docker/atlas/bin/orchstack.sh app start --jar PATH   # run one specific jar
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

## Updates: the stand refreshes itself when you start it

There is no timer, no agent and nothing polling in the background. Starting the
app is the update:

```bash
docker/atlas/bin/orchstack.sh up
docker/atlas/bin/orchstack.sh app start
```

`app start` asks GitHub whether `main` carries a build newer than the one
deployed here. If it does, the jar is downloaded and started. If it does not — or
there is no network, no `gh`, no artifact — the jar already in `~/.orch-deploy`
starts instead. Either way the stand comes up; the check never blocks it. Whatever
lands on `main` after that is picked up the next time you start the app.

CI builds that jar once per commit on `main` (`.github/workflows/ci.yml`) and it
carries the macOS/arm64 TDLight natives. That matters: the natives are baked into
the fat jar at build time, so the jar CI *tests* (linux classifier) cannot start
natively here — the workflow builds the deploy jar separately with
`-Dtdlight.native.classifier=macos_arm64` and publishes it as `app-jar-<sha>`.
Work integrates on `dev`, and promoting `dev` into `main` is what produces a
build this stand will take.

`gh auth login` once — the download needs `actions:read`.

### The other ways to start

```bash
docker/atlas/bin/orchstack.sh app start --no-update    # run what is here, no GitHub
docker/atlas/bin/orchstack.sh app start --build        # build this working copy
docker/atlas/bin/orchstack.sh app start --jar PATH     # run one specific jar
```

`--build` is the way to run uncommitted work; `--no-update` is for offline or when
you deliberately want to stay on the current build.

### What lives in ~/.orch-deploy

| File | Meaning |
|---|---|
| `current.jar` | what the app runs |
| `previous.jar` | the build before it — the rollback target |
| `failed.jar` | a downloaded build that would not come up, kept for a post-mortem |
| `state` | SHA of the build that last came up healthy |

It is outside the repository on purpose: `git clean` cannot wipe a running
deployment.

### Failure and rollback

A downloaded build has to answer `HTTP 200` on `/actuator/health` before it counts
as deployed — `state` is written only then. The predecessor of this setup on the
WSL stand marked the revision before the deploy was proven, and one broken build
left the stand pinned to a revision that never came up.

If a freshly downloaded build does not come up, `app start` puts it aside as
`failed.jar`, restores `previous.jar` and starts that, leaves `state` pointing at
the older working build, and exits non-zero. A jar you asked for by hand
(`--jar`, `--build`) is never swapped out from under you — it just fails.

To go back a version deliberately:

```bash
docker/atlas/bin/orchstack.sh app stop
cp ~/.orch-deploy/previous.jar ~/.orch-deploy/current.jar
docker/atlas/bin/orchstack.sh app start --no-update
```

`--no-update` matters there: without it the newer build is fetched again.

### Which version is running

```bash
cat ~/.orch-deploy/state
curl -s localhost:8099/actuator/info
docker/atlas/bin/orchstack.sh app status
```

`state` is the SHA, `/actuator/info` is the build stamp from inside the running
process, and `app status` shows the PID and the jar path it was started from.

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
