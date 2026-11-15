#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
require_linux
RELAY_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
system=false
install_node=false
args=()
for arg in "$@"; do
  case "$arg" in --systemd) system=true ;; --install-node) install_node=true ;; *) args+=("$arg") ;; esac
done
if $system; then
  require_root_systemd
  exec 9>/run/lock/openintel-relay-maintenance.lock
  flock -n 9 || die "Another install/update is in progress."
  if $install_node; then
    bash "$SCRIPT_DIR/install-node.sh"
    NODE_BIN=/opt/openintel-node/current/bin/node
  elif [[ -x /opt/openintel-node/current/bin/node ]]; then
    NODE_BIN=${NODE_BIN:-/opt/openintel-node/current/bin/node}
  fi
  require_node
  [[ $NODE_BIN != /home/* && $NODE_BIN != /root/* && $NODE_BIN =~ ^/[a-zA-Z0-9_./+-]+$ ]] || die "Use a system-wide Node binary with a simple absolute path, not a private home installation."
  system_paths
  for arg in "${args[@]}"; do
    [[ $arg != --config && $arg != --data-dir ]] || die "System mode uses fixed config/data paths."
  done
  if [[ -L /opt/openintel-relay/current ]]; then
    "$NODE_BIN" /opt/openintel-relay/current/src/cli.js check-config
    printf '%s\n' "Existing installation preserved. Use scripts/update.sh to update it."
    exit 0
  fi
  [[ ! -e /opt/openintel-relay/current ]] || die "The managed current path already exists and is not a release symlink."
  [[ ! -e /etc/systemd/system/openintel-relay.service ]] || die "An unmanaged service unit already exists. Stop/back it up and remove that unit before managed installation."
  id openintel-relay >/dev/null 2>&1 || useradd --system --user-group --home-dir /var/lib/openintel-relay --shell /usr/sbin/nologin openintel-relay
  install -d -m 0750 -o root -g openintel-relay /etc/openintel-relay
  install -d -m 0700 -o openintel-relay -g openintel-relay /var/lib/openintel-relay
  install -d -m 0755 /opt/openintel-relay /opt/openintel-relay/releases
  "$NODE_BIN" "$RELAY_DIR/src/cli.js" init "${args[@]}"
  chown root:openintel-relay /etc/openintel-relay/config.json
  chmod 0640 /etc/openintel-relay/config.json
  chown -R openintel-relay:openintel-relay /var/lib/openintel-relay
  release="/opt/openintel-relay/releases/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  stage_release "$RELAY_DIR" "$release"
  runuser -u openintel-relay -- env OPENINTEL_CONFIG="$OPENINTEL_CONFIG" OPENINTEL_DATA_DIR="$OPENINTEL_DATA_DIR" \
    "$NODE_BIN" "$release/src/cli.js" check-config
  ln -s "$release" /opt/openintel-relay/current
  sed "s|@NODE@|$NODE_BIN|g" "$RELAY_DIR/deploy/openintel-relay.service" > /etc/systemd/system/openintel-relay.service
  install -d -m 0755 /etc/logrotate.d
  install -m 0644 "$RELAY_DIR/deploy/logrotate.conf" /etc/logrotate.d/openintel-relay
  systemctl daemon-reload
  systemctl enable --now openintel-relay
  if ! wait_ready "$release"; then
    systemctl stop openintel-relay
    die "Service did not become ready. Configuration/data retained; inspect journalctl -u openintel-relay."
  fi
  printf '%s\n' "Relay installed and ready. Logs: journalctl -u openintel-relay -f"
else
  $install_node && die "--install-node requires --systemd; otherwise install Node 24 as your user."
  [[ $EUID != 0 ]] || die "Use foreground setup as a normal user, or choose --systemd."
  require_node
  local_paths
  "$NODE_BIN" "$RELAY_DIR/src/cli.js" init "${args[@]}"
  npm --prefix "$RELAY_DIR" ci --omit=dev --ignore-scripts --no-audit --no-fund
  printf 'Setup complete. Start with: %s/start.sh\n' "$SCRIPT_DIR"
  printf '%s\n' "If you supplied --config/--data-dir, export OPENINTEL_CONFIG/OPENINTEL_DATA_DIR to those paths before starting."
fi
