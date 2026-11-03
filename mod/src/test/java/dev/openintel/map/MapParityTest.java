package dev.openintel.map;

import com.google.gson.JsonObject;
import dev.openintel.config.OIConfig;
import dev.openintel.relic.LibraryMapInput;
import dev.openintel.relic.RelicLibraryEntry;
import dev.openintel.relic.RelicLibraryVisit;
import dev.openintel.wartable.Sanctuaries;
import dev.openintel.wartable.Stroke;
import dev.openintel.xaero.RelicMapMath;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * JourneyMap and Xaero must draw the same fullscreen-map content. Headless:
 * no Minecraft client and no map mod is loaded; adapter wiring is checked
 * from class bytes.
 */
public final class MapParityTest {
    private static final long NOW = 1_000_000_000L;
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    /** A type reference into either map mod's own packages (not dev/openintel/xaero). */
    private static final Pattern MAP_MOD_TYPE = Pattern.compile("(^|[L\\[(;])(xaero|journeymap)/");

    public static void main(String[] args) throws Exception {
        playerModes();
        expiry();
        toggles();
        overworldOnlyNames();
        styles();
        signatures();
        icons();
        migration();
        migrationFromFile();
        libraryInput();
        adapterWiring();
        System.out.println("MapParityTest passed");
    }

    // ------------------------------------------------------------ source

    private static OIConfig config() {
        OIConfig cfg = new OIConfig();
        cfg.snitchMarkerSeconds = 120;
        cfg.staleAfterMs = 30_000;
        return cfg;
    }

    private static MapMarkerSource.Inputs inputs(RelicLibraryVisit visit) {
        Map<String, double[]> loaded = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        loaded.put("Alice", new double[]{10, 64, 0});
        loaded.put("Me", new double[]{0, 64, 0});
        List<MapMarkerSource.PlayerReport> players = List.of(
                new MapMarkerSource.PlayerReport("alice", 100, 64, 0, OVERWORLD, NOW, null),
                new MapMarkerSource.PlayerReport("Bob", 30, 64, 40, OVERWORLD, NOW, "Carol"),
                new MapMarkerSource.PlayerReport("Dan", 5, 70, 5, NETHER, NOW, "Erin"),
                new MapMarkerSource.PlayerReport("me", 0, 64, 0, OVERWORLD, NOW, null),
                new MapMarkerSource.PlayerReport("Stale", 1, 64, 1, OVERWORLD, NOW - 30_001, null),
                new MapMarkerSource.PlayerReport("Edge", 2, 64, 2, OVERWORLD, NOW - 30_000, null));
        List<MapMarkerSource.SnitchReport> snitches = List.of(
                new MapMarkerSource.SnitchReport("Vault", "Bob", "Carol", 1, 2, 3, OVERWORLD, NOW - 119_000),
                new MapMarkerSource.SnitchReport("Gate", "Dan", "Carol", 4, 5, 6, OVERWORLD, NOW - 121_000),
                new MapMarkerSource.SnitchReport("Lost", "Dan", "Carol", 4, 5, 6, "openintel:unknown", NOW));
        List<MapMarkerSource.PingReport> pings = List.of(
                new MapMarkerSource.PingReport("p1", "here", 0x00FF00, "Bob", 7, 64, 8, NETHER, NOW + 5_000),
                new MapMarkerSource.PingReport("p2", "old", 0x00FF00, "Bob", 7, 64, 8, NETHER, NOW - 1),
                new MapMarkerSource.PingReport("p3", "nan", 0x00FF00, "Bob", Double.NaN, 64, 8, NETHER, NOW + 5));
        List<MapMarker> relics = List.of(new MapMarker("relic:7:x@0", 300.5, RelicMapMath.MARKER_Y, -40.5,
                OVERWORLD, RelicMapMath.COLOR, "Thor's Hammer", "Unbreaking", Long.MAX_VALUE));
        List<Stroke> strokes = List.of(
                new Stroke("pin", Stroke.Tool.MARKER, 0x80FFAA00, 4f, new float[]{10f, -4f}, "Vault", "ada", 0),
                new Stroke("city", Stroke.Tool.LABEL, 0x5533CCFF, 4f, new float[]{100f, 200f}, "Mt Augusta", "ada", 0));
        List<Sanctuaries.Sanctuary> sanctuaries = List.of(new Sanctuaries.Sanctuary("Haven", 50, -80, 48, "world"));
        return new MapMarkerSource.Inputs("Me", OVERWORLD, new double[]{0, 64, 0}, loaded,
                players, snitches, pings, relics, visit, strokes, sanctuaries,
                name -> switch (name.toLowerCase()) {
                    case "bob" -> 0x00AA00;
                    case "dan" -> 0xAA0000;
                    default -> 0x8888FF;
                });
    }

    private static Map<String, MapMarker> byId(List<MapMarker> markers) {
        Map<String, MapMarker> map = new TreeMap<>();
        for (MapMarker marker : markers) {
            check(map.put(marker.id(), marker) == null, "duplicate marker " + marker.id());
        }
        return map;
    }

    private static void playerModes() {
        RelicLibraryVisit visit = libraryVisit();
        OIConfig cfg = config();
        Map<String, MapMarker> live = byId(MapMarkerSource.build(inputs(visit), cfg, NOW,
                MapMarkerSource.PlayerMode.LIVE_POSITIONS));
        Map<String, MapMarker> skip = byId(MapMarkerSource.build(inputs(visit), cfg, NOW,
                MapMarkerSource.PlayerMode.SKIP_LOADED));

        // The only planned difference: JourneyMap's radar already draws loaded players.
        Set<String> difference = new HashSet<>(live.keySet());
        difference.removeAll(skip.keySet());
        check(difference.equals(Set.of("player:alice")), "only loaded players differ: " + difference);
        check(live.keySet().containsAll(skip.keySet()), "SKIP_LOADED adds nothing");
        for (String id : skip.keySet()) {
            check(live.get(id).equals(skip.get(id)), "same marker on both maps: " + id);
        }

        MapMarker alice = live.get("player:alice");
        check(alice.x() == 10 && alice.label().equals("10m"), "loaded player uses live position and distance");
        MapMarker bob = live.get("player:Bob");
        check(bob.label().equals("50m via Carol"), "distance plus reporter: " + bob.label());
        check(bob.argb() == 0xFF00AA00, "allegiance color, opaque");
        MapMarker dan = live.get("player:Dan");
        check(dan.label().equals("via Erin") && dan.x() == 5, "other dimension: report position, no distance");
        check(!live.containsKey("player:me"), "local player skipped, any case");
    }

    private static void expiry() {
        Map<String, MapMarker> markers = byId(MapMarkerSource.build(inputs(null), config(), NOW,
                MapMarkerSource.PlayerMode.LIVE_POSITIONS));
        check(markers.containsKey("snitch:Bob@Vault"), "recent snitch hit shown");
        check(!markers.containsKey("snitch:Dan@Gate"), "snitch hit hidden after snitchMarkerSeconds");
        check(!markers.containsKey("snitch:Dan@Lost"), "unknown dimension skipped");
        check(!markers.containsKey("player:Stale"), "stale player hidden after staleAfterMs");
        check(markers.containsKey("player:Edge"), "player exactly at staleAfterMs still shown");
        check(markers.containsKey("ping:p1") && !markers.containsKey("ping:p2"), "expired ping hidden");
        check(!markers.containsKey("ping:p3"), "non-finite position skipped");
        MapMarker snitch = markers.get("snitch:Bob@Vault");
        check(snitch.title().equals("Snitch: Vault") && snitch.label().equals("Bob (via Carol)"), "snitch text");
        check(snitch.expiresAt() == NOW - 119_000 + 120_000, "snitch expiry");
        for (MapMarker marker : markers.values()) check(marker.drawable(NOW), "only drawable markers: " + marker.id());
    }

    private static void toggles() {
        OIConfig cfg = config();
        cfg.worldMapIntel = false;
        cfg.worldMapRelics = false;
        cfg.warTablePinWorldMap = false;
        cfg.warTableLabelWorldMap = false;
        cfg.warTableSanctuaryWorldMap = false;
        Map<String, MapMarker> library = byId(MapMarkerSource.build(inputs(libraryVisit()), cfg, NOW,
                MapMarkerSource.PlayerMode.LIVE_POSITIONS));
        for (String id : library.keySet()) {
            check(id.startsWith(MapMarkerStyle.LIBRARY_PREFIX), "only library pins survive every toggle off: " + id);
        }
        check(!library.isEmpty(), "library pins follow the visit, not the relic toggle");

        cfg.worldMapRelics = true;
        cfg.warTableLabelWorldMap = true;
        Map<String, MapMarker> some = byId(MapMarkerSource.build(inputs(null), cfg, NOW,
                MapMarkerSource.PlayerMode.LIVE_POSITIONS));
        check(some.keySet().equals(Set.of("relic:7:x@0", "wartable-label:city")), "per-group toggles: " + some.keySet());
    }

    private static void overworldOnlyNames() {
        Map<String, MapMarker> markers = byId(MapMarkerSource.build(inputs(libraryVisit()), config(), NOW,
                MapMarkerSource.PlayerMode.SKIP_LOADED));
        int names = 0;
        int pins = 0;
        for (MapMarker marker : markers.values()) {
            boolean name = WarTableMapMarkers.nameMarker(marker);
            boolean pin = marker.id().startsWith(MapMarkerStyle.LIBRARY_PREFIX);
            if (!name && !pin) continue;
            if (name) names++;
            if (pin) pins++;
            check(marker.visibleIn(OVERWORLD, NOW), "Overworld shows " + marker.id());
            check(!marker.visibleIn(NETHER, NOW), "Nether hides " + marker.id());
            check(!marker.visibleIn("minecraft:the_end", NOW), "End hides " + marker.id());
        }
        check(names == 3, "pin, place label, and sanctuary name: " + names);
        check(pins == 2, "one pin per located site: " + pins);
        check(markers.get(MapMarkerStyle.LIBRARY_PREFIX + "100,100").argb() == MapMarkerStyle.SELECTED_COLOR,
                "selected library site is green");
        check(markers.get(MapMarkerStyle.LIBRARY_PREFIX + "-500,20").argb() == RelicMapMath.COLOR,
                "unselected library site is relic gold");

        RelicLibraryVisit closed = libraryVisit();
        closed.endMapVisit();
        Map<String, MapMarker> after = byId(MapMarkerSource.build(inputs(closed), config(), NOW,
                MapMarkerSource.PlayerMode.SKIP_LOADED));
        check(after.keySet().stream().noneMatch(id -> id.startsWith(MapMarkerStyle.LIBRARY_PREFIX)),
                "library pins only during a map visit");
        check(MapMarkerSource.selectedLibrarySites(closed).isEmpty(), "no selected sites outside a visit");
        RelicLibraryVisit.clear();
    }

    private static RelicLibraryVisit libraryVisit() {
        List<RelicLibraryEntry> entries = List.of(
                entry("a", "Thor's Hammer", "Unbreaking", 100, 100),
                entry("a2", "Thor's Hammer", "Unbreaking", 100, 100),
                entry("b", "Unknown Thing", "", -500, 20),
                entry("c", "Wish Bone", "Fortune", null, null));
        RelicLibraryVisit visit = RelicLibraryVisit.start(entries, Map.of("a", "stack-a", "a2", "stack-a2", "b", "stack-b"));
        visit.selectIds(Set.of("a"));
        visit.beginMapVisit();
        return visit;
    }

    private static RelicLibraryEntry entry(String id, String title, String enchant, Integer x, Integer z) {
        return new RelicLibraryEntry(id, title, enchant, x, z, OVERWORLD, 0, 64, 0);
    }

    // ------------------------------------------------------------- style

    private static void styles() {
        MapMarker player = new MapMarker("player:Bob", 0, 0, 0, OVERWORLD, 0x00AA00, "Bob", "5m", 1);
        MapMarker snitch = new MapMarker("snitch:Bob@Vault", 0, 0, 0, OVERWORLD, 0xFF00AA00, "Snitch: Vault", "Bob (via Carol)", 1);
        MapMarker ping = new MapMarker("ping:p1", 0, 0, 0, OVERWORLD, 0xFF00FF00, "Ping: here", "Bob", 1);
        MapMarker pin = new MapMarker("wartable:pin", 0, 0, 0, OVERWORLD, 0xFFFFAA00, "Vault", "", 1);
        MapMarker place = new MapMarker("wartable-label:city", 0, 0, 0, OVERWORLD, 0xFF33CCFF, "Mt Augusta", "", 1);
        MapMarker sanctuary = new MapMarker("sanctuary:Haven@50,-80", 0, 0, 0, OVERWORLD,
                WarTableMapMarkers.SANCTUARY_COLOR, "Haven", "", 1);
        MapMarker relic = new MapMarker("relic:7:x@0", 0, 0, 0, OVERWORLD, RelicMapMath.COLOR, "Thor's Hammer", "Unbreaking", 1);
        MapMarker unknown = new MapMarker("relic:7:x@1", 0, 0, 0, OVERWORLD, RelicMapMath.COLOR, "Mystery", "1, 2", 1);
        MapMarker library = new MapMarker(MapMarkerStyle.LIBRARY_PREFIX + "100,100", 0, 0, 0, OVERWORLD,
                MapMarkerStyle.SELECTED_COLOR, "Pixie Dust", "Efficiency", 1);
        MapMarker libraryCross = new MapMarker(MapMarkerStyle.LIBRARY_PREFIX + "1,1", 0, 0, 0, OVERWORLD,
                RelicMapMath.COLOR, "Mystery", "1, 1", 1);

        MapMarkerStyle.Style p = MapMarkerStyle.of(player, true);
        check(p.shape() == MapMarkerStyle.Shape.CIRCLE && !p.selected(), "player circle, never selected");
        check(p.argb() == 0xFF00AA00, "style color is opaque");
        check(p.text("\n").equals("Bob\n5m"), "title then caption");
        check(MapMarkerStyle.of(snitch, false).shape() == MapMarkerStyle.Shape.DIAMOND, "snitch diamond");
        check(MapMarkerStyle.of(ping, false).shape() == MapMarkerStyle.Shape.DIAMOND, "ping diamond");
        for (MapMarker name : List.of(pin, place, sanctuary)) {
            MapMarkerStyle.Style s = MapMarkerStyle.of(name, true);
            check(s.shape() == MapMarkerStyle.Shape.NAME_DIAMOND, "name diamond " + name.id());
            check(s.caption().isEmpty() && s.text("\n").equals(name.title()), "one line " + name.id());
            check(!s.selected(), "names are never selected");
        }
        check(MapMarkerStyle.of(sanctuary, false).argb() == WarTableMapMarkers.SANCTUARY_COLOR, "sanctuary gold");
        MapMarkerStyle.Style r = MapMarkerStyle.of(relic, true);
        check(r.shape() == MapMarkerStyle.Shape.RELIC && r.relicTexture().equals("thors_hammer.png")
                && r.relicSize() == 32, "catalog relic texture");
        check(!r.selected(), "carried relics are never selected");
        check(MapMarkerStyle.of(unknown, false).shape() == MapMarkerStyle.Shape.CROSS, "unknown relic cross");
        MapMarkerStyle.Style lib = MapMarkerStyle.of(library, true);
        check(lib.shape() == MapMarkerStyle.Shape.RELIC && lib.selected()
                && lib.relicTexture().equals("angel_dust.png"), "selected library relic");
        check(!MapMarkerStyle.of(library, false).selected(), "unselected library relic");
        MapMarkerStyle.Style libCross = MapMarkerStyle.of(libraryCross, true);
        check(libCross.shape() == MapMarkerStyle.Shape.CROSS && libCross.selected(), "selected library cross");
        check("100,100".equals(MapMarkerStyle.librarySite(library)), "library site key");
        check(MapMarkerStyle.librarySite(relic) == null && MapMarkerStyle.librarySite(player) == null,
                "only library pins have a site");
    }

    private static void signatures() {
        MapMarker base = new MapMarker("player:Bob", 10.2, 64, -3.7, OVERWORLD, 0xFF00AA00, "Bob", "5m", 1);
        String sig = sig(base, false);
        check(sig.equals(sig(new MapMarker("player:Bob", 10.9, 64.5, -3.1, OVERWORLD, 0xFF00AA00, "Bob", "5m", 99), false)),
                "same block, same look, same signature");
        check(!sig.equals(sig(new MapMarker("player:Bob", 11.0, 64, -3.7, OVERWORLD, 0xFF00AA00, "Bob", "5m", 1), false)),
                "moved one block");
        check(!sig.equals(sig(new MapMarker("player:Bob", 10.2, 64, -3.7, NETHER, 0xFF00AA00, "Bob", "5m", 1), false)),
                "changed dimension");
        check(!sig.equals(sig(new MapMarker("player:Bob", 10.2, 64, -3.7, OVERWORLD, 0xFFAA0000, "Bob", "5m", 1), false)),
                "changed color");
        check(!sig.equals(sig(new MapMarker("player:Bob", 10.2, 64, -3.7, OVERWORLD, 0xFF00AA00, "Bob", "6m", 1), false)),
                "changed caption");
        MapMarker pin = new MapMarker(MapMarkerStyle.LIBRARY_PREFIX + "1,1", 1.5, 64, 1.5, OVERWORLD,
                RelicMapMath.COLOR, "Wish Bone", "Fortune", Long.MAX_VALUE);
        check(!sig(pin, false).equals(sig(pin, true)), "selection changes the signature");
    }

    private static String sig(MapMarker marker, boolean selected) {
        return MapMarkerStyle.signature(marker, MapMarkerStyle.of(marker, selected));
    }

    private static void icons() {
        MapIconPixels.Icon circle = MapIconPixels.circle();
        check(circle.width() == 11 && circle.height() == 11, "circle size");
        check(circle.at(5, 5) == MapIconPixels.INK && circle.at(5, 0) == MapIconPixels.OUTLINE, "circle ink and ring");
        check(circle.at(0, 0) == 0, "circle corner transparent");
        MapIconPixels.Icon diamond = MapIconPixels.diamond();
        check(diamond.width() == 13 && diamond.at(6, 5) == MapIconPixels.INK && diamond.at(0, 5) == MapIconPixels.OUTLINE,
                "diamond ink and outline");
        check(diamond.at(6, 0) == MapIconPixels.OUTLINE && diamond.at(0, 0) == 0, "diamond tip and corner");
        MapIconPixels.Icon name = MapIconPixels.nameDiamond();
        check(name.at(4, 4) == MapIconPixels.INK && name.at(4, 0) == MapIconPixels.NAME_BACKING, "name diamond");
        MapIconPixels.Icon cross = MapIconPixels.cross();
        check(cross.at(6, 6) == MapIconPixels.INK && cross.at(2, 2) == MapIconPixels.INK
                && cross.at(10, 2) == MapIconPixels.INK && cross.at(6, 0) == 0, "cross arms");

        int[] red = new int[32 * 32];
        java.util.Arrays.fill(red, 0xFFFF0000);
        MapIconPixels.Icon plain = MapIconPixels.relic(red, 32, false);
        MapIconPixels.Icon picked = MapIconPixels.relic(red, 32, true);
        check(plain.width() == MapIconPixels.RELIC_SIZE && picked.width() == MapIconPixels.RELIC_SIZE, "relic size");
        check(plain.at(11, 11) == 0xFFFF0000, "opaque art covers the backing");
        check(plain.at(2, 2) == MapIconPixels.RELIC_BACKING, "dark backing edge");
        check(plain.at(0, 0) == 0 && picked.at(0, 0) == MapMarkerStyle.SELECTED_FILL, "green only when selected");
        check(picked.at(2, 2) >>> 24 > MapIconPixels.RELIC_BACKING >>> 24, "backing over the green highlight");
        int[] checker = new int[32 * 32];
        for (int i = 0; i < checker.length; i++) checker[i] = ((i % 2) == 0) ? 0xFFFFFFFF : 0;
        int sampled = MapIconPixels.sample(checker, 32, 0, 0);
        check(sampled >>> 24 == 127 && (sampled & 0xFFFFFF) == 0xFFFFFF, "box filter averages alpha: " + Integer.toHexString(sampled));
    }

    // ------------------------------------------------------------ config

    private static void migration() {
        OIConfig absent = new OIConfig();
        absent.worldMapIntel = false;
        absent.worldMapRelics = false;
        absent.jmMarkers = false;
        absent.xaeroWorldMap = false;
        check(OIConfig.applyWorldMapDefaults(absent, new JsonObject()), "missing keys are filled");
        check(absent.worldMapIntel && absent.worldMapRelics && absent.jmMarkers && absent.xaeroWorldMap,
                "no old or new keys: everything on");

        OIConfig old = new OIConfig();
        old.xaeroMarkers = false;
        old.xaeroRelicMaps = true;
        JsonObject raw = new JsonObject();
        raw.addProperty("xaeroMarkers", false);
        raw.addProperty("xaeroRelicMaps", true);
        raw.addProperty("jmMarkers", false);
        old.jmMarkers = false;
        OIConfig.applyWorldMapDefaults(old, raw);
        check(!old.worldMapIntel, "old Xaero intel switch carries over");
        check(old.worldMapRelics, "old Xaero relic switch carries over");
        check(!old.jmMarkers, "saved JourneyMap switch kept");
        check(old.xaeroWorldMap, "new Xaero master defaults on");

        OIConfig current = new OIConfig();
        current.xaeroMarkers = false;
        current.worldMapIntel = true;
        current.xaeroRelicMaps = false;
        current.worldMapRelics = false;
        current.xaeroWorldMap = false;
        JsonObject saved = new JsonObject();
        saved.addProperty("xaeroMarkers", false);
        saved.addProperty("worldMapIntel", true);
        saved.addProperty("xaeroRelicMaps", false);
        saved.addProperty("worldMapRelics", false);
        saved.addProperty("jmMarkers", true);
        saved.addProperty("xaeroWorldMap", false);
        check(!OIConfig.applyWorldMapDefaults(current, saved), "complete config is left alone");
        check(current.worldMapIntel && !current.worldMapRelics && !current.xaeroWorldMap,
                "saved shared keys win over legacy ones");
    }

    private static void migrationFromFile() throws Exception {
        Path game = Files.createTempDirectory("openintel-map-config");
        Path configDir = game.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("openintel.json"), """
                { "xaeroMarkers": false, "xaeroRelicMaps": false, "pingSeconds": 12 }
                """);
        Object loader = FabricLoader.getInstance();
        Object previousGame = field(loader, "gameDir");
        Object previousConfig = field(loader, "configDir");
        setField(loader, "gameDir", game.toAbsolutePath().normalize());
        setField(loader, "configDir", configDir);
        try {
            OIConfig loaded = OIConfig.load();
            check(!loaded.worldMapIntel && !loaded.worldMapRelics, "legacy Xaero-off config keeps intel and relics off");
            check(loaded.jmMarkers && loaded.xaeroWorldMap, "master switches on");
            String written = Files.readString(configDir.resolve("openintel.json"));
            check(written.contains("\"worldMapIntel\": false") && written.contains("\"xaeroWorldMap\": true"),
                    "migration is saved");
        } finally {
            setField(loader, "gameDir", previousGame);
            setField(loader, "configDir", previousConfig);
            try (var paths = Files.walk(game)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    // ----------------------------------------------------- library input

    private static void libraryInput() {
        List<RelicLibraryEntry> entries = List.of(
                entry("near", "Wish Bone", "Fortune", 10, 10),
                entry("mid", "The Lie", "Smite", 100, 100),
                entry("far", "Gold Coin", "Looting", 400, 400));
        RelicLibraryVisit visit = RelicLibraryVisit.start(entries, Map.of("near", "s-near", "mid", "s-mid"));
        LibraryMapInput input = new LibraryMapInput();
        check(!input.press(visit, true, null, 0, 0, 5, 5), "no press outside a map visit");
        visit.beginMapVisit();
        check(!input.press(visit, false, null, 0, 0, 5, 5), "plain press off a pin is the map's");
        check(!input.release(visit, 0, 0, 5, 5, 36, 0, 0), "release without a press is the map's");

        // Plain click on a pin toggles that site.
        check(input.press(visit, false, "100,100", 100, 100, 50, 50), "press on a pin");
        check(input.selecting(), "press held");
        check(input.release(visit, 100, 100, 52, 51, 36, 0, 0), "release ends the press");
        check(visit.selectedIds().equals(Set.of("mid")), "click selects the site: " + visit.selectedIds());
        input.press(visit, false, "100,100", 100, 100, 50, 50);
        input.release(visit, 100, 100, 50, 50, 36, 0, 0);
        check(visit.selectedIds().isEmpty(), "second click deselects");

        // Shift-click on a pin with no drag is still a click.
        input.press(visit, true, "10,10", 10, 10, 20, 20);
        input.release(visit, 10, 10, 21, 20, 36, 0, 0);
        check(visit.selectedIds().equals(Set.of("near")) && !visit.isLassoDragging(), "shift-click toggles");
        visit.clearSelection();

        // Lasso over all three with room for two keeps the two nearest the player.
        check(input.press(visit, true, null, -50, -50, 0, 0), "shift starts a lasso");
        check(visit.isLassoDragging(), "lasso open");
        input.drag(visit, 200, 200, 40, 40);
        check(visit.pendingSelectionCount() == 2, "pending count follows the drag");
        input.release(visit, 500, 500, 80, 80, 2, 0, 0);
        check(!visit.isLassoDragging() && !input.selecting(), "lasso closed");
        check(visit.selectedIds().equals(Set.of("near", "mid")), "capacity keeps the nearest: " + visit.selectedIds());

        // A full inventory: a click cannot add, the release still trims the selection.
        check(!LibraryMapInput.click(visit, "400,400", 2, 0, 0), "click past the cap is refused");
        check(LibraryMapInput.click(visit, "100,100", 2, 0, 0) && visit.selectedIds().equals(Set.of("near")),
                "a full selection can still deselect: " + visit.selectedIds());
        visit.selectIds(Set.of("near", "mid"));
        check(LibraryMapInput.click(visit, "10,10", 1, 0, 0) && visit.selectedIds().isEmpty(),
                "the cap trims to the nearest before the click: " + visit.selectedIds());
        check(!LibraryMapInput.click(visit, null, 36, 0, 0), "null site ignored");

        // Show chests: the selection first, otherwise the pin's own copies.
        visit.selectIds(Set.of("mid"));
        check(LibraryMapInput.chestStacks(visit, "10,10").equals(List.of("s-mid")), "selection wins");
        visit.clearSelection();
        check(LibraryMapInput.chestStacks(visit, "10,10").equals(List.of("s-near")), "pin copies");
        check(LibraryMapInput.chestStacks(visit, null).isEmpty(), "carried relic without selection");

        visit.endMapVisit();
        check(!input.press(visit, false, "10,10", 10, 10, 0, 0), "visit over");
        RelicLibraryVisit.clear();
    }

    // ---------------------------------------------------------- adapters

    private static void adapterWiring() throws Exception {
        check(callsSource(readClass("dev/openintel/jm/JmBridge"), "SKIP_LOADED"),
                "JourneyMap reads MapMarkerSource with SKIP_LOADED");
        check(callsSource(readClass("dev/openintel/xaero/XaeroMarkerRenderer"), "LIVE_POSITIONS"),
                "Xaero reads MapMarkerSource with LIVE_POSITIONS");
        check(calls(readClass("dev/openintel/jm/JmBridge"), "dev/openintel/map/MapMarkerStyle", "of")
                        && calls(readClass("dev/openintel/xaero/XaeroMarkerRenderer"), "dev/openintel/map/MapMarkerStyle", "of"),
                "both maps style markers through MapMarkerStyle");
        check(calls(readClass("dev/openintel/mixin/GuiMapLibraryMixin"), "dev/openintel/relic/LibraryMapInput", "press")
                        && calls(readClass("dev/openintel/jm/JmLibraryMap"), "dev/openintel/relic/LibraryMapInput", "press"),
                "both maps share LibraryMapInput");

        List<String> neutral = new ArrayList<>(classesIn("dev/openintel/map"));
        check(neutral.contains("dev/openintel/map/MapMarkerSource"), "map package classes found: " + neutral);
        neutral.add("dev/openintel/relic/RelicMapBridge");
        neutral.add("dev/openintel/relic/LibraryMapInput");
        neutral.add("dev/openintel/relic/LibraryMapOverlay");
        for (String name : neutral) {
            for (String utf8 : constantStrings(name)) {
                check(!MAP_MOD_TYPE.matcher(utf8).find(), name + " references a map mod type: " + utf8);
            }
        }
        // The chooser loads each adapter only after its mod is present, Xaero first.
        ClassNode bridge = readClass("dev/openintel/relic/RelicMapBridge");
        check(calls(bridge, "dev/openintel/xaero/XaeroLibraryMap", "open")
                && calls(bridge, "dev/openintel/jm/JmLibraryMap", "open"), "library opens either map");
    }

    private static boolean callsSource(ClassNode node, String mode) {
        boolean all = false;
        boolean usesMode = false;
        for (var method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("dev/openintel/map/MapMarkerSource")
                        && call.name.equals("all")) all = true;
                if (insn instanceof FieldInsnNode field && field.owner.equals("dev/openintel/map/MapMarkerSource$PlayerMode")
                        && field.name.equals(mode)) usesMode = true;
            }
        }
        return all && usesMode;
    }

    private static boolean calls(ClassNode node, String owner, String name) {
        for (var method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
            }
        }
        return false;
    }

    private static ClassNode readClass(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = MapParityTest.class.getResourceAsStream("/" + name + ".class")) {
            check(input != null, "class packaged: " + name);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static List<String> classesIn(String pkg) throws URISyntaxException, java.io.IOException {
        var url = MapParityTest.class.getResource("/" + pkg + "/MapMarkerSource.class");
        check(url != null && url.getProtocol().equals("file"), "map package on a directory classpath");
        Path dir = Path.of(url.toURI()).getParent();
        List<String> names = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String f = file.getFileName().toString();
                if (f.endsWith(".class")) names.add(pkg + "/" + f.substring(0, f.length() - ".class".length()));
            }
        }
        return names;
    }

    /** Every CONSTANT_Utf8 in a class file: type names, descriptors, and string literals. */
    private static List<String> constantStrings(String name) throws Exception {
        byte[] bytes;
        try (InputStream input = MapParityTest.class.getResourceAsStream("/" + name + ".class")) {
            check(input != null, "class packaged: " + name);
            bytes = input.readAllBytes();
        }
        var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        in.skipBytes(8);
        int count = in.readUnsignedShort();
        List<String> strings = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> strings.add(in.readUTF());
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> { in.skipBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                default -> throw new AssertionError("constant pool tag " + tag + " in " + name);
            }
        }
        return strings;
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + name);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
