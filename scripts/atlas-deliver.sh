#!/usr/bin/env bash
# =============================================================================
# atlas-deliver.sh - build-once / deploy-artifact CD for the atlas stand (macOS,
# Apple Silicon). CI builds the jar once per merge to main; this script only
# fetches it and runs it. Nothing is compiled here.
#
#   scripts/atlas-deliver.sh [--force]
#
# What it does, in order:
#   1. find the newest successful CI run on main and read its head SHA
#   2. stop if that SHA is already deployed (state file), so the poll is a no-op
#   3. download the run's `app-jar-<sha>` artifact
#   4. rotate it in as current.jar (the jar it replaces becomes previous.jar)
#   5. restart the app from current.jar and gate on HTTP 200 from /actuator/health
#   6. record the SHA **only after** that gate passed; on failure roll back to
#      previous.jar and exit non-zero
#
# Step 6 is the point of the whole script. Its predecessor on the WSL stand
# (scripts/staging-watch.sh) advanced its marker before the deploy was proven,
# so one broken build made every later poll a no-op and the stand sat on a dead
# revision believing it was current.
#
# Layout (outside the repo, so a `git clean` cannot wipe a running deployment):
#   ~/.orch-deploy/current.jar    what the app runs now
#   ~/.orch-deploy/previous.jar   last known-good jar, the rollback target
#   ~/.orch-deploy/failed.jar     the jar of the last failed deploy, kept to debug
#   ~/.orch-deploy/state          SHA of the last deploy that passed the gate
#   ~/.orch-deploy/last-failed    SHA of the last deploy that failed the gate
#
# Environment:
#   ORCH_DEPLOY_DIR      deployment directory        (default ~/.orch-deploy)
#   ORCH_HEALTH_TIMEOUT  health gate budget, seconds (default 60)
#   ORCH_APP_PORT        app port                    (default 8099)
#   ORCH_REPO            owner/repo for gh           (default: from git remote)
#   ORCH_BRANCH          branch to follow            (default main)
#   ORCH_ORCHSTACK       path to orchstack.sh        (default: in this repo)
#
# Exit codes:
#   0  deployed, already up to date, or the stand is down (nothing to do)
#   1  missing tooling, no gh auth, or no successful CI run to deploy
#   2  health gate failed, rolled back to previous.jar
#   3  health gate failed and there was no previous jar - the app is left stopped
#   4  this SHA already failed the gate before; re-run with --force to retry it
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEPLOY_DIR="${ORCH_DEPLOY_DIR:-$HOME/.orch-deploy}"
CURRENT_JAR="$DEPLOY_DIR/current.jar"
PREVIOUS_JAR="$DEPLOY_DIR/previous.jar"
FAILED_JAR="$DEPLOY_DIR/failed.jar"
STATE_FILE="$DEPLOY_DIR/state"
FAILED_FILE="$DEPLOY_DIR/last-failed"

ORCHSTACK="${ORCH_ORCHSTACK:-$ROOT/docker/atlas/bin/orchstack.sh}"
BRANCH="${ORCH_BRANCH:-main}"
WORKFLOW="ci.yml"
APP_PORT="${ORCH_APP_PORT:-8099}"
HEALTH_URL="http://127.0.0.1:$APP_PORT/actuator/health"
HEALTH_TIMEOUT="${ORCH_HEALTH_TIMEOUT:-60}"
# The stand's Postgres. The app cannot boot without it, so an absent container
# means the stand is simply not up rather than that the delivery failed.
PG_CONTAINER="${ORCH_PG_CONTAINER:-tg-orch-postgres}"

FORCE=false

say() { printf '[%s] atlas-deliver: %s\n' "$(date +%Y-%m-%dT%H:%M:%S%z)" "$*"; }
die() { say "ERROR: $*"; exit "${2:-1}"; }

usage() {
  sed -n '2,/^# ===/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --force)    FORCE=true; shift ;;
    -h|--help)  usage 0 ;;
    *)          say "unknown argument: $1"; usage 1 ;;
  esac
done

# --- preflight ---------------------------------------------------------------

command -v gh   >/dev/null 2>&1 || die "the gh CLI is not installed - brew install gh"
command -v curl >/dev/null 2>&1 || die "curl is not installed"
[ -x "$ORCHSTACK" ] || die "$ORCHSTACK is missing or not executable"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated - run 'gh auth login'"

# The stand being down is a normal state on a laptop, not a delivery failure:
# report it and leave the exit code clean so an unattended timer stays quiet.
if ! docker info >/dev/null 2>&1; then
  say "the Docker engine is not running - the stand is down, nothing to deliver"
  exit 0
fi
if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  say "$PG_CONTAINER is not running - the stand is down (run 'orchstack.sh up'), nothing to deliver"
  exit 0
fi

REPO="${ORCH_REPO:-}"
if [ -z "$REPO" ]; then
  REPO="$(cd "$ROOT" && gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)"
fi
[ -n "$REPO" ] || die "cannot tell which GitHub repository to use - set ORCH_REPO=owner/repo"

mkdir -p "$DEPLOY_DIR"

# --- what does CI have for us ------------------------------------------------

# `gh --jq` uses the jq built into gh, so no external jq is needed on the Mac.
run_line="$(gh run list -R "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
              --status success --limit 1 \
              --json databaseId,headSha --jq '.[] | "\(.databaseId) \(.headSha)"' || true)"
[ -n "$run_line" ] || die "no successful '$WORKFLOW' run on $BRANCH in $REPO to deploy"

run_id="${run_line%% *}"
sha="${run_line##* }"
short_sha="$(echo "$sha" | cut -c1-12)"

deployed_sha="$(cat "$STATE_FILE" 2>/dev/null || true)"
if [ "$sha" = "$deployed_sha" ] && [ "$FORCE" = false ]; then
  say "up to date ($short_sha) - nothing to do"
  exit 0
fi

failed_sha="$(cat "$FAILED_FILE" 2>/dev/null || true)"
if [ "$sha" = "$failed_sha" ] && [ "$FORCE" = false ]; then
  # Without this an unattended timer would restart the stand onto the same broken
  # jar every 5 minutes for as long as that revision stays the newest green run.
  say "$short_sha already failed the health gate here - not retrying (use --force)"
  exit 4
fi

say "deploying $short_sha (CI run $run_id) over ${deployed_sha:-nothing}"

# --- download ----------------------------------------------------------------

tmp_dir="$(mktemp -d "$DEPLOY_DIR/download.XXXXXX")"
cleanup() { rm -rf "$tmp_dir"; }
trap cleanup EXIT

if ! gh run download "$run_id" -R "$REPO" -n "app-jar-$sha" -D "$tmp_dir" >/dev/null 2>&1; then
  # Older runs, or a renamed artifact: fall back to whatever the run published.
  gh run download "$run_id" -R "$REPO" -D "$tmp_dir" >/dev/null 2>&1 ||
    die "could not download the artifact of run $run_id"
fi

new_jar="$(find "$tmp_dir" -type f -name '*.jar' | head -1)"
[ -n "$new_jar" ] || die "run $run_id published no jar"

# The whole reason CI builds a separate deploy jar: TDLight natives are baked in
# at build time, and a linux-classifier jar cannot start natively on this Mac.
# Refuse it here rather than discovering it as a boot failure at the health gate.
if command -v unzip >/dev/null 2>&1; then
  jar_listing="$(unzip -l "$new_jar" 2>/dev/null || true)"
  echo "$jar_listing" | grep -q 'tdlight-natives-.*-macos_arm64\.jar' ||
    die "the downloaded jar has no macos_arm64 TDLight natives - it cannot run on this stand"
fi

# --- rotate ------------------------------------------------------------------

rotated=false
if [ -f "$CURRENT_JAR" ]; then
  mv -f "$CURRENT_JAR" "$PREVIOUS_JAR"
  rotated=true
fi
mv -f "$new_jar" "$CURRENT_JAR"
cleanup
trap - EXIT
say "installed $(basename "$CURRENT_JAR") ($(wc -c <"$CURRENT_JAR" | tr -d ' ') bytes)"

# --- restart + health gate ---------------------------------------------------

# orchstack's own start command waits for health on a 180s budget, which would
# sit past this script's gate. Run it in the background and gate here instead:
# the JVM it launches is detached with its own pidfile, so dropping the starter
# leaves the app itself running and `orchstack.sh app stop` still owns it.
start_app() {
  local jar="$1" budget="$2" starter_pid deadline code
  "$ORCHSTACK" app start --jar "$jar" &
  starter_pid=$!

  deadline=$(( $(date +%s) + budget ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$HEALTH_URL" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
      kill "$starter_pid" 2>/dev/null || true
      wait "$starter_pid" 2>/dev/null || true
      return 0
    fi
    sleep 2
  done
  kill "$starter_pid" 2>/dev/null || true
  wait "$starter_pid" 2>/dev/null || true
  return 1
}

"$ORCHSTACK" app stop

if start_app "$CURRENT_JAR" "$HEALTH_TIMEOUT"; then
  # Only now is the revision considered deployed.
  echo "$sha" >"$STATE_FILE"
  rm -f "$FAILED_FILE"
  say "OK - $short_sha is live, health 200 on $HEALTH_URL"
  exit 0
fi

# --- rollback ----------------------------------------------------------------

say "the health gate failed within ${HEALTH_TIMEOUT}s - rolling back"
echo "$sha" >"$FAILED_FILE"
"$ORCHSTACK" app stop
mv -f "$CURRENT_JAR" "$FAILED_JAR" 2>/dev/null || true

if [ "$rotated" = false ] || [ ! -f "$PREVIOUS_JAR" ]; then
  say "no previous jar to roll back to - the app is left stopped ($short_sha kept as failed.jar)"
  exit 3
fi

# Copied, not moved: previous.jar stays the known-good rollback target for the
# next attempt as well.
cp -f "$PREVIOUS_JAR" "$CURRENT_JAR"
# Getting the stand back up matters more than the deploy budget, and a deliberately
# tiny ORCH_HEALTH_TIMEOUT (the way the rollback drill is triggered) must not make
# the recovery look broken as well.
if start_app "$CURRENT_JAR" "$(( HEALTH_TIMEOUT > 120 ? HEALTH_TIMEOUT : 120 ))"; then
  say "rolled back to ${deployed_sha:-the previous jar} - it is healthy again; $short_sha kept as failed.jar"
else
  say "ALSO FAILED to bring the previous jar back up - the stand needs a look (logs/app.log)"
fi
exit 2
