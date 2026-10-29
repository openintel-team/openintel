# Map integration reference

OpenIntel displays player intelligence, snitch alerts, relics, and War Table
annotations on optional fullscreen maps. Players install the map mods separately.
The Minecraft target and dependency versions are defined by each branch's
`mod/gradle.properties` and `mod/build.gradle`.

## Xaero's World Map

`XaeroIntegration` is the optional entry point. It checks for `xaeroworldmap`
before loading the renderer and keeps Xaero types out of the always-loaded class.
Initialization runs after client startup; registration retries on client ticks
until the map's render handler is ready. An incompatible map dependency disables
the integration without preventing OpenIntel from running.

`XaeroMarkerRenderer` renders fullscreen map markers through the Xaero World Map
and XaeroLib APIs. OpenIntel does not write saved Xaero waypoints or draw markers
on Xaero's Minimap. Xaero dependencies are compile-time only.

## Relic tracking and library

`RelicMapScanner` reads carried treasure maps. `RelicCatalog`, `RelicNames`, and
`RelicMapMath` provide presentation and coordinate helpers; the relic textures
live under `mod/src/main/resources/assets/openintel/textures/relic/`.
The HUD and map integrations consume the same inventory observations.

`RelicChestSort` optionally orders located treasure maps in opened containers.
The `dev.openintel.relic` package implements the searchable relic library,
Chest Tracker integration, and optional Where Is It chest highlights.

## War Table and local data

`WarTableMapMarkers` supplies map annotations with independent HUD/map visibility
settings. Relic inventory observations, local coverage shapes, and local War Table
strokes remain distinct from shared relay data. Relic markers are transient and
do not become a persistent waypoint database.

## Source locations

| Path | Responsibility |
|---|---|
| `mod/src/main/java/dev/openintel/xaero/` | Xaero rendering, relic scanning and helpers, War Table markers |
| `mod/src/main/java/dev/openintel/relic/` | Relic library and optional storage integrations |
| `mod/src/main/java/dev/openintel/jm/` | JourneyMap integration |
| `mod/src/main/resources/assets/openintel/textures/relic/` | Relic artwork |
| `mod/src/test/java/dev/openintel/xaero/` | Marker and relic coordinate checks |

## Verification

Run `./gradlew build` from `mod/` for the branch's headless checks, including map
markers, relic geometry, library behavior, and map-font scoping. Test in-game
with optional integrations present and absent, then verify fullscreen markers,
dimension changes, independent visibility settings, and library selections.
