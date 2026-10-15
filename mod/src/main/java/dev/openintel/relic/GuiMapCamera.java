package dev.openintel.relic;

import dev.openintel.mixin.GuiMapCameraAccessor;
import xaero.map.gui.GuiMap;

/** Sets the world-map camera after opening from the relic library. */
public final class GuiMapCamera {
    private GuiMapCamera() { }

    public static void apply(GuiMap map, double x, double z, double userScale) {
        if (map == null) return;
        GuiMapCameraAccessor access = (GuiMapCameraAccessor) (Object) map;
        access.setCameraX(x);
        access.setCameraZ(z);
        double scale = userScale > 0 ? userScale : 1.0;
        access.setUserScale(scale);
        access.setScale(scale);
        try {
            java.lang.reflect.Field dest = GuiMap.class.getDeclaredField("destScale");
            dest.setAccessible(true);
            dest.setDouble(null, scale);
        } catch (ReflectiveOperationException ignored) {
            // Zoom still works with userScale alone on most builds.
        }
    }

    public static int mouseBlockX(GuiMap map) {
        return ((GuiMapCameraAccessor) (Object) map).getMouseBlockPosX();
    }

    public static int mouseBlockZ(GuiMap map) {
        return ((GuiMapCameraAccessor) (Object) map).getMouseBlockPosZ();
    }
}
