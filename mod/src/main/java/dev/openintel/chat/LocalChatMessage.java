package dev.openintel.chat;

/** A stored chat line that the client inserted itself. */
public interface LocalChatMessage {
    boolean openintel$isLocal();

    void openintel$setLocal(boolean local);
}
