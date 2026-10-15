<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/icon-dark.png">
    <img src="mod/src/main/resources/assets/openintel/icon.png" alt="OpenIntel logo" width="128">
  </picture>
</p>

<h1 align="center">OpenIntel</h1>

<p align="center">
  Fabric client <strong>2.3.0</strong> for Minecraft <strong>26.3</strong> and Java 25.<br>
  A fork of <a href="https://github.com/hiimcapri/openintel">hiimcapri/openintel</a> with Civ map, relic, and coverage changes on top of the shared relay client.
</p>

OpenIntel shares player positions, snitch alerts, and map annotations through a relay you run yourself. Local radar, HUDs, macros, relic maps, and coverage drawings work without that relay. The client does not ship a minimap or a waypoint database. Fullscreen map markers use JourneyMap or Xaero's World Map when those mods are installed.

Public source and release jars are token-neutral. Set your own relay address, Minecraft server, and token after install. Local features do not require a connection.

## Contents

- [What this fork changes](#what-this-fork-changes)
- [Requirements](#requirements)
- [Install](#install)
- [Settings](#settings)
- [Playing](#playing)
- [Commands and keys](#commands-and-keys)
- [Relay, roles, and Discord](#relay-roles-and-discord)
- [Integration API](#integration-api)
- [Screenshots](#screenshots)
- [Building](#building)

## What this fork changes

Upstream OpenIntel's `26.2` line is the base. This tree targets Minecraft `~26.3` and keeps that client's relay, War Table, radar, pings, macros, clean font, and HUD editor. The rows below are the changes on top of that base.

| Area | Upstream 26.2 | This fork |
|---|---|---|
| Minecraft | 26.2 | **26.3**, Fabric Loader 0.19.5, Java 25 |
| Xaero's World Map | Optional bridge mod and vendored bridge jar | Direct compile against Xaero's World Map and XaeroLib. No bridge jar |
| World-map markers | Small dots and diamonds on the fullscreen map | Outlined player circles, outlined diamonds for snitches, pings, and pins, Civ relic icons, two-line labels. Minimap and saved waypoints are left alone |
| War Table on the map | HUD pin toggle | Separate toggles for HUD pins, world-map pins, place labels, and sanctuary names. Labels and sanctuary names are a small colored diamond plus one line |
| Relic maps | Inventory scan, plus a chat line when a map is detected or claimed | Inventory scan only. Treasure Maps named for a relic are drawn. Filled maps named "World Map" are locators and are skipped |
| Nearby relic pins | Every carried relic, gold diamond at eye height | Horizontal range, default **300** blocks (50–2000). Label is name, enchantment, and distance |
| Chests | No relic sorting | Optional closest-to-furthest sort of located relic maps in chests, barrels, ender chests, and shulkers |
| Relic library | None | Chest Tracker list of stored treasure maps, searchable by name or enchantment. Where Is It highlights the chests for a selection. Those pins show on the fullscreen world map only while that map was opened from the library |
| Coverage | None in the client | Block-aligned snitch shells (radius default **8**), bastion disks (radius default **10.5**, diameter 21), and sanctuary rings. Manual shapes persist in `config/openintel-ranges.json`. Live-map sanctuaries reuse the War Table feed |
| Investigate | None | Paste Discord or in-game snitch logs and step through break, place, and enter highlights. Playback does not write live snitch hits |
| Snitch alerts without a world | Feed only | HUD and world-map markers still appear. Dimension comes from the snitch name, then the tripper, then the reporter, then the local player. The feed includes Y when the alert has it |
| Event feed | Presence, sightings, and snitch lines | Defaults to the top-right, right-aligned, wrapping inside about a third of the screen |
| Logo | Toggleable watermark | Hidden by default. Turn it on under HUD → Elements |
| Marker size | Opacity stored as 0–255 alpha | Opacity is a **0–100%** slider, default **59%**. Marker scale defaults to **60%** |
| Settings entry | `/oi settings` | `/oi settings` and Mod Menu's config button |

A file-level port back to upstream lives in [`docs/UPSTREAM_PORT_GUIDE.md`](docs/UPSTREAM_PORT_GUIDE.md).

## Requirements

| | |
|---|---|
| Minecraft | `~26.3` |
| Java | 25 |
| Loader | Fabric Loader `>= 0.19.5` |
| Required mod | Fabric API |

Optional. The client starts without them. A mismatched Xaero build disables that overlay and leaves the rest of OpenIntel running.

| Mod | Used for |
|---|---|
| [Xaero's World Map](https://chocolateminecraft.com/) | Fullscreen world map (`M`). Built against World Map 1.46.4 and XaeroLib 1.7.17 |
| JourneyMap | Fullscreen map (`J`) |
| Mod Menu | Config button opens the same screen as `/oi settings` |
| Chest Tracker | Relic library of maps remembered from opened chests |
| Where Is It | Chest highlights for a library selection |

## Install

### Client

From `mod/`:

```bash
./gradlew build          # Unix
gradlew.bat build        # Windows
```

The jar is `mod/build/libs/openintel-2.3.0.jar`. Put it in `.minecraft/mods` with Fabric API. On first launch the mod writes `config/openintel.json`.

Open `/oi settings` → **General** and set:

| Field | Example | Purpose |
|---|---|---|
| Relay URL | `ws://your.server:8765` | WebSocket address of your relay. Leave blank to stay local |
| Minecraft server | `play.example.net` | Relay connects only while you are on this server |
| Token | personal token from the relay admin | Keep it private. Source builds ship the placeholder `CHANGE_ME` |
| Live map URL | `https://host/livemap-api` | Backdrop for the War Table, and the sanctuary list used by coverage overlays |

`/oi url`, `/oi token`, and `/oi reconnect` edit the same relay fields. Manual coverage shapes are a second file, `config/openintel-ranges.json`, and survive restarts.

### Relay

```bash
cd relay
npm ci
cp config.example.json config.json
cp users.example.json users.json
cp allegiances.example.json allegiances.json
node server.js
```

Set the listen port, admin token, and webhook URLs in `config.json`. Approved players and their tokens live in `users.json`, not in the mod jar.

### Discord webhooks

1. Create two webhooks: one in the alerts channel, one in a private admin channel.
2. Put both URLs in `relay/config.json`.
3. For role pings, set `alertRoleId` and allow the role to be mentioned by webhooks.

The optional Discord bot is separate from the webhooks. Setup is under [Discord terminal](#discord-terminal).

## Settings

`/oi settings` opens a sidebar. The same screen opens from Mod Menu. Search covers every option. Keybinds can be rebound in place. `/oi radar`, `/oi macros`, and `/oi viz` open the matching category. `/oi visualizations` and `/oi investigate` open those editors directly.

| Category | What it controls |
|---|---|
| General | Relay URL, Minecraft server, token, live map URL, reconnect |
| Markers | Relay rendering master switch, edge chevrons, stale fade, enemy alert, marker opacity (0–100%, default 59%), marker scale (default 60%), marker range (`0` = unlimited) |
| War Table | Shared map, sanctuary overlay on that map, HUD pins, world-map pins, place labels, sanctuary names, waypoint opacity |
| Radar | Enable, north-up, distance compression, items, boats and minecarts, which players appear, pings, size, range, rings, icon and text scale, colors |
| HUD | HUD editor, Clean font / Minecraft font, logo, armor layout and durability mode, potions, roster, event feed alignment and duration |
| Snitch | Forward alerts to the relay, marker lifetime, range, opacity, allegiance color or a fixed color |
| Visualizations | Snitch, bastion, and sanctuary shells, radii, live-map sanctuaries, see-through wireframes, shape editor |
| Investigate | Paste logs, or clear the playback session |
| Pings | Ping wheel and how long a ping stays up (default 45s) |
| Integrations | JourneyMap markers, Xaero markers, relic maps, relic pin range (default 300), chest sort |
| Macros | Attack interval, use interval, ice-road snap, auto-eat, low-hunger park |
| Keybinds | Every OpenIntel bind, plus reset |
| Inventory | Auto-drop junk, and the item list |

**Relay rendering** is the master switch for in-world markers, including local snitches and relic pins. Map integrations have their own toggles, so the world map can stay on while the HUD markers are off.

**Forward snitch alerts** controls sending only. Local detection, the feed, and markers keep working when forwarding is off or the relay is down.

**Clean font** applies immediately and reflows chat without clearing it. It covers OpenIntel HUDs, chat and the chat input, the action bar, titles, and OpenIntel-owned map labels. Inventories, other menus, and other mods keep their fonts.

Positions from `/oi hud` are stored as anchors against a reference window size. Resizing the game does not rewrite them.

## Playing

Allegiance colors are the same on markers, nameplates, radar, and the roster: green friends, purple allies, red enemies, grey neutrals. Focus targets are a bright purple diamond.

### Markers, radar, and pings

Live player markers project in world space and hand off to the real entity when that player is loaded. Off-screen targets sit on four edges. Each edge has its own draggable anchor in the HUD editor.

The radar is local. It draws loaded players inside its range (default 64 blocks), plus boats, minecarts, and optional dropped items. Distant relay-only players stay on world markers and the fullscreen maps. Shared pings can still sit on the radar rim. Contact names avoid each other; a crowded cluster shows `+N more`.

Hold the ping key (`G`) for the radial wheel, or run `/oi ping`. The marker is created locally. Other clients see it when the relay accepts it.

### Snitches

In-game chat and Discord JukeAlert posts use one parser. Enter, login, logout, and other coordinate-bearing actions (containers, blocks, sanctuaries) refresh that player's marker instead of leaving a trail. Matching lines are deduplicated for about 10 seconds.

A marker needs a player, a snitch name, coordinates, and a dimension. If the line has no world name, the client tries the snitch name (for example `EndSpawn`), then the tripper's last dimension, then the reporter's, then the dimension you are standing in. The event feed prints Y when the payload has it.

Names in the alert line are recolored by allegiance. `/oi snitchtest` drops a sample marker 40 blocks out so you can check color, fade, and placement.

### War Table

`O`, `/oi wartable`, or `/oi map` opens the shared map. Admins draw strokes, lines, arrows, circles, rectangles, and labels in world coordinates. Lower ranks see the board read-only. The relay stores the shared layer across restarts.

With no relay login, strokes stay in a private local layer. An authoritative sync replaces the shared layer and leaves that local file in place.

Sanctuary circles on the War Table come from the live-map URL. Bastions do not have matching live-map entries.

On Xaero's fullscreen map, when the matching toggle is on:

- Pins use the stroke color.
- Place labels are a small diamond and one line in the stroke color.
- Sanctuary names use the gold circle color.

HUD pins and world-map pins are separate switches.

### Relics

Civ+ relic maps are inventory items named "Treasure Map" whose lore names the relic. Right-clicking at the site turns the map into the relic item, and the pin goes away with it. The lore line "Displays where you are in the world" is a locator, not a relic name. The relic once labeled Angel Dust now reads Pixie Dust.

While a located map is in your inventory, including inside a bundle or container in that inventory:

- Xaero's World Map (Overworld) draws the relic icon, name, and enchantment, or block coordinates when there is no enchantment.
- JourneyMap draws the same scan as a marker.
- Within **Relic pin range** (default 300 blocks, Overworld), the HUD pins that column with name, enchantment, and distance, including an edge marker when it is off screen.
- **Sort chest maps**, on by default, orders those maps from closest to furthest when you open a chest, barrel, ender chest, or shulker. Other items stay put.

Nothing here is written as a Xaero waypoint, and nothing is drawn on the minimap.

### Relic library

With Chest Tracker installed, its screen grows a **Relic maps** button beside the dimension switches. The library lists treasure maps Chest Tracker has seen: relic name, enchantment, and coordinates. Search matches either the name or the enchantment. You can deselect one map or every map.

**Show on map** opens Xaero's World Map with that selection. Those pins exist only for that visit. **Show chests** asks Where Is It to highlight the chests that hold the selection. Those highlights are Overworld coordinates, and they are drawn only during the library visit.

### Coverage

**Visualizations** draws Civ+ fields in the world. Defaults match a common Civ+ setup. Other servers can differ, so the radii are settings.

| Shape | Default | Notes |
|---|---|---|
| Snitch | Radius 8 | Block shell around the block. Width steps inward from the axis. Logging and notifier are separate kinds |
| Bastion | Radius 10.5 (diameter 21) | Horizontal disk. Blocks below the bastion are uncovered. A 64-block column is drawn upward. Protection continues above that column |
| Sanctuary | Radius per shape | Same block-center disk. Live-map sanctuaries use the War Table feed and do not need a manual shape |

Snitches on this server are skulk sensors by default and are often disguised, commonly as obsidian. Bastions are sponge. Sanctuaries are beacons. The editor places a shape on the block you are looking at. `/oi visualizations` opens it. See-through draws the wireframe over terrain.

### Investigate

`/oi investigate` takes a paste of Discord or in-game snitch lines. Lines without coordinates are skipped. Parentheses and brackets follow the usual dimension rules. Previous, next, and play/pause step the highlights in the world (`Left`, `Right`, `Down` while a session is active). Clearing the session removes the highlights. Playback never enters the live snitch tracker.

### HUDs that do not need the relay

Radar contacts, armor and potion HUDs, fonts, settings, the HUD editor, macros, junk drop, relic scans, and snitch lines you actually see in chat all work in a world with no relay. Remote positions, other people's alerts, the roster, and focus edits need the relay and its permissions.

Local observations are stored apart from received intel. Reconnects and relay visibility resets clear shared data and leave local snitches, your own pings, and local feed lines. Joining or leaving a Minecraft server clears both. Hidden local observations still expire. Offline observations are not queued to send later.

The event feed defaults to the top-right, right-aligned, and wraps inside about a third of the width. The bottom-right logo is off until HUD → Elements → OpenIntel logo.

### Macros and junk

Attack and use macros repeat a click on an interval. Hold-attack and hold-use release when a screen opens, the mouse unlocks, or the hotbar slot changes. Ice road snaps yaw (and optionally pitch), sprints and jumps, can eat, and can park at low hunger. It keeps running through chat, inventory, and the scroll wheel. Its own key stops it.

**Auto-drop junk** throws matching items the tick they enter your inventory. The list starts as common blocks and is edited under Inventory.

## Commands and keys

| Command | Action |
|---|---|
| `/oi settings` | Settings screen |
| `/oi hud` | Drag HUD elements |
| `/oi radar` | Settings, Radar category |
| `/oi macros` | Settings, Macros category |
| `/oi viz` | Settings, Visualizations category |
| `/oi visualizations` | Coverage shape editor |
| `/oi investigate` | Log playback |
| `/oi ping` | Ping wheel without holding the key |
| `/oi map`, `/oi wartable` | War Table |
| `/oi status` | Relay connection |
| `/oi reconnect` | Reconnect after credential edits |
| `/oi url <url>` | Set the relay WebSocket URL |
| `/oi token <token>` | Set the personal token |
| `/oi focus <player>` | Operator or higher: priority target for every client |
| `/oi unfocus <player>` | Remove a priority target |
| `/oi focus clear` | Clear every focus target |
| `/oi cut` | Admin only: toggle who receives your outgoing intel |
| `/oi cut on\|off\|status` | Set or inspect that switch |
| `/oi snitchtest` | Place a sample snitch marker 40 blocks out |

| Default key | Action |
|---|---|
| `R` | Toggle radar |
| `G` | Hold for the ping wheel |
| `O` | War Table |
| `0` | Toggle timed attack |
| `]` | Toggle timed use |
| `-` | Toggle hold attack |
| `=` | Toggle hold use |
| `Backspace` | Toggle ice road |
| `Left` / `Right` / `Down` | Investigate previous, next, play/pause, while a session is active |

All of these are rebindable under Keybinds.

### Admin relay cut

`/oi cut` is available to authenticated relay admins. It limits that admin's outgoing position, snitch, and ping reports.

- **Off** (default): share with every rank.
- **On**: share with admins only. Members, operators, and captains are excluded.
- Admin-to-admin sharing stays on when both admins have cut enabled.
- Incoming intel is unchanged. Cut does not disconnect you.

The relay applies this from the authenticated account. Cut survives reconnects. In-game focus commands stay public. Public Discord enemy alerts are not generated from cut reports. `!where` follows the caller's Discord tier.

Updated clients drop cached relay intel when visibility changes and then receive a fresh snapshot. Chat you already saw, copies other mods kept, and players loaded in your world stay visible. Older clients stop receiving new restricted updates and can keep old entries until they expire. After a promotion, run `/oi reconnect` so command access refreshes.

## Relay, roles, and Discord

```
Client mod  ←WebSocket→  Relay (Node.js)  →  Discord #alerts
                              │               →  Discord #admin
                              ├─ users.json
                              └─ allegiances.json
```

The relay deduplicates the same sighting from several clients, cools down an enemy standing on a snitch line, and keeps the approved-user list in `users.json`. Clients connect only while playing the configured Minecraft server. The relay rejects a session whose server identity does not match.

### Roles

| Role | Access |
|---|---|
| `trial` | Send reports. Does not receive shared intel |
| `member` | Read relay intel. Default for new users |
| `operator` | Member, plus focus targets and admin broadcasts |
| `captain` | Operator, plus allegiances, quarantine, and kicks |
| `admin` | Captain, plus users, tokens, roles, and disable |

Disabled users cannot authenticate. Quarantined users can read, and their positions, pings, and snitch reports are ignored. Existing `member`, `captain`, and `admin` entries stay valid.

Focus lives in `allegiances.json`, is pushed live, and each change is announced in the alerts and admin Discord channels.

```
/oi focus <player>
/oi unfocus <player>
/oi focus clear
```

### Discord terminal

One bot can bridge several Discord servers. Terminal channels run admin commands. Snitch channels ingest JukeAlert posts into the same deduplication as in-game chat. The bot stays off until `config.discord.botToken` is set. Positions still move over the relay WebSocket. Discord is command and control only.

1. Create an application at the Discord developer portal, open **Bot**, reset the token, and store it in `discord.botToken`.
2. Enable **Message Content Intent**.
3. Invite with the `bot` scope and permissions View Channels, Send Messages, Embed Links, and Read Message History. Admins who create users must allow DMs from the bot. A failed DM does not post the token in the channel.
4. With Developer Mode on, copy terminal channel IDs into `terminalChannelIds` and JukeAlert channel IDs into `snitchChannelIds`.
5. Copy role IDs into `operatorRoleIds`, `captainRoleIds`, and `adminRoleIds`. Each tier includes the tiers below it. Discord Administrator maps to OpenIntel admin.
6. Restart the relay. Startup logs guild, terminal, and snitch channel counts.

```jsonc
"discord": {
  "botToken": "YOUR_DISCORD_BOT_TOKEN",
  "terminalChannelIds": ["TERMINAL_CHANNEL_ID"],
  "snitchChannelIds": ["SNITCH_CHANNEL_ID"],
  "operatorRoleIds": ["OPERATOR_ROLE_ID"],
  "captainRoleIds": ["CAPTAIN_ROLE_ID"],
  "adminRoleIds": ["ADMIN_ROLE_ID"]
}
```

The older singular fields (`terminalChannelId`, `snitchChannelId`, and the singular role IDs) still work and are merged with the arrays. The bot token stays in `config.json`, which is gitignored. Reset the token in the developer portal if it leaks.

| Command | Effect | Tier |
|---|---|---|
| `!help` | List commands | member |
| `!online [page]` | Connected relay users | member |
| `!list [users\|allies\|enemies\|focus\|online\|all] [page]` | Relay lists. Bare `!list` lists everyone | member |
| `!where <player>` | Last known position | member |
| `!broadcast <message>` | Notice on connected clients | operator |
| `!focus` / `!unfocus` / `!focus clear` | Focus targets | operator |
| `!panel` | Status, list, and activity panel | operator |
| `!ally add\|remove`, `!enemy add\|remove` | Allegiance lists | captain |
| `!kick <user>` | Close that user's sockets | captain |
| `!quarantine` / `!unquarantine` | Block or restore submissions | captain |
| `!user add <name> [role]` | Create a user and DM the token | admin |
| `!user remove\|disable\|enable` | Access, and revoke affected sessions | admin |
| `!user role <name> <trial\|member\|operator\|captain\|admin>` | Change role | admin |
| `!user rotate-token <name>` | New token, revoked sessions, DM | admin |
| `!user info <name>` | Status, token fingerprint, session count | admin |

### Admin HTTP API

Authenticated with the header `x-admin-token`:

| Endpoint | Methods | Purpose |
|---|---|---|
| `/allegiances` | GET, PUT | Read or replace ally and enemy lists. PUT is pushed live |
| `/users` | GET, PUT | Read or replace the approved-user list |
| `/online` | GET | Currently connected users |

Administrative actions append JSON lines to `relay/audit.jsonl` and a readable copy to the admin webhook. Entries record actor, tier, action, target, guild, channel, before and after state, and success or reason. Tokens are stored only as short SHA-256 fingerprints. The audit file and the live relay JSON files are gitignored.

## Integration API

The public Java API is `dev.openintel.api`, major version **1**. The installed mod version is **2.3.0**. Use `OpenIntelApi` as the entry point. Do not link against `tracker`, `net`, `render`, or `api.internal`.

This is a client Fabric API for Minecraft 26.3 with official Mojang names. It is separate from the Discord bot and the admin HTTP API.

Publish a neutral jar locally, then depend on it. The consuming project must target the same Minecraft version. Install OpenIntel itself in `mods/`. Do not nest a jar that contains a real token.

```bash
cd mod
./gradlew build publishToMavenLocal
```

```groovy
repositories { mavenLocal() }

dependencies {
    modImplementation "dev.openintel:openintel:2.3.0"
}
```

A required integration adds `"openintel": ">=2.3.0"` under `depends` and this entrypoint:

```json
"entrypoints": {
  "openintel:integration": ["example.ExampleOpenIntelIntegration"]
}
```

An optional integration puts the constraint in `suggests` and keeps every OpenIntel import inside the integration class. OpenIntel calls that entrypoint only when it is installed. Check `FabricLoader.getInstance().isModLoaded("openintel")` before touching it from a normal client entrypoint.

A compiling example is `mod/src/test/java/example/ExampleOpenIntelIntegration.java`.

| Accessor | Contract |
|---|---|
| `players()` | Immutable tracked players, lookup by name |
| `snitches()` | Active dimension-bound snitch markers |
| `pings()` | Active pings, lookup, and validated submit |
| `allegiances()` | Snapshot, lookup, focus requests |
| `relay()` | Connection, optional server, optional role. No credentials |
| `notifications()` | Local event-feed line, obeying the user's visibility settings |
| `events()` | Typed subscriptions with an unsubscribe handle |
| `screens()` | Open settings or the HUD editor |
| `settings()` | Read-only radar, marker, snitch, HUD, and ping settings. No secrets |
| `hud()` | Register an independent draggable HUD element |

`API_VERSION` is `1`. `isReady()` means the API has initialized, which is separate from relay authentication. Use `relay().snapshot().authenticated()` for a session that has received its welcome. Initialization runs on Fabric `CLIENT_STARTED`, after Minecraft's constructor.

Events: `Ready`, `PlayersChanged`, `SnitchesChanged`, `PingsChanged`, `AllegiancesChanged`, `ConnectionChanged`, `SnitchArrived`, `NotificationReceived`, `NotificationsCleared`, `SettingsChanged`. Callbacks run on the client thread. Subscriptions do not replay history. A listener that throws is logged once and unsubscribed. Recursive dispatch stops at 256 events.

`SnitchArrived` can describe a feed line with no marker. A local alert may later show up again as a relay echo. Snapshots are safe to keep. Their coordinates do not change. Fetch again for newer intel.

Actions return `CompletableFuture<ActionResult>`. Do not block the client thread on them. `SUBMITTED` means the message was handed to the relay, which is separate from the server accepting it. Roles, quarantine, and server binding still apply. The API does not expose tokens, mutable config, raw sockets, or Discord admin operations.

HUD elements draw in local pixels after OpenIntel has translated the context. They show up in `/oi hud`. Position and visibility are saved by id in `config/openintel.json`. Registration must use your own namespace. `openintel` and duplicate ids are rejected. HUD writes run on the client thread and do not return futures.

`./gradlew build` runs the headless checks, including the API contract test. `./gradlew cleanFontShaderTest` needs a graphical OpenGL session and is separate from `build`.

### Publication safety

- Leave `mod/src/main/resources/openintel_token.txt` set to `CHANGE_ME`.
- Commit example relay JSON only. Live `config.json`, `users.json`, `allegiances.json`, audit logs, personal Minecraft configs, and profiling captures stay out of git.
- Build public jars from neutral source. Scrubbing the current file does not remove secrets from history, tags, or old release assets.

## Screenshots

The gallery below is from an earlier client. It shows the relay HUD, War Table, and JourneyMap overlay. It does not show the 26.3 world-map markers, relic library, or coverage shells.

![OpenIntel War Table](docs/wartable.png)

![OpenIntel gameplay overview](docs/openintel-overview.png)

<table>
  <tr>
    <td align="center"><img src="docs/radar-modern.png" alt="Circular player radar"/><br>Radar</td>
    <td align="center"><img src="docs/presence-panel.png" alt="Relay roster"/><br>Roster</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/relay-marker.png" alt="Live player marker"/><br>Player marker</td>
    <td align="center"><img src="docs/snitch-marker.png" alt="Snitch marker"/><br>Snitch marker</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/armor-hud.png" alt="Armor HUD"/><br>Armor</td>
    <td align="center"><img src="docs/potion-hud.png" alt="Potion HUD"/><br>Potions</td>
  </tr>
</table>

![Event feed](docs/event-feed.png)

![Edge marker](docs/edge-marker-modern.png)

![Focus target](docs/focus-target.png)

![Enemy alert in chat](docs/enemy-alert-chat.png)

![JourneyMap fullscreen markers](docs/journeymap-bigmap.png)

## Building

| Path | Contents |
|---|---|
| `mod/` | Fabric client |
| `relay/` | Node.js relay, Discord bot, and admin HTTP API |
| `docs/UPSTREAM_PORT_GUIDE.md` | How to carry this fork's map and relic work back to upstream |

```bash
cd mod && ./gradlew build
cd relay && npm test
```

`npm test` runs the relay handlers in-process with fake sockets. It does not contact Discord or edit live users. `gradlew build` includes the headless Java checks (API contract, snitch dimensions, fonts, radar layout, relic map math, coverage shapes, investigate parsing, and the offline-intel split). `gradlew cleanFontShaderTest` compiles the font shaders in a hidden OpenGL 3.3 window and is not part of `build`.

## Fair play

Confirm that position sharing and radar mods are allowed on a server before you use them. Rules differ between Civ servers.

## License

OpenIntel is MIT. See `LICENSE`.

The bundled Noto fonts are SIL Open Font License 1.1. See `mod/src/main/resources/assets/openintel/font/LICENSE-Noto.txt`. JourneyMap, Xaero, Mod Menu, Chest Tracker, and Where Is It keep their own licenses. They are not redistributed as runtime dependencies of the OpenIntel jar. Chest Tracker and Where Is It jars under `mod/libs/` are compile-only.
