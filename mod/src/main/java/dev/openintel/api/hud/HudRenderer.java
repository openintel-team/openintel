package dev.openintel.api.hud;

import net.minecraft.client.gui.GuiGraphics;

@FunctionalInterface
public interface HudRenderer {
    void render(GuiGraphics localOriginContext, HudSize size, float tickDelta);
}
