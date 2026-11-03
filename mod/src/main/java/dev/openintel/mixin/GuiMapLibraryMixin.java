package dev.openintel.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.relic.GuiMapCamera;
import dev.openintel.relic.LibraryMapInput;
import dev.openintel.relic.LibraryMapOverlay;
import dev.openintel.relic.RelicCarryHint;
import dev.openintel.relic.RelicLibraryVisit;
import dev.openintel.xaero.XaeroIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;

/**
 * Click, shift + left-drag lasso, and overlay while a relic-library map visit is
 * open. The selection logic is {@link LibraryMapInput}, shared with JourneyMap.
 * Mouse button constants follow the input backend of this Minecraft version.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapLibraryMixin {
    @Unique private LibraryMapInput openintel$input;

    @Unique
    private LibraryMapInput openintel$input() {
        if (openintel$input == null) openintel$input = new LibraryMapInput();
        return openintel$input;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void openintel$lassoStart(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isMapVisit()) return;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        GuiMap map = (GuiMap) (Object) this;
        if (openintel$input().press(visit, client.hasShiftDown(), XaeroIntegration.hoveredLibrarySite(),
                GuiMapCamera.mouseBlockX(map), GuiMapCamera.mouseBlockZ(map), event.x(), event.y())) {
            // Cancel so GuiMap never sets leftMouseButton.isDown (which drives panning).
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void openintel$lassoEnd(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isMapVisit() || !openintel$input().selecting()) return;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        GuiMap map = (GuiMap) (Object) this;
        Minecraft client = Minecraft.getInstance();
        int free = client != null && client.player != null
                ? RelicCarryHint.freeSlots(client.player)
                : Integer.MAX_VALUE;
        double px = client != null && client.player != null ? client.player.getX() : 0;
        double pz = client != null && client.player != null ? client.player.getZ() : 0;
        openintel$input().release(visit, GuiMapCamera.mouseBlockX(map), GuiMapCamera.mouseBlockZ(map),
                event.x(), event.y(), free, px, pz);
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

    @Inject(method = "render", at = @At("TAIL"))
    private void openintel$drawLasso(GuiGraphics graphics, int mouseX, int mouseY, float delta,
                                     CallbackInfo ci) {
        LibraryMapOverlay.draw(graphics, ((GuiMap) (Object) this).width);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void openintel$endVisit(CallbackInfo ci) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit != null) visit.endMapVisit();
        openintel$input().reset();
    }
}
