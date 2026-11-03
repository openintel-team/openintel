package dev.openintel.map;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.ping.PingManager;
import dev.openintel.relic.RelicLibraryEntry;
import dev.openintel.relic.RelicLibraryModel;
import dev.openintel.relic.RelicLibraryVisit;
import dev.openintel.tracker.Tracker;
import dev.openintel.wartable.Sanctuaries;
import dev.openintel.wartable.Stroke;
import dev.openintel.wartable.WarTable;
import dev.openintel.xaero.RelicMapMath;
import dev.openintel.xaero.RelicMapScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * The one list of fullscreen world-map markers. JourneyMap and Xaero both read
 * it, so intel, relics, library pins, and War Table names match on both maps.
 * The list spans every dimension; a map shows the entries for the dimension it
 * is viewing.
 */
public final class MapMarkerSource {
    public enum PlayerMode {
        /** A loaded player's live position replaces the relay report (Xaero). */
        LIVE_POSITIONS,
        /** Loaded players are left out because the map's own radar already draws them (JourneyMap). */
        SKIP_LOADED
    }

    public record PlayerReport(String name, double x, double y, double z, String dimension,
                               long lastSeen, String reporter) { }

    public record SnitchReport(String snitch, String player, String reporter,
                               double x, double y, double z, String dimension, long t) { }

    public record PingReport(String id, String label, int color, String sender,
                             double x, double y, double z, String dimension, long expiresAt) { }

    /**
     * Everything the list is built from. {@link #all(Minecraft, OIConfig, long, PlayerMode)}
     * reads these from the running client; tests supply them directly.
     *
     * @param self             local player name, or null
     * @param currentDimension dimension the local player is in, or null
     * @param selfPosition     local player position (x, y, z), or null
     * @param loaded           loaded player entities by name (any case) with their interpolated positions
     */
    public record Inputs(String self, String currentDimension, double[] selfPosition,
                         Map<String, double[]> loaded,
                         List<PlayerReport> players, List<SnitchReport> snitches, List<PingReport> pings,
                         List<MapMarker> relics, RelicLibraryVisit visit,
                         Iterable<Stroke> strokes, Iterable<Sanctuaries.Sanctuary> sanctuaries,
                         ToIntFunction<String> allegianceColor) { }

    private MapMarkerSource() { }

    /** Every drawable marker for the running client. Shared toggles in {@code cfg} gate each group. */
    public static List<MapMarker> all(Minecraft client, OIConfig cfg, long now, PlayerMode mode) {
        if (client == null || client.level == null || cfg == null) {
            RelicMapScanner.clear();
            return List.of();
        }
        return build(inputs(client, cfg), cfg, now, mode);
    }

    /** Pure core of {@link #all}: no client state is read here. */
    public static List<MapMarker> build(Inputs in, OIConfig cfg, long now, PlayerMode mode) {
        List<MapMarker> result = new ArrayList<>();
        if (cfg.worldMapIntel) {
            addPlayers(result, in, now, mode, cfg.staleAfterMs);
            for (SnitchReport h : in.snitches()) {
                add(result, now, new MapMarker("snitch:" + h.player() + "@" + h.snitch(),
                        h.x(), h.y(), h.z(), h.dimension(),
                        in.allegianceColor().applyAsInt(h.player()) | 0xFF000000,
                        "Snitch: " + h.snitch(), h.player() + " (via " + h.reporter() + ")",
                        h.t() + cfg.snitchMarkerSeconds * 1000L));
            }
            for (PingReport p : in.pings()) {
                add(result, now, new MapMarker("ping:" + p.id(), p.x(), p.y(), p.z(), p.dimension(),
                        p.color() | 0xFF000000, "Ping: " + p.label(), p.sender(), p.expiresAt()));
            }
        }
        if (cfg.worldMapRelics) {
            for (MapMarker marker : in.relics()) add(result, now, marker);
        }
        addLibraryRelics(result, in.visit(), now);
        if (cfg.warTablePinWorldMap || cfg.warTableLabelWorldMap || cfg.warTableSanctuaryWorldMap) {
            List<MapMarker> names = new ArrayList<>();
            WarTableMapMarkers.add(names, in.strokes(),
                    cfg.warTableSanctuaryWorldMap ? in.sanctuaries() : List.of(),
                    cfg.warTablePinWorldMap, cfg.warTableLabelWorldMap);
            for (MapMarker marker : names) add(result, now, marker);
        }
        return List.copyOf(result);
    }

    /** Site keys of the selected, located library maps. Empty outside a map visit. */
    public static Set<String> selectedLibrarySites(RelicLibraryVisit visit) {
        if (visit == null || !visit.isMapVisit()) return Set.of();
        Set<String> sites = new HashSet<>();
        for (RelicLibraryEntry entry : visit.selectedEntries()) {
            if (entry.located()) sites.add(entry.siteKey());
        }
        return sites;
    }

    private static void add(List<MapMarker> result, long now, MapMarker marker) {
        if (marker != null && marker.drawable(now)) result.add(marker);
    }

    /** Relay positions. A loaded player's live position wins in the dimension the local player is in. */
    private static void addPlayers(List<MapMarker> result, Inputs in, long now, PlayerMode mode, int staleAfterMs) {
        String current = in.currentDimension();
        double[] selfPos = in.selfPosition();
        for (PlayerReport player : in.players()) {
            if (player.name() == null || player.name().equalsIgnoreCase(in.self())) continue;
            double[] live = in.loaded().get(player.name());
            if (mode == PlayerMode.SKIP_LOADED && live != null) continue;
            boolean here = current != null && current.equals(player.dimension());
            double x = player.x();
            double y = player.y();
            double z = player.z();
            if (here && live != null) {
                x = live[0];
                y = live[1];
                z = live[2];
            }
            String via = player.reporter() == null || player.reporter().isBlank() ? "" : "via " + player.reporter();
            String label = via;
            if (here && selfPos != null) {
                long meters = Math.round(Math.hypot(selfPos[0] - x, selfPos[2] - z));
                label = via.isEmpty() ? meters + "m" : meters + "m " + via;
            }
            int color = in.allegianceColor().applyAsInt(player.name()) | 0xFF000000;
            add(result, now, new MapMarker("player:" + player.name(), x, y, z, player.dimension(),
                    color, player.name(), label, player.lastSeen() + staleAfterMs));
        }
    }

    /**
     * Located Chest Tracker relics only while the map was opened from the relic
     * library (map visit). Normal world-map opens stay clear of the library.
     * Sites are Overworld coordinates regardless of which memory key held the map.
     */
    private static void addLibraryRelics(List<MapMarker> result, RelicLibraryVisit visit, long now) {
        if (visit == null || !visit.isMapVisit() || visit.entries().isEmpty()) return;
        Set<String> selectedSites = selectedLibrarySites(visit);
        for (RelicLibraryEntry pin : RelicLibraryModel.sitePins(visit.entries())) {
            if (!pin.located()) continue;
            boolean selected = selectedSites.contains(pin.siteKey());
            int color = selected ? MapMarkerStyle.SELECTED_COLOR : RelicMapMath.COLOR;
            String subtitle = pin.enchantment().isEmpty()
                    ? pin.siteX() + ", " + pin.siteZ()
                    : pin.enchantment();
            add(result, now, new MapMarker(
                    MapMarkerStyle.LIBRARY_PREFIX + pin.siteKey(),
                    pin.siteX() + 0.5, RelicMapMath.MARKER_Y, pin.siteZ() + 0.5,
                    RelicMapMath.OVERWORLD,
                    color, pin.title(), subtitle, Long.MAX_VALUE));
        }
    }

    private static Inputs inputs(Minecraft client, OIConfig cfg) {
        String self = client.player == null ? null : client.player.getGameProfile().name();
        String current = client.level.dimension().identifier().toString();
        double[] selfPos = null;
        if (client.player != null) {
            Vec3 pos = client.player.position();
            selfPos = new double[]{pos.x, pos.y, pos.z};
        }
        Map<String, double[]> loaded = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<PlayerReport> players = new ArrayList<>();
        List<SnitchReport> snitches = new ArrayList<>();
        List<PingReport> pings = new ArrayList<>();
        Tracker tracker = OpenIntelClient.tracker();
        if (cfg.worldMapIntel && tracker != null) {
            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            for (AbstractClientPlayer entity : client.level.players()) {
                Vec3 live = entity.getPosition(tickDelta);
                loaded.put(entity.getGameProfile().name(), new double[]{live.x, live.y, live.z});
            }
            for (Tracker.RemotePlayer p : tracker.all()) {
                players.add(new PlayerReport(p.name, p.x, p.y, p.z, p.dimension, p.lastSeen, p.reporter));
            }
            for (Tracker.SnitchHit h : tracker.snitchHits()) {
                snitches.add(new SnitchReport(h.snitch, h.player, h.reporter, h.x, h.y, h.z, h.dimension, h.t));
            }
            for (PingManager.Ping p : PingManager.active()) {
                pings.add(new PingReport(p.id, p.label, p.color, p.sender, p.x, p.y, p.z, p.dimension, p.expiresAt));
            }
        }
        List<MapMarker> relics = cfg.worldMapRelics ? RelicMapScanner.markers(client) : List.of();
        boolean names = cfg.warTablePinWorldMap || cfg.warTableLabelWorldMap || cfg.warTableSanctuaryWorldMap;
        if (cfg.warTableSanctuaryWorldMap) Sanctuaries.ensureLoaded();
        return new Inputs(self, current, selfPos, loaded, players, snitches, pings, relics,
                RelicLibraryVisit.current(),
                names ? WarTable.waypointStrokes() : List.of(),
                cfg.warTableSanctuaryWorldMap ? Sanctuaries.all() : List.of(),
                name -> OpenIntelClient.allegiances().of(name).argb);
    }
}
