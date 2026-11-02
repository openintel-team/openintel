package dev.openintel.config;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Legacy config repair. File-backed cases use a temporary Fabric config directory
 * because {@link OIConfig#load()} reads config/openintel.json.
 */
public final class OIConfigMigrationTest {
    private static final String OLD_SNITCH_PATTERN = "(?i)entered snitch|snitch at|you have entered";

    public static void main(String[] args) throws Exception {
        externalHud();
        legacyChatView();
        byNameFallbacks();
        defaultJunk();
        loadLegacyFile();
        System.out.println("OIConfigMigrationTest passed");
    }

    private static void externalHud() {
        OIConfig config = new OIConfig();
        config.externalHudElements = null;
        config.repairExternalHudElements();
        if (config.externalHudElements == null || !config.externalHudElements.isEmpty()) {
            throw new AssertionError("Null external HUD map was not replaced");
        }

        OIConfig.ExternalHudState negative = new OIConfig.ExternalHudState();
        negative.x = -4;
        negative.y = -8;
        negative.enabled = null;
        OIConfig.ExternalHudState kept = new OIConfig.ExternalHudState(12, 18, false);
        config.externalHudElements = new LinkedHashMap<>();
        config.externalHudElements.put(null, new OIConfig.ExternalHudState(1, 1, true));
        config.externalHudElements.put("missing", null);
        config.externalHudElements.put("neg", negative);
        config.externalHudElements.put("kept", kept);
        config.repairExternalHudElements();
        equal(List.of("neg", "kept"), List.copyOf(config.externalHudElements.keySet()));
        equal(null, negative.x);
        equal(null, negative.y);
        equal(true, negative.enabled);
        equal(12, kept.x);
        equal(18, kept.y);
        equal(false, kept.enabled);
    }

    private static void legacyChatView() {
        OIConfig snitches = new OIConfig();
        snitches.chatChannel = "snitches";
        snitches.chatViewChat = true;
        snitches.chatViewMining = true;
        snitches.chatViewClient = true;
        snitches.repairChatView(new JsonObject());
        equal(false, snitches.chatViewChat);
        equal(true, snitches.chatViewSnitches);
        equal(false, snitches.chatViewMining);
        equal(false, snitches.chatViewClient);

        OIConfig mining = new OIConfig();
        mining.chatChannel = "mining";
        mining.repairChatView(new JsonObject());
        equal(false, mining.chatViewChat);
        equal(false, mining.chatViewSnitches);
        equal(true, mining.chatViewMining);

        OIConfig kept = new OIConfig();
        kept.chatChannel = "snitches";
        kept.chatViewChat = true;
        kept.chatViewSnitches = false;
        JsonObject raw = new JsonObject();
        raw.addProperty("chatViewChat", true);
        kept.repairChatView(raw);
        equal(true, kept.chatViewChat);
        equal(false, kept.chatViewSnitches);
    }

    private static void byNameFallbacks() {
        equal(OIConfig.FeedAlign.RIGHT, OIConfig.FeedAlign.byName("missing"));
        equal(OIConfig.FeedAlign.LEFT, OIConfig.FeedAlign.byName("left"));
        equal(OIConfig.ArmorHudLayout.HORIZONTAL, OIConfig.ArmorHudLayout.byName("missing"));
        equal(OIConfig.ArmorHudLayout.VERTICAL, OIConfig.ArmorHudLayout.byName("vertical"));
        equal(OIConfig.ArmorHudMode.PERCENT, OIConfig.ArmorHudMode.byName("missing"));
        equal(OIConfig.ArmorHudMode.BAR, OIConfig.ArmorHudMode.byName("bar"));
        equal(OIConfig.InvestigateCameraMode.PAN, OIConfig.InvestigateCameraMode.byName("missing"));
        equal(OIConfig.InvestigateCameraMode.OFF, OIConfig.InvestigateCameraMode.byName("off"));
        equal(OIConfig.RadarBlipFilter.EVERYONE, OIConfig.RadarBlipFilter.byName("missing"));
        equal(OIConfig.RadarBlipFilter.ENEMIES, OIConfig.RadarBlipFilter.byName("enemies"));
    }

    private static void defaultJunk() {
        equal(List.of(
                "cobblestone", "cobbled_deepslate", "dirt", "gravel", "sand",
                "andesite", "diorite", "granite", "deepslate", "tuff", "netherrack"),
                new OIConfig().junkItems);
    }

    private static void loadLegacyFile() throws Exception {
        Path game = Files.createTempDirectory("openintel-config");
        Path configDir = game.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("openintel.json"), """
                {
                  "maxMarkerDistance": 4096,
                  "snitchPattern": "(?i)entered snitch|snitch at|you have entered",
                  "snitchMarkerColor": -1,
                  "pingSeconds": 12
                }
                """);
        Object loader = FabricLoader.getInstance();
        Object previousGame = field(loader, "gameDir");
        Object previousConfig = field(loader, "configDir");
        setField(loader, "gameDir", game.toAbsolutePath().normalize());
        setField(loader, "configDir", configDir);
        try {
            OIConfig loaded = OIConfig.load();
            equal(0.0, loaded.maxMarkerDistance);
            equal(new OIConfig().snitchPattern, loaded.snitchPattern);
            if (OLD_SNITCH_PATTERN.equals(loaded.snitchPattern)) throw new AssertionError("Old snitch pattern kept");
            equal(true, loaded.snitchMarkerColorAuto);
            equal(0xFFAAAAAA, loaded.snitchMarkerColor);
            equal(12, loaded.pingSeconds);
        } finally {
            setField(loader, "gameDir", previousGame);
            setField(loader, "configDir", previousConfig);
            try (var paths = Files.walk(game)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + name);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
