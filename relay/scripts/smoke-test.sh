#!/usr/bin/env bash
# Unprivileged foreground integration check for a disposable Linux checkout.
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
RELAY_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
work=$(mktemp -d)
pid=""
cleanup() {
  if [[ -n $pid ]]; then kill -TERM "$pid" 2>/dev/null || true; wait "$pid" || true; fi
  rm -rf -- "$work"
}
trap cleanup EXIT
export OPENINTEL_CONFIG="$work/config/config.json"
export OPENINTEL_DATA_DIR="$work/state"
node "$RELAY_DIR/src/cli.js" init --minecraft-server play.example.net --admin-name SmokeAdmin --port 18765 --noninteractive
before=$(sha256sum "$OPENINTEL_DATA_DIR/users.json")
node "$RELAY_DIR/src/cli.js" init --noninteractive
[[ $(sha256sum "$OPENINTEL_DATA_DIR/users.json") == "$before" ]]
cd /tmp
bash "$SCRIPT_DIR/start.sh" >"$work/server.log" 2>&1 &
pid=$!
ready=false
for _ in {1..30}; do
  if node "$RELAY_DIR/src/cli.js" doctor > /dev/null 2>&1; then ready=true; break; fi
  sleep .2
done
if ! $ready; then cat "$work/server.log"; exit 1; fi
if node "$RELAY_DIR/src/cli.js" invite --name Another --token-file "$work/forbidden.txt" 2>/dev/null; then
  echo "Offline writer was incorrectly allowed while running" >&2; exit 1
fi
kill -TERM "$pid"
wait "$pid"
pid=""
[[ ! -f "$OPENINTEL_DATA_DIR/relay.lock" ]]
node "$RELAY_DIR/src/cli.js" invite --name Another --token-file "$work/invitation.txt"
printf '%s\n' "Foreground initialization, rerun, readiness, single-writer lock and SIGTERM smoke test passed."
