package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChatComponent.class)
public abstract class ChatHudFontMixin {
    @WrapMethod(method = "refreshTrimmedMessages")
    private void openintel$chatLayoutFont(Operation<Void> original) {
        UiFont.withHudFont(original::call);
    }

    @WrapMethod(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V")
    private void openintel$chatDrawFont(GuiGraphics context, Font renderer,
                                       int ticks, int mouseX, int mouseY,
                                       boolean focused, boolean hide,
                                       Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(context, renderer, ticks, mouseX, mouseY, focused, hide));
    }

    @WrapMethod(method = "captureClickableText(Lnet/minecraft/client/gui/ActiveTextCollector;IIZ)V")
    private void openintel$chatInteractionFont(ActiveTextCollector consumer, int x, int y,
                                              boolean focused,
                                              Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(consumer, x, y, focused));
    }
}
