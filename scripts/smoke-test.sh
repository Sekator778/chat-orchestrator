#!/usr/bin/env bash
# ============================================================================
# smoke-test.sh — Single-entry smoke test for telegram-userbot
# ============================================================================
#
# Brings up the full application stack (PostgreSQL + Redpanda + app) in an
# isolated Docker environment, waits for GET /actuator/health to return
# {"status":"UP"}, asserts Liquibase migrations were applied, prints PASS/FAIL,
# and unconditionally tears down all containers and volumes.
#
# Prerequisites: Docker Engine and Docker Compose V2
#   (https://docs.docker.com/compose/install/)
#
# Usage:
#   ./scripts/smoke-test.sh
#
# Exit codes:
#   0 = PASS (all assertions succeeded)
#   1 = FAIL (any assertion failed or timeout)
#
# The script is idempotent — consecutive runs each start from a clean state.
#
# ============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Locate the smoke compose directory relative to this script
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE_DIR="$(cd "${SCRIPT_DIR}/../docker/smoke" && pwd)"
COMPOSE_FILE="${SMOKE_DIR}/docker-compose.smoke.yml"
PROJECT_NAME="telegram-userbot-smoke"

# ---------------------------------------------------------------------------
# Configuration — tunable via environment variables
# ---------------------------------------------------------------------------
HEALTH_URL="${SMOKE_HEALTH_URL:-http://localhost:8099/actuator/health}"
POLL_INTERVAL="${SMOKE_POLL_INTERVAL:-5}"
HEALTH_TIMEOUT="${SMOKE_HEALTH_TIMEOUT:-120}"
COMPOSE_DOWN_OPTS="--volumes --remove-orphans"

# ---------------------------------------------------------------------------
# Detect Docker Compose V2 vs V1
# ---------------------------------------------------------------------------
COMPOSE_CMD=""
if docker compose version &>/dev/null; then
    COMPOSE_CMD="docker compose"
elif docker-compose --version &>/dev/null; then
    echo "WARNING: Docker Compose V2 (docker compose) is recommended; using V1 (docker-compose)"
    COMPOSE_CMD="docker-compose"
else
    echo "ERROR: Docker Compose is not installed. See docs/smoke-test-runbook.md for prerequisites."
    exit 1
fi

# ---------------------------------------------------------------------------
# Timestamped logging
# ---------------------------------------------------------------------------
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

# ---------------------------------------------------------------------------
# Trap: guaranteed teardown on any exit (PASS, FAIL, SIGINT, SIGTERM)
# ---------------------------------------------------------------------------
cleanup() {
    log "TEARDOWN: Stopping smoke stack and removing containers/volumes..."
    cd "${SMOKE_DIR}"
    ${COMPOSE_CMD} -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" down ${COMPOSE_DOWN_OPTS} 2>/dev/null || true
    log "TEARDOWN: Complete"
}
trap cleanup EXIT SIGINT SIGTERM

# ---------------------------------------------------------------------------
# Step 1: Build the Docker image
# ---------------------------------------------------------------------------
log "BUILD: Building smoke Docker image..."
cd "${SMOKE_DIR}"
${COMPOSE_CMD} -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build 2>&1 | while IFS= read -r line; do
    log "BUILD: ${line}"
done

# ---------------------------------------------------------------------------
# Step 2: Start the smoke stack
# ---------------------------------------------------------------------------
log "START: Starting smoke stack (PostgreSQL + Redpanda + app)..."
${COMPOSE_CMD} -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d 2>&1 | while IFS= read -r line; do
    log "START: ${line}"
done

# ---------------------------------------------------------------------------
# Step 3: Poll GET /actuator/health until UP or timeout
# ---------------------------------------------------------------------------
log "HEALTH: Polling ${HEALTH_URL} (interval=${POLL_INTERVAL}s, timeout=${HEALTH_TIMEOUT}s)..."
START_TIME=$(date +%s)
HEALTH_UP=false

while true; do
    ELAPSED=$(( $(date +%s) - START_TIME ))
    if [ "${ELAPSED}" -ge "${HEALTH_TIMEOUT}" ]; then
        log "HEALTH: TIMEOUT after ${HEALTH_TIMEOUT}s — health endpoint did not return UP"
        break
    fi

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${HEALTH_URL}" 2>/dev/null || echo "000")
    if [ "${HTTP_CODE}" = "200" ]; then
        HEALTH_BODY=$(curl -s "${HEALTH_URL}" 2>/dev/null || echo "{}")
        if echo "${HEALTH_BODY}" | grep -q '"status":"UP"'; then
            log "HEALTH: SUCCESS — ${HEALTH_BODY}"
            HEALTH_UP=true
            break
        fi
        log "HEALTH: Got HTTP ${HTTP_CODE} but status is not UP yet — body: ${HEALTH_BODY}"
    else
        log "HEALTH: HTTP ${HTTP_CODE} — retrying in ${POLL_INTERVAL}s..."
    fi

    sleep "${POLL_INTERVAL}"
done

# ---------------------------------------------------------------------------
# Step 3a: Fail if health never came UP
# ---------------------------------------------------------------------------
if [ "${HEALTH_UP}" != "true" ]; then
    log "HEALTH: FAIL — printing application logs for diagnosis"
    ${COMPOSE_CMD} -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=80 app 2>/dev/null || true
    echo "FAIL"
    exit 1
fi

# ---------------------------------------------------------------------------
# Step 4: Assert Liquibase migrations were applied
# ---------------------------------------------------------------------------
log "MIGRATIONS: Asserting Liquibase migrations were applied..."
MIGRATION_COUNT=$(
    ${COMPOSE_CMD} -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" exec -T postgres \
        psql -U smoke -d news_aggregator_db -t -c \
        "SELECT count(*) FROM bot.databasechangelog;" 2>/dev/null | tr -d '[:space:]'
)
if [ -z "${MIGRATION_COUNT}" ] || [ "${MIGRATION_COUNT}" = "0" ]; then
    log "MIGRATIONS: FAIL — expected >=1 row in bot.databasechangelog, got '${MIGRATION_COUNT}'"
    echo "FAIL"
    exit 1
fi
log "MIGRATIONS: SUCCESS — ${MIGRATION_COUNT} migration(s) applied in bot.databasechangelog"

# ---------------------------------------------------------------------------
# Step 5: All assertions passed — print PASS
# ---------------------------------------------------------------------------
log "RESULT: All smoke assertions passed"
echo "PASS"
exit 0
