#!/usr/bin/env bash
# orchstack.sh - control the chat-orchestrator stand on atlas (Apple Silicon).
#
# Usage:
#   orchstack.sh up               containers only, wait until healthy
#   orchstack.sh down             stop and remove containers (volumes are kept)
#   orchstack.sh nuke             down + delete volumes (wipes the database)
#   orchstack.sh status           containers, app, health and memory
#   orchstack.sh logs [service]   follow container logs
#   orchstack.sh sql              open psql as the application user
#   orchstack.sh app start [--build]   build if needed, then run the jar natively
#   orchstack.sh app start --jar PATH  run a jar that was built elsewhere (CI)
#   orchstack.sh app stop|status|restart
#
# The app is NOT a container here: it runs as a plain JVM on the Mac against the
# infra this script starts. Its environment comes from docker/atlas/.env.atlas.
#
# The jar defaults to target/telegram-userbot-1.0.0.jar and is built on demand.
# `--jar PATH` (or APP_JAR=PATH in the environment) points the run at a prebuilt
# jar instead - that is how scripts/orch-deploy.sh runs the CI artifact. With an
# explicit jar nothing is ever built: a missing file is an error, not a rebuild.
set -euo pipefail

STACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "$STACK_DIR/../.." && pwd)"
COMPOSE=(docker compose -f "$STACK_DIR/docker-compose.yml")

PG_CONTAINER="tg-orch-postgres"
PG_USER="staging"
PG_DB="news_aggregator_db"
KAFKA_BOOTSTRAP="127.0.0.1:9094"
QDRANT_URL="http://127.0.0.1:6335"
# Reused, not containerized: the TEI service serving BAAI/bge-m3 already runs
# natively on this machine and is shared with the other local stacks.
EMBEDDING_URL="http://127.0.0.1:8087"

ENV_FILE="$STACK_DIR/.env.atlas"
SETTINGS_REL="docker/atlas/settings-atlas.xml"
# Verified against the TDLight BOM 3.4.0+td.1.8.26 (natives 4.0.506): macos_arm64
# is published and carries libtdjni.macos_arm64.dylib, so the jar runs natively -
# no Rosetta and no linux_amd64_gnu_ssl3-in-a-container fallback needed.
TD_CLASSIFIER="macos_arm64"
DEFAULT_APP_JAR="$PROJECT_ROOT/target/telegram-userbot-1.0.0.jar"
# An APP_JAR inherited from the environment marks the jar as externally supplied,
# exactly like --jar does: build-if-missing is off for it.
APP_JAR="${APP_JAR:-$DEFAULT_APP_JAR}"
if [ "$APP_JAR" = "$DEFAULT_APP_JAR" ]; then APP_JAR_EXTERNAL=false; else APP_JAR_EXTERNAL=true; fi
APP_PORT=8099
APP_LOG="$PROJECT_ROOT/logs/app.log"
BUILD_LOG="$PROJECT_ROOT/logs/build.log"
APP_PID_FILE="$PROJECT_ROOT/.pids/app.pid"
# The jar the running app was started from. Without it a plain `app status` (no
# APP_JAR in the environment) would not recognise a run started from a CI jar.
APP_JAR_FILE="$PROJECT_ROOT/.pids/app.jar"
JAVA_REQUIRED=21

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }

# OrbStack keeps a login helper running at all times, so `open -a OrbStack` finds
# the app already up and returns without booting the VM. orbctl is the only thing
# that actually starts and stops it.
orbctl_bin() {
  if command -v orbctl >/dev/null 2>&1; then
    echo orbctl
  elif [ -x "$HOME/.orbstack/bin/orbctl" ]; then
    echo "$HOME/.orbstack/bin/orbctl"
  else
    return 1
  fi
}

start_docker_engine() {
  local orb
  docker info >/dev/null 2>&1 && return 0
  if ! orb="$(orbctl_bin)"; then
    warn "the Docker engine is down and orbctl was not found - start OrbStack by hand"
    return 1
  fi
  log "starting the OrbStack engine"
  "$orb" start >/dev/null 2>&1 || true
  for _ in $(seq 30); do
    docker info >/dev/null 2>&1 && { log "the Docker engine is up"; return 0; }
    sleep 1
  done
  warn "the Docker engine did not come up in 30s - check '$orb status'"
  return 1
}

# The engine is shared with other local stacks and the ai-delivery
# stacks, so it may only be stopped once this stand was the last thing left on it.
stop_docker_engine() {
  local orb others
  others="$(docker ps -q 2>/dev/null | wc -l | tr -d ' ')" || others=0
  if [ "$others" != 0 ]; then
    warn "$others other container(s) are still running - leaving the Docker engine up"
    return 0
  fi
  if ! orb="$(orbctl_bin)"; then
    warn "orbctl was not found - leaving the Docker engine up"
    return 0
  fi
  log "stopping the OrbStack engine"
  "$orb" stop
}

wait_healthy() {
  local name="$1" tries="${2:-60}" state
  log "waiting for $name ..."
  for _ in $(seq "$tries"); do
    state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$state" in
      healthy)     log "$name is healthy"; return 0 ;;
      exited|dead) warn "$name died - see: orchstack.sh logs"; return 1 ;;
    esac
    sleep 2
  done
  warn "$name did not become healthy in time"
  return 1
}

# The embedding service is deliberately outside this stack, so its absence must be
# reported rather than silently tolerated: the app is fail-open and would run on
# with cosine ranking quietly degraded to value-only.
check_embeddings() {
  if curl -fsS -o /dev/null -m 3 "$EMBEDDING_URL/info" 2>/dev/null; then
    log "embeddings (TEI, native): UP on $EMBEDDING_URL"
  else
    warn "no TEI service on $EMBEDDING_URL - news embedding and cosine ranking will no-op"
  fi
}

cmd_up() {
  start_docker_engine || return 1

  # Another heavyweight local stack and this stand together exceed the Docker VM
  # memory budget on this machine, so warn instead of silently thrashing.
  if docker ps --format '{{.Names}}' | grep -q '^prisma-wls-'; then
    warn "another heavyweight local stack is running - stop it first if memory is tight"
  fi

  log "starting containers"
  "${COMPOSE[@]}" up -d
  wait_healthy "$PG_CONTAINER" 60
  wait_healthy tg-orch-redpanda 60
  wait_healthy tg-orch-qdrant 60
  check_embeddings
  containers_status
  cat <<EOF

  Postgres    localhost:5433/$PG_DB  (user $PG_USER / $PG_USER)
  Kafka       $KAFKA_BOOTSTRAP
  Qdrant      $QDRANT_URL
  Embeddings  $EMBEDDING_URL  (native, not part of this stack)
  App         orchstack.sh app start   ->  http://localhost:$APP_PORT/actuator/health
EOF
}

cmd_down() {
  log "stopping containers"
  "${COMPOSE[@]}" down
  stop_docker_engine
  log "everything is down - the Postgres, Redpanda and Qdrant volumes are kept"
}

cmd_nuke() {
  warn "this deletes the Postgres, Redpanda and Qdrant volumes - all local data is lost"
  read -r -p "type 'yes' to continue: " answer
  [ "$answer" = yes ] || { log "cancelled"; return 1; }
  "${COMPOSE[@]}" down -v
}

# --- native app --------------------------------------------------------------

# The machine default JDK is Java 8 (sdkman's `current`, which another project
# needs). This project is Java 21 end to end, so pin it here instead of changing
# the global default.
resolve_java() {
  local cand
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] &&
     "$JAVA_HOME/bin/java" -version 2>&1 | grep -q "version \"$JAVA_REQUIRED\."; then
    return 0
  fi
  for cand in "$HOME/.sdkman/candidates/java/$JAVA_REQUIRED"*/ \
              /Library/Java/JavaVirtualMachines/*"$JAVA_REQUIRED"*/Contents/Home/; do
    [ -x "${cand}bin/java" ] || continue
    JAVA_HOME="${cand%/}"
    export JAVA_HOME
    return 0
  done
  warn "no JDK $JAVA_REQUIRED found - install one (sdk install java 21.0.11-oracle) or export JAVA_HOME"
  return 1
}

build_app() {
  resolve_java || return 1
  mkdir -p "$PROJECT_ROOT/logs"
  log "building the jar (JDK $JAVA_REQUIRED, TDLight $TD_CLASSIFIER) - log: logs/build.log"
  # -s settings-atlas.xml: the machine-wide ~/.m2/settings.xml is offline and
  # mirrors every repository id to Central, which hides the TDLight repository.
  if ! (cd "$PROJECT_ROOT" && mvn -s "$SETTINGS_REL" -ntp \
          -Dtdlight.native.classifier="$TD_CLASSIFIER" \
          package -DskipTests > "$BUILD_LOG" 2>&1); then
    warn "the build failed - see logs/build.log"
    return 1
  fi
  log "build finished"
}

app_pid() {
  local pid running_jar
  [ -f "$APP_PID_FILE" ] || return 1
  pid="$(cat "$APP_PID_FILE" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  running_jar="$(cat "$APP_JAR_FILE" 2>/dev/null || true)"
  [ -n "$running_jar" ] || running_jar="$APP_JAR"
  # A recycled PID number from a stale pidfile must not be reported as ours, and
  # must never be killed by app_stop.
  ps -p "$pid" -o command= 2>/dev/null | grep -q -- "$(basename "$running_jar")" || return 1
  echo "$pid"
}

app_start() {
  local force_build=false
  while [ $# -gt 0 ]; do
    case "$1" in
      --build) force_build=true; shift ;;
      --jar)   [ -n "${2:-}" ] || { warn "--jar needs a path"; return 1; }
               APP_JAR="$2"; APP_JAR_EXTERNAL=true; shift 2 ;;
      *)       warn "unknown option: $1"; return 1 ;;
    esac
  done

  if app_pid >/dev/null; then
    log "the app is already running (PID $(app_pid)) - http://localhost:$APP_PORT/actuator/health"
    return 0
  fi
  if [ ! -f "$ENV_FILE" ]; then
    warn "$ENV_FILE is missing - copy docker/atlas/.env.atlas.example to it first"
    return 1
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    warn "$PG_CONTAINER is not running - run 'orchstack.sh up' first"
    return 1
  fi

  if [ "$APP_JAR_EXTERNAL" = true ]; then
    # A jar built elsewhere (CI) is never regenerated here: rebuilding would write
    # to target/ and leave this path untouched, so the run would silently use a
    # different jar than the one that was asked for.
    [ -f "$APP_JAR" ] || { warn "$APP_JAR does not exist - nothing to run"; return 1; }
    if [ "$force_build" = true ]; then warn "--build ignored: an explicit jar was given"; fi
    log "using the supplied jar: $APP_JAR"
  elif [ "$force_build" = true ] || [ ! -f "$APP_JAR" ]; then
    build_app || return 1
  else
    log "using the existing jar (pass --build to rebuild)"
  fi
  resolve_java || return 1

  mkdir -p "$PROJECT_ROOT/logs" "$PROJECT_ROOT/.pids"
  # One-generation rotation: the current
  # run and the one before it, nothing else.
  if [ -f "$APP_LOG" ]; then mv -f "$APP_LOG" "$APP_LOG.prev"; fi

  log "starting the app"
  # `exec` re-points the subshell's own descriptors before the JVM is forked, so
  # neither it nor anything it spawns is left holding this script's stdout - a
  # piped `orchstack.sh app start | tee` would otherwise never see end of input.
  (
    cd "$PROJECT_ROOT" || exit 1
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
    exec </dev/null >"$APP_LOG" 2>&1
    nohup "$JAVA_HOME/bin/java" -jar "$APP_JAR" &
    echo $! >"$APP_PID_FILE"
    echo "$APP_JAR" >"$APP_JAR_FILE"
  )

  for _ in $(seq 90); do
    if curl -fsS -o /dev/null -m 2 "http://localhost:$APP_PORT/actuator/health" 2>/dev/null; then
      log "the app is up - http://localhost:$APP_PORT/actuator/health"
      return 0
    fi
    app_pid >/dev/null || { warn "the app exited during startup - see logs/app.log"; return 1; }
    sleep 2
  done
  warn "the app did not answer on $APP_PORT within 180s - see logs/app.log"
  return 1
}

app_stop() {
  local pid
  if ! pid="$(app_pid)"; then
    log "the app is not running"
    rm -f "$APP_PID_FILE" "$APP_JAR_FILE"
    return 0
  fi
  log "stopping the app (PID $pid)"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 15); do
    ps -p "$pid" >/dev/null 2>&1 || { rm -f "$APP_PID_FILE" "$APP_JAR_FILE"; log "the app stopped"; return 0; }
    sleep 1
  done
  warn "the app ignored SIGTERM - sending SIGKILL"
  kill -9 "$pid" 2>/dev/null || true
  sleep 1
  rm -f "$APP_PID_FILE" "$APP_JAR_FILE"
}

app_status() {
  local pid health
  if pid="$(app_pid)"; then
    health="$(curl -fsS -m 3 "http://localhost:$APP_PORT/actuator/health" 2>/dev/null || echo 'no answer')"
    log "app: RUNNING (PID $pid, jar $(cat "$APP_JAR_FILE" 2>/dev/null || echo "$APP_JAR")) - $health"
  else
    log "app: STOPPED"
  fi
}

cmd_app() {
  case "${1:-status}" in
    start)   shift; app_start ${1+"$@"} ;;
    stop)    app_stop ;;
    status)  app_status ;;
    restart) shift; app_stop; sleep 2; app_start ${1+"$@"} ;;
    *)       warn "usage: orchstack.sh app [start [--build|--jar PATH]|stop|status|restart]"; return 1 ;;
  esac
}

# --- status / logs / sql -----------------------------------------------------

containers_status() {
  if ! docker info >/dev/null 2>&1; then
    warn "the Docker engine is not running - nothing from this stand can be up"
    return 0
  fi
  log "containers"
  "${COMPOSE[@]}" ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}'
  local names
  names="$(docker ps --filter label=com.docker.compose.project=chat-orchestrator --format '{{.Names}}')"
  if [ -n "$names" ]; then
    log "memory"
    # shellcheck disable=SC2086
    docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' $names
  fi
}

cmd_status() {
  containers_status
  app_status
}

cmd_logs() { "${COMPOSE[@]}" logs -f "${@}"; }

cmd_sql() { docker exec -it "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" "${@}"; }

case "${1:-status}" in
  # ${1+"$@"} passes nothing when nothing is left, instead of one empty argument.
  up)     cmd_up ;;
  down)   cmd_down ;;
  nuke)   cmd_nuke ;;
  app)    shift; cmd_app ${1+"$@"} ;;
  status) cmd_status ;;
  logs)   shift; cmd_logs ${1+"$@"} ;;
  sql)    shift; cmd_sql ${1+"$@"} ;;
  # Print the header comment block, whatever length it happens to be.
  *)      awk 'NR>1 && /^#/ { print; next } NR>1 { exit }' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
