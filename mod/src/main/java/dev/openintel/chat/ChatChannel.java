package dev.openintel.chat;

import java.util.Locale;

/** Display channel for one chat line. Chat is everything that is not a snitch, a hidden-ore reveal, a client-origin line, or an OpenIntel event. */
public enum ChatChannel {
    CHAT, SNITCHES, MINING, CLIENT, EVENTS;

    /** Null, blank, and unknown names are Chat. */
    public static ChatChannel parse(String value) {
        if (value == null || value.isBlank()) return CHAT;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "snitches" -> SNITCHES;
            case "mining" -> MINING;
            case "client" -> CLIENT;
            case "events", "oi", "oi events" -> EVENTS;
            default -> CHAT;
        };
    }

    public String id() {
        return switch (this) {
            case CHAT -> "chat";
            case SNITCHES -> "snitches";
            case MINING -> "mining";
            case CLIENT -> "client";
            case EVENTS -> "events";
        };
    }
}
