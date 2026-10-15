package dev.openintel.viz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Persisted manual coverage shapes in config/openintel-ranges.json. */
public final class RangeStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_SHAPES = 256;

    private static final RangeStore INSTANCE = new RangeStore();

    @SerializedName("shapes")
    private List<RangeShape> shapes = new ArrayList<>();

    private RangeStore() { }

    public static RangeStore get() { return INSTANCE; }

    public synchronized List<RangeShape> shapes() {
        return List.copyOf(shapes);
    }

    public synchronized int size() { return shapes.size(); }

    public synchronized boolean add(RangeShape shape) {
        if (shape == null || shapes.size() >= MAX_SHAPES) return false;
        normalize(shape);
        shapes.add(shape);
        save();
        return true;
    }

    public synchronized boolean remove(String id) {
        if (id == null) return false;
        boolean removed = shapes.removeIf(s -> id.equals(s.id));
        if (removed) save();
        return removed;
    }

    public synchronized RangeShape find(String id) {
        if (id == null) return null;
        for (RangeShape s : shapes) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    public synchronized void mutate(String id, java.util.function.Consumer<RangeShape> op) {
        RangeShape s = find(id);
        if (s == null) return;
        op.accept(s);
        save();
    }

    public synchronized void clear() {
        shapes.clear();
        save();
    }

    public synchronized void load() {
        shapes = readFile(configPath());
    }

    public synchronized void save() {
        writeFile(configPath(), shapes);
    }

    /** JSON round-trip used by {@link #load()} / {@link #save()}. Headless tests pass their own path. */
    static List<RangeShape> readFile(Path path) {
        try {
            if (path == null || !Files.exists(path)) return new ArrayList<>();
            FileBody loaded = GSON.fromJson(Files.readString(path), FileBody.class);
            if (loaded == null || loaded.shapes == null) return new ArrayList<>();
            List<RangeShape> next = new ArrayList<>();
            for (RangeShape s : loaded.shapes) {
                if (s == null) continue;
                normalize(s);
                next.add(s);
                if (next.size() >= MAX_SHAPES) break;
            }
            return next;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static void writeFile(Path path, List<RangeShape> shapes) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            FileBody body = new FileBody();
            body.shapes = new ArrayList<>();
            if (shapes != null) {
                for (RangeShape s : shapes) {
                    if (s != null) body.shapes.add(s);
                }
            }
            Files.writeString(path, GSON.toJson(body));
        } catch (IOException ignored) {
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("openintel-ranges.json");
    }

    private static void normalize(RangeShape s) {
        if (s.id == null || s.id.isBlank()) s.id = java.util.UUID.randomUUID().toString();
        if (s.type == null) s.type = RangeType.SNITCH;
        if (s.dimension == null || s.dimension.isBlank()) s.dimension = "minecraft:overworld";
        if (!(s.sanctuaryRadius > 0) || !Double.isFinite(s.sanctuaryRadius)) s.sanctuaryRadius = 50;
        if (s.snitchKind == null) s.snitchKind = SnitchKind.NOTIFIER;
    }

    /** Gson file body. Kept separate so the singleton is not what gets written. */
    private static final class FileBody {
        @SerializedName("shapes")
        List<RangeShape> shapes = new ArrayList<>();
    }

    /** Drop shapes with blank ids after a failed edit; keeps the list tidy. */
    public synchronized void compact() {
        Iterator<RangeShape> it = shapes.iterator();
        while (it.hasNext()) {
            RangeShape s = it.next();
            if (s == null || s.id == null) it.remove();
        }
    }
}
