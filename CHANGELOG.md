# Changelog

## Unreleased

- Use OpenIntel branding and Minecraft-specific version identifiers.
- Link update notifications, builds, and documentation to the OpenIntel team repository.

## 3.2.1+mc26.2 — join crash hotfix

- Minecraft 1.21.11 no longer crashes on join. The chat channel cache now lives
  outside the mixin package, which Mixin refused to load.
- Minecraft 26.3, 26.2, and 26.1.2 have no gameplay change in this hotfix. Their
  versions are aligned so the release is one number.

## 3.2.0+mc26.2 — chat, relays, and coverage

- Add display-only Chat, Snitches, Mining, Client, and OI Events filters with
  independent fading controls, while preserving stored chat history.
- Keep a scrollable session event log and allow resizing the HUD feed from
  its edges and corners.
- Support multiple relay profiles with independent sharing controls and loop-safe
  forwarding; negotiate same-host WSS upgrades before sending credentials.
- Add grouped coverage entries, search, import/export, anchored dropdowns, and
  automatic coordinates from nearby already-loaded blocks.
- Add a V keybind for coverage visibility and default new see-through settings off.
- Match the default bastion field to Civ+ coverage, mark its center with a hollow
  plus, extend outline posts to build height, and draw a border at the player's Y.
- Cache coverage geometry and retain GPU buffers to reduce visualization overhead.
- Check the War Table live map on world join and periodically; show saved imagery
  only after the server confirms it is current.
- Import Discord text exports into Investigate with a local .txt file picker and
  preserve multiple-event order regardless of bot/webhook display names.
- Keep routine relay connections quiet and announce a compatible newer release
  once per launch; update relay dependencies and add per-branch canary builds.

## 3.1.0+mc26.2 — investigation and overlay improvements

- Refresh the relic-map library with a compact vertical layout, taller entries,
  clearer selection/capacity states, and search preserved across window resizes.
- Toggle individual relic selections from the list or the fullscreen Xaero map
  without clearing other selections; retain Shift-drag area selection.
- Correct mouse-button handling in the HUD editor and Investigate event list.
- Render Investigate and coverage overlays through dedicated Fabric pipelines
  registered with Iris. Keep faces on exact block boundaries, use GPU depth bias
  for surface overlays, and preserve terrain depth for shader packs.
- Highlight player names, actions, and focused coordinates in the Investigate
  caption, with wrapped descriptions and a different-dimension notice.
- Add Pan (default), Snap, and Off camera-focus modes under Investigate settings.
  Pan duration defaults to 350 ms and is adjustable from 100–2000 ms; mouse look
  cancels a pan and camera control releases when the turn finishes.
- Expand regression coverage for geometry, depth policies, camera transitions,
  caption emphasis, settings persistence, and event-selection lifecycle.

## 3.0.1+mc26.2 — defaults and compatibility

- New installs use Minecraft's font by default. Clean font remains available
  under HUD → Text → Font. Existing saved font preferences are preserved.
- The Minecraft 1.21.11 build corrects the font and logo shader uniform layout
  to match that version's GUI rendering pipeline.
- Provide fresh Fabric builds for Minecraft 26.3, 26.2, 26.1.2, and 1.21.11
  together in one release. The mod ID and integration API stay unchanged.

## 3.0.0+mc26.2 — maps, relics, and coverage

- Support Minecraft 26.3, 26.2, 26.1.2, and 1.21.11 on separate branches.
- Integrate directly with Xaero's fullscreen World Map; preserve independent
  HUD/map marker settings, War Table labels, and relic presentation.
- Add fixed nearby relic pins, Chest Tracker relic browsing, and inventory-slot
  limits for library selections, with optional Where Is It chest highlights.
- Add block-aligned coverage settings and offline snitch-log investigation.
- Improve missing-world snitch markers, event-feed wrapping, marker opacity,
  and access to settings through Mod Menu.
- Keep source/base jars token-neutral and resolve optional compile dependencies
  from their public releases. Mod ID and integration API major version stay unchanged.

## 1.7.0 — War Table

- Added War Table, a shared annotation layer over the server's own live
  map. The client downloads the configured live-map render, slices it into
  a cached tile pyramid, and presents a full-screen pan/zoom canvas.
- Admins can draw freehand strokes, straight lines, arrows, circles,
  rectangles, and text labels in world coordinates; strokes survive map
  image refreshes because geometry is stored in blocks, not pixels.
- Strokes sync through the relay with server-side admin gating;
  lower-rank users see the board read-only. Relay state persists in
  `wartable.json` and is replayed on connect.
- Controls: `O` opens the board (rebindable), left-drag pans/draws,
  scroll zooms to cursor, `Ctrl+Z` removes your last stroke, eraser and
  clear-all on the toolbar. `/oi wartable` and `/oi map` open it too.

## 1.6.1 — OpenIntel branding and Noto Sans

- Added the OpenIntel mark — a black O with a joined eye — as a small
  resolution-aware HUD element in the bottom-right corner, toggleable under
  HUD settings, with standalone SVG/PNG artwork in the assets.
- Replaced the bundled DejaVu Sans clean font with Noto Sans Medium plus Noto
  Sans Math/Symbols/Symbols2 companions, under the SIL Open Font License.
  The heavier face removes the shader's artificial coverage boost.
- Fixed the event-feed prefix showing `??`: unsupported codepoints now fall
  back once to `?` instead of once per UTF-16 surrogate half.

## 1.6.0 — clean text, responsive HUDs, and local intelligence

- Added a bundled clean font with light antialiasing and no shadows, plus a
  live Clean font / Minecraft font toggle under HUD settings.
- Applied font selection to chat and its input field, actionbar messages,
  titles, OpenIntel HUDs, and OpenIntel-owned map labels only. Other menus,
  text fields, map-mod UI, and explicit server icon fonts keep their fonts.
- Batched custom text by label and reused font styles/atlas identifiers to
  reduce per-character GUI submission and allocation overhead.
- Added responsive HUD scaling, relative anchors, consistent editor/runtime
  bounds, small-window fitting, and true top-edge snapping at Y=0.
- Radar player contacts are now local Minecraft entities within radar range;
  distant relay-only contacts remain in world/map intel, not on the radar rim.
- Added bounded, identity-stable radar label placement, transparent labels,
  and explicit overflow counts without compass collision reservations.
- Added compatible relic-map tracking using item decorations, lore-based
  names, gold world/map overlays, and map-away/claim cleanup without permanent
  waypoints or a relay dependency.
- Snitch forwarding can be disabled without stopping local detection. Local
  hits on relay friends are retained, and reconnect/visibility resets preserve
  local observations while removing received relay data.
- Kept normal expiry and full Minecraft-session cleanup, including local
  ping/feed data and snitch deduplication state.
- Added regression coverage for font rendering, input metrics, map ownership,
  radar rotation/layout, viewport resizing, relic names, and offline data ownership.
- Public source/base builds contain a placeholder token and no configured
  deployment endpoints; live relay configuration and personalized artifacts
  remain outside version control.
- Updated the relay's Express/Discord.js dependencies and affected transitive
  packages; the verified lockfile has no reported npm audit vulnerabilities.

## 1.5.0 — marker scaling and public focus

- Added a Marker scale slider (50–200%) sizing over-head relay markers —
  chevron/diamond, snitch glyph, and name label together — with scaled
  label stacking. Edge-of-screen indicators keep their fixed size.
- Focus targets are now shared with every relay rank; `/oi focus` no longer
  becomes admin-only intel when the caller is relay-cut.

## 1.3.0 — public integration API

- Added admin-only `/oi cut [on|off|status]`: outgoing relay intel can be
  restricted to admins without changing incoming or admin-to-admin relay.
- Added per-recipient filtering, per-reporter player state, audience-aware
  snitch deduplication, and cache resets on visibility changes.

- Added versioned `dev.openintel.api` modules for immutable player, snitch,
  ping, allegiance, connection, and non-secret settings snapshots.
- Added typed client-thread events, closeable subscriptions, listener failure
  isolation, and bounded recursive dispatch.
- Added future-based validated ping/focus requests, local notifications, and
  settings/editor access. Submission does not bypass relay permissions.
- Added namespaced third-party HUD registration with optional previews,
  persistent position/visibility, drag editing, and isolated rendering.
- Added optional `openintel:integration` entrypoint and local Maven publication.
- Deferred API initialization to Fabric's client-started lifecycle event,
  avoiding the startup thread-check failure during Minecraft construction.
- Marshaled relay updates onto the client thread, guarded stale queued work
  across reconnects, and distinguished authentication from socket connection.
- Added API contract checks and a compiling example integration.
- Client-captured snitches read dimension hover metadata; unknown-world
  local/received alerts stay feed-only instead of guessing the current world.

## 1.2.1 — modernized client (unreleased)

A full modernization pass on the relay client: own code, own UI, shared-intel
features on top of the original position/allegiance/focus core.

### Radar
- Player radar: allegiance-colored blips, range rings, log-scale distance
  compression, crisp anti-aliased dial (vector geometry, no texels).
- `/oi radar` settings screen + orientation options.
- Configurable radar line and background colors via a real HSV color picker
  (SV square + hue bar + alpha + hex).

### Macros
- `/oi macros` — attack, hold-key, and ice-road input macros with a config
  screen and keybinds.

### Relay markers & nameplates
- Live handoff: tracked players within render distance anchor to the entity's
  lerped position; relay coords are only a fallback.
- Nearby projected name labels use collision-aware vertical stacking while
  their marker chevrons remain anchored to the players' exact positions.
- Relay player snapshots are published atomically, preventing render crashes
  when a newly tracked player arrives during a HUD frame.
- Dynamic-FOV-safe projection via `GameRenderer.project()` — sprint/zoom no
  longer shifts markers.
- Allegiance-tinted vanilla nameplates; relay nameplate + chevron overlay can
  be disabled for visible players ("Markers + nameplates in render distance").
- Master relay-render toggle + opacity slider.
- Edge chevrons: left/right vertical stacks, top/bottom single-chevron
  columns. Anchors fully adjustable (`/oi settings` → edge position/offset
  sliders) so the stacks can park around your HUD.
- Unlimited marker range by default (old 4096 cap migrates automatically);
  optional cap slider.

### Presence panel
- Side panel listing relay members: name, allegiance, dimension, distance.
- Stale-intel decay on blips and markers.

### HUD editor
- `/oi hud` visual editor with click-and-drag placement, center/edge snapping,
  persistent positions, and a reset layout action.
- Independently movable top, bottom, left, and right marker-stack anchors.
- Movable relay roster, radar, event feed, armor HUD, and potion-effect HUD.
- Armor durability percentages and compact potion names, levels, and timers.

### Ping wheel
- Ping-wheel-style shared pings (`/oi ping`), enable toggle + keybind.

### Event feed
- One Discord bot can ingest multiple terminal and snitch channels across
  multiple Discord servers, with global semantic deduplication across all
  Discord and in-game sources.
- Snitch parsing distinguishes normal presence events from interactions and
  refreshes player markers for container, block, sanctuary, and similar events.
- Incomplete snitch messages remain feed-only instead of creating `?` markers.
- Teammate death/logout/enemy-in-range toasts; feed duration slider.

### Snitch intel
- In-game JukeAlert lines (game + chat channels) forward to the relay;
  clients get a ⚠ marker with `snitch | player | age | distance`, live age
  counter, and a fade over a configurable duration (default 2 min).
- Per-tripper markers: a new hit moves the player's marker — no ghost trails.
- Relay-side semantic dedupe (`player@x,y,z`) — the same hit from game chat
  and Discord produces one marker.
- Approved relay users don't get snitch markers (live position already
  streams).
- Dimension filtering: named worlds pin to that dimension; snitch-name
  inference ("EndSpawn" → the End) and tripper/reporter fallbacks otherwise.
- Discord snitch channel → in-game markers: the relay bot watches a
  configured channel, parses `Name: Player entered snitch at (x, y, z)` and
  `player entered snitch at name [world x y z]` formats, bot-authored posts
  allowed in that channel only.
- Snitch marker color picker (icon + text), with "Allegiance color" reset.
- `/oi snitchtest` places a fake hit for local verification.

### Settings
- Snitch markers default to neutral grey, with an explicit allegiance-color
  option for users who want dynamic tripper colors.
- Relay sessions are bound to a configured Minecraft server address on both
  client and relay; other servers cannot publish or receive shared intel.
- Unified `/oi settings` screen: relay URL, Minecraft server, and token fields, reconnect button,
  all render toggles, keybind rebinding, links into radar/macro screens,
  and the color pickers.

### Discord relay administration
- Added member, operator, captain, and admin permission tiers with multi-guild
  role mapping and Discord Administrator override.
- Added admin-only user lifecycle, role, disable, and secure token rotation
  commands; new tokens are delivered only by DM and affected sessions revoke.
- Added captain quarantine/kick controls, operator broadcasts, paginated lists,
  and an interactive status panel with per-click permission checks.
- Added atomic relay JSON persistence and structured, secret-redacted JSONL
  auditing mirrored to the admin webhook.

### Infra
- Relay broadcasts are delivered once per authenticated identity even if a
  legacy client has duplicate sockets.
- Client reconnects use generation guards so overlapping connection attempts
  and stale close callbacks cannot create or clear the wrong socket.
- Terminal authentication failures stop client reconnect loops, while repeated
  legacy-client rejection logs are rate-limited to prevent Discord spam.
- Token-specific builds bake `openintel_token.txt` inside the jar — no
  recompile per user.
- Relay server: snitch dedupe, Discord snitch-channel watch, granular Discord
  administration, and a deterministic `node server.js --self-test` harness.
