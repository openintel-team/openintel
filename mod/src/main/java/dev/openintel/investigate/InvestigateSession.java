package dev.openintel.investigate;

import dev.openintel.snitch.SnitchLogParser;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory playback of pasted snitch / Discord logs. Independent of
 * {@code Tracker} snitch hits — never expires, collapses, or relays.
 */
public final class InvestigateSession {
    private static final InvestigateSession INSTANCE = new InvestigateSession();

    public record Event(
            SnitchLogParser.ParsedLine line,
            String dimension
    ) {
        public boolean isBlockHighlight() { return line.isBlockHighlight(); }
        public SnitchLogParser.Kind kind() { return line.kind(); }
        public int x() { return line.x(); }
        public int y() { return line.y(); }
        public int z() { return line.z(); }
        public String detail() { return line.detail(); }
    }

    private final List<Event> events = new ArrayList<>();
    private int index;
    private boolean watching;
    private boolean playing;
    private long nextAdvanceAt;
    private int skippedNoCoords;
    private String defaultDimension = "minecraft:overworld";

    private InvestigateSession() { }

    public static InvestigateSession get() { return INSTANCE; }

    public synchronized void load(SnitchLogParser.ParseResult result, String defaultDim) {
        events.clear();
        index = 0;
        watching = false;
        playing = false;
        skippedNoCoords = result.skippedNoCoords();
        defaultDimension = defaultDim == null || defaultDim.isBlank()
                ? "minecraft:overworld" : defaultDim;
        for (SnitchLogParser.ParsedLine line : result.events()) {
            String dim = line.world();
            if (dim == null || dim.isBlank()) dim = defaultDimension;
            events.add(new Event(line, normalizeDim(dim)));
        }
    }

    public synchronized void clear() {
        events.clear();
        index = 0;
        watching = false;
        playing = false;
        skippedNoCoords = 0;
    }

    public synchronized boolean isWatching() { return watching; }
    public synchronized boolean isPlaying() { return playing; }
    public synchronized int size() { return events.size(); }
    public synchronized int index() { return index; }
    public synchronized int skippedNoCoords() { return skippedNoCoords; }

    public synchronized List<Event> events() { return List.copyOf(events); }

    public synchronized Event current() {
        if (events.isEmpty() || index < 0 || index >= events.size()) return null;
        return events.get(index);
    }

    public synchronized void watch() {
        if (events.isEmpty()) return;
        watching = true;
        playing = false;
        if (index < 0 || index >= events.size()) index = 0;
    }

    public synchronized void stopWatching() {
        watching = false;
        playing = false;
    }

    public synchronized void setPlaying(boolean play) {
        playing = play && watching && !events.isEmpty();
        if (playing) nextAdvanceAt = System.currentTimeMillis() + 500;
    }

    public synchronized void togglePlay() {
        setPlaying(!playing);
    }

    public synchronized void next() {
        if (events.isEmpty()) return;
        index = Math.min(events.size() - 1, index + 1);
        nextAdvanceAt = System.currentTimeMillis() + 500;
    }

    public synchronized void previous() {
        if (events.isEmpty()) return;
        index = Math.max(0, index - 1);
        nextAdvanceAt = System.currentTimeMillis() + 500;
    }

    public synchronized void select(int i) {
        if (i < 0 || i >= events.size()) return;
        index = i;
    }

    /** Advance playhead when watching + playing. */
    public synchronized void tick() {
        if (!watching || !playing || events.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < nextAdvanceAt) return;
        if (index >= events.size() - 1) {
            playing = false;
            return;
        }
        index++;
        nextAdvanceAt = now + 500;
    }

    static String normalizeDim(String world) {
        if (world == null || world.isBlank()) return "minecraft:overworld";
        String w = world.trim().toLowerCase();
        return switch (w) {
            case "world", "overworld", "minecraft:overworld" -> "minecraft:overworld";
            case "world_nether", "nether", "the_nether", "minecraft:the_nether" -> "minecraft:the_nether";
            case "world_the_end", "end", "the_end", "minecraft:the_end" -> "minecraft:the_end";
            default -> world.contains(":") ? world : "minecraft:overworld";
        };
    }
}
