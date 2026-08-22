#!/usr/bin/env bash
# =============================================================================
# install-deploy-agent.sh - install (or remove) the LaunchAgent that polls CI and
# deploys new builds to this stand. macOS counterpart of the WSL stand's
# ops/install-staging-timer.sh.
#
#   docker/atlas/bin/install-deploy-agent.sh                 install, hourly, main
#   docker/atlas/bin/install-deploy-agent.sh --interval 600  poll every 10 minutes
#   docker/atlas/bin/install-deploy-agent.sh --branch dev    follow dev instead
#   docker/atlas/bin/install-deploy-agent.sh --status        is it loaded?
#   docker/atlas/bin/install-deploy-agent.sh --uninstall     unload and delete
#
# It renders docker/atlas/com.chat-orchestrator.deploy.plist.example into
# ~/Library/LaunchAgents/, so the template stays the single source of truth for
# what the agent does. Re-running it is how you change the interval or branch:
# the old agent is unloaded first, so installs are idempotent.
#
# The agent runs scripts/orch-deploy.sh out of THIS working copy. Move the
# repository and you have to run this again.
# =============================================================================
set -euo pipefail

LABEL="com.chat-orchestrator.deploy"
STACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$STACK_DIR/../.." && pwd)"
TEMPLATE="$STACK_DIR/$LABEL.plist.example"
AGENT_DIR="$HOME/Library/LaunchAgents"
PLIST="$AGENT_DIR/$LABEL.plist"
DEPLOY_DIR="${ORCH_DEPLOY_DIR:-$HOME/.orch-deploy}"

INTERVAL=3600
BRANCH=main
ACTION=install

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { warn "$*"; exit 1; }

usage() { sed -n '2,/^# ===/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --interval)  [ -n "${2:-}" ] || die "--interval needs seconds"; INTERVAL="$2"; shift 2 ;;
    --branch)    [ -n "${2:-}" ] || die "--branch needs a name";    BRANCH="$2";   shift 2 ;;
    --uninstall) ACTION=uninstall; shift ;;
    --status)    ACTION=status; shift ;;
    -h|--help)   usage 0 ;;
    *)           warn "unknown argument: $1"; usage 1 ;;
  esac
done

[ "$(uname -s)" = Darwin ] || die "this installs a launchd agent - macOS only"
case "$INTERVAL" in
  ''|*[!0-9]*) die "--interval must be a whole number of seconds" ;;
esac
# launchd will happily hammer a job every few seconds; the poll is an API call
# against GitHub, so keep it civilised.
[ "$INTERVAL" -ge 60 ] || die "--interval below 60s is not worth the API calls"

domain="gui/$(id -u)"

agent_loaded() { launchctl print "$domain/$LABEL" >/dev/null 2>&1; }

unload_agent() {
  if agent_loaded; then
    log "unloading the running agent"
    launchctl bootout "$domain/$LABEL" 2>/dev/null || true
  fi
}

case "$ACTION" in
  status)
    if agent_loaded; then
      log "$LABEL is loaded"
      launchctl print "$domain/$LABEL" | grep -E 'state|program|last exit' || true
      [ -f "$DEPLOY_DIR/state" ] && log "deployed SHA: $(cat "$DEPLOY_DIR/state")"
      log "log: $DEPLOY_DIR/deploy.log"
    else
      log "$LABEL is not loaded"
    fi
    exit 0 ;;
  uninstall)
    unload_agent
    rm -f "$PLIST"
    log "removed - $DEPLOY_DIR is left alone (jars, state and log are still there)"
    exit 0 ;;
esac

# --- install -----------------------------------------------------------------

[ -f "$TEMPLATE" ] || die "$TEMPLATE is missing"
[ -x "$REPO_DIR/scripts/orch-deploy.sh" ] || die "$REPO_DIR/scripts/orch-deploy.sh is missing or not executable"
if ! command -v gh >/dev/null 2>&1; then
  warn "the gh CLI is not on PATH - the agent can only log errors until 'brew install gh' and 'gh auth login'"
elif ! gh auth status >/dev/null 2>&1; then
  warn "gh is not authenticated - run 'gh auth login' or the agent can only log errors"
fi

# The agent used to be called orch-deliver, under a com.example label. Its
# directory is unchanged, so the only leftovers to clear are the old launchd job,
# its plist and the log file under its old name.
LEGACY_LABEL="com.example.orch-deliver"
if launchctl print "$domain/$LEGACY_LABEL" >/dev/null 2>&1; then
  log "unloading the old $LEGACY_LABEL agent"
  launchctl bootout "$domain/$LEGACY_LABEL" 2>/dev/null || true
fi
if [ -f "$AGENT_DIR/$LEGACY_LABEL.plist" ]; then
  rm -f "$AGENT_DIR/$LEGACY_LABEL.plist"
  log "removed the old agent plist"
fi
if [ -f "$DEPLOY_DIR/deliver.log" ] && [ ! -f "$DEPLOY_DIR/deploy.log" ]; then
  mv "$DEPLOY_DIR/deliver.log" "$DEPLOY_DIR/deploy.log"
fi

mkdir -p "$AGENT_DIR" "$DEPLOY_DIR"
unload_agent

sed -e "s|__REPO_DIR__|$REPO_DIR|g" -e "s|__HOME__|$HOME|g" "$TEMPLATE" >"$PLIST"
# plutil edits the values by key, so the template keeps real defaults and stays
# lint-clean instead of carrying placeholders that are not integers.
plutil -replace StartInterval -integer "$INTERVAL" "$PLIST"
plutil -replace EnvironmentVariables.ORCH_BRANCH -string "$BRANCH" "$PLIST"
plutil -lint "$PLIST" >/dev/null || die "the rendered plist is not valid: $PLIST"

launchctl bootstrap "$domain" "$PLIST"
log "installed $LABEL - every ${INTERVAL}s, following '$BRANCH', from $REPO_DIR"
cat <<EOF

  run one poll now   launchctl kickstart -p $domain/$LABEL
  watch it           tail -f $DEPLOY_DIR/deploy.log
  is it loaded       $0 --status
  remove             $0 --uninstall

The first poll runs within ${INTERVAL}s (not at load time, on purpose). Nothing
is deployed unless the stand is up AND the app is running - the agent replaces a
running app, it never starts one. Bring the stand up with
'docker/atlas/bin/orchstack.sh up' and 'orchstack.sh app start'.
EOF
