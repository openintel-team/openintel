package dev.openintel;

import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fails the build when this branch is still pinned to Minecraft 26.3. */
public final class VersionPinTest {
    public static void main(String[] args) throws Exception {
        check(args.length == 2, "Expected gradle.properties and fabric.mod.json paths");
        String minecraftVersion = null;
        for (String line : Files.readString(Path.of(args[0])).split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("minecraft_version=")) {
                minecraftVersion = trimmed.substring("minecraft_version=".length()).strip();
            }
        }
        check("26.2".equals(minecraftVersion), "minecraft_version must be 26.2, was " + minecraftVersion);

        var modJson = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        String dependency = modJson.getAsJsonObject("depends").get("minecraft").getAsString();
        check("~26.2".equals(dependency), "fabric.mod.json minecraft dependency must be ~26.2, was " + dependency);
        System.out.println("Version pin tests passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
