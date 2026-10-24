package dev.openintel.viz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;

/** Manual coverage fields and named visibility groups. Version 1 files remain readable. */
public final class RangeStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final RangeStore INSTANCE = new RangeStore();
    private List<RangeShape> shapes = new ArrayList<>();
    private List<RangeGroup> groups = new ArrayList<>();
    private final Deque<List<Deleted>> undo = new ArrayDeque<>();
    private final Path overridePath;

    private RangeStore() { this(null); }
    /** Isolated stores for headless persistence/interaction tests. */
    public RangeStore(Path path) { overridePath = path; }
    public static RangeStore get() { return INSTANCE; }
    private Path configPath() {
        return overridePath != null ? overridePath : FabricLoader.getInstance().getConfigDir().resolve("openintel-ranges.json");
    }
    public synchronized List<RangeShape> shapes() { return List.copyOf(shapes); }
    public synchronized List<RangeGroup> groups() { return List.copyOf(groups); }
    public synchronized int size() { return shapes.size(); }
    public synchronized RangeShape find(String id) {
        return shapes.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
    }
    public synchronized RangeGroup group(String id) {
        return groups.stream().filter(g -> g.id.equals(id)).findFirst().orElse(null);
    }
    public synchronized boolean isVisible(RangeShape s) {
        RangeGroup group = group(s.groupId);
        return s.enabled && (group == null || group.enabled);
    }
    /** Used by the world renderer so group flags affect actual coverage, not just the editor. */
    public synchronized List<RangeShape> visibleShapes() {
        Set<String> hidden = new HashSet<>();
        for (RangeGroup g : groups) if (!g.enabled) hidden.add(g.id);
        return shapes.stream().filter(s -> s.enabled && !hidden.contains(s.groupId)).toList();
    }
    public synchronized boolean add(RangeShape shape) {
        if (shape == null) return false;
        normalize(shape);
        if (find(shape.id) != null) shape.id = UUID.randomUUID().toString();
        if (group(shape.groupId) == null) shape.groupId = "";
        shapes.add(shape);
        save();
        return true;
    }
    public synchronized void mutate(String id, Consumer<RangeShape> operation) {
        mutateAll(Set.of(id), operation);
    }
    public synchronized void mutateAll(Set<String> ids, Consumer<RangeShape> operation) {
        for (RangeShape s : shapes) if (ids.contains(s.id)) {
            operation.accept(s);
            normalize(s);
            if (group(s.groupId) == null) s.groupId = "";
        }
        save();
    }
    public synchronized RangeGroup createGroup(String name) {
        String clean = cleanName(name);
        for (RangeGroup g : groups) if (g.name.equalsIgnoreCase(clean)) return g;
        RangeGroup group = new RangeGroup(clean);
        groups.add(group);
        save();
        return group;
    }
    public synchronized void renameGroup(String id, String name) {
        String clean = cleanName(name);
        if (groups.stream().anyMatch(g -> !g.id.equals(id) && g.name.equalsIgnoreCase(clean)))
            throw new IllegalArgumentException("A group with that name already exists.");
        RangeGroup group = group(id);
        if (group != null) { group.name = clean; save(); }
    }
    public synchronized void toggleGroup(String id) {
        RangeGroup group = group(id);
        if (group != null) { group.enabled = !group.enabled; save(); }
    }
    /** Removing a group keeps its entries in Ungrouped. */
    public synchronized void removeGroup(String id) {
        groups.removeIf(g -> g.id.equals(id));
        for (RangeShape s : shapes) if (s.groupId.equals(id)) s.groupId = "";
        save();
    }
    public synchronized boolean remove(String id) { return id != null && removeAll(Set.of(id)) > 0; }
    public synchronized int removeAll(Set<String> ids) {
        List<Deleted> deleted = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) if (ids.contains(shapes.get(i).id))
            deleted.add(new Deleted(i, copy(shapes.get(i))));
        if (deleted.isEmpty()) return 0;
        shapes.removeIf(s -> ids.contains(s.id));
        undo.addLast(deleted);
        while (undo.size() > 20) undo.removeFirst();
        save();
        return deleted.size();
    }
    public synchronized boolean canUndo() { return !undo.isEmpty(); }
    public synchronized List<String> undoDelete() {
        if (undo.isEmpty()) return List.of();
        List<String> restored = new ArrayList<>();
        for (Deleted d : undo.removeLast()) {
            RangeShape s = d.shape;
            if (find(s.id) != null) s.id = UUID.randomUUID().toString();
            if (group(s.groupId) == null) s.groupId = "";
            shapes.add(Math.min(d.index, shapes.size()), s);
            restored.add(s.id);
        }
        save();
        return restored;
    }
    public synchronized void clear() { shapes.clear(); groups.clear(); undo.clear(); save(); }
    public synchronized void load() {
        try {
            FileBody body = readBody(configPath(), false);
            shapes = body.shapes; groups = body.groups; undo.clear();
        } catch (IOException ignored) { }
    }
    public synchronized void save() {
        try { writeBody(configPath(), body(shapes, groups)); } catch (IOException ignored) { }
    }
    static List<RangeShape> readFile(Path path) {
        try { return readBody(path, false).shapes; } catch (IOException ex) { return new ArrayList<>(); }
    }
    static void writeFile(Path path, List<RangeShape> shapes) {
        try { writeBody(path, body(shapes, List.of())); } catch (IOException ignored) { }
    }
    public synchronized void exportFile(Path path) throws IOException { exportFile(path, (String) null); }
    /** null exports all; empty string exports Ungrouped; other IDs export that group only. */
    public synchronized void exportFile(Path path, String groupId) throws IOException {
        List<RangeShape> entries = groupId == null ? shapes : shapes.stream().filter(s -> s.groupId.equals(groupId)).toList();
        List<RangeGroup> layers = groupId == null ? groups : groups.stream().filter(g -> g.id.equals(groupId)).toList();
        exportBody(path, body(entries, layers));
    }
    static void exportFile(Path path, List<RangeShape> entries) throws IOException {
        exportBody(path, body(entries, List.of()));
    }
    private static void exportBody(Path path, FileBody body) throws IOException {
        Files.writeString(path, new Gson().toJson(body), StandardOpenOption.CREATE_NEW);
    }
    static List<RangeShape> readImport(Path path) throws IOException { return readBody(path, true).shapes; }
    public synchronized int importFile(Path path) throws IOException {
        FileBody incoming = readBody(path, true);
        List<RangeShape> next = new ArrayList<>(shapes);
        List<RangeGroup> layers = new ArrayList<>(groups);
        Map<String, String> remap = new HashMap<>();
        for (RangeGroup g : incoming.groups) {
            RangeGroup existing = layers.stream().filter(e -> e.name.equalsIgnoreCase(g.name)).findFirst().orElse(null);
            if (existing != null) { remap.put(g.id, existing.id); continue; }
            String old = g.id;
            if (layers.stream().anyMatch(e -> e.id.equals(g.id))) g.id = UUID.randomUUID().toString();
            layers.add(g); remap.put(old, g.id);
        }
        for (RangeShape s : incoming.shapes) s.groupId = remap.getOrDefault(s.groupId, "");
        int added = merge(next, incoming.shapes);
        writeBody(configPath(), body(next, layers));
        shapes = next; groups = layers;
        return added;
    }
    static int merge(List<RangeShape> existing, List<RangeShape> incoming) {
        Set<String> keys = new HashSet<>(), ids = new HashSet<>();
        for (RangeShape s : existing) { keys.add(locationKey(s)); ids.add(s.id); }
        int added = 0;
        for (RangeShape s : incoming) {
            if (!keys.add(locationKey(s))) continue;
            if (!ids.add(s.id)) { s.id = UUID.randomUUID().toString(); ids.add(s.id); }
            existing.add(s); added++;
        }
        return added;
    }
    private static String locationKey(RangeShape s) {
        return s.type + "|" + s.dimension + "|" + s.x + "|" + s.y + "|" + s.z
                + "|" + (s.type == RangeType.SNITCH ? s.snitchKind : "")
                + "|" + (s.type == RangeType.SANCTUARY ? s.sanctuaryRadius : "");
    }
    private static FileBody readBody(Path path, boolean strict) throws IOException {
        if (!strict && (path == null || !Files.exists(path))) return new FileBody();
        try {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!root.has("shapes") || !root.get("shapes").isJsonArray()) throw new IllegalArgumentException();
            int version = root.has("version") ? root.get("version").getAsBigDecimal().intValueExact() : 1;
            if (version < 1 || version > 2) throw new IllegalArgumentException();
            if (strict) for (var item : root.getAsJsonArray("shapes")) {
                var entry = item.getAsJsonObject();
                for (String key : List.of("type", "x", "y", "z", "dimension"))
                    if (!entry.has(key) || entry.get(key).isJsonNull()) throw new IllegalArgumentException();
                for (String key : List.of("x", "y", "z")) entry.get(key).getAsBigDecimal().intValueExact();
            }
            FileBody body = GSON.fromJson(root, FileBody.class);
            if (body.groups == null) body.groups = new ArrayList<>();
            Set<String> groupIds = new HashSet<>(), names = new HashSet<>();
            for (RangeGroup g : body.groups) {
                if (g == null || g.id == null || g.id.isBlank() || !groupIds.add(g.id)) throw new IllegalArgumentException();
                g.name = cleanName(g.name);
                if (!names.add(g.name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            }
            List<RangeShape> normalized = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (RangeShape s : body.shapes) {
                if (s == null) { if (strict) throw new IllegalArgumentException(); else continue; }
                if (strict && (s.type == null || s.dimension == null || !s.dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                        || !Double.isFinite(s.sanctuaryRadius) || s.sanctuaryRadius < 1 || s.sanctuaryRadius > 512
                        || s.snitchKind == null)) throw new IllegalArgumentException();
                normalize(s);
                if (!s.groupId.isEmpty() && !groupIds.contains(s.groupId)) {
                    if (strict) throw new IllegalArgumentException();
                    s.groupId = "";
                }
                if (!ids.add(s.id)) { s.id = UUID.randomUUID().toString(); ids.add(s.id); }
                normalized.add(s);
            }
            body.shapes = normalized;
            return body;
        } catch (RuntimeException ex) { throw new IOException("Invalid coverage JSON or unsupported version.", ex); }
    }
    private static FileBody body(List<RangeShape> shapes, List<RangeGroup> groups) {
        FileBody body = new FileBody();
        body.shapes = new ArrayList<>(shapes); body.groups = new ArrayList<>(groups);
        return body;
    }
    private static void writeBody(Path path, FileBody body) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "openintel-ranges-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(body));
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String cleanName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 80 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a group name of 1–80 characters.");
        return name.trim();
    }
    private static void normalize(RangeShape s) {
        if (s.id == null || s.id.isBlank()) s.id = UUID.randomUUID().toString();
        if (s.type == null) s.type = RangeType.SNITCH;
        if (s.dimension == null || s.dimension.isBlank()) s.dimension = "minecraft:overworld";
        if (!(s.sanctuaryRadius > 0) || !Double.isFinite(s.sanctuaryRadius)) s.sanctuaryRadius = 50;
        if (s.snitchKind == null) s.snitchKind = SnitchKind.NOTIFIER;
        if (s.groupId == null) s.groupId = "";
    }
    private static RangeShape copy(RangeShape s) { return GSON.fromJson(GSON.toJson(s), RangeShape.class); }
    public synchronized void compact() { shapes.removeIf(s -> s == null || s.id == null); }
    private record Deleted(int index, RangeShape shape) { }
    private static final class FileBody {
        int version = 2;
        List<RangeShape> shapes = new ArrayList<>();
        List<RangeGroup> groups = new ArrayList<>();
    }
}
