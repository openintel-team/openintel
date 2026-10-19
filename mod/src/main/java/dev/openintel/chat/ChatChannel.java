package dev.openintel.chat;

import java.util.Locale;

/** Display channel for one chat line. Chat is everything that is not a snitch, a hidden-ore reveal, or a client-origin line. */
public enum ChatChannel {
    CHAT, SNITCHES, MINING, CLIENT;

    /** Null, blank, and unknown names are Chat. */
    public static ChatChannel parse(String value) {
        if (value == null || value.isBlank()) return CHAT;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "snitches" -> SNITCHES;
            case "mining" -> MINING;
            case "client" -> CLIENT;
            default -> CHAT;
        };
    }

    public String id() {
        return switch (this) {
            case CHAT -> "chat";
            case SNITCHES -> "snitches";
            case MINING -> "mining";
            case CLIENT -> "client";
        };
    }
}
