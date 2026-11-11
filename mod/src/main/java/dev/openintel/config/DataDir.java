package dev.openintel.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where OpenIntel keeps everything that is not a setting: relay tokens, War Table
 * strokes, the live-map cache and manual coverage shapes. It is a folder named
 * {@code openintel} next to {@code config/}, so the config folder holds only
 * {@code openintel.json} and can be shared without credentials or personal data.
 */
public final class DataDir {
    public static final String FOLDER = "openintel";
    public static final String TOKENS = "relay-tokens.json";
    public static final String RANGES = "ranges.json";
    public static final String RANGES_EXPORT = "ranges-export.json";

    /** Headless tests point both locations at scratch folders. */
    private static volatile Path testRoot;
    private static volatile Path testConfig;

    private DataDir() { }

    public static void useForTest(Path root, Path config) { testRoot = root; testConfig = config; }

    public static Path root() {
        Path dir = testRoot;
        return dir != null ? dir : FabricLoader.getInstance().getGameDir().resolve(FOLDER);
    }

    public static Path file(String name) { return root().resolve(name); }

    private static Path configDir() {
        Path dir = testConfig;
        return dir != null ? dir : FabricLoader.getInstance().getConfigDir();
    }

    /**
     * Moves data an earlier version kept inside {@code config/} into {@link #root()}.
     * Safe to run on every launch: it only moves a file whose destination does not exist,
     * so newer data is never overwritten, and a failed move leaves the old file in place.
     * Runs before anything reads the data.
     */
    public static synchronized void migrate() {
        Path config = configDir();
        if (config == null) return;
        try {
            Files.createDirectories(root());
        } catch (IOException ignored) {
            return;
        }
        moveFile(config.resolve("openintel-relay-tokens.json"), file(TOKENS));
        moveFile(config.resolve("openintel-ranges.json"), file(RANGES));
        moveFile(config.resolve("openintel-ranges-export.json"), file(RANGES_EXPORT));
        // War Table files and the live-map cache lived in config/openintel/.
        moveTree(config.resolve(FOLDER), root());
    }

    private static void moveFile(Path from, Path to) {
        try {
            if (!Files.isRegularFile(from) || Files.exists(to)) return;
            Files.createDirectories(to.getParent());
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(from, to);
            }
        } catch (IOException ignored) { }
    }

    private static void moveTree(Path from, Path to) {
        if (!Files.isDirectory(from)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(from)) {
            for (Path child : children) {
                Path target = to.resolve(child.getFileName().toString());
                if (Files.isDirectory(child)) {
                    if (Files.exists(target) && !Files.isDirectory(target)) continue;
                    Files.createDirectories(target);
                    moveTree(child, target);
                } else {
                    moveFile(child, target);
                }
            }
        } catch (IOException ignored) { }
        try {
            Files.deleteIfExists(from); // only succeeds once it is empty
        } catch (IOException ignored) { }
    }
}
