package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.chat.ChatReception;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;

/** Player, system, and disguised chat packets are lines received from the server. */
@Mixin(ClientPacketListener.class)
public abstract class ChatServerPacketMixin {
    @WrapMethod(method = "handleSystemChat")
    private void openintel$systemChat(ClientboundSystemChatPacket packet, Operation<Void> original) {
        openintel$receive(original, packet);
    }

    @WrapMethod(method = "handlePlayerChat")
    private void openintel$playerChat(ClientboundPlayerChatPacket packet, Operation<Void> original) {
        openintel$receive(original, packet);
    }

    @WrapMethod(method = "handleDisguisedChat")
    private void openintel$disguisedChat(ClientboundDisguisedChatPacket packet, Operation<Void> original) {
        openintel$receive(original, packet);
    }

    private static void openintel$receive(Operation<Void> original, Object packet) {
        ChatReception.beginServer();
        try {
            original.call(packet);
        } finally {
            ChatReception.endServer();
        }
    }
}
