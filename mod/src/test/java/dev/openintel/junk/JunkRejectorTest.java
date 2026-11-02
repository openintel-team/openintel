package dev.openintel.junk;

import java.util.List;
import java.util.Set;

/** Junk ids are trimmed, lowercased, namespaced, and blank entries are dropped. */
public final class JunkRejectorTest {
    public static void main(String[] args) {
        Set<String> ids = JunkRejector.normalized(List.of(
                " Cobblestone ",
                "MINECRAFT:DIRT",
                "  ",
                "",
                "mod:Custom_Item"));
        if (!ids.equals(Set.of("minecraft:cobblestone", "minecraft:dirt", "mod:custom_item"))) {
            throw new AssertionError(ids);
        }
        System.out.println("JunkRejectorTest passed");
    }
}
