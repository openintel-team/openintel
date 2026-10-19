package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.OpenIntelClient;
import dev.openintel.chat.ChatChannel;
import dev.openintel.chat.ChatChannelFilter;
import dev.openintel.config.OIConfig;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Draws and scrolls the visible channel only. Lines still enter allMessages
 * and the chat log; this never skips addMessage. This version has no
 * setVisibleMessageFilter.
 *
 * <p>{@code forEachLine} runs on every chat draw, twice per frame, and again
 * while the chat screen is open. ChatPatches restores up to 16384 messages
 * after a relog, so classifying that whole list on each draw collapses the
 * frame rate. The visible list is rebuilt only when the trimmed lines or the
 * channel settings change, and each message is classified once.
 *
 * <p>1.21.11 lines do not point at a parent message. Lines of one message are
 * contiguous, with endOfEntry on the newest line of that message, so the
 * group is classified together from its joined plain text. The newest line
 * identifies that message; a group whose lines changed is classified again.
 */
@Mixin(ChatComponent.class)
public abstract class ChatChannelMixin {
    @Shadow private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;
    @Shadow public abstract int getLinesPerPage();
    @Shadow public abstract boolean isChatFocused();

    @Unique private int openintel$cachedSize = -1;
    @Unique private GuiMessage.Line openintel$cachedHead;
    @Unique private GuiMessage.Line openintel$cachedTail;
    @Unique private boolean openintel$cachedOpen;
    @Unique private boolean openintel$cachedViewChat;
    @Unique private boolean openintel$cachedViewSnitches;
    @Unique private boolean openintel$cachedViewMining;
    @Unique private boolean openintel$cachedViewEvents;
    @Unique private boolean openintel$cachedShowChat;
    @Unique private boolean openintel$cachedShowSnitches;
    @Unique private boolean openintel$cachedShowMining;
    @Unique private boolean openintel$cachedShowEvents;
    @Unique private String openintel$cachedPatternText;
    @Unique private Pattern openintel$cachedPattern;
    @Unique private final Map<GuiMessage.Line, CachedChatChannel> openintel$channels = new IdentityHashMap<>();
    @Unique private List<GuiMessage.Line> openintel$cachedVisible = List.of();

    @WrapMethod(method = "forEachLine")
    private int openintel$filterLines(ChatComponent.AlphaCalculator calculator, ChatComponent.LineConsumer consumer,
                                      Operation<Integer> original) {
        OIConfig config = OpenIntelClient.config();
        if (config == null) return original.call(calculator, consumer);

        List<GuiMessage.Line> visible = openintel$visibleLines(config);
        int linesPerPage = getLinesPerPage();
        int accepted = 0;
        // chatScrollbarPos counts packed lines. Hidden lines do not keep a row.
        for (int row = Math.min(visible.size() - chatScrollbarPos, linesPerPage) - 1; row >= 0; row--) {
            int index = row + chatScrollbarPos;
            if (index < 0 || index >= visible.size()) continue;
            GuiMessage.Line line = visible.get(index);
            float alpha = calculator.calculate(line);
            if (alpha > 1.0E-5f) {
                accepted++;
                consumer.accept(line, row, alpha);
            }
        }
        return accepted;
    }

    @Inject(method = "scrollChat(I)V", at = @At("TAIL"))
    private void openintel$clampVisibleScroll(int amount, CallbackInfo ci) {
        OIConfig config = OpenIntelClient.config();
        if (config == null) return;
        int max = Math.max(0, openintel$visibleLines(config).size() - getLinesPerPage());
        if (chatScrollbarPos > max) chatScrollbarPos = max;
    }

    @Unique
    private List<GuiMessage.Line> openintel$visibleLines(OIConfig config) {
        int size = trimmedMessages.size();
        GuiMessage.Line head = size == 0 ? null : trimmedMessages.get(0);
        GuiMessage.Line tail = size == 0 ? null : trimmedMessages.get(size - 1);
        boolean open = isChatFocused();
        if (size == openintel$cachedSize
                && head == openintel$cachedHead
                && tail == openintel$cachedTail
                && open == openintel$cachedOpen
                && config.chatViewChat == openintel$cachedViewChat
                && config.chatViewSnitches == openintel$cachedViewSnitches
                && config.chatViewMining == openintel$cachedViewMining
                && config.chatViewEvents == openintel$cachedViewEvents
                && config.chatShowChat == openintel$cachedShowChat
                && config.chatShowSnitches == openintel$cachedShowSnitches
                && config.chatShowMining == openintel$cachedShowMining
                && config.chatShowEvents == openintel$cachedShowEvents
                && Objects.equals(config.snitchPattern, openintel$cachedPatternText)) {
            return openintel$cachedVisible;
        }

        if (!Objects.equals(config.snitchPattern, openintel$cachedPatternText)
                || config.chatViewChat != openintel$cachedViewChat
                || config.chatViewSnitches != openintel$cachedViewSnitches
                || config.chatViewMining != openintel$cachedViewMining
                || config.chatViewEvents != openintel$cachedViewEvents
                || config.chatShowChat != openintel$cachedShowChat
                || config.chatShowSnitches != openintel$cachedShowSnitches
                || config.chatShowMining != openintel$cachedShowMining
                || config.chatShowEvents != openintel$cachedShowEvents) {
            openintel$cachedPatternText = config.snitchPattern;
            openintel$cachedPattern = openintel$compile(config.snitchPattern);
            openintel$channels.clear();
        }

        // Drop classifications for messages that have fallen out of the trimmed log.
        boolean compact = openintel$channels.size() > size + 32;
        Map<GuiMessage.Line, CachedChatChannel> live = compact ? new IdentityHashMap<>() : null;
        List<GuiMessage.Line> visible = new ArrayList<>(Math.min(size, 64));
        List<GuiMessage.Line> group = new ArrayList<>();
        for (GuiMessage.Line line : trimmedMessages) {
            if (line.endOfEntry() && !group.isEmpty()) {
                openintel$acceptGroup(visible, group, open, config, live);
                group.clear();
            }
            group.add(line);
        }
        if (!group.isEmpty()) openintel$acceptGroup(visible, group, open, config, live);
        if (live != null) {
            openintel$channels.clear();
            openintel$channels.putAll(live);
        }
        openintel$cachedSize = size;
        openintel$cachedHead = head;
        openintel$cachedTail = tail;
        openintel$cachedOpen = open;
        openintel$cachedViewChat = config.chatViewChat;
        openintel$cachedViewSnitches = config.chatViewSnitches;
        openintel$cachedViewMining = config.chatViewMining;
        openintel$cachedViewEvents = config.chatViewEvents;
        openintel$cachedShowChat = config.chatShowChat;
        openintel$cachedShowSnitches = config.chatShowSnitches;
        openintel$cachedShowMining = config.chatShowMining;
        openintel$cachedShowEvents = config.chatShowEvents;
        openintel$cachedVisible = visible;
        return visible;
    }

    @Unique
    private void openintel$acceptGroup(List<GuiMessage.Line> visible, List<GuiMessage.Line> group,
                                       boolean open, OIConfig config,
                                       Map<GuiMessage.Line, CachedChatChannel> live) {
        if (ChatChannelFilter.shows(openintel$channel(group, live), open,
                config.chatViewChat, config.chatViewSnitches, config.chatViewMining,
                config.chatShowChat, config.chatShowSnitches, config.chatShowMining,
                config.chatViewEvents, config.chatShowEvents)) {
            visible.addAll(group);
        }
    }

    /**
     * One classification per stored message. Wrapped lines share the newest
     * line of the group. The oldest line and the line count have to match, so
     * a partially trimmed message is classified from the text that remains.
     */
    @Unique
    private ChatChannel openintel$channel(List<GuiMessage.Line> group, Map<GuiMessage.Line, CachedChatChannel> live) {
        GuiMessage.Line anchor = group.get(0);
        GuiMessage.Line tail = group.get(group.size() - 1);
        int lines = group.size();
        CachedChatChannel cached = openintel$channels.get(anchor);
        if (cached == null || cached.tail != tail || cached.lines != lines) {
            cached = new CachedChatChannel(tail, lines,
                    ChatChannelFilter.classify(openintel$groupText(group), openintel$cachedPattern));
            openintel$channels.put(anchor, cached);
        }
        if (live != null) live.put(anchor, cached);
        return cached.channel;
    }

    /** Reading order is the reverse of the newest-first trimmed list. */
    @Unique
    private static String openintel$groupText(List<GuiMessage.Line> group) {
        StringBuilder builder = new StringBuilder();
        for (int i = group.size() - 1; i >= 0; i--) {
            FormattedCharSequence content = group.get(i).content();
            if (content == null) continue;
            content.accept((index, style, codePoint) -> {
                builder.appendCodePoint(codePoint);
                return true;
            });
        }
        return builder.toString();
    }

    /** A pattern that fails to compile classifies nothing as snitches. */
    @Unique
    private static Pattern openintel$compile(String pattern) {
        if (pattern == null) return null;
        try {
            return Pattern.compile(pattern);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}

/**
 * Classification of one trimmed message. Not a mixin inner class: Mixin would
 * copy one of those onto {@code ChatComponent}. The newest line is the map
 * key; the oldest line and the line count say whether that text is unchanged.
 */
final class CachedChatChannel {
    final GuiMessage.Line tail;
    final int lines;
    final ChatChannel channel;

    CachedChatChannel(GuiMessage.Line tail, int lines, ChatChannel channel) {
        this.tail = tail;
        this.lines = lines;
        this.channel = channel;
    }
}
