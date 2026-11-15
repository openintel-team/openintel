#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
require_linux
require_node
local_paths
RELAY_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
"$NODE_BIN" "$RELAY_DIR/src/cli.js" check-config
[[ -d "$RELAY_DIR/node_modules" ]] || die "Dependencies missing; run setup.sh once first."
exec "$NODE_BIN" "$RELAY_DIR/server.js"
