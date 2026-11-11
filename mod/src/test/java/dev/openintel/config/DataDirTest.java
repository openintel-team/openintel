package dev.openintel.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Headless checks for moving data out of config/ into the openintel data folder. */
public final class DataDirTest {
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("openintel-datadir");
        try {
            Path config = Files.createDirectory(base.resolve("config"));
            Path root = base.resolve("openintel");
            DataDir.useForTest(root, config);

            // Layout written by earlier versions.
            Files.writeString(config.resolve("openintel.json"), "{\"settings\":1}");
            Files.writeString(config.resolve("openintel-relay-tokens.json"), "{\"token\":\"t\"}");
            Files.writeString(config.resolve("openintel-ranges.json"), "{\"shapes\":[]}");
            Files.writeString(config.resolve("openintel-ranges-export.json"), "{\"export\":1}");
            Path legacyData = Files.createDirectories(config.resolve("openintel").resolve("livemap").resolve("a").resolve("b"));
            Files.writeString(config.resolve("openintel").resolve("wartable-strokes.json"), "{\"strokes\":[]}");
            Files.writeString(config.resolve("openintel").resolve("wartable-local-strokes.json"), "{\"strokes\":[1]}");
            Files.writeString(legacyData.resolve("tile.png"), "png");

            DataDir.migrate();

            assert Files.readString(DataDir.file(DataDir.TOKENS)).contains("\"t\"");
            assert Files.exists(DataDir.file(DataDir.RANGES));
            assert Files.exists(DataDir.file(DataDir.RANGES_EXPORT));
            assert Files.exists(root.resolve("wartable-strokes.json"));
            assert Files.readString(root.resolve("wartable-local-strokes.json")).contains("1");
            assert Files.readString(root.resolve("livemap").resolve("a").resolve("b").resolve("tile.png")).equals("png");

            // config/ keeps only the settings; no leftovers, not even the old folder.
            try (var names = Files.list(config)) {
                var left = names.map(p -> p.getFileName().toString()).sorted().toList();
                assert left.equals(java.util.List.of("openintel.json")) : "left behind in config/: " + left;
            }

            // Running again changes nothing, and newer data is never overwritten by an old copy.
            Files.writeString(config.resolve("openintel-ranges.json"), "{\"shapes\":[\"stale\"]}");
            Files.writeString(DataDir.file(DataDir.RANGES), "{\"shapes\":[\"current\"]}");
            DataDir.migrate();
            assert Files.readString(DataDir.file(DataDir.RANGES)).contains("current");
            assert Files.exists(config.resolve("openintel-ranges.json")) : "old copy must stay when the new one exists";

            // A fresh install with nothing to move still gets a data folder and no error.
            Path fresh = Files.createTempDirectory(base, "fresh");
            DataDir.useForTest(fresh.resolve("openintel"), Files.createDirectory(fresh.resolve("config")));
            DataDir.migrate();
            assert Files.isDirectory(fresh.resolve("openintel"));
        } finally {
            DataDir.useForTest(null, null);
            try (var walk = Files.walk(base)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println("DataDirTest passed");
    }
}
