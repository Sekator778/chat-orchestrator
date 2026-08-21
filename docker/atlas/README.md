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
docker/atlas/bin/orchstack.sh app start     # build if needed, then run the jar
docker/atlas/bin/orchstack.sh app start --build   # force a rebuild first
docker/atlas/bin/orchstack.sh app start --jar PATH  # run a jar built elsewhere (CI)
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

## Continuous delivery (build once in CI, deploy the artifact here)

The stand does not build the mainline any more: `.github/workflows/ci.yml` builds
the jar once per merge to `main` and `scripts/atlas-deliver.sh` pulls it in.

Why CI builds a *second* jar rather than shipping the one it tested: the TDLight
natives are baked into the fat jar at build time, chosen by
`-Dtdlight.native.classifier`. The tested jar carries `linux_amd64_gnu_ssl3` and
cannot start natively on this Mac, so the workflow adds a
`-Dtdlight.native.classifier=macos_arm64 -DskipTests` build and publishes that as
`app-jar-<sha>`. The natives are an ordinary Maven artifact, so building the
macOS jar on an ubuntu runner is fine; CI asserts the right natives are inside
before uploading, and the deliver script checks again before installing.

`orchstack.sh app start --build` still builds locally — that path is untouched
and remains the way to run an uncommitted working tree.

### Deliver by hand

```bash
gh auth login                  # once; the script needs `actions:read`
scripts/atlas-deliver.sh       # newest green main build -> this stand
scripts/atlas-deliver.sh --force   # redeploy the same SHA, or retry a failed one
```

One run: find the newest successful CI run on `main`, compare its head SHA with
what is deployed, download `app-jar-<sha>`, restart the app from it, and gate on
`HTTP 200` from `/actuator/health` (60s, `ORCH_HEALTH_TIMEOUT`). **The SHA is
recorded only after that gate passes** — the WSL stand's `staging-watch.sh`
marked the revision before the deploy was proven and then sat on a dead build
believing it was current. A second run on the same SHA is a no-op, so the script
is safe to poll.

Everything lives outside the repo, in `~/.orch-deploy/` (`ORCH_DEPLOY_DIR`):

| File | Meaning |
|---|---|
| `current.jar` | what the app runs now |
| `previous.jar` | last known-good jar — the rollback target |
| `failed.jar` | the jar of the last failed deploy, kept for a post-mortem |
| `state` | SHA of the last deploy that passed the health gate |
| `last-failed` | SHA of the last deploy that failed it |
| `deliver.log` | where the launchd agent writes (see below) |

Exit codes: `0` deployed / up to date / stand is down, `1` tooling or no green
run, `2` gate failed and rolled back, `3` gate failed with no previous jar (the
app is left stopped), `4` this SHA already failed here — `--force` retries it.
That last guard is what keeps a broken mainline from restarting the stand every
five minutes.

A stand whose containers are down is not an error: the script says so and exits
`0`. It also never resurrects an app that died on its own — it only reacts to a
new SHA. Use `orchstack.sh app start` for that.

### Automate it (optional)

`docker/atlas/com.example.orch-deliver.plist.example` is a LaunchAgent that runs
the script every 5 minutes — the macOS counterpart of the WSL stand's
`ops/systemd/staging-deliver.timer`. Install:

```bash
sed -e "s|__REPO_DIR__|$PWD|g" -e "s|__HOME__|$HOME|g" \
  docker/atlas/com.example.orch-deliver.plist.example \
  > ~/Library/LaunchAgents/com.example.orch-deliver.plist
plutil -lint ~/Library/LaunchAgents/com.example.orch-deliver.plist
mkdir -p ~/.orch-deploy
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.example.orch-deliver.plist
launchctl kickstart -p gui/$(id -u)/com.example.orch-deliver   # run one poll now
launchctl bootout   gui/$(id -u)/com.example.orch-deliver      # uninstall
```

Logs land in `~/.orch-deploy/deliver.log`. It is an agent, not a daemon, because
it needs the logged-in user's Docker socket, `gh` credentials and JDK.

### Rollback

Automatic on a failed health gate: the app is stopped, the bad jar is moved aside
to `failed.jar`, `previous.jar` is restored and started, and the script exits
non-zero with the state file still pointing at the older, working SHA.

By hand:

```bash
docker/atlas/bin/orchstack.sh app stop
cp ~/.orch-deploy/previous.jar ~/.orch-deploy/current.jar
docker/atlas/bin/orchstack.sh app start --jar ~/.orch-deploy/current.jar
# and stop the next poll from re-installing the bad build:
echo "<sha-to-skip>" > ~/.orch-deploy/last-failed
```

To rehearse the rollback path on a healthy stand, give the gate a budget the app
cannot possibly meet — the deploy fails, `previous.jar` comes back (the recovery
gate keeps a 120s floor of its own) and the exit code is `2`:

```bash
ORCH_HEALTH_TIMEOUT=1 scripts/atlas-deliver.sh --force
```

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
