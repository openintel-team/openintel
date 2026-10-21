package dev.openintel.mixin;

import dev.openintel.chat.LocalChatMessage;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Trimmed lines do not point back at their message. The flag is copied onto
 * each line when the message is split, including a later rebuild.
 */
@Mixin(GuiMessage.Line.class)
public class GuiMessageLineLocalMixin implements LocalChatMessage {
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
