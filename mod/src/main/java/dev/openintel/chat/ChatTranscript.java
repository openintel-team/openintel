package dev.openintel.chat;

import net.minecraft.client.GuiMessage;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the newest-first trimmed chat log into the text 26.3 classifies from
 * the parent message. 1.21.11 lines do not point at that message, so a wrapped
 * line is joined, in reading order, before the channel filter sees it.
 */
public final class ChatTranscript {
    private ChatTranscript() { }

    /**
     * Splits a newest-first trimmed log into messages. {@code endOfEntry} is on
     * the newest line, so the next message starts when that flag arrives after
     * a group has already started.
     */
    public static List<List<GuiMessage.Line>> messages(List<GuiMessage.Line> newestFirst) {
        List<List<GuiMessage.Line>> messages = new ArrayList<>();
        if (newestFirst == null) return messages;
        List<GuiMessage.Line> group = new ArrayList<>();
        for (GuiMessage.Line line : newestFirst) {
            if (line.endOfEntry() && !group.isEmpty()) {
                messages.add(List.copyOf(group));
                group.clear();
            }
            group.add(line);
        }
        if (!group.isEmpty()) messages.add(List.copyOf(group));
        return messages;
    }

    /** Reading order is the reverse of the newest-first trimmed list. */
    public static String readingOrder(List<FormattedCharSequence> newestFirst) {
        StringBuilder builder = new StringBuilder();
        if (newestFirst == null) return "";
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            FormattedCharSequence content = newestFirst.get(i);
            if (content == null) continue;
            content.accept((index, style, codePoint) -> {
                builder.appendCodePoint(codePoint);
                return true;
            });
        }
        return builder.toString();
    }

    public static String readingOrderLines(List<GuiMessage.Line> newestFirst) {
        if (newestFirst == null) return "";
        List<FormattedCharSequence> contents = new ArrayList<>(newestFirst.size());
        for (GuiMessage.Line line : newestFirst) contents.add(line.content());
        return readingOrder(contents);
    }
}
