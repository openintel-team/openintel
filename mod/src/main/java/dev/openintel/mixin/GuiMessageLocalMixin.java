package dev.openintel.mixin;

import dev.openintel.chat.LocalChatMessage;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers a message the client inserted, separately from its indicator tag. */
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
