package dev.openintel.xaero;

import java.util.List;

/** Picks the relic out of a treasure map's tooltip lines. */
public final class RelicNames {
    public record Label(String title, String enchantment) { }

    private RelicNames() { }

    /**
     * Treasure maps are all named "Treasure Map". The relic name is a later
     * line, such as "Lunar Wand", followed by copy text such as "Original".
     */
    public static Label resolve(List<String> lines) {
        if (lines != null) {
            for (String line : lines) {
                RelicCatalog.Relic relic = RelicCatalog.find(line);
                if (relic != null) return new Label(relic.name(), relic.enchantment());
            }
            for (String line : lines) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || RelicCatalog.isGeneric(trimmed)) continue;
                return new Label(trimmed, "");
            }
        }
        return new Label("Relic", "");
    }
}
