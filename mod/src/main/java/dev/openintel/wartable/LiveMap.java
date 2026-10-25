package dev.openintel.wartable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.OpenIntelClient;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the server's live-map renders and slices them into on-disk tile
 * pyramids under config/openintel/livemap/.
 *
 * The API serves whole-map PNGs (one per renderer) plus a JSON metadata file
 * with world bounds — see MapProjection. Tiles are 256px; level L covers
 * 2^L image pixels per tile pixel, so zooming out just climbs the pyramid.
 *
 * Each renderer gets its own persistent pyramid, so switching between the
 * site's renderers does not poison another layer's tiles or require a fresh
 * download when switching back. Joining a world asks for the current render.
 * A saved pyramid is drawn only after the server confirms that file is still
 * current; a newer render is built before it replaces the one on screen.
 * The live map re-renders roughly hourly, and the previous image is hidden
 * until that check finishes.
 */
public final class LiveMap {
    private LiveMap() { }

    public static final int TILE = LiveMapCache.TILE;
    private static final long REFRESH_MS = 55 * 60 * 1000;
    private static final long FAILURE_RETRY_MS = 30 * 1000;
    private static final long RENDERER_REFRESH_MS = 5 * 60 * 1000;
    private static final List<String> FALLBACK_RENDERERS =
            List.of("hillshade", "color", "shaded", "contour");

    public enum State { EMPTY, LOADING, READY, FAILED }

    private record Entry(State state, MapProjection projection, int maxLevel,
                         long fetchedAt, long attemptAt, String error) {
        static Entry empty() { return new Entry(State.EMPTY, null, 0, 0, 0, null); }
        static Entry loading(Entry previous) {
            return new Entry(State.LOADING,
                    previous != null ? previous.projection : null,
                    previous != null ? previous.maxLevel : 0,
                    previous != null ? previous.fetchedAt : 0,
                    System.currentTimeMillis(),
                    null);
        }
        LiveMapCache.Freshness freshness() {
            return switch (state) {
                case EMPTY -> LiveMapCache.Freshness.EMPTY;
                case LOADING -> LiveMapCache.Freshness.LOADING;
                case READY -> LiveMapCache.Freshness.READY;
                case FAILED -> LiveMapCache.Freshness.FAILED;
            };
        }
    }

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir()
            .resolve("openintel").resolve("livemap");
    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean rendererLoading = new AtomicBoolean();

    private static volatile List<String> renderers = FALLBACK_RENDERERS;
    private static volatile String renderer;
    private static volatile long renderersFetchedAt;

    private static Entry entry() { return entry(renderer()); }

    private static Entry entry(String name) {
        return entries.getOrDefault(safeRenderer(name), Entry.empty());
    }

    public static State state() { return entry().state(); }
    public static MapProjection projection() { return entry().projection(); }
    public static int maxLevel() { return entry().maxLevel(); }
    public static String error() { return entry().error(); }
    public static long fetchedAt() { return entry().fetchedAt(); }
    public static String renderer() {
        String current = renderer;
        if (current != null) return current;
        var cfg = OpenIntelClient.config();
        return renderer = safeRenderer(cfg != null && cfg.warTableRenderer != null
                ? cfg.warTableRenderer : "hillshade");
    }

    public static List<String> renderers() { return renderers; }

    /** Switch the rendered base map; its tile cache loads lazily. */
    public static void selectRenderer(String name) {
        String next = safeRenderer(name);
        if (next.isEmpty()) return;
        renderer = next;
        var cfg = OpenIntelClient.config();
        if (cfg != null) {
            cfg.warTableRenderer = next;
            cfg.save();
        }
        ensureLoaded(next);
    }

    /** Cycle through the API's renderer list. */
    public static void cycleRenderer(int direction) {
        List<String> list = renderers();
        if (list.isEmpty()) return;
        int index = list.indexOf(renderer());
        if (index < 0) index = 0;
        int next = Math.floorMod(index + direction, list.size());
        selectRenderer(list.get(next));
    }

    /** Map px covered by one tile pixel at this level (2^level). */
    public static double levelScale(int level) { return 1 << level; }

    /** Pixel width/height of the level image (halved per level, floor). */
    public static int levelSize(int level, boolean xAxis) {
        MapProjection p = projection();
        if (p == null) return 0;
        return (xAxis ? p.width() : p.height()) >> level;
    }

    /** Number of tiles along one axis at a level (ceil). */
    public static int tileCount(int level, boolean xAxis) {
        int px = levelSize(level, xAxis);
        return (px + TILE - 1) / TILE;
    }

    public static Path tileFile(int level, int tx, int ty) {
        return rendererRoot(renderer()).resolve("tiles")
                .resolve("l" + level).resolve(tx + "_" + ty + ".png");
    }

    public static boolean tileExists(int level, int tx, int ty) {
        return Files.isRegularFile(tileFile(level, tx, ty));
    }

    /** Ask for the current render when the confirmed copy is missing or due. */
    public static void ensureLoaded() {
        refreshRenderers();
        ensureLoaded(renderer());
    }

    public static void ensureLoaded(String name) {
        String target = safeRenderer(name);
        Entry current = entry(target);
        long now = System.currentTimeMillis();
        if (!LiveMapCache.needsFetch(current.freshness(), current.fetchedAt(),
                current.attemptAt(), now, REFRESH_MS, FAILURE_RETRY_MS)) return;
        if (!loading.add(target)) return;
        // Hide whatever was on screen. A saved pyramid is not drawn until this
        // fetch confirms it is still the server's file.
        entries.put(target, Entry.loading(null));
        Thread t = new Thread(() -> load(target), "openintel-livemap-" + target);
        t.setDaemon(true);
        t.start();
    }

    /** Refresh the server's advertised renderer list, lazily and off-thread. */
    private static void refreshRenderers() {
        if (System.currentTimeMillis() - renderersFetchedAt < RENDERER_REFRESH_MS) return;
        if (!rendererLoading.compareAndSet(false, true)) return;
        Thread t = new Thread(LiveMap::loadRenderers, "openintel-livemap-renderers");
        t.setDaemon(true);
        t.start();
    }

    private static void loadRenderers() {
        try {
            renderersFetchedAt = System.currentTimeMillis();
            String base = baseUrl();
            if (base == null) return;
            JsonArray arr = JsonParser.parseString(
                    get(base + "/api/renderers", "application/json")).getAsJsonArray();
            List<String> parsed = new ArrayList<>(arr.size());
            for (JsonElement e : arr) {
                if (!e.isJsonPrimitive()) continue;
                String name = safeRenderer(e.getAsString());
                if (!name.isEmpty() && !parsed.contains(name)) parsed.add(name);
            }
            if (!parsed.isEmpty()) {
                parsed.sort((a, b) -> {
                    int ai = FALLBACK_RENDERERS.indexOf(a);
                    int bi = FALLBACK_RENDERERS.indexOf(b);
                    if (ai < 0) ai = Integer.MAX_VALUE;
                    if (bi < 0) bi = Integer.MAX_VALUE;
                    return ai != bi ? Integer.compare(ai, bi) : a.compareTo(b);
                });
                renderers = List.copyOf(parsed);
                renderersFetchedAt = System.currentTimeMillis();
                if (!parsed.contains(renderer())) selectRenderer(preferredRenderer(parsed));
            }
        } catch (Exception ignored) {
            renderersFetchedAt = System.currentTimeMillis();
        } finally {
            rendererLoading.set(false);
        }
    }

    private static String preferredRenderer(List<String> list) {
        return list.contains("hillshade") ? "hillshade" : list.get(0);
    }

    /** Forget loaded state (e.g. config reload with a new base URL). */
    public static void invalidate() {
        entries.clear();
        renderer = null;
    }

    private static void load(String name) {
        String target = safeRenderer(name);
        dbg(target, "load start");
        try {
            String base = baseUrl();
            dbg(target, "base=" + base);
            if (base == null) {
                rememberFailure(target, "no live map url configured");
                return;
            }
            String metaUrl = base + "/maps/map-" + target + ".json";
            String pngUrl = base + "/maps/map-" + target + ".png";

            String metaJson = get(metaUrl, "application/json");
            dbg(target, "meta fetched (" + metaJson.length() + " chars)");
            JsonObject meta = JsonParser.parseString(metaJson).getAsJsonObject();
            MapProjection proj = MapProjection.fromJson(meta);
            if (proj == null) throw new IllegalStateException("bad map metadata");
            dbg(target, "projection ok " + proj.width() + "x" + proj.height());

            Path root = rendererRoot(target);
            LiveMapCache.Cached cached = LiveMapCache.read(root);
            boolean metaMatches = cached != null && proj.equals(cached.projection());
            Body png = getBytes(pngUrl,
                    metaMatches ? cached.etag() : null,
                    metaMatches ? cached.lastModified() : null);
            String hash = png.bytes() == null
                    ? (cached == null ? null : cached.sha256())
                    : LiveMapCache.sha256(png.bytes());
            if (cached != null && LiveMapCache.confirmedCurrent(
                    png.status(), metaMatches, cached.sha256(), hash)) {
                long fetchedAt = System.currentTimeMillis();
                LiveMapCache.writeStamp(root, proj.width(), proj.height(), cached.maxLevel(),
                        fetchedAt, png.etag(), png.lastModified(), hash);
                entries.put(target, new Entry(State.READY, proj, cached.maxLevel(),
                        fetchedAt, fetchedAt, null));
                dbg(target, "READY unchanged");
                return;
            }
            if (png.bytes() == null) throw new IllegalStateException("HTTP " + png.status());
            dbg(target, "png fetched (" + png.bytes().length + " bytes)");

            Files.createDirectories(root);
            Path pngPath = root.resolve("source.png");
            Files.write(pngPath, png.bytes());
            Files.writeString(root.resolve("source.json"), GSON.toJson(meta));
            dbg(target, "source written, building pyramid");

            int maxLevel = buildPyramid(root, pngPath);
            long fetchedAt = System.currentTimeMillis();
            LiveMapCache.writeStamp(root, proj.width(), proj.height(), maxLevel, fetchedAt,
                    png.etag(), png.lastModified(), hash);
            entries.put(target, new Entry(State.READY, proj, maxLevel, fetchedAt, fetchedAt, null));
            dbg(target, "READY maxLevel=" + maxLevel);
        } catch (Throwable e) {
            dbg(target, "FAILED " + e);
            e.printStackTrace();
            rememberFailure(target, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            loading.remove(target);
        }
    }

    /** A failed check does not put an older pyramid back on screen. */
    private static void rememberFailure(String target, String message) {
        long now = System.currentTimeMillis();
        entries.put(target, new Entry(State.FAILED, null, 0, now, now, message));
    }

    private static void dbg(String target, String msg) {
        System.out.println("[openintel-livemap][" + target + "] " + msg);
    }

    private static String baseUrl() {
        var cfg = OpenIntelClient.config();
        String base = cfg != null ? cfg.liveMapBase : null;
        if (base == null || base.isBlank()) return null;
        return base.replaceAll("/+$", "");
    }

    private static String safeRenderer(String name) {
        if (name == null) return "hillshade";
        String clean = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return clean.isEmpty() ? "hillshade" : clean;
    }

    private static Path rendererRoot(String name) {
        return ROOT.resolve(safeRenderer(name));
    }

    private static String get(String url, String accept) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", accept)
                .header("User-Agent", "OpenIntel-WarTable")
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    private record Body(int status, byte[] bytes, String etag, String lastModified) { }

    /** 304 keeps the saved image; any other success is a new render. */
    private static Body getBytes(String url, String etag, String lastModified) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "OpenIntel-WarTable");
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        else if (lastModified != null && !lastModified.isBlank()) request.header("If-Modified-Since", lastModified);
        HttpResponse<byte[]> res = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = res.statusCode();
        if (status != 304 && status / 100 != 2) throw new IllegalStateException("HTTP " + status);
        return new Body(status,
                status == 304 ? null : res.body(),
                res.headers().firstValue("etag").orElse(etag),
                res.headers().firstValue("last-modified").orElse(lastModified));
    }

    /**
     * Slice the source image into 256px tiles, then downsample level by level
     * until the whole map fits on a single tile. The new pyramid is written
     * beside the one on screen and swapped in only after it is complete, so a
     * join-time refresh cannot tear tiles the War Table is already showing.
     */
    private static int buildPyramid(Path root, Path pngPath) throws Exception {
        Path next = root.resolve("tiles-next");
        if (Files.exists(next)) deleteTree(next);
        try {
            int maxLevel = writePyramid(root.getFileName().toString(), next, pngPath);
            swapTiles(root, next);
            return maxLevel;
        } catch (Exception e) {
            if (Files.exists(next)) deleteTree(next);
            throw e;
        }
    }

    private static int writePyramid(String renderer, Path tileRoot, Path pngPath) throws Exception {
        BufferedImage source = ImageIO.read(pngPath.toFile());
        if (source == null) throw new IllegalStateException("unreadable map image");
        BufferedImage argb = toArgb(source);

        int level = 0, maxLevel = 0;
        BufferedImage current = argb;
        while (true) {
            int cols = (int) Math.ceil(current.getWidth() / (double) TILE);
            int rows = (int) Math.ceil(current.getHeight() / (double) TILE);
            Path dir = tileRoot.resolve("l" + level);
            Files.createDirectories(dir);
            dbg(renderer, "level " + level + " " + cols + "x" + rows + " tiles");
            for (int ty = 0; ty < rows; ty++) {
                for (int tx = 0; tx < cols; tx++) {
                    // Edge tiles keep their true extent — the rest of the tile
                    // stays transparent so content is never stretched.
                    int srcW = Math.min(current.getWidth(), (tx + 1) * TILE) - tx * TILE;
                    int srcH = Math.min(current.getHeight(), (ty + 1) * TILE) - ty * TILE;
                    BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = tile.createGraphics();
                    g.drawImage(current, 0, 0, srcW, srcH,
                            tx * TILE, ty * TILE,
                            tx * TILE + srcW, ty * TILE + srcH, null);
                    g.dispose();
                    ImageIO.write(tile, "PNG", dir.resolve(tx + "_" + ty + ".png").toFile());
                }
            }
            if (cols <= 1 && rows <= 1) { maxLevel = level; break; }
            current = downsample2x(current);
            level++;
            if (level > LiveMapCache.MAX_LEVEL) { maxLevel = LiveMapCache.MAX_LEVEL; break; }
        }
        return maxLevel;
    }

    private static void swapTiles(Path root, Path next) throws Exception {
        Path tiles = root.resolve("tiles");
        Path old = root.resolve("tiles-old");
        if (Files.exists(old)) deleteTree(old);
        if (Files.exists(tiles)) Files.move(tiles, old);
        try {
            Files.move(next, tiles);
        } catch (Exception e) {
            if (!Files.exists(tiles) && Files.exists(old)) Files.move(old, tiles);
            throw e;
        }
        if (Files.exists(old)) deleteTree(old);
    }

    /** Full-size image → half size, bilinear. */
    private static BufferedImage downsample2x(BufferedImage src) {
        int w = Math.max(1, src.getWidth() / 2);
        int h = Math.max(1, src.getHeight() / 2);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null, null);
        g.dispose();
        return out;
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        }
    }

    /** Human-readable renderer name → display label, matching the site UI. */
    public static String rendererLabel(String name) {
        return switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
            case "hillshade" -> "Default";
            case "color" -> "Flat";
            case "contour" -> "Contour";
            case "shaded" -> "Shaded";
            default -> name == null ? "?" : name;
        };
    }
}
