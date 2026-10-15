package dev.openintel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails the build if this worktree is still pinned to Minecraft 26.3 or 26.2.
 * Expects minecraft_version 26.1.2 and fabric.mod.json "~26.1.2".
 */
public final class VersionPinTest {
    private static final String EXPECTED = "26.1.2";

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String properties = Files.readString(root.resolve("gradle.properties"), StandardCharsets.UTF_8);
        String minecraftVersion = property(properties, "minecraft_version");
        check(EXPECTED.equals(minecraftVersion),
                "minecraft_version must be " + EXPECTED + " (was " + minecraftVersion + ")");
        check(!"26.3".equals(minecraftVersion) && !"26.2".equals(minecraftVersion),
                "minecraft_version is still a newer pin");

        String modJson = Files.readString(root.resolve("src/main/resources/fabric.mod.json"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"").matcher(modJson);
        check(matcher.find(), "fabric.mod.json is missing a minecraft dependency");
        String dependency = matcher.group(1);
        check(("~" + EXPECTED).equals(dependency),
                "fabric.mod.json minecraft dependency must be ~" + EXPECTED + " (was " + dependency + ")");
        check(!dependency.contains("26.3") && !dependency.contains("26.2"),
                "fabric.mod.json minecraft dependency is still 26.3 or 26.2");
        System.out.println("Version pin is Minecraft " + EXPECTED);
    }

    private static String property(String text, String key) {
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + "=")) return trimmed.substring(key.length() + 1).trim();
        }
        return "";
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
