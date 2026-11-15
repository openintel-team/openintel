# Relay deployment — 2026-11-15 UTC

Deployed the `relay-modules-linux` working tree to the existing OpenIntel relay,
with explicit authorization. No client jar or GitHub branch was published.

## Production state

- Host: Ubuntu 26.04.1 LTS, x86_64.
- Runtime: private Node 24.22.3 under `/opt/openintel-node`; system Node 22 retained.
- Service: `openintel-relay.service`, still running as `openintel-relay`.
- Active release: `/opt/openintel-relay/releases/20261115T030548Z-159085`.
- Entry point: `/opt/openintel-relay/current/server.js`.
- Config: `/etc/openintel-relay/config.json`.
- Persistent state: `/var/lib/openintel-relay`.
- Public WSS endpoint and Caddy routing unchanged.
- All 23 users, token hashes, existing 13 UUID pins, allegiances, War Table,
  and audit history were preserved. Configuration bytes were also preserved.
- The existing security drop-in was retained, changing its writable directory
  from the old code directory to `/var/lib/openintel-relay`.

Artifact SHA-256:
`66f0db8d51a35698ab7880aeb6306b6f8f4cb8556f4dc7846f6c0a3b0c8d6be9`.
All 24 installed runtime module/package files matched the tested artifact.

## Live-only behavior discovered and preserved

The old live `server.js` included an optional position-velocity extension absent
from GitHub's baseline. The deployment retains it:

- `vx`/`vz` must both be finite and bounded to 100 blocks/second.
- Accepted components are rounded to two decimal places.
- Velocity-only changes trigger a position update.
- Compact rows append the optional velocity pair; absent/invalid pairs keep the
  original nine-field row.
- Bridged position reports preserve the same validated optional pair.

Regression checks were added to `relay/positions.test.js` before deployment.

## Verification performed

- All nine Node test programs passed locally and on the Linux host.
- Foreground Linux smoke test passed as an unprivileged user.
- A separate validation service passed startup/readiness, forced-crash recovery,
  stale-lock recovery, and clean shutdown under the new systemd settings.
- Production configuration and stored state passed validation before cutover.
- Final consistent state snapshot taken while the old service was stopped.
- Managed setup adopted the migrated state without issuing or rotating tokens.
- Production became ready in approximately seven seconds of cutover.
- Both previously connected clients re-authenticated.
- Discord reported ready; no production restart loop was observed.
- Public WSS upgrade and certificate verification passed from the host and
  an external machine.
- Mojang HTTPS was reachable; successful client reconnections separately
  demonstrated the account-proof handshake.

The five-minute automatic rollback guard was removed after these checks.
This was not a production reboot test. The new CI matrix and fresh Caddy
certificate issuance still require their own verification.

## Rollback

Private bundle on the host:

`/var/backups/openintel-relay/20261115-monolith-migration`

It contains the old service/drop-in, legacy code archive, and a final state
snapshot. The legacy runtime files remain at `/opt/openintel-relay`.

To explicitly restore the pre-migration service:

```bash
bash /var/backups/openintel-relay/20261115-monolith-migration/rollback.sh --force
```

This stops the new service and restores the legacy code entry point, Node runtime,
service permissions, and pre-cutover state. Current new-layout state/config are
retained in timestamped `failed-data-*` / `failed-config-*` directories in the
bundle for reconciliation; later edits are not silently merged into old state.
