#!/usr/bin/env bash
# Install the staging systemd timers (requires sudo):
#   staging-up.timer      — bring the full stack up ~5 min after boot (OnBootSec)
#   staging-deliver.timer — poll origin/main every 5 min, deliver on new commits
# Repo path / user / docker path are computed at runtime, so no host path is
# hardcoded in the committed unit files.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
USER_NAME="$(whoami)"
DOCKER_BIN="$(command -v docker || echo /usr/bin/docker)"
UNITS="staging-up.service staging-up.timer staging-deliver.service staging-deliver.timer"

tmp="$(mktemp -d)"
for u in $UNITS; do
  sed -e "s#__REPO_DIR__#${ROOT}#g" \
      -e "s#__USER__#${USER_NAME}#g" \
      -e "s#__DOCKER__#${DOCKER_BIN}#g" \
      "$ROOT/ops/systemd/$u" > "$tmp/$u"
done

echo "Installing staging systemd units (sudo) for repo: $ROOT"
sudo cp "$tmp"/*.service "$tmp"/*.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now staging-up.timer staging-deliver.timer

echo "--- timers ---"
systemctl list-timers staging-up.timer staging-deliver.timer --no-pager 2>/dev/null | head -5 || true
echo "Done."
echo "  boot-up (~5 min after boot):  journalctl -u staging-up.service -f"
echo "  deliver (every 5 min):        journalctl -u staging-deliver.service -f"
echo "  bring up NOW without waiting:  sudo systemctl start staging-up.service"
