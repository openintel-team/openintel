package dev.openintel.xaero;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Civ+ relic names, the enchantment each one applies, and the icon shipped
 * with the mod. Icons are the CivWiki relic textures.
 */
public final class RelicCatalog {
    public record Relic(String name, String enchantment, String texture, int size) { }

    private static final List<Relic> ALL = List.of(
            relic("Ancient Helmet", "Respiration", "ancient_helmet.png", 16),
            relic("Boots Of Midas", "Depth Strider", "boots_of_midas.png", 16),
            relic("To The Fairest", "Feather Falling", "to_the_fairest.png", 16),
            relic("Prehistoric Arrowhead", "Sharpness", "prehistoric_arrowhead.png", 16),
            relic("Lost Apocrypha", "Luck Of The Sea", "lost_apocrypha.png", 16),
            relic("The New Testament", "Projectile Protection", "the_new_testament.png", 16),
            relic("Wish Bone", "Fortune", "wish_bone.png", 16),
            relic("Code of Hammurabi", "Fire Aspect", "code_of_hammurabi.png", 16),
            relic("White Rabbit's Timepiece", "Lure", "white_rabbits_timepiece.png", 16),
            relic("Thor's Hammer", "Unbreaking", "thors_hammer.png", 32),
            relic("Gold Coin", "Looting", "gold_coin.png", 32),
            relic("Milk of Human Kindness", "Aqua Affinity", "milk_of_human_kindness.png", 16),
            relic("Captain Jack's Compass", "Bane Of Arthropods", "captain_jacks_compass.png", 16),
            relic("Telltale Heart", "Protection", "telltale_heart.png", 16),
            relic("The Lie", "Smite", "the_lie.png", 16),
            relic("Workbench", "Blast Protection", "workbench.png", 32),
            relic("Silk from Akkabah", "Silk Touch", "silk_from_akkabah.png", 32),
            relic("Paddlin' Stick", "Knockback", "paddlin_stick.png", 16),
            relic("Wonder of Alchemy", "Infinity", "wonder_of_alchemy.png", 16),
            relic("Flamel's Stone", "Flame", "flamels_stone.png", 32),
            relic("Lunar Wand", "Fire Protection", "lunar_wand.png", 16),
            relic("Shriveled Soul", "Power", "shriveled_soul.png", 16),
            relic("An Appeal To Heaven", "Punch", "an_appeal_to_heaven.png", 16),
            relic("Pixie Dust", "Efficiency", "angel_dust.png", 16),
            relic("Vorpal Sword", "Vorpal", "vorpal_sword.png", 16)
    );

    private static final Map<String, Relic> BY_NAME = ALL.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(relic -> key(relic.name()), relic -> relic));

    /** Older lore / chat names that still resolve to a current catalog entry. */
    private static final Map<String, String> ALIASES = Map.of(
            key("Angel Dust"), "Pixie Dust",
            key("Pixel Dust"), "Pixie Dust"
    );

    private static final Set<String> GENERIC = Set.of(
            "treasure map", "filled map", "map", "original", "copy", "copy of a copy", "relic");

    private RelicCatalog() { }

    public static Relic find(String text) {
        String normalized = key(text);
        Relic direct = BY_NAME.get(normalized);
        if (direct != null) return direct;
        String alias = ALIASES.get(normalized);
        return alias == null ? null : BY_NAME.get(key(alias));
    }

    public static List<Relic> all() {
        return ALL;
    }

    /** Tooltip lines that are the map title or the copy generation, not the relic. */
    public static boolean isGeneric(String text) {
        return GENERIC.contains(key(text));
    }

    /** Civ+ locator maps. They are filled maps, not treasure maps. */
    public static boolean isWorldMapName(String text) {
        return "world map".equals(key(text));
    }

    static String key(String raw) {
        if (raw == null) return "";
        String stripped = raw.replaceAll("§.", "");
        String normalized = stripped.replace('’', '\'').replace('‘', '\'').replace('`', '\'');
        return normalized.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Relic relic(String name, String enchantment, String texture, int size) {
        return new Relic(name, enchantment, texture, size);
    }
}
