package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.chat.ChatReception;
import dev.openintel.chat.LocalChatMessage;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Marks lines stored while no server packet is being handled. This version has
 * no client-system source, and a trimmed line does not point at its message, so
 * the mark is stored on the message and copied onto the lines when they are
 * split. Priority is above the default so this runs before another mod cancels
 * the add and replays it.
 */
@Mixin(value = ChatComponent.class, priority = 2000)
public abstract class ChatOriginMixin {
    @Shadow private List<GuiMessage> allMessages;
    @Shadow private List<GuiMessage.Line> trimmedMessages;
    @Unique private int openintel$linesBefore;

    @WrapMethod(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V")
    private void openintel$track(Component contents, MessageSignature signature, GuiMessageTag tag, Operation<Void> original) {
        ChatReception.observe(contents, tag == null ? null : tag.logTag());
        GuiMessage previous = allMessages.isEmpty() ? null : allMessages.get(0);
        try {
            original.call(contents, signature, tag);
        } finally {
            GuiMessage current = allMessages.isEmpty() ? null : allMessages.get(0);
            ChatReception.finish(current != previous);
        }
    }

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"))
    private void openintel$beforeSplit(GuiMessage message, CallbackInfo ci) {
        openintel$linesBefore = trimmedMessages.size();
        if (message == null) return;
        LocalChatMessage marked = (LocalChatMessage) (Object) message;
        if (ChatReception.peekLocal()) marked.openintel$setLocal(true);
    }

    @Inject(method = "addMessageToDisplayQueue", at = @At("RETURN"))
    private void openintel$copyToLines(GuiMessage message, CallbackInfo ci) {
        if (message == null || !((LocalChatMessage) (Object) message).openintel$isLocal()) return;
        int added = trimmedMessages.size() - openintel$linesBefore;
        for (int i = 0; i < added && i < trimmedMessages.size(); i++) {
            ((LocalChatMessage) (Object) trimmedMessages.get(i)).openintel$setLocal(true);
        }
    }
}
