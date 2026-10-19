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
    /**
     * The sentence can sit behind a timestamp and in front of a duplicate
     * counter. ChatPatches stores both on the same component, and snitch
     * detection already searches rather than requiring the whole line.
     */
    private static final Pattern MINING = Pattern.compile(
            "(?i)you\\s+revealed\\s+(?:an?|\\d+)\\s+hidden\\s+\\[[^\\]]+]\\s*!+");
    private static final Pattern SECTION_CODE = Pattern.compile("\u00A7.");

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
        return systemClient && !"Restored".equals(logTag);
    }

    /**
     * {@code clientOrigin} is Minecraft's own client-system mark. It is not a
     * guess from the text, so a server or player line never takes this path.
     * That mark wins over the snitch and mining phrases.
     */
    public static ChatChannel classify(String text, Pattern snitchPattern, boolean clientOrigin) {
        if (clientOrigin) return ChatChannel.CLIENT;
        String line = plain(text);
        if (snitchPattern != null && snitchPattern.matcher(line).find()) return ChatChannel.SNITCHES;
        if (MINING.matcher(line).find()) return ChatChannel.MINING;
        return ChatChannel.CHAT;
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
        if (line == null) return false;
        if (chatOpen) {
            return switch (line) {
                case CHAT -> viewChat;
                case SNITCHES -> viewSnitches;
                case MINING -> viewMining;
                case CLIENT -> viewClient;
            };
        }
        return switch (line) {
            case CHAT -> showChat;
            case SNITCHES -> showSnitches;
            case MINING -> showMining;
            case CLIENT -> showClient;
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
