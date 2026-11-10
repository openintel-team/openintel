# Map integration reference

OpenIntel displays player intelligence, snitch alerts, relics, and War Table
annotations on optional fullscreen maps. Players install the map mods separately.
The Minecraft target and dependency versions are defined by each branch's
`mod/gradle.properties` and `mod/build.gradle`.

## Shared marker source

`dev.openintel.map` holds the map-neutral model. It references neither map
mod's types, so JourneyMap and Xaero draw the same content:

- `MapMarker` is one transient marker: id, position, dimension, color, title,
  caption, and expiry.
- `MapMarkerSource.all(client, config, now, mode)` returns every drawable marker
  across all dimensions: relay players (hidden after `staleAfterMs`), snitch hits
  (hidden after `snitchMarkerSeconds`), shared pings, carried relic maps, relic
  library pins during a library map visit, and War Table pins, place labels, and
  sanctuary names. `build(inputs, ...)` is the pure core used by tests.
- `PlayerMode.LIVE_POSITIONS` (Xaero) replaces a loaded player's relay report
  with the live position. `PlayerMode.SKIP_LOADED` (JourneyMap) leaves loaded
  players out because JourneyMap's own radar already draws them. This is the
  only intended difference between the two maps. Relay players are drawn at
  the last reported position, with no projection or easing between reports.
- `MapMarkerStyle.of(marker, selected)` picks the shape (circle, diamond, name
  diamond, relic texture, or cross), the relic texture, the title and caption,
  the color, and the selected-library highlight. `signature` is the rebuild key.
- `MapIconPixels` bakes those shapes as pixel grids for maps that need images.
- `WarTableMapMarkers` supplies War Table annotations.

Settings: `jmMarkers` and `xaeroWorldMap` are per-mod master switches.
`worldMapIntel`, `worldMapRelics`, and the three `warTable*WorldMap` toggles apply
to both maps. Configs saved before the shared switches start `worldMapIntel` and
`worldMapRelics` from the old `xaeroMarkers` and `xaeroRelicMaps` values.

## Xaero's World Map

`XaeroIntegration` is the optional entry point. It checks for `xaeroworldmap`
before loading the renderer and keeps Xaero types out of the always-loaded class.
Initialization runs after client startup; registration retries on client ticks
until the map's render handler is ready. An incompatible map dependency disables
the integration without preventing OpenIntel from running.

`XaeroMarkerRenderer` renders fullscreen map markers through the Xaero World Map
and XaeroLib APIs, filtered to the viewed dimension. OpenIntel does not write
saved Xaero waypoints or draw markers on Xaero's Minimap. Xaero dependencies are
compile-time only.

## JourneyMap

`OpenIntelJmPlugin` is registered under the `journeymap` entrypoint, so
JourneyMap instantiates it only when JourneyMap is loaded. Only
`dev.openintel.jm` references the JourneyMap API.

`JmBridge` reconciles `MapMarkerSource` (`SKIP_LOADED`) against JourneyMap
`MarkerOverlay`s every tick while the fullscreen map is open (and at once when
it opens), every 20 ticks otherwise. When only the position or text changed
(`MapMarkerStyle.signature` differs but `iconSignature` holds), the overlay is
moved and retitled in place and shown again; a new icon, color, selection, or
dimension gets a new overlay. Markers
are fullscreen-only; nothing is written as a waypoint. `JmIcons` bakes one image
per look, including each relic texture on its dark backing and a green-backed
variant for selected library pins. Overlay text is the title and caption on two
lines. An error logs one warning and disables the bridge for the session.

## Relic tracking and library

`RelicMapScanner` reads carried treasure maps. `RelicCatalog`, `RelicNames`, and
`RelicMapMath` provide presentation and coordinate helpers; the relic textures
live under `mod/src/main/resources/assets/openintel/textures/relic/`.
The HUD and map integrations consume the same inventory observations.

`RelicChestSort` optionally orders located treasure maps in opened containers.
The `dev.openintel.relic` package implements the searchable relic library,
Chest Tracker integration, and optional Where Is It chest highlights.

`RelicMapBridge` opens the library's map visit and names neither mod's types:
Xaero's World Map when it is installed and `xaeroWorldMap` is on
(`XaeroLibraryMap`), otherwise JourneyMap (`JmLibraryMap`). A map whose switch
is off is skipped because it would not draw the library pins. `LibraryMapInput` holds the click, lasso, and capacity rules;
`LibraryMapOverlay` draws the carry hint and lasso box. Both maps use them:

- Xaero: `GuiMapLibraryMixin` forwards `GuiMap` press, release, and tick.
- JourneyMap: the API cannot open the fullscreen map, so `JmLibraryMap` calls
  `journeymap.client.ui.UIManager.INSTANCE.open(Fullscreen.class)` reflectively.
  A failed lookup logs one warning and the library stays open. Library pin
  overlays carry an `IOverlayListener` for hover, click, and the
  **Show chests** popup item. Shift + left press is a cancelled PRE
  `FULLSCREEN_MAP_CLICK_EVENT`; PRE `FULLSCREEN_MAP_DRAG_EVENT`s update the lasso
  and are cancelled so the map does not pan. JourneyMap sends no release for a
  drag it never started, so the release is the left button going up during
  `FULLSCREEN_RENDER_EVENT`, which also draws the overlay. The client tick
  centers the map once and ends the visit when the fullscreen map closes.

## War Table and local data

`WarTableMapMarkers` supplies map annotations with independent HUD/map visibility
settings. Relic inventory observations, local coverage shapes, and local War Table
strokes remain distinct from shared relay data. Relic markers are transient and
do not become a persistent waypoint database.

## Source locations

| Path | Responsibility |
|---|---|
| `mod/src/main/java/dev/openintel/map/` | Shared marker list, styles, icon pixels, War Table markers |
| `mod/src/main/java/dev/openintel/xaero/` | Xaero rendering and library open, relic scanning and helpers |
| `mod/src/main/java/dev/openintel/jm/` | JourneyMap overlays, icons, and library visit |
| `mod/src/main/java/dev/openintel/relic/` | Relic library, shared map input, optional storage integrations |
| `mod/src/main/resources/assets/openintel/textures/relic/` | Relic artwork |
| `mod/src/test/java/dev/openintel/map/` | Marker, parity, style, migration, and library-input checks |
| `mod/src/test/java/dev/openintel/xaero/` | Relic coordinate checks |

## Verification

Run `./gradlew build` from `mod/` for the branch's headless checks, including map
markers, map parity (`mapParityTest`), relic geometry, library behavior, and
map-font scoping. `./gradlew mapFontTest -PjourneyMapJar=<jar>` also checks the
installed JourneyMap jar's text hook and the internal fullscreen-open target.

Test in-game with JourneyMap only, Xaero only, both, and neither. Check every
marker kind, a dimension change, each toggle, the library (open, click, lasso,
Show chests, close), and that nothing appears on either minimap.
