package dev.openintel.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.openintel.chat.ChatReception;
import dev.openintel.chat.LocalChatMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks lines stored while no server packet is being handled. Priority is above
 * the default so this runs before another mod cancels the add and replays it.
 */
@Mixin(value = ChatComponent.class, priority = 2000)
public abstract class ChatOriginMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"))
    private void openintel$observe(Component contents, MessageSignature signature, GuiMessageSource source,
                                   GuiMessageTag tag, CallbackInfo ci) {
        ChatReception.observe(contents, tag == null ? null : tag.logTag());
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("RETURN"))
    private void openintel$mark(Component contents, MessageSignature signature, GuiMessageSource source,
                                GuiMessageTag tag, CallbackInfo ci, @Local GuiMessage message) {
        boolean local = ChatReception.takeLocal();
        if (local && message != null) {
            // GuiMessage is final, so the injected interface is only visible at runtime.
            ((LocalChatMessage) (Object) message).openintel$setLocal(true);
        }
    }
}
