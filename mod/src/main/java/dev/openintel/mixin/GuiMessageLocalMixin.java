package dev.openintel.mixin;

import dev.openintel.chat.LocalChatMessage;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers lines inserted on the client, separately from the server source mark. */
@Mixin(GuiMessage.class)
public class GuiMessageLocalMixin implements LocalChatMessage {
    @Unique private boolean openintel$local;

    @Override
    public boolean openintel$isLocal() {
        return openintel$local;
    }

    @Override
    public void openintel$setLocal(boolean local) {
        openintel$local = local;
    }
}
