# Relay modularization and Linux setup task list

Status: implemented on `relay-modules-linux` and deployed with authorization on
2026-11-15 UTC. See `docs/relay-deployment-2026-11-15.md` for the exact deployed
artifact, preserved live-only behavior, validation, and rollback.

## Implementation and verification record

- [x] Baseline relay behavior tests passed before refactoring.
- [x] Replace the 1,636-line entry point with an application factory and cohesive
  modules for users/sessions, positions, fanout, bridge, War Table, allegiances,
  HTTP/WebSocket, Discord, audit, configuration, and storage.
- [x] Preserve the existing behavior suites through a shared module-aware fixture.
- [x] Add real local socket tests for lifecycle, isolation, failed startup,
  shutdown cancellation, and optional Discord failure.
- [x] Add initialization, one-time invitation files, validation, diagnostics,
  single-writer locking, stale-lock recovery, and explicit legacy migration.
- [x] Add foreground setup/start, verified optional Node installation, systemd,
  optional Caddy integration, update/rollback, and bounded successful backups.
- [x] Add Linux CI for Ubuntu x86_64/arm64 and Debian, including systemd crash
  restart and update/rollback checks, plus the operator guide.
- [x] Run all nine Node test programs locally on Node 24; ShellCheck 0.11.0,
  Bash syntax checks, and Git whitespace checks pass.
- [x] Compare maintained relay trees: `main`, `1.21.11`, `26.1.2`, and `26.2`
  share baseline tree `05c08f24824c33b60ad277c41bd7687ca883773f`.
- [ ] Run the new workflow on Linux; it has been added, not executed remotely.
- [x] Run the nine Node programs and foreground smoke test on the Linux host;
  verify a separate systemd instance starts, recovers from a crash, and stops.
- [x] Verify production readiness, real client re-authentication, Discord, and
  public WSS certificate validation from an external machine.
- [ ] Verify a real reboot and public certificate issuance on a disposable host.
- [ ] Review and publish the change, then propagate the same relay change to
  the three maintained version branches (their baseline relay code is identical).
- [x] Stage and approve a production rollout with its rollback bundle.

Implementation uses flat `src/` modules rather than a directory per domain;
the ownership boundaries below are retained. Successful backup retention is
configurable (default five); failed snapshots and old code releases require
operator review. Test fixtures retain their existing fake socket shapes, with
one shared CommonJS loader replacing source-text execution of the monolith.

The detailed checklist below is the original acceptance specification. The
record above distinguishes implemented/local-tested work from Linux release
gates. Subsequent authorized SSH deployment supplied Linux-host validation;
the full distribution/architecture CI matrix and reboot tests remain outstanding.

Baseline: `openintel-team/openintel`, commit `1e13c9c`.

## Outcome

An administrator can clone OpenIntel on a Linux host, run a guided setup command,
create the first invited player, and start the relay without manually assembling
JSON files. A systemd installation starts on boot, has useful diagnostics, and
keeps configuration and player data across upgrades.

Keep one Node.js process and the existing WebSocket/HTTP protocol. Split the
implementation into cohesive CommonJS modules; this work does not require
microservices, a database, a TypeScript migration, or a container orchestrator.

## Findings from the current implementation

- `relay/server.js` is 1,636 lines. It owns configuration, JSON persistence, users,
  token hashing, audit logging, webhooks, session registries, position aggregation,
  visibility-aware broadcasts, bridging, War Table state, HTTP administration,
  Discord commands, timers, and process startup.
- `authentication.js`, `visibility.js`, and `transport.js` already provide useful
  boundaries. Extend/reuse these rather than reimplementing their behavior.
- Importing `server.js` reads files, can migrate plaintext tokens, registers
  timers, connects Discord when configured, and opens a listening socket.
- `OPENINTEL_RELAY_HOME` already relocates files, but configuration and writable
  data share one directory. JSON reads other than configuration silently fall
  back on errors, including malformed existing files.
- JSON saves already use temporary files and rename. Retain that behavior and the
  restrictive permissions, particularly for users and audit records.
- Setup currently requires `npm ci`, copying three example files, and manual
  configuration. There is no tracked installer, startup script, systemd unit,
  health endpoint, or coordinated signal-driven shutdown.
- The listener does not specify a host. A proxy installation needs an explicit
  loopback bind rather than relying on firewall rules.
- The relay package advertises Node >=18; existing build CI uses Node 24. Use
  Node 24 LTS as the initial documented/tested Linux runtime and align metadata.
- Most integration tests execute the server source in a VM with fake sockets,
  files, clocks, and Discord clients. Preserve the scenarios while changing the
  fixtures to exercise exported modules and an application factory.

## Intended structure

```text
relay/
  server.js                    compatibility entry point; delegates to start
  src/
    app.js                     createRelay(dependencies), start(), stop()
    config.js                  validated configuration and resolved paths
    storage/
      json-store.js            atomic reads/writes, migration, write ownership
    users/
      service.js               invitations, hashing, roles, mutations
    sessions/
      registry.js              authenticated sockets, revocation, pending limits
    intel/
      positions.js             merging, deltas, throttling, stale cleanup
      allegiances.js           allegiance/focus operations
      wartable.js              stroke validation and persistence
      bridge.js                envelope validation, dedupe, hop limits
      fanout.js                recipient filtering and outbound messages
    http/
      routes.js                admin endpoints and health endpoints
    websocket/
      connection.js            handshake and connection lifecycle
      handlers.js              message dispatch into domain services
    discord/
      client.js                optional bot lifecycle
      commands.js              command parsing and responses
      snitch.js                incoming Discord snitch parsing
      panel.js                 admin panel/button presentation
    audit.js                   redacted audit records and recent activity
    webhooks.js                bounded outbound delivery
    cli.js                     init, check-config, doctor, offline bootstrap
  authentication.js            retain established identity-verification boundary
  visibility.js                retain shared access policy
  transport.js                 retain upgrade/TLS-redirect behavior
  scripts/
    setup.sh                   guided or unattended initial setup
    start.sh                   preflight, then exec Node; foreground operation
    update.sh                  staged update, backup, health check, rollback
  deploy/
    openintel-relay.service
    Caddyfile.example
  README.md                    Linux operator guide
```

These are responsibility boundaries, not a requirement to split every helper into
its own file. Domain modules receive stores, clock/network adapters, and explicit
callbacks through constructors/factories. They do not import mutable application
globals or create a second policy implementation for Discord versus HTTP.

## Target operator workflow

The following is the interface to implement; these commands do not exist yet.

```bash
git clone https://github.com/openintel-team/openintel.git
cd openintel

# Unprivileged setup: prompts, initializes local state, installs dependencies.
./relay/scripts/setup.sh
./relay/scripts/start.sh

# Alternative: guided system installation, enabled on boot.
sudo ./relay/scripts/setup.sh --systemd
sudo systemctl status openintel-relay
sudo journalctl -u openintel-relay -f
```

Initial supported distributions: Ubuntu 24.04 LTS and Debian 12/13 with systemd.
Other Linux hosts can use foreground mode with the documented Node runtime.
Support x86_64 and arm64 where the selected Node distribution is available.

The setup flow asks for Minecraft server name, bind/port, first invited Minecraft
account name, and optionally a public hostname and Discord integration. It shows
the final client relay URL and how to retrieve the initial token securely.
It must not require Discord to bootstrap or operate the core relay.

Production layout:

| Location | Contents / ownership |
| --- | --- |
| `/opt/openintel-relay/releases/<id>` | Immutable application/dependencies; service cannot modify |
| `/opt/openintel-relay/current` | Active release pointer |
| `/etc/openintel-relay` | Configuration/secrets; root-owned, service-readable where needed |
| `/var/lib/openintel-relay` | Users, allegiances, War Table, audit file; service-writable |
| journal | Process logs and startup/shutdown diagnostics |

Foreground mode uses a user-owned directory, configurable through the CLI.
Define exact precedence for explicit CLI paths, new path environment variables,
legacy `OPENINTEL_RELAY_HOME`, and legacy source-directory files.

## Ordered work items

### R01 — Establish a behavior baseline and refactor fixtures

- [ ] Run and record the current relay test suite on Node 24.
- [ ] Extract shared fake sockets, in-memory stores, clocks, and fake Discord/
  Mojang adapters from the repeated test fixtures.
- [ ] Inventory WebSocket message types, HTTP endpoints, stored JSON shapes,
  roles, and side effects exercised by existing tests.
- [ ] Preserve negative cases for quarantine, cuts, trials, token rotation,
  account UUID pinning, bridge spoofing, and pending-login limits.

Done when the baseline passes without external services, and reusable fixtures
can test both the old entry point and the new application factory.

### R02 — Separate construction from startup and shutdown

Depends on R01.

- [ ] Introduce `createRelay(dependencies)` with explicit `start()` and `stop()`.
- [ ] Keep `node server.js` and `npm start` working through a small entry point.
- [ ] Move embedded self-tests into the test suite; update `npm test`.
- [ ] Give the application ownership of every interval, authentication timeout,
  HTTP listener, WebSocket server, Discord client, and pending outbound request.
- [ ] Handle SIGINT/SIGTERM with bounded shutdown: reject new upgrades, close
  clients, cancel timers/requests, finish writes, disconnect Discord, then exit.
- [ ] Roll back partially started resources if startup fails.

Done when importing a module has no file writes/network/timer side effects,
two independently constructed relay instances share no mutable state, and tests
can start/stop the relay without leaked handles.

### R03 — Validate configuration and separate persistent data

Depends on R02.

- [ ] Resolve config/data paths centrally; retain documented legacy behavior.
- [ ] Validate required server name, bind address, port, timing/rate limits,
  optional Discord settings, webhook URLs, and secure WebSocket URL.
- [ ] Distinguish absent optional state from invalid or unreadable existing
  files. Fail with a useful filename/error rather than silently replacing data.
- [ ] Extract atomic JSON writes and token-sealing migration; keep token hashes
  and pinned UUIDs intact.
- [ ] Document single-writer ownership. Offline bootstrap/migration tools must
  refuse to modify state while the service is running.
- [ ] Implement a read-only `check-config` command with redacted diagnostics.

Done when missing required values fail before binding, corrupted users cannot
be silently reset, and both legacy and split-directory layouts pass tests.

### R04 — Extract users, sessions, and common administrative operations

Depends on R03.

- [ ] Move user lookup, roles, invitation creation, sealing, token rotation,
  enable/disable, quarantine, and session revocation behind explicit services.
- [ ] Keep the existing authentication challenge and Mojang verification module.
- [ ] Make HTTP and Discord adapters call the same domain operations, while
  preserving their distinct caller authorization rules and existing endpoints.
- [ ] Centralize audit events so one operation produces one consistent record.

Done when token recognition versus ownership verification stays distinct,
quarantine retains its current submission policy, rotations revoke sessions,
and existing authentication/security tests pass through the new services.

### R05 — Extract intel state and delivery

Depends on R04.

- [ ] Extract position merging, dirty tracking, delta/full-list encoding,
  freshness, rate limiting, near/far updates, and stale cleanup.
- [ ] Extract allegiance/focus, War Table state, and bridge envelope handling.
- [ ] Keep recipient filtering in one outbound delivery layer using `visibility`.
- [ ] Inject time and delivery adapters for deterministic tests.

Done when legacy full-list clients and delta clients see equivalent authorized
state, cuts/trials never gain visibility, and bridge/War Table/position tests pass.

### R06 — Reduce HTTP/WebSocket/Discord code to adapters

Depends on R04 and R05.

- [ ] Separate socket handshake/lifecycle from authenticated message dispatch.
- [ ] Extract admin routes and retain authentication, payload limits, and status
  codes. Keep existing client-visible message formats and rejection reasons.
- [ ] Extract Discord commands, panel rendering, snitch parsing, and lifecycle.
- [ ] Keep Discord and webhooks optional; a bot login failure must not stop core
  relay service. Bound network requests so an unavailable webhook cannot hang it.

Done when adapters contain transport/presentation logic, and domain operations
can be tested without importing Express, WebSocket servers, or discord.js.

### R07 — Add health reporting and operator diagnostics

Depends on R02, R03, and R06.

- [ ] Add minimal liveness/readiness endpoints with no users, tokens, or intel.
- [ ] Report optional Discord/webhook degradation separately from core readiness.
- [ ] Add `doctor`: runtime, config/path permissions, listener reachability, and
  optional DNS/TLS/Mojang connectivity checks with timeouts.
- [ ] Log actionable startup and auth-failure categories without plaintext
  credentials. Retain the existing remote-address privacy policy.
- [ ] Keep service names, paths, version, and configured endpoint visible in
  nonsecret status output.

Done when common setup failures can be identified without editing source or
requiring Discord to work first.

### R08 — Implement initialization and first-player onboarding

Depends on R03 and R04.

- [ ] Add interactive and noninteractive initialization with validation.
- [ ] Create empty state files, not the example player invitations.
- [ ] Create the first invitation from a valid Minecraft username with a random
  token; persist only its hash and disclose the token once through an explicit
  terminal flow or a requested private output file.
- [ ] Leave HTTP administration disabled unless explicitly configured with a
  generated strong secret. Keep Discord credentials optional.
- [ ] Protect secrets with restrictive modes; do not put them into command-line
  arguments, shell tracing, regular logs, or tracked source.
- [ ] Make reruns preserve existing configuration, users, and secrets. Separate
  explicit migration/reset operations from ordinary setup.

Done when a fresh installation can issue a usable invitation without Discord,
and running initialization again cannot erase users or rotate their tokens.

### R09 — Build the foreground Linux setup/start scripts

Depends on R07 and R08.

- [ ] Implement `setup.sh` with clear prompts and a documented noninteractive
  config-file mode; fail clearly if unattended inputs are incomplete.
- [ ] Check Bash, Linux, Node 24, npm, disk/path permissions, and the lockfile.
  Align package engine metadata/docs/CI with the tested runtime.
- [ ] Use `npm ci --omit=dev`; never install dependencies on each restart.
- [ ] Offer an explicit supported runtime-install step when Node is missing.
  Pin/check downloaded artifacts; do not pipe an unverified network script into
  a root shell.
- [ ] Implement `start.sh` with script-relative paths, config preflight, and
  `exec` so supervisors/signals reach Node directly.
- [ ] Use LF endings and executable Git modes for shell scripts.

Done when setup/start works from a different current directory and a path
containing spaces, fails early on bad prerequisites, and preserves existing data.

### R10 — Add the systemd installation path

Depends on R09.

- [ ] Implement `setup.sh --systemd` using the production directories above.
- [ ] Create a dedicated non-login `openintel-relay` account and install a unit
  with explicit Node path, working directory, config/data paths, restart policy,
  shutdown timeout, and restrictive umask.
- [ ] Run dependency installation as an unprivileged staging user, including
  when the installer was invoked with sudo.
- [ ] Restrict service writes to its data directory; test the chosen systemd
  hardening options with Node/V8 instead of blindly enabling incompatible ones.
- [ ] Validate configuration before enable/start, then verify readiness.
- [ ] Make installation repeatable; preserve config/state and report exactly
  which service/system files were created or changed.

Done when the service runs unprivileged, starts after reboot, restarts after
failure, stops cleanly, and survives an installer rerun with the same users.

### R11 — Provide optional Caddy HTTPS/WSS setup

Depends on R07 and R10.

- [ ] Supply a minimal Caddy template and optional guided integration.
- [ ] Bind the relay to `127.0.0.1` for proxy installs and preserve the
  `/openintel-relay` WebSocket path used by existing deployments.
- [ ] Set the public `secureWebSocketUrl` consistently with proxy routing;
  preserve same-host secure redirect compatibility for supported clients.
- [ ] Check hostname/DNS and explain port 80/443 requirements. Do not overwrite
  an existing Caddyfile or alter firewall rules without an explicit setup choice.
- [ ] Keep admin and detailed readiness routes private by default; do not
  accidentally expose the entire backend through a catch-all proxy rule.
- [ ] Define the proxy-address policy for pending-login limits; do not trust
  arbitrary forwarded headers or silently remove the limit.

Done when the public WSS upgrade works with a valid certificate, local health
checks still work, and existing proxy sites remain intact.

### R12 — Implement migration, backup, upgrade, and rollback

Depends on R03, R07, and R10.

- [ ] Add an explicit migration path from a legacy relay directory, including
  config, hashed/plaintext user migration, allegiances, War Table, and audit data.
- [ ] Stage a selected release/ref and dependencies before stopping the service;
  record the resolved commit. Avoid automatic pulls on service startup.
- [ ] Stop the writer before a consistent state backup/migration; verify owner,
  permissions, and integrity before switching the active release.
- [ ] Start the new release and verify readiness; restore the prior release and
  compatible state backup if startup fails.
- [ ] Document data-schema compatibility boundaries: old builds cannot read
  hashed-only invitations, so rollback is not just copying old JavaScript.
- [ ] Rotate/retain audit backups with explicit limits; keep backup directories
  private and avoid unbounded growth.

Done when a failed update leaves the previous release runnable, state survives
successful updates, and a migration dry run changes nothing.

### R13 — Linux verification, operator docs, and rollout

Depends on R01–R12.

- [ ] Run the existing relay suites against the extracted modules and application.
- [ ] Add end-to-end tests using real local HTTP/WebSocket sockets, temporary
  state, and fake Mojang/Discord services; verify login, rejection, fanout, and
  clean shutdown without contacting production.
- [ ] Add ShellCheck/bash syntax checks and a Linux setup smoke test to CI.
- [ ] Test fresh setup, rerun, existing data, malformed config/state, full disk/
  permission failure, occupied port, and missing/wrong Node.
- [ ] Test systemd enable/restart/reboot and rollback in a disposable Linux VM;
  a shell-only container is insufficient evidence for reboot behavior.
- [ ] Test supported distributions; record x86_64/arm64 coverage explicitly.
- [ ] Write `relay/README.md` for install, invite, configure Discord, view logs,
  diagnose authentication, back up, update, roll back, and uninstall while keeping
  data. Link it from the root README.
- [ ] Check maintained Minecraft branches for relay drift; decide and document
  how the tested server change is propagated so release instructions agree.
- [ ] Stage a complete test installation before any production rollout. Preserve
  existing production users/configuration and prepare the exact rollback bundle.

Done when a clean supported Linux machine can reach a working WSS relay through
the documented setup flow, and the installer/refactor checks pass in CI.

## Delivery slices

1. R01–R03: test baseline, application lifecycle, configuration/persistence.
2. R04–R06: user/session services, intel services, thin transport/Discord adapters.
3. R07–R09: diagnostics, first-user initialization, foreground setup/start.
4. R10–R11: systemd and optional HTTPS/WSS.
5. R12–R13: migration/update/rollback, Linux validation, documentation.

R05 can proceed independently of most Discord presentation extraction once R04
defines the common services. Packaging templates can be drafted early, but must
not ship before lifecycle, paths, and initialization are stable.

## Completion boundary

The refactor is complete when `server.js` only launches the application, imports
have no startup effects, every long-lived resource has an owner/shutdown path,
and behavior tests protect existing client/data contracts.

The setup work is complete when a new Linux administrator can install, invite a
player, start on boot, inspect failures, and upgrade/roll back without editing
source, losing stored state, or depending on Discord for initial access.
