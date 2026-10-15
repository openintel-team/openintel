package dev.openintel.investigate;

import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Semantic emphasis keeps the actor, action and focused coordinate distinct from playback chrome. */
public final class InvestigateCaption {
    public static final int MUTED = 0x989FAF, PLAYER = 0x80CFFF, COORDINATES = 0xFFE38A;
    private InvestigateCaption() { }

    public static int actionColor(SnitchLogParser.Kind kind) {
        return switch (kind) {
            case BREAK -> 0xFF827A;
            case SANCTUARY -> 0xFFAA64;
            case PLACE -> 0x7EE6A2;
            case CONTAINER -> 0xFFD166;
            case ENTER -> 0xB4CEFF;
            case LOGIN -> 0x9BD8B1;
            case LOGOUT, OTHER, UNKNOWN -> 0xCCD2DE;
        };
    }

    public static Component header(int index, int size, boolean playing) {
        return text("Investigate  ", MUTED, false)
                .append(text((index + 1) + " / " + size, 0xE8EAF0, false))
                .append(text(playing ? "   PLAYING" : "   PAUSED", playing ? 0x7EE6A2 : 0xD7BA80, false));
    }

    public static Component detail(InvestigateSession.Event event) {
        var line = event.line();
        MutableComponent detail = Component.empty();
        if (line.player() != null && !line.player().isBlank()) detail.append(text(line.player() + " ", PLAYER, true));
        detail.append(text(line.action() == null || line.action().isBlank() ? "Event" : line.action(),
                actionColor(event.kind()), true));
        detail.append(text("  @  ", MUTED, false));
        detail.append(text(event.x() + ", " + event.y() + ", " + event.z(), COORDINATES, true));
        if (line.snitch() != null && !line.snitch().isBlank())
            detail.append(text("  (" + line.snitch() + ")", 0xB9B1D6, false));
        detail.append(text("  [" + shortDimension(event.dimension()) + "]", MUTED, false));
        return detail;
    }

    public static Component dimensionNotice(InvestigateSession.Event event, String currentDimension) {
        if (event == null || event.dimension().equalsIgnoreCase(currentDimension)) return null;
        return text("Event is in " + shortDimension(event.dimension()), 0xFFAA64, true);
    }

    private static MutableComponent text(String text, int color, boolean bold) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withBold(bold));
    }

    private static String shortDimension(String dimension) {
        return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    }
}
