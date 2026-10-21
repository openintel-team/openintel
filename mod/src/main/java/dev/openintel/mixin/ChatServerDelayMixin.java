package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.chat.ChatReception;
import net.minecraft.client.multiplayer.chat.ChatListener;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Chat delay holds a server line and adds it on a later tick. That add is still
 * server chat.
 */
@Mixin(ChatListener.class)
public abstract class ChatServerDelayMixin {
    @WrapMethod(method = "tick")
    private void openintel$tick(Operation<Void> original) {
        openintel$receive(original);
    }

    @WrapMethod(method = "flushQueue")
    private void openintel$flush(Operation<Void> original) {
        openintel$receive(original);
    }

    @WrapMethod(method = "acceptNextDelayedMessage")
    private void openintel$acceptNext(Operation<Void> original) {
        openintel$receive(original);
    }

    private static void openintel$receive(Operation<Void> original) {
        ChatReception.beginServer();
        try {
            original.call();
        } finally {
            ChatReception.endServer();
        }
    }
}
