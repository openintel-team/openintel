#!/usr/bin/env bash
# Shared by the Linux installer, updater, and foreground entry point.
set -euo pipefail
die() { printf '%s\n' "Error: $*" >&2; exit 1; }
require_linux() { [[ $(uname -s) == Linux ]] || die "This script targets Linux."; }
require_node() {
  NODE_BIN=${NODE_BIN:-$(command -v node || true)}
  [[ -x "$NODE_BIN" ]] || die "Node 24 LTS is required. Install it or use setup.sh --systemd --install-node."
  [[ $("$NODE_BIN" -p 'process.versions.node.split(".")[0]') == 24 ]] || die "Expected Node 24 LTS."
  NODE_BIN=$(readlink -f "$NODE_BIN")
  PATH="$(dirname "$NODE_BIN"):$PATH"
  export PATH
  command -v npm >/dev/null || die "npm is required."
}
local_paths() {
  if [[ -n ${OPENINTEL_RELAY_HOME:-} ]]; then
    export OPENINTEL_CONFIG=${OPENINTEL_CONFIG:-"$OPENINTEL_RELAY_HOME/config.json"}
    export OPENINTEL_DATA_DIR=${OPENINTEL_DATA_DIR:-"$OPENINTEL_RELAY_HOME"}
  else
    export OPENINTEL_CONFIG=${OPENINTEL_CONFIG:-"${XDG_CONFIG_HOME:-$HOME/.config}/openintel-relay/config.json"}
    export OPENINTEL_DATA_DIR=${OPENINTEL_DATA_DIR:-"${XDG_STATE_HOME:-$HOME/.local/state}/openintel-relay"}
  fi
}
system_paths() {
  export OPENINTEL_CONFIG=/etc/openintel-relay/config.json
  export OPENINTEL_DATA_DIR=/var/lib/openintel-relay
}
require_root_systemd() {
  require_linux
  [[ $EUID == 0 ]] || die "Run this system installation command with sudo."
  [[ -d /run/systemd/system ]] || die "A running systemd host is required; use foreground mode otherwise."
  command -v runuser >/dev/null || die "runuser (util-linux) is required."
}
stage_release() {
  local source=$1 stage=$2
  # Only application files are copied. Never copy local JSON, token files,
  # node_modules, diagnostic dumps, or other contents of a developer checkout.
  install -d -m 0755 "$stage"
  local file
  for file in server.js authentication.js visibility.js transport.js package.json package-lock.json; do
    install -m 0644 "$source/$file" "$stage/$file"
  done
  cp -R "$source/src" "$source/scripts" "$source/deploy" "$stage/"
  [[ ! -f "$source/README.md" ]] || install -m 0644 "$source/README.md" "$stage/README.md"
  chown -R openintel-relay:openintel-relay "$stage"
  runuser -u openintel-relay -- env PATH="$PATH" npm --prefix "$stage" --cache "$stage/.npm-cache" ci --omit=dev --ignore-scripts --no-audit --no-fund
  rm -rf -- "$stage/.npm-cache"
  chown -R root:root "$stage"
  chmod -R go-w "$stage"
}
wait_ready() {
  local app=$1
  for _ in {1..20}; do
    if "$NODE_BIN" "$app/src/cli.js" doctor >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}
