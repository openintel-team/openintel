package dev.openintel.relic;

import dev.openintel.OpenIntelClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.LoggerFactory;

/**
 * Picks the fullscreen map for a library visit: Xaero's World Map when it is
 * installed and its switch is on, otherwise JourneyMap. A map whose OpenIntel
 * switch is off is skipped, because it would not draw the library pins. This
 * class names neither mod's types; each adapter loads only after its mod is
 * confirmed present.
 */
public final class RelicMapBridge {
    private static boolean warned;

    private RelicMapBridge() { }

    public static boolean xaeroLoaded() {
        return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
    }

    public static boolean journeyMapLoaded() {
        return FabricLoader.getInstance().isModLoaded("journeymap");
    }

    private static boolean xaeroUsable() {
        var cfg = OpenIntelClient.config();
        return xaeroLoaded() && (cfg == null || cfg.xaeroWorldMap);
    }

    private static boolean journeyMapUsable() {
        var cfg = OpenIntelClient.config();
        return journeyMapLoaded() && (cfg == null || cfg.jmMarkers);
    }

    public static boolean available() {
        return xaeroUsable() || journeyMapUsable();
    }

    public static boolean openMap(Screen parent, double focusX, double focusZ, double userScale) {
        try {
            if (xaeroUsable() && dev.openintel.xaero.XaeroLibraryMap.open(parent, focusX, focusZ, userScale)) {
                return true;
            }
            if (journeyMapUsable()) return dev.openintel.jm.JmLibraryMap.open(parent, focusX, focusZ);
        } catch (LinkageError | RuntimeException error) {
            if (!warned) {
                warned = true;
                LoggerFactory.getLogger("openintel").warn("Relic library could not open the world map", error);
            }
        }
        return false;
    }
}
