package dev.openintel.relic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/**
 * Opens Xaero's fullscreen world map for a library visit. Soft-fails when Xaero
 * is not present.
 */
public final class RelicMapBridge {
    private RelicMapBridge() { }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
    }

    public static boolean openMap(Screen parent, double focusX, double focusZ, double userScale) {
        if (!available()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return false;
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) return false;
        MapProcessor processor = session.getMapProcessor();
        if (processor == null) return false;
        Entity camera = client.getCameraEntity();
        if (camera == null) camera = client.player;

        GuiMap map = new GuiMap(parent, parent, processor, camera);
        client.gui.setScreen(map);
        // Mark the visit after setScreen so a prior GuiMap.removed() cannot clear it.
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit != null) visit.beginMapVisit();
        GuiMapCamera.apply(map, focusX, focusZ, userScale);
        return true;
    }
}
