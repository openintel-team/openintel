package dev.openintel.render;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class CrosshairRenderer {
    public static final Identifier VANILLA = Identifier.fromNamespaceAndPath("minecraft", "hud/crosshair");

    private CrosshairRenderer() { }

    public static void draw(GuiGraphicsExtractor ctx, CrosshairStyle style, int cx, int cy) {
        style.draw(ctx::fill, cx, cy);
    }

    public static void preview(GuiGraphicsExtractor ctx, CrosshairStyle style, int cx, int cy) {
        if (style.enabled) draw(ctx, style, cx, cy);
        else ctx.blitSprite(RenderPipelines.CROSSHAIR, VANILLA, cx - 7, cy - 7, 15, 15);
    }
}
