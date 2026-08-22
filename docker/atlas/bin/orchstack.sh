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
#   orchstack.sh app start             update from main if there is anything
#                                      newer, then run it
#   orchstack.sh app start --no-update run the jar already here, as is
#   orchstack.sh app start --build     build this working copy and run that
#   orchstack.sh app start --jar PATH  run a specific jar
#   orchstack.sh app stop|status|restart
#
# The app is NOT a container here: it runs as a plain JVM on the Mac against the
# infra this script starts. Its environment comes from docker/atlas/.env.atlas.
#
# Starting the app is also how the stand updates itself. `app start` asks GitHub
# whether main has a build newer than the one deployed here; if it does, the jar
# is downloaded and started, and if it does not - or there is no network, no gh,
# nothing to download - the jar already in ~/.orch-deploy starts instead. Nothing
# runs in the background: no timer, no agent, no polling. The stand is current as
# of the moment you started it.
#
# CI builds that jar once per commit on main (see .github/workflows/ci.yml); it
# carries the macOS/arm64 TDLight natives, which a linux-classifier jar does not,
# so the artifact from a main build is the only one that runs natively here.
#
#   ~/.orch-deploy/current.jar    what the app runs
#   ~/.orch-deploy/previous.jar   the one before it - the rollback target
#   ~/.orch-deploy/state          SHA of the build that last came up healthy
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
BUILT_APP_JAR="$PROJECT_ROOT/target/telegram-userbot-1.0.0.jar"

# What the stand runs, kept outside the repo so a `git clean` cannot wipe a
# running deployment.
DEPLOY_DIR="${ORCH_DEPLOY_DIR:-$HOME/.orch-deploy}"
CURRENT_JAR="$DEPLOY_DIR/current.jar"
PREVIOUS_JAR="$DEPLOY_DIR/previous.jar"
STATE_FILE="$DEPLOY_DIR/state"

# Where the update comes from. ORCH_REPO overrides the repository the git remote
# points at; ORCH_BRANCH the branch whose builds are deployable.
ORCH_REPO="${ORCH_REPO:-}"
ORCH_BRANCH="${ORCH_BRANCH:-main}"
# How far back to look for a build that actually published a jar. Only matters
# for old commits: green runs from before the deploy job existed have none.
ORCH_SCAN_RUNS="${ORCH_SCAN_RUNS:-10}"

APP_JAR="$CURRENT_JAR"
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

# Ask GitHub whether main carries a build newer than the one deployed here and,
# if so, put it in place as current.jar. Best effort by design: no gh, no network,
# no artifact - the stand still starts on the jar it already has. UPDATED_SHA is
# left non-empty only when a new jar was installed, so app_start knows to record
# it once the app answers, and to roll back if it does not.
UPDATED_SHA=""

resolve_repo() {
  [ -n "$ORCH_REPO" ] && return 0
  ORCH_REPO="$(cd "$PROJECT_ROOT" && gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)"
  [ -n "$ORCH_REPO" ]
}

# A green run is not automatically a deployable one: runs from before the deploy
# job existed, and runs whose artifact has aged out of retention, publish nothing.
run_has_jar() {
  gh api "repos/$ORCH_REPO/actions/runs/$1/artifacts" \
     --jq '[.artifacts[] | select(.expired == false) | select(.name | startswith("app-jar-"))] | length' \
     2>/dev/null | grep -qx '[1-9][0-9]*'
}

update_from_main() {
  local deployed runs candidate_id candidate_sha run_id sha tmp new_jar

  command -v gh >/dev/null 2>&1 || { warn "gh is not installed - starting the jar already here"; return 0; }
  gh auth status >/dev/null 2>&1 || { warn "gh is not authenticated ('gh auth login') - starting the jar already here"; return 0; }
  resolve_repo || { warn "cannot tell which GitHub repository to use - starting the jar already here"; return 0; }

  mkdir -p "$DEPLOY_DIR"
  deployed="$(cat "$STATE_FILE" 2>/dev/null || true)"

  log "checking $ORCH_BRANCH for a newer build"
  runs="$(gh run list -R "$ORCH_REPO" --workflow ci.yml --branch "$ORCH_BRANCH" \
            --status success --limit "$ORCH_SCAN_RUNS" \
            --json databaseId,headSha --jq '.[] | "\(.databaseId) \(.headSha)"' 2>/dev/null || true)"
  if [ -z "$runs" ]; then
    warn "GitHub gave no successful $ORCH_BRANCH build (offline?) - starting the jar already here"
    return 0
  fi

  run_id=""; sha=""
  # Newest first. Reaching the deployed SHA means everything newer published no
  # jar, so there is nothing to update to.
  while read -r candidate_id candidate_sha; do
    [ -n "$candidate_id" ] || continue
    if [ "$candidate_sha" = "$deployed" ]; then
      log "already on the newest $ORCH_BRANCH build ($(short_sha "$deployed"))"
      return 0
    fi
    if run_has_jar "$candidate_id"; then run_id="$candidate_id"; sha="$candidate_sha"; break; fi
  done <<EOF
$runs
EOF

  if [ -z "$run_id" ]; then
    warn "no $ORCH_BRANCH build in the last $ORCH_SCAN_RUNS published a jar - starting the jar already here"
    return 0
  fi

  log "new build on $ORCH_BRANCH: $(short_sha "$sha") (run $run_id) - downloading"
  tmp="$(mktemp -d "$DEPLOY_DIR/download.XXXXXX")"
  if ! gh run download "$run_id" -R "$ORCH_REPO" -n "app-jar-$sha" -D "$tmp" >/dev/null 2>&1; then
    rm -rf "$tmp"
    warn "could not download the artifact of run $run_id - starting the jar already here"
    return 0
  fi
  new_jar="$(find "$tmp" -type f -name '*.jar' | head -1)"
  # The natives are baked in at build time: a linux-classifier jar cannot start
  # natively on this Mac, so refuse it here rather than at the health wait.
  if [ -z "$new_jar" ] ||
     ! unzip -l "$new_jar" 2>/dev/null | grep -q 'tdlight-natives-.*-macos_arm64\.jar'; then
    rm -rf "$tmp"
    warn "that build has no usable macOS jar - starting the jar already here"
    return 0
  fi

  [ -f "$CURRENT_JAR" ] && mv -f "$CURRENT_JAR" "$PREVIOUS_JAR"
  mv -f "$new_jar" "$CURRENT_JAR"
  rm -rf "$tmp"
  UPDATED_SHA="$sha"
  log "installed the new jar - the running one is kept as previous.jar"
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

short_sha() { echo "$1" | cut -c1-12; }

# Forks the JVM and returns; the caller decides how long to wait for it.
launch_app() {
  mkdir -p "$PROJECT_ROOT/logs" "$PROJECT_ROOT/.pids"
  # One-generation rotation: the current run and the one before it, nothing else.
  if [ -f "$APP_LOG" ]; then mv -f "$APP_LOG" "$APP_LOG.prev"; fi
  log "starting the app from $APP_JAR"
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
}

# Our process first, the port second. A health check that trusts the port alone
# passes on a stale JVM from an earlier run that is still bound to it, and then a
# deploy records a SHA that is not what is actually serving.
wait_for_health() {
  local tries="${1:-90}"
  for _ in $(seq "$tries"); do
    app_pid >/dev/null || { warn "the app exited during startup - see logs/app.log"; return 1; }
    if curl -fsS -o /dev/null -m 2 "http://localhost:$APP_PORT/actuator/health" 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  warn "the app did not answer on $APP_PORT in time - see logs/app.log"
  return 1
}

# Something on the port that is not ours means an earlier run outlived its
# pidfile. Starting a second JVM on top of it would fail to bind and then look
# healthy, because the old one answers.
port_taken_by_stranger() {
  app_pid >/dev/null && return 1
  curl -fsS -o /dev/null -m 2 "http://localhost:$APP_PORT/actuator/health" 2>/dev/null
}

app_start() {
  local mode=update

  while [ $# -gt 0 ]; do
    case "$1" in
      --no-update) mode=asis;  shift ;;
      --build)     mode=build; shift ;;
      --jar)       [ -n "${2:-}" ] || { warn "--jar needs a path"; return 1; }
                   mode=jar; APP_JAR="$2"; shift 2 ;;
      *)           warn "usage: orchstack.sh app start [--no-update|--build|--jar PATH]"; return 1 ;;
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
  if port_taken_by_stranger; then
    warn "something is already answering on port $APP_PORT and it is not ours"
    warn "an app from an earlier run outlived its pidfile - kill it, then start again:"
    warn "  lsof -ti tcp:$APP_PORT | xargs kill"
    return 1
  fi

  case "$mode" in
    update)
      # The whole update story: one check, right here, before the app starts.
      update_from_main
      if [ ! -f "$APP_JAR" ]; then
        warn "$APP_JAR does not exist and nothing could be downloaded"
        warn "build this working copy instead: orchstack.sh app start --build"
        return 1
      fi ;;
    asis)
      [ -f "$APP_JAR" ] || { warn "$APP_JAR does not exist - drop --no-update to fetch it"; return 1; }
      log "not checking $ORCH_BRANCH - using the jar already here" ;;
    build)
      build_app || return 1
      APP_JAR="$BUILT_APP_JAR" ;;
    jar)
      [ -f "$APP_JAR" ] || { warn "$APP_JAR does not exist - nothing to run"; return 1; } ;;
  esac

  resolve_java || return 1
  launch_app

  if wait_for_health 90; then
    log "the app is up - http://localhost:$APP_PORT/actuator/health"
    # A downloaded build counts as deployed only once it has answered. Marking it
    # earlier is how the old WSL stand ended up pinned to a revision that never
    # came up.
    if [ -n "$UPDATED_SHA" ]; then
      echo "$UPDATED_SHA" >"$STATE_FILE"
      log "deployed $(short_sha "$UPDATED_SHA")"
    fi
    return 0
  fi

  # Only a fresh download is rolled back: anything else is the jar the operator
  # asked for, and swapping it out from under them would be worse than failing.
  if [ -z "$UPDATED_SHA" ] || [ ! -f "$PREVIOUS_JAR" ]; then
    warn "the app did not come up - see logs/app.log"
    return 1
  fi

  warn "$(short_sha "$UPDATED_SHA") did not come up - rolling back to the previous jar"
  app_stop
  mv -f "$CURRENT_JAR" "$DEPLOY_DIR/failed.jar"
  cp -f "$PREVIOUS_JAR" "$CURRENT_JAR"
  APP_JAR="$CURRENT_JAR"
  UPDATED_SHA=""
  launch_app
  if wait_for_health 90; then
    log "rolled back - the previous build is up again; the bad one is kept as failed.jar"
  else
    warn "the previous jar did not come up either - the stand needs a look (logs/app.log)"
  fi
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
    *)       warn "usage: orchstack.sh app [start [--no-update|--build|--jar PATH]|stop|status|restart]"; return 1 ;;
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
