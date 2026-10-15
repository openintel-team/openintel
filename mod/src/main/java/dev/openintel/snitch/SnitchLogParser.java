package dev.openintel.snitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared extractor for JukeAlert / Civ+ snitch chat and Discord log lines.
 * Used by live chat forwarding and the Investigate paste UI.
 */
public final class SnitchLogParser {
    private SnitchLogParser() { }

    private static final Pattern PLAYER =
            Pattern.compile("\\*\\s*(?<player>[A-Za-z0-9_]{3,16})");
    private static final Pattern NAMED_EVENT = Pattern.compile(
            "(?<snitch>[^:]{2,64}?)\\s*:\\s*(?<player>[A-Za-z0-9_]{3,16})\\s+" +
                    "(?<action>entered snitch|logged (?:in|out))\\s+at\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_INTERACTION = Pattern.compile(
            "(?<snitch>[^:]{2,64}?)\\s*:\\s*(?<player>[A-Za-z0-9_]{3,16})\\s+" +
                    "(?<action>(?!entered snitch\\b|logged (?:in|out)\\b).{2,96}?)\\s+at\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SNITCH =
            Pattern.compile("(?i)entered snitch at\\s+(?<snitch>\\S+)");
    private static final Pattern COORDS = Pattern.compile(
            "\\[\\s*(?<world>\\S+)\\s+(?<x>-?\\d+)\\s+(?<y>-?\\d+)\\s+(?<z>-?\\d+)\\s*\\]");
    private static final Pattern COORDS_PAREN = Pattern.compile(
            "\\(\\s*(?<x>-?\\d+)[,\\s]+(?<y>-?\\d+)[,\\s]+(?<z>-?\\d+)\\s*\\)");

    public enum Kind {
        BREAK, PLACE, ENTER, LOGIN, LOGOUT, CONTAINER, SANCTUARY, OTHER, UNKNOWN
    }

    public record ParsedLine(
            String raw,
            String player,
            String snitch,
            String action,
            String eventKind,
            Integer x,
            Integer y,
            Integer z,
            String world,
            boolean hasCoords
    ) {
        public Kind kind() {
            if (action == null || action.isBlank()) return Kind.UNKNOWN;
            String a = action.toLowerCase(Locale.ROOT).trim();
            if (a.startsWith("broke")) return Kind.BREAK;
            if (a.startsWith("placed")) return Kind.PLACE;
            if (a.contains("entered snitch")) return Kind.ENTER;
            if (a.startsWith("logged in")) return Kind.LOGIN;
            if (a.startsWith("logged out")) return Kind.LOGOUT;
            if (a.contains("opened container") || a.contains("opened")) return Kind.CONTAINER;
            if (a.contains("sanctuary")) return Kind.SANCTUARY;
            return Kind.OTHER;
        }

        public boolean isBlockHighlight() {
            Kind k = kind();
            return k == Kind.BREAK || k == Kind.PLACE;
        }

        public String detail() {
            StringBuilder sb = new StringBuilder();
            if (player != null) sb.append(player);
            if (action != null) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(action);
            }
            if (hasCoords) {
                sb.append(" @ ").append(x).append(", ").append(y).append(", ").append(z);
            }
            if (snitch != null && !snitch.isBlank()) {
                sb.append(" (").append(snitch).append(')');
            }
            if (world != null && !world.isBlank()) {
                sb.append(" [").append(world).append(']');
            }
            return sb.isEmpty() ? raw : sb.toString();
        }
    }

    public record ParseResult(List<ParsedLine> events, int skippedNoCoords, int skippedEmpty) { }

    /** Parse a multiline paste; blank lines are ignored. */
    public static ParseResult parsePaste(String text) {
        List<ParsedLine> events = new ArrayList<>();
        int skippedNoCoords = 0;
        int skippedEmpty = 0;
        if (text == null || text.isBlank()) {
            return new ParseResult(List.of(), 0, 0);
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                skippedEmpty++;
                continue;
            }
            ParsedLine parsed = parseLine(trimmed);
            if (!parsed.hasCoords()) {
                skippedNoCoords++;
                continue;
            }
            events.add(parsed);
        }
        return new ParseResult(List.copyOf(events), skippedNoCoords, skippedEmpty);
    }

    public static ParsedLine parseLine(String text) {
        if (text == null) {
            return new ParsedLine("", null, null, null, null, null, null, null, null, false);
        }
        String player = null, snitchName = null, world = null, action = null, eventKind = null;
        Integer x = null, y = null, z = null;
        boolean hasCoords = false;

        Matcher nm = NAMED_EVENT.matcher(text);
        eventKind = "event";
        if (!nm.find()) {
            nm = NAMED_INTERACTION.matcher(text);
            eventKind = "interaction";
        }
        if (nm.find(0)) {
            player = nm.group("player");
            snitchName = nm.group("snitch").replaceAll("^[+\\s*]+|[+\\s*]+$", "");
            action = nm.group("action").trim();
        } else {
            eventKind = null;
        }
        if (player == null) {
            Matcher pm = PLAYER.matcher(text);
            if (pm.find()) player = pm.group("player");
        }
        if (snitchName == null) {
            Matcher sm = SNITCH.matcher(text);
            if (sm.find()) {
                snitchName = sm.group("snitch");
                if (snitchName.startsWith("(") || snitchName.startsWith("[")) {
                    snitchName = null;
                }
            }
        }
        if (action == null && snitchName != null) {
            action = "entered snitch";
            if (eventKind == null) eventKind = "event";
        }

        Matcher cm = COORDS.matcher(text);
        if (cm.find()) {
            try {
                world = cm.group("world");
                x = Integer.parseInt(cm.group("x"));
                y = Integer.parseInt(cm.group("y"));
                z = Integer.parseInt(cm.group("z"));
                hasCoords = true;
            } catch (NumberFormatException ignored) { }
        }
        if (!hasCoords) {
            Matcher pc = COORDS_PAREN.matcher(text);
            if (pc.find()) {
                try {
                    x = Integer.parseInt(pc.group("x"));
                    y = Integer.parseInt(pc.group("y"));
                    z = Integer.parseInt(pc.group("z"));
                    hasCoords = true;
                } catch (NumberFormatException ignored) { }
            }
        }

        return new ParsedLine(text, player, snitchName, action, eventKind, x, y, z, world, hasCoords);
    }

    /** Character span of the triggering player name, or null. */
    public static int[] playerSpan(String text) {
        Matcher m = NAMED_EVENT.matcher(text);
        if (!m.find()) m = NAMED_INTERACTION.matcher(text);
        if (m.find(0)) return new int[]{m.start("player"), m.end("player")};
        m = PLAYER.matcher(text);
        if (m.find()) return new int[]{m.start("player"), m.end("player")};
        return null;
    }
}
