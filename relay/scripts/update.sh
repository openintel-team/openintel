#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
require_root_systemd
system_paths
[[ -x /opt/openintel-node/current/bin/node ]] && NODE_BIN=${NODE_BIN:-/opt/openintel-node/current/bin/node}
require_node
exec 9>/run/lock/openintel-relay-maintenance.lock
flock -n 9 || die "Another install/update is in progress."
source_dir=$(cd "$SCRIPT_DIR/.." && pwd)
rollback=""
keep_backups=5
while (($#)); do
  case "$1" in
    --source) [[ $# -ge 2 ]] || die "--source needs a directory"; source_dir=$(realpath "$2"); shift 2 ;;
    --rollback) [[ $# -ge 2 ]] || die "--rollback needs a backup directory"; rollback=$(realpath "$2"); shift 2 ;;
    --keep-backups) [[ $# -ge 2 && $2 =~ ^[0-9]{1,2}$ && $2 -ge 2 ]] || die "--keep-backups must be 2–99"; keep_backups=$2; shift 2 ;;
    *) die "Usage: update.sh [--source relay-directory | --rollback /var/backups/openintel-relay/BACKUP] [--keep-backups 5]" ;;
  esac
done
current=/opt/openintel-relay/current
[[ -L $current ]] || die "No managed installation; run setup.sh --systemd first."
old=$(readlink -f "$current")
[[ $old == /opt/openintel-relay/releases/* && -f $old/server.js ]] || die "Invalid active release."
backup="/var/backups/openintel-relay/$(date -u +%Y%m%dT%H%M%SZ)-$$"
install -d -m 0700 /var/backups/openintel-relay "$backup"
restore() {
  local from=$1 target
  read -r target < "$from/release"
  [[ $target == /opt/openintel-relay/releases/* && -f $target/server.js && -d $from/data && -d $from/config ]] || die "Invalid rollback bundle"
  # Fixed managed paths; keep failed state in the new backup for diagnosis.
  [[ ! -d /var/lib/openintel-relay ]] || mv /var/lib/openintel-relay "$backup/failed-data"
  [[ ! -d /etc/openintel-relay ]] || mv /etc/openintel-relay "$backup/failed-config"
  cp -a "$from/data" /var/lib/openintel-relay
  cp -a "$from/config" /etc/openintel-relay
  ln -sfn "$target" "$current.next"
  mv -Tf "$current.next" "$current"
}
if [[ -n $rollback ]]; then
  [[ $rollback == /var/backups/openintel-relay/* && -f $rollback/release && -d $rollback/data && -d $rollback/config ]] || die "Not a rollback bundle"
else
  release="/opt/openintel-relay/releases/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  stage_release "$source_dir" "$release"
  "$NODE_BIN" "$release/src/cli.js" check-config
  if git -C "$source_dir" rev-parse HEAD > "$release/SOURCE_REVISION" 2>/dev/null; then
    git -C "$source_dir" diff --quiet || printf '%s\n' 'Includes working-tree changes' >> "$release/SOURCE_REVISION"
  fi
fi
stopped=false
saved=false
switched=false
on_failure() {
  local code=$?
  ((code != 0)) || code=1
  trap - ERR INT TERM
  if $stopped; then
    if $saved && $switched; then
      systemctl stop openintel-relay || true
      # Avoid overwriting diagnosis from an explicitly requested rollback.
      [[ ! -d "$backup/failed-data" ]] || mv "$backup/failed-data" "$backup/earlier-data"
      [[ ! -d "$backup/failed-config" ]] || mv "$backup/failed-config" "$backup/earlier-config"
      restore "$backup"
    fi
    systemctl start openintel-relay || true
  fi
  printf 'Update failed; prior release restored where possible. Inspect %s and journalctl -u openintel-relay.\n' "$backup" >&2
  exit "${code:-1}"
}
trap on_failure ERR INT TERM
systemctl stop openintel-relay
stopped=true
cp -a /var/lib/openintel-relay "$backup/data"
cp -a /etc/openintel-relay "$backup/config"
printf '%s\n' "$old" > "$backup/release"
saved=true
switched=true
if [[ -n $rollback ]]; then
  restore "$rollback"
else
  ln -sfn "$release" "$current.next"
  mv -Tf "$current.next" "$current"
fi
systemctl start openintel-relay
wait_ready "$current"
stopped=false
trap - ERR INT TERM
touch "$backup/COMPLETE"
# Only prune completed bundles made by this updater. Failed attempts and
# operator-created backups are left for inspection.
mapfile -t completed < <(find /var/backups/openintel-relay -mindepth 2 -maxdepth 2 -type f -name COMPLETE -printf '%h\n' | sort -r)
for ((i=keep_backups; i<${#completed[@]}; i++)); do
  candidate=${completed[$i]}
  [[ $candidate != "$backup" && ${candidate##*/} =~ ^[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || continue
  rm -rf -- "$candidate"
done
printf 'Relay ready. Rollback bundle: %s\n' "$backup"
printf 'Retaining %s completed backup bundles. Failed attempts and old release directories require operator review.\n' "$keep_backups"
