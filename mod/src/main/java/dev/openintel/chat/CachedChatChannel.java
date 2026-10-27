package dev.openintel.chat;

import net.minecraft.client.GuiMessage;

/**
 * Classification of one trimmed message. Lives outside the mixin package:
 * Mixin refuses to load a normal class from {@code dev.openintel.mixin}, and
 * the first chat draw after joining would disconnect. It is also not a mixin
 * inner class, which Mixin would copy onto {@code ChatComponent}. The newest
 * line is the map key; the oldest line and the line count say whether that
 * text is unchanged.
 */
public final class CachedChatChannel {
    public final GuiMessage.Line tail;
    public final int lines;
    public final ChatChannel channel;

    public CachedChatChannel(GuiMessage.Line tail, int lines, ChatChannel channel) {
        this.tail = tail;
        this.lines = lines;
        this.channel = channel;
    }
}
