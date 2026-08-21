# Staging stand — local prod-like dev environment

A persistent, prod-like environment on the local host, entirely in Docker. **One**
app instance manages every test account in-process (the `telegram.accounts` list —
2 today, 100 if you want), exactly like production. Backed by an isolated Postgres
+ Kafka (Redpanda) + Qdrant. Code reaches it via a local pipeline — no GitHub
Actions, no registry.

```
[Да] merge → origin/main
  → staging-watch (systemd timer, every 5 min) sees the new commit
  → deliver-staging.sh:  docker build  →  SMOKE GATE (headless boot + health)
                         → if green: docker compose up -d  (staging, rolling)
  → the one app instance runs the merged build as ALL the test accounts
```

## Architecture note — one instance, N accounts

The app is designed to manage many accounts in a single process:
`TelegramClientManager` iterates `telegram.accounts`, initialises the **primary**
(`bot.persona-ids[0]`) first and the rest as secondary clients. So staging is **one**
container, not one-per-account. Scaling = adding to the accounts list, nothing else.

## Prerequisites (one-time)

1. **Test Telegram accounts** (real user accounts — TDLight logs in as a user, not a
   bot token). Per account: `api_id` + `api_hash` from <https://my.telegram.org> and
   the phone number.
2. Docker Engine + Compose v2.
3. `gitleaks` on PATH (the pre-push guard uses it).
4. **Host kernel: `fs.aio-max-nr` ≥ 1048576.** Redpanda (seastar) reserves a large
   pool of async-IO slots; the smoke gate's redpanda runs *alongside* the live
   stand's redpanda, so the default (65536) is exhausted and the smoke broker fails
   to start (`Could not setup Async I/O`). Set persistently:
   `echo 'fs.aio-max-nr = 1048576' | sudo tee /etc/sysctl.d/99-redpanda-aio.conf && sudo sysctl --system`

## Setup

```bash
cp docker/staging/.env.staging.example docker/staging/.env.staging
$EDITOR docker/staging/.env.staging
```
Fill: `STAGING_DB_PASSWORD`, `STAGING_DATA_DIR` (a stable host dir OUTSIDE the repo —
see *Sacred sessions*), `BOT_PERSONA_IDS`, and one `TELEGRAM_ACCOUNTS_<n>_*` block per
account. `.env.staging` is gitignored — it never reaches GitHub.

### First boot + one-time Telegram auth

Run the app once interactively; TDLight asks for the login code of **each** account in
sequence on the console. Sessions then persist in `STAGING_DATA_DIR`.

```bash
mkdir -p "$STAGING_DATA_DIR"   # the dir from .env.staging
docker compose --env-file docker/staging/.env.staging \
  -f docker/staging/docker-compose.staging.yml run --rm app
# enter each account's code as Telegram sends it; once all are "logged in", Ctrl-C.
```
After that, normal delivery (`up -d`) reuses the saved sessions — no code re-entry.

## 🔒 Sacred sessions — do not wipe

Once the accounts are authorized, the session state is precious (re-auth needs the
phone code again). It is deliberately a **bind mount** to `STAGING_DATA_DIR`, a host
directory **outside the repo**, which means it survives everything that would nuke a
Docker volume:

- ✅ survives `docker compose down` and `down --volumes`/`-v` (bind mounts are untouched)
- ✅ survives `docker volume prune` and `docker system prune -a --volumes`
- ✅ survives container rebuilds/redeploys and any git operation (it's outside the repo)

**Rules:** never `rm -rf "$STAGING_DATA_DIR"`; back it up (`tar czf sessions-backup.tgz
-C "$STAGING_DATA_DIR" .`); keep it `chmod 700`. The delivery pipeline only ever does
`up -d` on staging (never `down -v`), so deploys never touch sessions.

## Delivery

**Manual:** `./scripts/deliver-staging.sh` (build → smoke gate → deploy).

**Automatic:** `./ops/install-staging-timer.sh` installs TWO systemd timers (sudo):
- **`staging-up.timer`** — `OnBootSec=5min`: brings the whole stack up ~5 min after the
  machine/WSL boots (just `up -d` on the last image + saved sessions; no rebuild). So
  the stand is live after a reboot without any manual step.
- **`staging-deliver.timer`** — polls `origin/main` every 5 min and delivers on a new
  commit (build → smoke gate → deploy).

The smoke gate is mandatory in deliver — if the headless boot/health check fails,
staging is **not** touched (deliver exits 2). To bring it up immediately without
waiting for the boot timer: `sudo systemctl start staging-up.service`.

## Operate

```bash
C="docker compose --env-file docker/staging/.env.staging -f docker/staging/docker-compose.staging.yml"
$C ps
docker logs -f tg-staging-app
curl -s localhost:${STAGING_APP_PORT:-8099}/actuator/health | jq .
$C stop          # stop (KEEPS everything)
$C down          # remove containers (KEEPS sessions — they're a bind mount)
# There is intentionally no documented `down -v` here: it would drop the DB + Kafka
# volumes (sessions are safe regardless). Run it only on a deliberate full reset.
```

## After a reboot

Nothing to do by hand — `staging-up.timer` brings the whole stack up automatically
~5 min after the machine/WSL boots, reusing the saved sessions (no re-login). To
bring it up **immediately** without waiting:

```bash
sudo systemctl start staging-up.service
# check:
systemctl list-timers 'staging-*' --no-pager
docker ps --filter name=tg-staging
```

If the timers aren't installed yet (fresh clone): `./ops/install-staging-timer.sh`
(sudo). Sessions live in `STAGING_DATA_DIR` (a bind mount, outside the repo) and
survive reboots, container rebuilds, and `docker compose down`.

## Safety

- Real creds live only in `docker/staging/.env.staging` (gitignored) and in
  `STAGING_DATA_DIR` (host dir) — never committed.
- The staging profile keeps heavy auto-posting (proactive engagement, digests) OFF by
  default so a test account can't spam real channels by accident.
- A `.git/hooks/pre-push` blocks real IDs / personal paths / secret-shaped strings from
  reaching the public repo (gitleaks runs in git mode, so it ignores `.env.staging`).
