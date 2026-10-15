package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Gui.class)
public abstract class HudTextFontMixin {
    @WrapMethod(method = {"renderOverlayMessage", "renderTitle"})
    private void openintel$hudTextFont(GuiGraphics context, DeltaTracker tickCounter, Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(context, tickCounter));
    }
}
