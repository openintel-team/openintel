# OpenIntel relay on Linux

The relay is one Node.js service. Discord is optional. You can initialize it,
invite a player, and run it without configuring a Discord bot or webhook.

Supported runtime: Node **24 LTS** and its bundled npm. The installation scripts
target Ubuntu 24.04 and Debian 12/13, with systemd for managed installations.
Foreground operation also works on Linux without systemd.

## Quick start as your normal user

From the repository root:

```bash
./relay/scripts/setup.sh
./relay/scripts/start.sh
```

Setup prompts for the Minecraft server address and the first admin's **Minecraft
account name**, installs locked dependencies, and initializes empty state. It
writes a random invitation token to a private file and prints that file's path,
not the token itself. Deliver that token to its owner, then remove the file.

The default listener is `127.0.0.1:8765`. For access from another machine, either
configure HTTPS/WSS below or explicitly choose a network bind with `--host`.

An unattended example:

```bash
./relay/scripts/setup.sh --noninteractive \
  --minecraft-server play.example.net --admin-name PlayerName
```

`--input /private/path/config.json` accepts an existing configuration template,
including optional Discord settings. No example users are installed. Repeating
setup preserves existing users, credentials, and configuration.

Default foreground locations:

- Configuration: `$XDG_CONFIG_HOME/openintel-relay/config.json`, or
  `~/.config/openintel-relay/config.json`.
- Data: `$XDG_STATE_HOME/openintel-relay`, or `~/.local/state/openintel-relay`.
- Initial token file: beside the configuration, `initial-token.txt`, mode 0600.

For custom paths, export `OPENINTEL_CONFIG` and `OPENINTEL_DATA_DIR` before **both**
setup and startup. The Node CLI also accepts `--config FILE --data-dir DIRECTORY`.
The scripts resolve application paths from their own location, so you can launch
them from another working directory.

## Install as a service

Have Node 24 available system-wide, then run:

```bash
sudo ./relay/scripts/setup.sh --systemd \
  --minecraft-server play.example.net --admin-name PlayerName
sudo systemctl status openintel-relay
sudo journalctl -u openintel-relay -f
```

If Node is not installed, explicitly opt into a private runtime installation:

```bash
sudo ./relay/scripts/setup.sh --systemd --install-node \
  --minecraft-server play.example.net --admin-name PlayerName
```

The runtime installer downloads the latest Node 24 archive from nodejs.org,
checks its SHA-256 against the official HTTPS manifest, and installs its exact
version under `/opt/openintel-node`. It does not replace distribution binaries.
It requires `curl`, `xz`, `tar`, and `sha256sum`. System installation also requires
systemd and `runuser`/`flock` from util-linux. Install `logrotate` to activate the
included audit rotation policy.

For an existing nonstandard runtime, use
`sudo env NODE_BIN=/system/path/to/node ./relay/scripts/setup.sh --systemd ...`.
Home-directory/nvm binaries are unsuitable because the service cannot read
private home directories.

Installation creates:

| Path | Purpose |
| --- | --- |
| `/opt/openintel-relay/releases/<id>` | Versioned application and dependencies, owned by root |
| `/opt/openintel-relay/current` | Active release |
| `/etc/openintel-relay/config.json` | Configuration, root-owned and service-readable |
| `/var/lib/openintel-relay` | Users, allegiances, War Table, audit, and writer lock |
| `/etc/systemd/system/openintel-relay.service` | Dedicated non-login `openintel-relay` account; start on boot |

Dependency installation runs as the service user in the staging directory,
with lifecycle scripts disabled. Runtime code is read-only to the service.
SIGTERM closes sockets, clears timers, aborts identity/webhook requests, and
disconnects Discord. systemd restarts crashes and clears a stale writer lock
only after verifying that the recorded process no longer exists.

Rerunning system setup validates the existing installation without replacing it.
Use the update command below to install new code. If initial service startup
fails, configuration/data remain available; inspect the journal and use
`systemctl start openintel-relay` after correcting the problem.

## Public encrypted endpoint

Install Caddy using its supported distribution package instructions. Point a
hostname at this server and allow incoming TCP 80 and 443. Then:

```bash
sudo ./relay/scripts/setup-caddy.sh relay.example.net
```

This adds a separate Caddy site, retaining existing site configuration. It
validates the combined configuration before applying it and configures the relay
to listen on loopback. Its public endpoint is:

```text
wss://relay.example.net/openintel-relay
```

Only `/openintel-relay` is proxied. Admin, health, and readiness endpoints are
not exposed by the template. No firewall rules are changed automatically.
The helper refuses to replace an existing OpenIntel site; subsequent edits can
be made in `/etc/caddy/openintel-relay.caddy` and validated/reloaded normally.
Certificate issuance depends on correct DNS and reachable ports.

Alternatively, adapt `deploy/Caddyfile.example` for your own reverse proxy and
set `secureWebSocketUrl` in the relay configuration. Keep `host` set to loopback
when proxying locally. The existing opt-in WS-to-WSS redirect is retained.

Behind a proxy, pending-login limits see the proxy's address. Size
`maxPendingPerAddress` for expected simultaneous logins (default 16); forwarded
address headers are not blindly trusted. Increasing the limit is an explicit
configuration choice.

## Configuration and diagnostics

The CLI does not print secrets:

```bash
node relay/src/cli.js check-config --config /path/config.json --data-dir /path/data
node relay/src/cli.js doctor --config /path/config.json --data-dir /path/data
node relay/src/cli.js doctor --network --config /path/config.json --data-dir /path/data
```

`doctor` verifies local configuration/state, permissions, and readiness.
`--network` also checks Mojang HTTPS connectivity and, when configured, a real
public WSS upgrade with certificate validation. A reachable Mojang endpoint does
not prove that a particular player's launcher session is valid.

For the system service:

```bash
sudo env OPENINTEL_CONFIG=/etc/openintel-relay/config.json \
  OPENINTEL_DATA_DIR=/var/lib/openintel-relay \
  node /opt/openintel-relay/current/src/cli.js doctor
```

Use `/opt/openintel-node/current/bin/node` if you installed the private runtime.

Path precedence is explicit CLI options, `OPENINTEL_CONFIG`/`OPENINTEL_DATA_DIR`,
legacy `OPENINTEL_RELAY_HOME`, then files beside `server.js`. This last fallback
preserves `node server.js` for legacy deployments. Shell scripts choose the
user-owned paths above unless overridden.
Legacy configs without `host` retain the old all-interface bind; new
initializations and proxy setup explicitly use loopback.

Missing optional state can initialize empty. Malformed/unreadable existing JSON
is an error and is not silently reset. The admin HTTP API remains disabled unless
`adminToken` is a strong nonplaceholder secret; `init --enable-admin-api` generates
one in the private config. Discord credentials and webhooks remain optional.
An optional integration failing does not prevent core readiness.

`GET /healthz` reports liveness; `GET /readyz` reports core readiness. These routes
contain no player data. Logs distinguish token rejection, wrong server, and
Minecraft identity-proof failures. `authentication_timeout` means the ownership
handshake did not complete; check the client version and Minecraft launcher
session as well as connectivity.

## Invite another player without Discord

Stop the service before using the offline invitation tool:

```bash
sudo systemctl stop openintel-relay
sudo -u openintel-relay env \
  OPENINTEL_CONFIG=/etc/openintel-relay/config.json \
  OPENINTEL_DATA_DIR=/var/lib/openintel-relay \
  node /opt/openintel-relay/current/src/cli.js invite \
  --name PlayerName --role member \
  --token-file /var/lib/openintel-relay/player-invitation.txt
sudo systemctl start openintel-relay
```

Deliver and delete the private token file. Existing invitations cannot be
overwritten through this command. For online management use the existing
authenticated HTTP API or Discord commands. Token rotations remain one-time
disclosures; the relay persists hashes only. Account UUID pins are preserved.

## Move an existing relay

Stop the legacy relay first, including an old version that does not create a
writer lock. Back up its directory. Validate the move without writing:

```bash
node relay/src/cli.js migrate --from /path/old-relay \
  --config /new/config.json --data-dir /new/data --dry-run
```

Remove `--dry-run` to copy into an empty destination. This preserves the source,
hashes legacy plaintext invitation tokens, and retains users/UUIDs, allegiances,
War Table strokes, and audit records. Existing destination files are not replaced.
When migrating into the managed layout, perform the copy before service setup;
setup then adopts existing configuration/state and applies service ownership.

An existing legacy installation at `/opt/openintel-relay` can coexist with the
new `releases/` layout, but stop its service and migrate its files to the new
config/data paths before running the managed installer. Keep the original
service unit and runtime files in the rollback backup.
The installer refuses to replace an unmanaged service unit: after backing it up
and stopping/disabling that old service, remove the old unit and reload systemd
before running the managed installer.

## Update and roll back

Select the intended Git commit/tag in a separate checkout, review/test it, then:

```bash
sudo ./relay/scripts/update.sh --source "$PWD/relay"
```

The updater stages code/dependencies before stopping the relay, takes a private
consistent backup of config/data and the active release pointer, switches releases,
and verifies readiness. Failure after switching restores the previous release
and snapshot. It does not pull arbitrary new code at service startup.

For a deliberate rollback, pass the printed backup path:

```bash
sudo ./relay/scripts/update.sh --rollback /var/backups/openintel-relay/BACKUP
```

Rollback restores **both code and the backed-up data**. Later edits/invitations
are retained in the rollback attempt's diagnostic backup, but are not part of
the restored active state. Do not mix old code with a newer incompatible schema:
relay versions from before token hashing cannot read hashed invitations.

Audit logs rotate daily with 14 retained rotations when logrotate is installed.
The updater retains the five latest completed state/config backup bundles;
`--keep-backups N` changes that limit (minimum two). Failed update bundles,
operator-created backups, and old code releases are left for explicit review.
Monitor `/var/backups/openintel-relay` and `/opt/openintel-relay/releases` disk usage.

## Troubleshooting and removal

- Configuration failure: run `check-config`; fix the named file rather than
  replacing the users database with examples.
- Port in use: stop the old listener or choose another port and update the proxy.
- Lock held: only one process may write a data directory. Stop it before offline
  maintenance. `cli.js unlock-stale` removes only a dead process's lock.
- Discord failure: verify bot credentials/intents/channel/role IDs; core relay
  access and local logs remain available.
- Shutdown: `systemctl stop openintel-relay` or Ctrl-C in foreground mode.
- Uninstall service: stop/disable it, remove its unit, and run
  `systemctl daemon-reload`. Keep `/etc/openintel-relay`, `/var/lib/openintel-relay`,
  and backups until you deliberately decide to remove stored data.

## Development and verification

`server.js` is a small compatibility entry point. `src/app.js` owns startup/
shutdown and composes users, sessions, positions, bridge, War Table, allegiances,
fanout, audit, and HTTP/WebSocket/Discord adapters. Importing modules does not
start the application. `createRelay(options)` accepts temporary paths, stores,
an identity verifier, webhook fetch, and a logger for isolated tests.

```bash
cd relay
npm ci
npm test
shellcheck -x -P SCRIPTDIR scripts/*.sh
bash scripts/smoke-test.sh
```

The Linux workflow covers Ubuntu x86_64/arm64, Debian foreground operation,
systemd installation, crash restart, update and rollback. Real reboot and public
certificate issuance must additionally be verified on a disposable host before
production rollout. No production server is contacted by the ordinary test suite.
