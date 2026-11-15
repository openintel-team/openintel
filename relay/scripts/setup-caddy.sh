#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
require_root_systemd
system_paths
[[ -x /opt/openintel-node/current/bin/node ]] && NODE_BIN=${NODE_BIN:-/opt/openintel-node/current/bin/node}
require_node
[[ $# == 1 && $1 =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ && $1 == *.* ]] || die "Usage: setup-caddy.sh relay.example.net"
host=$1
command -v caddy >/dev/null || die "Install Caddy using your distribution's package manager first."
getent ahosts "$host" >/dev/null || die "Hostname does not resolve. Point DNS at this host first."
exec 9>/run/lock/openintel-relay-maintenance.lock
flock -n 9 || die "Another install/update is in progress."
[[ -f /etc/caddy/Caddyfile ]] || die "Missing /etc/caddy/Caddyfile; complete Caddy package setup first."
app=/opt/openintel-relay/current
port=$("$NODE_BIN" -e 'console.log(require(process.argv[1]).loadConfig({config:process.env.OPENINTEL_CONFIG}).port)' "$app/src/config.js")
work=$(mktemp -d)
candidate="/etc/caddy/.Caddyfile-openintel-$$"
trap 'rm -rf -- "$work"; rm -f -- "$candidate"' EXIT
cp -a /etc/caddy/Caddyfile "$work/Caddyfile.original"
cp -a "$OPENINTEL_CONFIG" "$work/config.original"
site=/etc/caddy/openintel-relay.caddy
[[ ! -e $site ]] || die "$site already exists; edit it and run caddy validate instead of overwriting it."
printf '%s {\n    @relay path /openintel-relay\n    reverse_proxy @relay 127.0.0.1:%s\n    respond 404\n}\n' "$host" "$port" > "$work/site"
cat /etc/caddy/Caddyfile > "$candidate"
printf '\nimport %s\n' "$site" >> "$candidate"
install -m 0644 "$work/site" "$site"
cleanup_failure() {
  local code=$?
  ((code != 0)) || code=1
  trap - ERR INT TERM
  cp -a "$work/Caddyfile.original" /etc/caddy/Caddyfile
  cp -a "$work/config.original" "$OPENINTEL_CONFIG"
  rm -f "$site"
  systemctl reload caddy || true
  systemctl restart openintel-relay || true
  exit "${code:-1}"
}
trap cleanup_failure ERR INT TERM
caddy validate --adapter caddyfile --config "$candidate"
"$NODE_BIN" "$app/src/cli.js" proxy --secure-url "wss://$host/openintel-relay"
install -m 0644 "$candidate" /etc/caddy/Caddyfile
systemctl reload caddy
systemctl restart openintel-relay
wait_ready "$app"
trap - ERR INT TERM
printf 'Configured wss://%s/openintel-relay. Allow inbound 80/443; Caddy obtains the certificate automatically.\n' "$host"
printf '%s\n' "Admin and health endpoints are not publicly proxied. Existing Caddy sites were retained."
