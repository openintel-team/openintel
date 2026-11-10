package dev.openintel.feature;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openintel.config.OIConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Feature switches: config mapping, defaults, old configs, dependencies, off-hooks and cancel. */
public final class FeatureRegistryTest {
    public static void main(String[] args) {
        mapping();
        defaults();
        oldConfig();
        dependencies();
        offHooks();
        cancelRestores();
        keybindFilter();
        settingsPages();
        System.out.println("FeatureRegistryTest passed");
    }

    private static void mapping() {
        Set<String> ids = new HashSet<>();
        for (Feature f : Feature.values()) {
            check(ids.add(f.id), "duplicate id " + f.id);
            OIConfig cfg = new OIConfig();
            f.set(cfg, true);
            check(f.get(cfg), f + " set(true) not read back");
            f.set(cfg, false);
            check(!f.get(cfg), f + " set(false) not read back");
            // A switch writes only its own flag.
            for (Feature other : Feature.values()) {
                if (other != f) check(other.get(cfg) == other.get(new OIConfig()) || other == f,
                        f + " also changed " + other);
            }
            if (f.parent != null) check(f.parent.group == f.group, f + " parent in another group");
        }
        // Spot checks of legacy fields.
        OIConfig cfg = new OIConfig();
        Feature.RADAR.set(cfg, false);
        check(!cfg.radarEnabled, "RADAR is not radarEnabled");
        Feature.PLAYER_MARKERS.set(cfg, false);
        check(!cfg.relayRendering, "PLAYER_MARKERS is not relayRendering");
        Feature.CROSSHAIR.set(cfg, true);
        check(cfg.crosshair.enabled, "CROSSHAIR is not crosshair.enabled");
        Feature.SNITCH_SHARE.set(cfg, false);
        check(!cfg.snitchRelay, "SNITCH_SHARE is not snitchRelay");
    }

    private static void defaults() {
        OIConfig cfg = new OIConfig();
        for (Feature f : Feature.values()) {
            check(f.get(cfg) == f.defaultOn, f + " field default differs from defaultOn");
        }
        check(!Feature.JUNK_REJECT.defaultOn && !Feature.CROSSHAIR.defaultOn && !Feature.CLEAN_FONT.defaultOn
                && !Feature.LOGO.defaultOn && !Feature.VIZ_SEE_THROUGH.defaultOn, "off-by-default set changed");
        Features.resetDefaults(cfg);
        for (Feature f : Feature.values()) check(f.get(cfg) == f.defaultOn, f + " reset");
    }

    private static void oldConfig() {
        Gson gson = new Gson();
        // A config saved before the feature switches: new keys absent, old flags kept.
        OIConfig saved = new OIConfig();
        saved.radarEnabled = false;
        saved.junkReject = true;
        JsonObject raw = gson.toJsonTree(saved).getAsJsonObject();
        for (String key : List.of("relayEnabled", "snitchAlertsEnabled", "investigateEnabled", "relicMapsEnabled",
                "relicLibraryEnabled", "relicHudPins", "chatFilterEnabled", "macrosEnabled", "updateCheckEnabled")) {
            raw.remove(key);
        }
        OIConfig loaded = gson.fromJson(raw, OIConfig.class);
        check(OIConfig.applyFeatureDefaults(loaded, raw), "missing keys not reported");
        check(loaded.relayEnabled && loaded.snitchAlertsEnabled && loaded.investigateEnabled
                && loaded.relicMapsEnabled && loaded.relicLibraryEnabled && loaded.relicHudPins
                && loaded.chatFilterEnabled && loaded.macrosEnabled && loaded.updateCheckEnabled,
                "new switches did not start on");
        check(!loaded.radarEnabled && loaded.junkReject, "old choices lost");

        // Saved off values stay off.
        OIConfig off = new OIConfig();
        off.investigateEnabled = false;
        JsonObject offRaw = gson.toJsonTree(off).getAsJsonObject();
        OIConfig offLoaded = gson.fromJson(offRaw, OIConfig.class);
        check(!OIConfig.applyFeatureDefaults(offLoaded, offRaw), "complete config reported changes");
        check(!offLoaded.investigateEnabled, "saved off switch was turned on");
    }

    private static void dependencies() {
        OIConfig cfg = new OIConfig();
        Features.bind(() -> cfg, mod -> !mod.equals("journeymap"));
        check(Features.isOn(Feature.VIZ_SNITCH), "child on");
        cfg.vizEnabled = false;
        check(!Features.isOn(Feature.VIZ_SNITCH), "child ignored parent off");
        check(cfg.vizSnitch, "parent off changed stored child");
        check("openintel.features.parent_off".equals(Features.blockedReason(cfg, Feature.VIZ_SNITCH)), "parent reason");
        cfg.vizEnabled = true;

        cfg.relayEnabled = false;
        check(!Features.isOn(Feature.PLAYER_MARKERS), "markers need relay");
        check(!Features.isOn(Feature.SNITCH_SHARE), "sharing needs relay");
        check(Features.isOn(Feature.SNITCH_ALERTS), "local snitch alerts survive relay off");
        check(Features.isOn(Feature.WAR_TABLE), "War Table local mode survives relay off");
        check("openintel.features.needs_relay".equals(Features.blockedReason(cfg, Feature.PINGS)), "relay reason");
        cfg.relayEnabled = true;

        check(!Features.isOn(Feature.JOURNEYMAP), "missing mod");
        check(Features.isOn(Feature.XAERO_WORLD_MAP), "installed mod");
        check("openintel.features.not_installed".equals(Features.blockedReason(cfg, Feature.JOURNEYMAP)), "mod reason");
        check(Features.blockedReason(cfg, Feature.RADAR) == null, "nothing blocks radar");
    }

    private static void offHooks() {
        OIConfig cfg = new OIConfig();
        Features.bind(() -> cfg, mod -> true);
        Features.clearHooksForTest();
        List<Feature> ran = new ArrayList<>();
        for (Feature f : Feature.values()) Features.onDisabled(f, () -> ran.add(f));
        Features.onDisabled(Feature.RADAR, () -> { throw new IllegalStateException("deliberate hook failure"); });

        EnumMap<Feature, Boolean> before = Features.snapshot(cfg);
        cfg.relayEnabled = false;
        cfg.radarEnabled = false;
        List<Feature> off = Features.applyChanges(before, cfg);
        check(off.contains(Feature.RELAY) && off.contains(Feature.RADAR)
                && off.contains(Feature.PLAYER_MARKERS) && off.contains(Feature.PINGS), "dependent features not reported");
        check(!off.contains(Feature.SNITCH_ALERTS) && !off.contains(Feature.CROSSHAIR), "unrelated features reported");
        check(ran.equals(off), "hooks did not run exactly for turned-off features: " + ran);

        // Already-off features do not run their hooks again; turning on runs nothing.
        ran.clear();
        before = Features.snapshot(cfg);
        cfg.relayEnabled = true;
        check(Features.applyChanges(before, cfg).isEmpty() && ran.isEmpty(), "turning on ran hooks");
        Features.clearHooksForTest();
    }

    private static void cancelRestores() {
        OIConfig cfg = new OIConfig();
        EnumMap<Feature, Boolean> stored = Features.stored(cfg);
        cfg.investigateEnabled = false;
        cfg.crosshair.enabled = true;
        Features.resetDefaults(cfg);
        cfg.macrosEnabled = false;
        Features.restore(cfg, stored);
        for (Feature f : Feature.values()) check(f.get(cfg) == stored.get(f), f + " not restored");
    }

    private static void keybindFilter() throws RuntimeException {
        String client;
        try {
            client = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/dev/openintel/OpenIntelClient.java"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        var m = java.util.regex.Pattern.compile("keybind\\(\"(key\\.openintel\\.[a-z_]+)\"").matcher(client);
        int keys = 0;
        while (m.find()) {
            keys++;
            // The settings key belongs to no feature and is always listed.
            if (m.group(1).equals("key.openintel.settings")) {
                check(dev.openintel.gui.KeybindFeatures.featureFor(m.group(1)) == null, "settings key has no owner");
                check(dev.openintel.gui.KeybindFeatures.visible(m.group(1), f -> false), "settings key always listed");
                keys--;
                continue;
            }
            check(dev.openintel.gui.KeybindFeatures.featureFor(m.group(1)) != null, m.group(1) + " has no feature");
        }
        check(keys == dev.openintel.gui.KeybindFeatures.keyIds().size(), "keybind mapping out of date");
        OIConfig cfg = new OIConfig();
        cfg.macrosEnabled = false;
        check(!dev.openintel.gui.KeybindFeatures.visible("key.openintel.ice_road", f -> Features.isOn(cfg, f)), "macro key shown");
        check(dev.openintel.gui.KeybindFeatures.visible("key.openintel.radar", f -> Features.isOn(cfg, f)), "radar key hidden");
    }

    /** Every tile's settings page exists in ClickGui, and a page owned by a feature belongs to that feature. */
    private static void settingsPages() {
        String gui;
        try {
            gui = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/openintel/gui/ClickGui.java"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        var owners = new java.util.HashMap<String, String>();
        var m = java.util.regex.Pattern.compile("category\\(\"([a-z]+)\",\\s*(null|Feature\\.([A-Z_]+))").matcher(gui);
        while (m.find()) owners.put(m.group(1), m.group(3));
        check(gui.contains("new Category(\"keybinds\""), "keybinds page");
        for (var e : dev.openintel.gui.FeatureSettings.pages().entrySet()) {
            check(owners.containsKey(e.getValue()), e.getKey() + " opens missing page " + e.getValue());
            String owner = owners.get(e.getValue());
            check(owner == null || owner.equals(e.getKey().name()), e.getKey() + " opens " + owner + "'s page");
        }
        // Every page with settings is reachable from a tile or a General link.
        java.util.Set<String> reachable = new java.util.HashSet<>(dev.openintel.gui.FeatureSettings.pages().values());
        for (var link : dev.openintel.gui.FeatureSettings.links()) if (link.page != null) reachable.add(link.page);
        for (String page : owners.keySet()) check(reachable.contains(page), "page " + page + " unreachable");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
