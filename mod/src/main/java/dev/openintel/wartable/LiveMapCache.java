package dev.openintel.wartable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;

/**
 * On-disk tile pyramid for one live-map renderer. The files are an
 * accelerator only: the pyramid is drawn after the server confirms that
 * image is still current.
 */
final class LiveMapCache {
    private LiveMapCache() { }

    static final int TILE = 256;
    /** Highest pyramid level {@code buildPyramid} will write. */
    static final int MAX_LEVEL = 8;
    private static final String STAMP = "pyramid.json";

    enum Freshness { EMPTY, LOADING, READY, FAILED }

    record Cached(MapProjection projection, int maxLevel, long fetchedAt,
                  String etag, String lastModified, String sha256) { }

    /** Whole-map pixel size → pyramid level where the image fits on one tile. */
    static int maxLevelFor(int width, int height) {
        int level = 0;
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        while (level < MAX_LEVEL) {
            int cols = (w + TILE - 1) / TILE;
            int rows = (h + TILE - 1) / TILE;
            if (cols <= 1 && rows <= 1) return level;
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            level++;
        }
        return level;
    }

    /**
     * A saved pyramid is current only when its metadata matches and the server
     * reports the file unchanged, or the downloaded bytes are identical.
     * Anything else has to be built before it may be drawn.
     */
    static boolean confirmedCurrent(int status, boolean metaMatches,
                                    String cachedHash, String downloadedHash) {
        if (!metaMatches) return false;
        if (status == 304) return true;
        return status / 100 == 2 && sameBytes(cachedHash, downloadedHash);
    }

    static boolean sameBytes(String cachedHash, String downloadedHash) {
        return cachedHash != null && !cachedHash.isEmpty() && cachedHash.equals(downloadedHash);
    }

    static String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append("%02x".formatted(b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Skip the network when a confirmed copy is still inside the refresh
     * window, or when a failed attempt is still inside the retry backoff.
     * LOADING is not skipped here; the in-flight set is the guard for that.
     */
    static boolean needsFetch(Freshness state, long fetchedAt, long attemptAt, long now,
                              long refreshMs, long failureRetryMs) {
        if (state == Freshness.READY && now - fetchedAt < refreshMs) return false;
        if ((state == Freshness.READY || state == Freshness.FAILED)
                && now - attemptAt < failureRetryMs) return false;
        return true;
    }

    static Cached read(Path root) {
        try {
            Path json = root.resolve("source.json");
            Path png = root.resolve("source.png");
            if (!Files.isRegularFile(json) || !Files.isRegularFile(png)) return null;
            JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            MapProjection proj = MapProjection.fromJson(meta);
            if (proj == null) return null;

            int maxLevel = maxLevelFor(proj.width(), proj.height());
            long fetchedAt = Files.getLastModifiedTime(png).toMillis();
            Path stamp = root.resolve(STAMP);
            if (Files.isRegularFile(stamp)) {
                JsonObject saved = JsonParser.parseString(Files.readString(stamp)).getAsJsonObject();
                int savedW = saved.get("width").getAsInt();
                int savedH = saved.get("height").getAsInt();
                int savedLevel = saved.get("maxLevel").getAsInt();
                long savedAt = saved.get("fetchedAt").getAsLong();
                if (savedW != proj.width() || savedH != proj.height()
                        || savedLevel < 0 || savedLevel > MAX_LEVEL) return null;
                maxLevel = savedLevel;
                fetchedAt = savedAt;
            }
            if (fetchedAt > System.currentTimeMillis() + 60_000) return null;
            if (!pyramidReady(root, proj.width(), proj.height(), maxLevel)) return null;
            String etag = textOrNull(stamp, "etag");
            String modified = textOrNull(stamp, "lastModified");
            String hash = textOrNull(stamp, "sha256");
            return new Cached(proj, maxLevel, fetchedAt, etag, modified, hash);
        } catch (Exception e) {
            return null;
        }
    }

    static void writeStamp(Path root, int width, int height, int maxLevel, long fetchedAt)
            throws Exception {
        writeStamp(root, width, height, maxLevel, fetchedAt, null, null, null);
    }

    static void writeStamp(Path root, int width, int height, int maxLevel, long fetchedAt,
                           String etag, String lastModified, String sha256) throws Exception {
        JsonObject stamp = new JsonObject();
        stamp.addProperty("width", width);
        stamp.addProperty("height", height);
        stamp.addProperty("maxLevel", maxLevel);
        stamp.addProperty("fetchedAt", fetchedAt);
        if (etag != null && !etag.isBlank()) stamp.addProperty("etag", etag);
        if (lastModified != null && !lastModified.isBlank()) stamp.addProperty("lastModified", lastModified);
        if (sha256 != null && !sha256.isBlank()) stamp.addProperty("sha256", sha256);
        Files.writeString(root.resolve(STAMP), stamp.toString());
        Files.setLastModifiedTime(root.resolve("source.png"), FileTime.fromMillis(fetchedAt));
    }

    private static String textOrNull(Path stamp, String field) {
        try {
            if (!Files.isRegularFile(stamp)) return null;
            JsonObject saved = JsonParser.parseString(Files.readString(stamp)).getAsJsonObject();
            if (!saved.has(field) || saved.get(field).isJsonNull()) return null;
            String value = saved.get(field).getAsString();
            return value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /** Tiles are written in order, so the last tile of a level means that level finished. */
    static boolean pyramidReady(Path root, int width, int height, int maxLevel) {
        if (maxLevel < 0 || maxLevel > MAX_LEVEL) return false;
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        for (int level = 0; level <= maxLevel; level++) {
            int cols = Math.max(1, (w + TILE - 1) / TILE);
            int rows = Math.max(1, (h + TILE - 1) / TILE);
            Path dir = root.resolve("tiles").resolve("l" + level);
            if (!Files.isRegularFile(dir.resolve("0_0.png"))) return false;
            if (!Files.isRegularFile(dir.resolve((cols - 1) + "_" + (rows - 1) + ".png"))) return false;
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return true;
    }
}
