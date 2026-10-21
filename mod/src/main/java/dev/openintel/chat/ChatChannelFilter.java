package dev.openintel.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure classification and packing for the chat channel filter. Callers pass the
 * same snitch pattern the relay uses. A null pattern, including one the caller
 * could not compile, classifies nothing as snitches.
 */
public final class ChatChannelFilter {
    /** Leading mark on OpenIntel status lines, such as "[OpenIntel] ice road on". */
    public static final String EVENT_PREFIX = "[OpenIntel]";

    /**
     * A clock, a short bracket tag, or decoration in front of the OpenIntel mark.
     * The mark itself is not stripped; callers stop once it is at the start.
     */
    private static final Pattern LEADING_MARK = Pattern.compile(
            "^(?:\\[[^\\]\\n]{1,32}]|\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp][Mm])?|[\\s>«»>*•|-]+)\\s*");

    /**
     * The sentence can sit behind a timestamp and in front of a duplicate
     * counter. ChatPatches stores both on the same component, and snitch
     * detection already searches rather than requiring the whole line.
     */
    private static final Pattern MINING = Pattern.compile(
            "(?i)you\\s+revealed\\s+(?:an?|\\d+)\\s+hidden\\s+\\[[^\\]]+]\\s*!+");
    private static final Pattern SECTION_CODE = Pattern.compile("(?i)\u00A7.|&[0-9a-fk-or]");

    private ChatChannelFilter() { }

    public static ChatChannel classify(String text, Pattern snitchPattern) {
        return classify(text, snitchPattern, false);
    }

    /**
     * A live client-system line is Client. ChatPatches restores saved history
     * through that same mark and stamps the log tag {@code Restored}, so those
     * lines are classified from their text instead.
     */
    public static boolean clientOrigin(boolean systemClient, String logTag) {
        return clientOrigin(systemClient, false, logTag);
    }

    /**
     * A line the client inserted is Client. {@code systemClient} is Minecraft's
     * own client-system mark. {@code locallyAdded} is a line written straight
     * into chat, outside a server packet. ChatPatches stamps restored history
     * with the log tag {@code Restored}, and those lines are classified from
     * their text instead.
     */
    public static boolean clientOrigin(boolean systemClient, boolean locallyAdded, String logTag) {
        if ("Restored".equals(logTag)) return false;
        return systemClient || locallyAdded;
    }

    /**
     * {@code clientOrigin} is a line the client inserted. It wins over the
     * snitch and mining phrases. OpenIntel status lines are decided first.
     */
    public static ChatChannel classify(String text, Pattern snitchPattern, boolean clientOrigin) {
        String line = plain(text);
        if (openIntelEvent(line)) return ChatChannel.EVENTS;
        if (clientOrigin) return ChatChannel.CLIENT;
        if (snitchPattern != null && snitchPattern.matcher(line).find()) return ChatChannel.SNITCHES;
        if (MINING.matcher(line).find()) return ChatChannel.MINING;
        return ChatChannel.CHAT;
    }

    private static boolean openIntelEvent(String line) {
        String rest = line;
        for (int i = 0; i < 4; i++) {
            if (rest.startsWith(EVENT_PREFIX)) return true;
            String stripped = LEADING_MARK.matcher(rest).replaceFirst("");
            if (stripped.equals(rest)) return false;
            rest = stripped;
        }
        return rest.startsWith(EVENT_PREFIX);
    }

    /** Drops legacy color codes, then trims. Styled components already omit them. */
    private static String plain(String text) {
        if (text == null || text.isEmpty()) return "";
        return SECTION_CODE.matcher(text).replaceAll("").trim();
    }

    /**
     * Open chat shows every category that is turned on. Closed chat shows each
     * line whose own eye is open. The two sets are independent, and a hidden
     * line stays in the stored history.
     */
    public static boolean shows(ChatChannel line, boolean chatOpen,
                                boolean viewChat, boolean viewSnitches, boolean viewMining,
                                boolean showChat, boolean showSnitches, boolean showMining) {
        return shows(line, chatOpen, viewChat, viewSnitches, viewMining,
                showChat, showSnitches, showMining, false, false);
    }

    public static boolean shows(ChatChannel line, boolean chatOpen,
                                boolean viewChat, boolean viewSnitches, boolean viewMining,
                                boolean showChat, boolean showSnitches, boolean showMining,
                                boolean viewClient, boolean showClient) {
        return shows(line, chatOpen, viewChat, viewSnitches, viewMining,
                showChat, showSnitches, showMining, viewClient, showClient, false, false);
    }

    public static boolean shows(ChatChannel line, boolean chatOpen,
                                boolean viewChat, boolean viewSnitches, boolean viewMining,
                                boolean showChat, boolean showSnitches, boolean showMining,
                                boolean viewClient, boolean showClient,
                                boolean viewEvents, boolean showEvents) {
        if (line == null) return false;
        if (chatOpen) {
            return switch (line) {
                case CHAT -> viewChat;
                case SNITCHES -> viewSnitches;
                case MINING -> viewMining;
                case CLIENT -> viewClient;
                case EVENTS -> viewEvents;
            };
        }
        return switch (line) {
            case CHAT -> showChat;
            case SNITCHES -> showSnitches;
            case MINING -> showMining;
            case CLIENT -> showClient;
            case EVENTS -> showEvents;
        };
    }

    /** Visible texts in their original order, with hidden lines removed. */
    public static List<String> pack(List<String> lines, Pattern snitchPattern, boolean chatOpen,
                                    boolean viewChat, boolean viewSnitches, boolean viewMining,
                                    boolean showChat, boolean showSnitches, boolean showMining) {
        List<String> visible = new ArrayList<>();
        if (lines == null) return visible;
        for (String line : lines) {
            if (shows(classify(line, snitchPattern), chatOpen,
                    viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining)) {
                visible.add(line);
            }
        }
        return visible;
    }
}
