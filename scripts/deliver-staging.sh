#!/usr/bin/env bash
# =============================================================================
# Local CD for the staging stand:  build -> smoke-gate -> deploy staging.
# No GitHub Actions / registry — everything on this host, in Docker.
#
#   ./scripts/deliver-staging.sh
#
# Exit 0 = staging updated. Exit 2 = smoke gate failed (staging untouched).
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

IMAGE="${APP_IMAGE:-telegram-userbot:staging}"
ENVFILE="docker/staging/.env.staging"
SMOKE="docker/smoke/docker-compose.smoke.yml"
STAGING="docker/staging/docker-compose.staging.yml"
SMOKE_APP="telegram-userbot-smoke-app"
log() { printf '\n\033[1m[deliver-staging]\033[0m %s\n' "$*"; }

[ -f "$ENVFILE" ] || { echo "ERROR: $ENVFILE missing — cp docker/staging/.env.staging.example $ENVFILE and fill it."; exit 1; }

log "1/4 build app image ($IMAGE) — TDLight classifier linux_amd64_gnu_ssl3"
docker build -f docker/smoke/Dockerfile.smoke \
  --build-arg TD_LIGHT_CLASSIFIER=linux_amd64_gnu_ssl3 -t "$IMAGE" .

log "2/4 smoke gate (headless boot + /actuator/health) — must pass before deploy"
docker compose -f "$SMOKE" up -d --build
gate_ok=0
for _ in $(seq 1 36); do
  st=$(docker inspect -f '{{.State.Health.Status}}' "$SMOKE_APP" 2>/dev/null || echo "")
  [ "$st" = "healthy" ] && { gate_ok=1; break; }
  run=$(docker inspect -f '{{.State.Running}}' "$SMOKE_APP" 2>/dev/null || echo "false")
  [ "$run" = "false" ] && break   # container exited before becoming healthy
  sleep 5
done
[ "$gate_ok" = 1 ] || docker compose -f "$SMOKE" logs --tail 40 app || true
docker compose -f "$SMOKE" down --volumes >/dev/null 2>&1 || true
[ "$gate_ok" = 1 ] || { echo "SMOKE GATE FAILED — staging NOT touched."; exit 2; }
log "smoke gate PASSED"

log "3/4 deploy staging (reuses persistent sessions/volumes)"
docker compose --env-file "$ENVFILE" -f "$STAGING" up -d

log "4/4 staging status"
sleep 5
for c in tg-staging-bot-a tg-staging-bot-b; do
  echo "  $c: $(docker inspect -f '{{.State.Status}} (health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}})' "$c" 2>/dev/null || echo absent)"
done
log "done. First run per account needs a one-time interactive login code — see docs/staging-stand.md."
