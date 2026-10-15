package dev.openintel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.GuiMap;

@Mixin(GuiMap.class)
public interface GuiMapCameraAccessor {
    @Accessor("cameraX")
    void setCameraX(double cameraX);

    @Accessor("cameraZ")
    void setCameraZ(double cameraZ);

    @Accessor("userScale")
    void setUserScale(double userScale);

    @Accessor("scale")
    void setScale(double scale);

    @Accessor("mouseBlockPosX")
    int getMouseBlockPosX();

    @Accessor("mouseBlockPosZ")
    int getMouseBlockPosZ();
}
