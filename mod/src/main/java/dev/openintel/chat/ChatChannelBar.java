package dev.openintel.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Chip geometry for the chat screen. Rendering stays in the mixin so this can
 * be checked without a client.
 */
public final class ChatChannelBar {
    public static final int BELL_W = 9;
    public static final int BELL_H = 9;
    private static final int PAD_X = 5;
    private static final int PAD_Y = 3;
    private static final int TEXT_H = 9;
    private static final int LABEL_GAP = 4;
    private static final int CHIP_GAP = 4;
    public static final int CHIP_H = PAD_Y + TEXT_H + PAD_Y;
    /** Dome, flared rim, and clapper. A lit bell means new lines still fade in. */
    private static final String[] BELL_ON = {
            "....#....",
            "...###...",
            "..#...#..",
            "..#...#..",
            ".#.....#.",
            ".#.....#.",
            "#########",
            "..#...#..",
            "...###..."
    };
    /** The same bell with a slash through it, for a muted category. */
    private static final String[] BELL_OFF = {
            "....#...#",
            "...###.#.",
            "..#...#..",
            "..#.#.#..",
            ".#..#..#.",
            ".#.#...#.",
            "##.######",
            ".#.#...#.",
            "#..###..."
    };

    private ChatChannelBar() { }

    public enum Hit { CHANNEL, BELL }

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    public record Chip(ChatChannel channel, Bounds plate, Bounds name, Bounds bell) { }

    public record Target(Hit hit, ChatChannel chatChannel) { }

    /**
     * Screen Y of the chip row, sitting just above the focused chat box.
     * {@code linesPerPage} and {@code lineSpacing} follow the player's chat
     * height. {@code chatShift} is ChatPatches' upward shift in chat-scaled
     * pixels, or 0 when that mod is absent. The 40px margin and 9px line match
     * {@code ChatComponent}.
     */
    public static int rowY(int screenHeight, double chatScale, double lineSpacing, int linesPerPage, int chatShift) {
        double scale = chatScale > 0 ? chatScale : 1;
        int lineHeight = (int) (9.0 * (1.0 + lineSpacing));
        if (lineHeight < 1) lineHeight = 1;
        int baseline = (int) Math.floor((screenHeight - 40) / scale) - chatShift;
        int top = (int) Math.floor((baseline - Math.max(linesPerPage, 0) * (double) lineHeight) * scale);
        return Math.max(2, top - 2 - CHIP_H);
    }

    /** True when this pixel belongs to the ringing or muted bell. */
    public static boolean bellPixel(boolean ringing, int x, int y) {
        String[] rows = ringing ? BELL_ON : BELL_OFF;
        if (y < 0 || y >= rows.length || x < 0 || x >= BELL_W) return false;
        return rows[y].charAt(x) == '#';
    }

    public static List<Chip> layout(int screenWidth, int rowY, ToIntFunction<String> textWidth) {
        int y = rowY;
        int x = 2;
        List<Chip> chips = new ArrayList<>(ChatChannel.values().length);
        for (ChatChannel channel : ChatChannel.values()) {
            if (x >= screenWidth) break;
            int text = Math.max(1, textWidth.applyAsInt(label(channel)));
            int plateW = PAD_X + text + LABEL_GAP + BELL_W + PAD_X;
            Bounds plate = new Bounds(x, y, plateW, CHIP_H);
            Bounds name = new Bounds(x + PAD_X, y + PAD_Y, text, TEXT_H);
            int bellY = y + (CHIP_H - BELL_H) / 2;
            Bounds bell = new Bounds(name.x() + text + LABEL_GAP, bellY, BELL_W, BELL_H);
            chips.add(new Chip(channel, plate, name, bell));
            x = plate.x() + plate.width() + CHIP_GAP;
        }
        return chips;
    }

    public static Target hit(List<Chip> chips, double x, double y) {
        if (chips == null) return null;
        for (Chip chip : chips) {
            if (!chip.plate().contains(x, y)) continue;
            // The bell is small; the whole right side of the chip toggles it.
            if (x >= chip.bell().x() - 2) return new Target(Hit.BELL, chip.channel());
            return new Target(Hit.CHANNEL, chip.channel());
        }
        return null;
    }

    public static String label(ChatChannel channel) {
        return switch (channel) {
            case CHAT -> "Chat";
            case SNITCHES -> "Snitches";
            case MINING -> "Mining";
        };
    }
}
