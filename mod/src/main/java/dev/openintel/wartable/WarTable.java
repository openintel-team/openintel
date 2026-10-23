package dev.openintel.wartable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.render.EventFeed;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * War Table state: the shared annotation layer drawn over the server's
 * rendered map image. Strokes are authored by relay admins, stored on the
 * relay, and replayed to every client.
 *
 * Wire protocol (all inside {"type":"wartable", ...}):
 *   client -> relay   { action:"add",    stroke:{...} }
 *                     { action:"delete", id }
 *                     { action:"clear",  scope:"mine"|"all" }
 *   relay -> clients  same shapes, plus { from, t } stamped by the relay.
 *   full sync:        {"type":"wartable_sync", strokes:[...]}
 *
 * The relay is authoritative for the shared layer: a sync replaces cached
 * shared strokes entirely. Offline annotations live in a separate local layer
 * and survive syncs without ever being submitted to the relay.
 */
public final class WarTable {
    private WarTable() { }

    private static final Gson GSON = new Gson();

    /** Ordered by insert time; the relay preserves this order. */
    private static final Map<String, Stroke> strokes = new LinkedHashMap<>();
    private static final Map<String, String> strokeSources = new LinkedHashMap<>();

    private static String sourceKey(String source, String id) {
        return dev.openintel.net.RelaySource.LEGACY.equals(source) ? id : source.length() + ":" + source + ":" + id;
    }

    private static String strokeSource(String key) {
        return strokeSources.getOrDefault(key, dev.openintel.net.RelaySource.LEGACY);
    }

    /** Remove only received annotations from one relay; offline annotations survive. */
    public static void clearRelay(String source) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) { client.execute(() -> clearRelay(source)); return; }
        String normalized = dev.openintel.net.RelaySource.normalize(source);
        strokes.keySet().removeIf(key -> normalized.equals(strokeSource(key)));
        strokeSources.keySet().retainAll(strokes.keySet());
        revision++;
        persistShared();
    }

    public static void receive(String source, JsonObject msg) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.level;
            client.execute(() -> { if (client.level == world) receive(source, copy); });
            return;
        }
        dev.openintel.net.RelaySource.run(source, () -> receive(msg));
    }

    public static void applySync(String source, JsonObject msg) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.level;
            client.execute(() -> { if (client.level == world) applySync(source, copy); });
            return;
        }
        dev.openintel.net.RelaySource.run(source, () -> applySync(msg));
    }
    /** Local-only annotations made while no authenticated relay session exists. */
    private static final Map<String, Stroke> local = new LinkedHashMap<>();
    /** ids we added locally that the relay hasn't echoed yet. */
    private static final Map<String, Stroke> pending = new LinkedHashMap<>();
    /** this session's own stroke ids, for undo (newest last). */
    private static final Deque<String> ownIds = new ArrayDeque<>();
    private static volatile int revision;          // bump → repaint

    public static Collection<Stroke> strokes() {
        // Local and pending strokes render immediately; the shared map remains
        // authoritative once a sync arrives.
        Map<String, Stroke> all = new LinkedHashMap<>();
        // Mirrored wire ids draw once, but every source remains independently removable.
        strokes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Stroke stroke = entry.getValue();
            all.merge(stroke.id, stroke, (a, b) -> a.t >= b.t ? a : b);
        });
        all.putAll(local);
        all.putAll(pending);
        return all.values();
    }

    /** Pins eligible for in-world markers: live shared pins, or local pins offline. */
    public static Collection<Stroke> waypointStrokes() {
        return live() ? strokes() : local.values();
    }

    public static int size() { return strokes().size(); }
    public static int revision() { return revision; }

    /** Relay admins share edits; without an authenticated session the table is local-only. */
    public static boolean canDraw() {
        var snap = OpenIntelApi.relay().snapshot();
        return !snap.authenticated()
                || snap.role().map(r -> r.equalsIgnoreCase("admin")).orElse(false);
    }

    public static boolean live() {
        return OpenIntelApi.relay().snapshot().authenticated();
    }

    // ----------------------------------------------------------- outbound ---

    /** Locally committed stroke — show now, then push to the relay. */
    public static boolean add(Stroke.Tool tool, int color, float width,
                              float[] points, String label) {
        if (!canDraw()) return false;
        Minecraft client = Minecraft.getInstance();
        String author = client.player != null ? client.player.getGameProfile().name() : "?";
        String id = Long.toString(System.currentTimeMillis(), 36) + "-"
                + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(), 36);

        Stroke s = new Stroke(id, tool, color, width, points, label, author, 0);
        if (!Stroke.validate(s)) return false;

        if (live()) {
            pending.put(id, s);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "wartable");
            msg.addProperty("action", "add");
            msg.add("stroke", s.toJson());
            OpenIntelClient.relay().send(msg);
        } else {
            local.put(id, s);
            persistLocal();
        }
        ownIds.addLast(id);
        revision++;
        return true;
    }

    public static void delete(String id) {
        if (id == null || !canDraw()) return;
        boolean localRemoved = local.remove(id) != null;
        boolean sharedRemoved = strokes.entrySet().removeIf(e -> e.getValue().id.equals(id));
        strokeSources.keySet().retainAll(strokes.keySet());
        sharedRemoved |= pending.remove(id) != null;
        ownIds.remove(id);
        revision++;
        if (localRemoved) persistLocal();
        if (sharedRemoved) persistShared();
        if (!live() || !sharedRemoved) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "wartable");
        msg.addProperty("action", "delete");
        msg.addProperty("id", id);
        OpenIntelClient.relay().send(msg);
    }

    /** Remove the newest stroke drawn by this session. */
    public static boolean undoOwn() {
        while (!ownIds.isEmpty()) {
            String id = ownIds.pollLast();
            if (strokes.values().stream().anyMatch(s -> s.id.equals(id)) || local.containsKey(id) || pending.containsKey(id)) {
                delete(id);
                return true;
            }
        }
        return false;
    }

    public static void clearAll() {
        if (!canDraw()) return;
        strokes.clear();
        strokeSources.clear();
        local.clear();
        pending.clear();
        ownIds.clear();
        revision++;
        persist();
        if (!live()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "wartable");
        msg.addProperty("action", "clear");
        msg.addProperty("scope", "all");
        OpenIntelClient.relay().send(msg);
    }

    // ------------------------------------------------------------ inbound ---

    /** Relay op: add / delete / clear. */
    public static void receive(JsonObject msg) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.level;
            String source = dev.openintel.net.RelaySource.current();
            client.execute(() -> { if (client.level == world) receive(source, copy); });
            return;
        }
        String action = msg.has("action") ? msg.get("action").getAsString() : "";
        String from = msg.has("from") ? msg.get("from").getAsString() : "?";
        switch (action) {
            case "add" -> {
                Stroke s = Stroke.fromJson(msg.getAsJsonObject("stroke"));
                if (s == null) return;
                pending.remove(s.id);
                String key = sourceKey(dev.openintel.net.RelaySource.current(), s.id);
                strokeSources.put(key, dev.openintel.net.RelaySource.current());
                strokes.put(key, s);   // put replaces → edits dedupe by id
                revision++;
            }
            case "delete" -> {
                String id = msg.has("id") ? msg.get("id").getAsString() : null;
                if (id == null) return;
                String key = sourceKey(dev.openintel.net.RelaySource.current(), id);
                boolean removed = strokes.remove(key) != null;
                strokeSources.remove(key);
                if (removed) revision++;
            }
            case "clear" -> {
                String scope = msg.has("scope") ? msg.get("scope").getAsString() : "all";
                if ("mine".equals(scope)) {
                    strokes.entrySet().removeIf(e -> dev.openintel.net.RelaySource.current().equals(strokeSource(e.getKey()))
                            && e.getValue().author != null && e.getValue().author.equalsIgnoreCase(from));
                    strokeSources.keySet().retainAll(strokes.keySet());
                } else {
                    clearRelay(dev.openintel.net.RelaySource.current());
                }
                ownIds.removeIf(id -> !local.containsKey(id));
                revision++;
                String self = client.player != null
                        ? client.player.getGameProfile().name().toLowerCase(Locale.ROOT) : "";
                if (!from.equalsIgnoreCase(self)) {
                    EventFeed.addRelay(from + " cleared the war table", 0xFFFFAA00);
                }
            }
            default -> { }
        }
    }

    /** Full authoritative set — replaces shared state on (re)auth; local edits survive. */
    public static void applySync(JsonObject msg) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.level;
            String source = dev.openintel.net.RelaySource.current();
            client.execute(() -> { if (client.level == world) applySync(source, copy); });
            return;
        }
        String source = dev.openintel.net.RelaySource.current();
        clearRelay(source);
        JsonArray arr = msg.getAsJsonArray("strokes");
        if (arr != null) {
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Stroke s = Stroke.fromJson(e.getAsJsonObject());
                if (s != null) {
                    String key = sourceKey(source, s.id);
                    strokes.put(key, s);
                    strokeSources.put(key, source);
                    pending.remove(s.id);
                }
            }
        }
        // Pending outbound edits have no single receiving source; do not let a
        // different relay's snapshot erase them. Echoed ids are acknowledged above.
        ownIds.removeIf(id -> !local.containsKey(id));
        revision++;
        persist();
    }

    // ---------------------------------------------------------- eraser -----

    /** Nearest stroke to a world point within `tol` blocks, or null. */
    public static Stroke nearStroke(double wx, double wz, double tol) {
        Stroke best = null;
        double bestD = tol;
        for (Stroke s : strokes()) {
            double d = s.distanceTo(wx, wz);
            if (d < bestD) { best = s; bestD = d; }
        }
        return best;
    }

    // ------------------------------------------------------- persistence ----

    /** Last-known shared strokes plus this client's local annotations. */
    public static void persist() {
        persistShared();
        persistLocal();
    }

    private static void persistShared() {
        Path path = configFile("wartable-strokes.json");
        if (path == null) return;
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (var entry : strokes.entrySet()) {
                JsonObject json = entry.getValue().toJson();
                json.addProperty("relaySource", strokeSource(entry.getKey()));
                arr.add(json);
            }
            for (Stroke s : pending.values()) arr.add(s.toJson());
            root.add("strokes", arr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception ignored) { }
    }

    private static void persistLocal() {
        Path path = configFile("wartable-local-strokes.json");
        if (path == null) return;
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Stroke s : local.values()) arr.add(s.toJson());
            root.add("strokes", arr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception ignored) { }
    }

    private static Path configFile(String name) {
        try {
            Path config = FabricLoader.getInstance().getConfigDir();
            return config == null ? null : config.resolve("openintel").resolve(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void load() {
        strokes.clear();
        strokeSources.clear();
        local.clear();
        pending.clear();
        loadInto(configFile("wartable-strokes.json"), strokes);
        loadInto(configFile("wartable-local-strokes.json"), local);
        revision++;
    }

    private static void loadInto(Path path, Map<String, Stroke> target) {
        if (path == null) return;
        try {
            if (!Files.exists(path)) return;
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("strokes");
            if (arr == null) return;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Stroke s = Stroke.fromJson(e.getAsJsonObject());
                if (s != null) {
                    String source = e.getAsJsonObject().has("relaySource")
                            ? e.getAsJsonObject().get("relaySource").getAsString() : dev.openintel.net.RelaySource.LEGACY;
                    String key = target == strokes ? sourceKey(source, s.id) : s.id;
                    target.putIfAbsent(key, s);
                    if (target == strokes) strokeSources.put(key, source);
                }
            }
        } catch (Exception ignored) { }
    }

    /** Session teardown — own-undo bookkeeping is per-session. */
    public static void reset() {
        ownIds.clear();
        pending.clear();
        persist();
    }
}
