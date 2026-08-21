#!/usr/bin/env bash
# =============================================================================
# Poll origin/main once; if it advanced, fast-forward and deliver to staging.
# Designed to be fired by a systemd timer (ops/systemd/staging-deliver.timer).
# Idempotent: no-op when already up to date.
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
BRANCH="${STAGING_BRANCH:-main}"

git fetch origin "$BRANCH" --quiet
local_sha="$(git rev-parse "$BRANCH" 2>/dev/null || echo none)"
remote_sha="$(git rev-parse "origin/$BRANCH")"

if [ "$local_sha" = "$remote_sha" ]; then
  echo "[$(date -Is)] staging-watch: up to date ($remote_sha)"
  exit 0
fi

echo "[$(date -Is)] staging-watch: origin/$BRANCH advanced $local_sha -> $remote_sha — delivering"
git checkout "$BRANCH" --quiet
git merge --ff-only "origin/$BRANCH" --quiet
exec ./scripts/deliver-staging.sh
