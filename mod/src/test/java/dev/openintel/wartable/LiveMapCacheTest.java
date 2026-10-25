package dev.openintel.wartable;

import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Disk-cache checks for the War Table live map. No Minecraft client. */
public final class LiveMapCacheTest {
    private LiveMapCacheTest() { }

    private static final String META = """
            {
              "topLeft": {"x": -3403, "z": -3403},
              "bottomRight": {"x": 3402, "z": 3402},
              "imageSize": {"width": 6806, "height": 6806},
              "scale": 1
            }""";

    public static void check() throws Exception {
        if (LiveMapCache.maxLevelFor(6806, 6806) != 5)
            throw new AssertionError("6806px map should settle at level 5");
        if (LiveMapCache.maxLevelFor(100, 100) != 0)
            throw new AssertionError("100px map fits on one tile");
        if (LiveMapCache.maxLevelFor(257, 257) != 1)
            throw new AssertionError("257px map needs one downsample");

        long now = 1_700_000_000_000L;
        long refresh = 55 * 60 * 1000L;
        long retry = 30 * 1000L;
        if (LiveMapCache.needsFetch(LiveMapCache.Freshness.READY, now - 60_000, now - 60_000,
                now, refresh, retry))
            throw new AssertionError("a fresh pyramid must not download again");
        if (!LiveMapCache.needsFetch(LiveMapCache.Freshness.READY, now - refresh, now - refresh,
                now, refresh, retry))
            throw new AssertionError("an hourly pyramid must refresh");
        if (LiveMapCache.needsFetch(LiveMapCache.Freshness.READY, now - refresh, now - 5_000,
                now, refresh, retry))
            throw new AssertionError("a failed refresh must wait out the backoff");
        if (LiveMapCache.needsFetch(LiveMapCache.Freshness.FAILED, now, now - 5_000,
                now, refresh, retry))
            throw new AssertionError("a failed download must wait out the backoff");
        if (!LiveMapCache.needsFetch(LiveMapCache.Freshness.FAILED, now, now - retry,
                now, refresh, retry))
            throw new AssertionError("a cooled-down failure must retry");
        if (!LiveMapCache.needsFetch(LiveMapCache.Freshness.EMPTY, 0, 0, now, refresh, retry))
            throw new AssertionError("a missing pyramid must download");
        if (!LiveMapCache.confirmedCurrent(304, true, "abc", null))
            throw new AssertionError("an unchanged server file must stay the current image");
        if (!LiveMapCache.confirmedCurrent(200, true, "abc", "abc"))
            throw new AssertionError("identical bytes are the current image");
        if (LiveMapCache.confirmedCurrent(200, true, "abc", "def"))
            throw new AssertionError("a changed image must not be treated as current");
        if (LiveMapCache.confirmedCurrent(304, false, "abc", "abc"))
            throw new AssertionError("changed metadata must not keep the old image");
        if (LiveMapCache.confirmedCurrent(200, true, null, "abc"))
            throw new AssertionError("a pyramid without a hash is not yet confirmed");
        byte[] sample = new byte[]{1, 2, 3};
        if (LiveMapCache.sha256(sample) == null
                || !LiveMapCache.sha256(sample).equals(LiveMapCache.sha256(sample)))
            throw new AssertionError("map bytes must hash stably");

        Path root = Files.createTempDirectory("openintel-livemap");
        try {
            MapProjection proj = MapProjection.fromJson(JsonParser.parseString(META).getAsJsonObject());
            if (proj == null) throw new AssertionError("metadata");
            Files.writeString(root.resolve("source.json"), META);
            Files.write(root.resolve("source.png"), new byte[]{1, 2, 3});
            int maxLevel = LiveMapCache.maxLevelFor(proj.width(), proj.height());
            writeCorners(root, proj.width(), proj.height(), maxLevel);
            long fetchedAt = now - 10 * 60 * 1000L;
            LiveMapCache.writeStamp(root, proj.width(), proj.height(), maxLevel, fetchedAt);

            LiveMapCache.Cached cached = LiveMapCache.read(root);
            if (cached == null) throw new AssertionError("complete pyramid was not reusable");
            if (cached.maxLevel() != maxLevel || cached.fetchedAt() != fetchedAt)
                throw new AssertionError("stamp was not restored");
            if (cached.projection().width() != 6806)
                throw new AssertionError("projection was not restored");

            Path last = root.resolve("tiles").resolve("l0")
                    .resolve(cornerName(proj.width(), proj.height()));
            Files.delete(last);
            if (LiveMapCache.read(root) != null)
                throw new AssertionError("a pyramid missing its last tile was treated as ready");

            writeCorners(root, proj.width(), proj.height(), maxLevel);
            Files.delete(root.resolve("pyramid.json"));
            Files.setLastModifiedTime(root.resolve("source.png"), FileTime.fromMillis(fetchedAt));
            LiveMapCache.Cached legacy = LiveMapCache.read(root);
            if (legacy == null || legacy.maxLevel() != maxLevel || legacy.fetchedAt() != fetchedAt)
                throw new AssertionError("a pre-stamp pyramid should still restore from tile files");

            Files.setLastModifiedTime(root.resolve("source.png"),
                    FileTime.fromMillis(System.currentTimeMillis() + 3_600_000));
            if (LiveMapCache.read(root) != null)
                throw new AssertionError("a future-dated pyramid must be downloaded again");
        } finally {
            delete(root);
        }
    }

    private static void writeCorners(Path root, int width, int height, int maxLevel) throws Exception {
        int w = width, h = height;
        for (int level = 0; level <= maxLevel; level++) {
            Path dir = root.resolve("tiles").resolve("l" + level);
            Files.createDirectories(dir);
            Files.write(dir.resolve("0_0.png"), new byte[]{1});
            Files.write(dir.resolve(cornerName(w, h)), new byte[]{1});
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
    }

    private static String cornerName(int width, int height) {
        int cols = Math.max(1, (width + LiveMapCache.TILE - 1) / LiveMapCache.TILE);
        int rows = Math.max(1, (height + LiveMapCache.TILE - 1) / LiveMapCache.TILE);
        return (cols - 1) + "_" + (rows - 1) + ".png";
    }

    private static void delete(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        }
    }
}
