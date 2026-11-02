package dev.openintel.snitch;

import java.util.Objects;

/** Login, logout, container, and sanctuary lines, including detail text and the player span. */
public final class SnitchLogParserTest {
    public static void main(String[] args) {
        line("Gate: Steve logged in at (10, 64, -20)", "Steve", "Gate", "logged in",
                SnitchLogParser.Kind.LOGIN, 10, 64, -20,
                "Steve logged in @ 10, 64, -20 (Gate)");
        line("Gate: Steve logged out at (8, 70, 12)", "Steve", "Gate", "logged out",
                SnitchLogParser.Kind.LOGOUT, 8, 70, 12,
                "Steve logged out @ 8, 70, 12 (Gate)");
        line("Chest: Alex opened container at (1, 2, 3)", "Alex", "Chest", "opened container",
                SnitchLogParser.Kind.CONTAINER, 1, 2, 3,
                "Alex opened container @ 1, 2, 3 (Chest)");
        line("Shrine: Bob damaged sanctuary at (4, 5, 6)", "Bob", "Shrine", "damaged sanctuary",
                SnitchLogParser.Kind.SANCTUARY, 4, 5, 6,
                "Bob damaged sanctuary @ 4, 5, 6 (Shrine)");
        System.out.println("SnitchLogParserTest passed");
    }

    private static void line(String text, String player, String snitch, String action,
                             SnitchLogParser.Kind kind, int x, int y, int z, String detail) {
        SnitchLogParser.ParsedLine parsed = SnitchLogParser.parseLine(text);
        equal(player, parsed.player());
        equal(snitch, parsed.snitch());
        equal(action, parsed.action());
        equal(kind, parsed.kind());
        equal(true, parsed.hasCoords());
        equal(x, parsed.x());
        equal(y, parsed.y());
        equal(z, parsed.z());
        equal(detail, parsed.detail());
        int[] span = SnitchLogParser.playerSpan(text);
        if (span == null) throw new AssertionError("No player span in " + text);
        equal(text.indexOf(player), span[0]);
        equal(span[0] + player.length(), span[1]);
        equal(player, text.substring(span[0], span[1]));
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
