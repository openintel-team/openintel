package dev.openintel.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.relic.GuiMapCamera;
import dev.openintel.relic.RelicCarryHint;
import dev.openintel.relic.RelicLibraryVisit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;

/**
 * Shift + left-drag lasso while a relic-library map visit is open.
 * MC 26.3 uses SDL button indices: left = 1, right = 3.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapLibraryMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void openintel$lassoStart(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isMapVisit()) return;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || !client.hasShiftDown()) return;
        GuiMap map = (GuiMap) (Object) this;
        int wx = GuiMapCamera.mouseBlockX(map);
        int wz = GuiMapCamera.mouseBlockZ(map);
        visit.beginLasso(wx, wz, (int) event.x(), (int) event.y());
        // Cancel so GuiMap never sets leftMouseButton.isDown (which drives panning).
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void openintel$lassoEnd(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isLassoDragging()) return;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        GuiMap map = (GuiMap) (Object) this;
        visit.updateLasso(GuiMapCamera.mouseBlockX(map), GuiMapCamera.mouseBlockZ(map),
                (int) event.x(), (int) event.y());
        Minecraft client = Minecraft.getInstance();
        int free = client != null && client.player != null
                ? RelicCarryHint.freeSlots(client.player)
                : Integer.MAX_VALUE;
        double px = client != null && client.player != null ? client.player.getX() : 0;
        double pz = client != null && client.player != null ? client.player.getZ() : 0;
        visit.finishLasso(free, px, pz);
        cir.setReturnValue(true);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void openintel$lassoTrack(CallbackInfo ci) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isLassoDragging()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        GuiMap map = (GuiMap) (Object) this;
        double mx = client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth()
                / Math.max(1, client.getWindow().getWidth());
        double my = client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight()
                / Math.max(1, client.getWindow().getHeight());
        visit.updateLasso(GuiMapCamera.mouseBlockX(map), GuiMapCamera.mouseBlockZ(map),
                (int) mx, (int) my);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void openintel$drawLasso(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                     CallbackInfo ci) {
        RelicCarryHint.draw(graphics, ((GuiMap) (Object) this).width);
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isLassoDragging()) return;
        int[] r = visit.lassoScreenRect();
        int left = r[0];
        int top = r[1];
        int right = r[2];
        int bottom = r[3];
        if (right - left < 1 || bottom - top < 1) return;
        graphics.fill(left, top, right, top + 1, 0xE8E8B84A);
        graphics.fill(left, bottom - 1, right, bottom, 0xE8E8B84A);
        graphics.fill(left, top, left + 1, bottom, 0xE8E8B84A);
        graphics.fill(right - 1, top, right, bottom, 0xE8E8B84A);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 0x44E8B84A);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void openintel$endVisit(CallbackInfo ci) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit != null) visit.endMapVisit();
    }
}
