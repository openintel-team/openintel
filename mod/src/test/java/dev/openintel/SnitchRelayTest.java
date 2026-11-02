package dev.openintel;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/** Ten-second snitch forwarding dedupe, including distinct hover worlds, reset, and feed compaction. */
public final class SnitchRelayTest {
    public static void main(String[] args) throws Exception {
        SnitchRelay.reset();
        Method dedupe = SnitchRelay.class.getDeclaredMethod("dedupe", String.class);
        dedupe.setAccessible(true);
        Field windowField = SnitchRelay.class.getDeclaredField("DEDUPE_MS");
        windowField.setAccessible(true);
        long window = windowField.getLong(null);
        equal(10_000L, window);

        String text = "Vault: Steve entered snitch at (1, 2, 3)";
        Component overworld = hover(text, "minecraft:overworld");
        Component nether = hover(text, "minecraft:the_nether");
        equal(text, overworld.getString());
        equal(text, nether.getString());
        String overKey = text + "|" + SnitchRelay.hoverWorld(overworld);
        String netherKey = text + "|" + SnitchRelay.hoverWorld(nether);
        if (overKey.equals(netherKey)) throw new AssertionError("Hover worlds collapsed");
        equal(true, dedupe.invoke(null, overKey));
        equal(true, dedupe.invoke(null, netherKey));
        equal(false, dedupe.invoke(null, overKey));

        recent().put("fresh|minecraft:overworld", System.currentTimeMillis() - (window - 1_000));
        equal(false, dedupe.invoke(null, "fresh|minecraft:overworld"));
        recent().put("stale|minecraft:overworld", System.currentTimeMillis() - (window + 1_000));
        equal(true, dedupe.invoke(null, "stale|minecraft:overworld"));

        SnitchRelay.reset();
        if (!recent().isEmpty()) throw new AssertionError("reset left dedupe entries");
        equal(true, dedupe.invoke(null, overKey));

        Method compact = SnitchRelay.class.getDeclaredMethod("compact", String.class);
        compact.setAccessible(true);
        String compacted = (String) compact.invoke(null, "x".repeat(80));
        equal("x".repeat(61) + "...", compacted);
        equal(64, compacted.length());
        equal("short alert", compact.invoke(null, "  short   alert  "));

        SnitchRelay.reset();
        System.out.println("SnitchRelayTest passed");
    }

    private static Component hover(String text, String dimension) {
        return Component.literal(text).withStyle(style ->
                style.withHoverEvent(new HoverEvent.ShowText(Component.literal(dimension))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> recent() throws Exception {
        Field field = SnitchRelay.class.getDeclaredField("recent");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(null);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
