package dev.openintel.investigate;

import dev.openintel.snitch.SnitchLogParser;

import java.util.Objects;

public final class InvestigateLogTest {
    public static void main(String[] args) {
        // Discord-style break / place with parentheses.
        SnitchLogParser.ParsedLine broke = SnitchLogParser.parseLine(
                "Vault: Steve broke block at (100, 64, -200)");
        equal("Steve", broke.player());
        equal("Vault", broke.snitch());
        equal("broke block", broke.action());
        equal(true, broke.hasCoords());
        equal(100, broke.x());
        equal(64, broke.y());
        equal(-200, broke.z());
        equal(SnitchLogParser.Kind.BREAK, broke.kind());
        equal(true, broke.isBlockHighlight());

        SnitchLogParser.ParsedLine placed = SnitchLogParser.parseLine(
                "Vault: Alex placed block stone at (10, 70, 20)");
        equal(SnitchLogParser.Kind.PLACE, placed.kind());
        equal(true, placed.isBlockHighlight());
        equal(10, placed.x());

        // In-game bracket form keeps the world name.
        SnitchLogParser.ParsedLine bracket = SnitchLogParser.parseLine(
                "* Steve entered snitch at MySnitch [world 100 64 -200]");
        equal("Steve", bracket.player());
        equal("MySnitch", bracket.snitch());
        equal("world", bracket.world());
        equal(true, bracket.hasCoords());
        equal(SnitchLogParser.Kind.ENTER, bracket.kind());
        equal(false, bracket.isBlockHighlight());

        // Line with no coordinates is skipped by paste parser.
        SnitchLogParser.ParseResult paste = SnitchLogParser.parsePaste(String.join("\n",
                "Vault: Steve broke block at (1, 2, 3)",
                "no coords here",
                "",
                "Vault: Alex placed block at (4, 5, 6)",
                "Vault: Bob entered snitch at (7, 8, 9)"));
        equal(3, paste.events().size());
        equal(1, paste.skippedNoCoords());
        equal(1, paste.skippedEmpty());
        equal(SnitchLogParser.Kind.BREAK, paste.events().get(0).kind());
        equal(SnitchLogParser.Kind.PLACE, paste.events().get(1).kind());
        equal(SnitchLogParser.Kind.ENTER, paste.events().get(2).kind());
        // Paste order preserved.
        equal(1, paste.events().get(0).x());
        equal(4, paste.events().get(1).x());
        equal(7, paste.events().get(2).x());

        InvestigateSession session = InvestigateSession.get();
        session.load(paste, "minecraft:the_nether");
        equal(3, session.size());
        // Parenthesis lines inherit the default dim.
        equal("minecraft:the_nether", session.events().get(0).dimension());
        // Bracket world "world" normalizes to overworld.
        SnitchLogParser.ParseResult withWorld = SnitchLogParser.parsePaste(
                "* Steve entered snitch at Gate [world 0 64 0]");
        session.load(withWorld, "minecraft:the_nether");
        equal("minecraft:overworld", session.events().get(0).dimension());

        session.clear();
        equal(0, session.size());
        equal(false, session.isWatching());

        System.out.println("Investigate log tests passed");
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(expected + " != " + actual);
        }
    }
}
