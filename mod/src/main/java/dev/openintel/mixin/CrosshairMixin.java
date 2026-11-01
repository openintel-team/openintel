package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.openintel.OpenIntelClient;
import dev.openintel.render.CrosshairRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
public abstract class CrosshairMixin {
    @WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void openintel$crosshair(GuiGraphicsExtractor ctx, RenderPipeline pipeline, Identifier sprite,
                                     int x, int y, int width, int height, Operation<Void> original) {
        var style = OpenIntelClient.config().crosshair;
        if (style.enabled && CrosshairRenderer.VANILLA.equals(sprite)) {
            CrosshairRenderer.draw(ctx, style, x + width / 2, y + height / 2);
        } else {
            original.call(ctx, pipeline, sprite, x, y, width, height);
        }
    }
}
