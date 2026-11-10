package dev.openintel.gui;

import dev.openintel.feature.Feature;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which settings page a Features tile opens, plus the General tiles that hold
 * settings no single feature owns. Minecraft-free so it can be tested headlessly.
 */
public final class FeatureSettings {
    private FeatureSettings() { }

    private static final Map<Feature, String> PAGES = new EnumMap<>(Map.ofEntries(
            Map.entry(Feature.RELAY, "general"),
            Map.entry(Feature.PLAYER_MARKERS, "markers"),
            Map.entry(Feature.SNITCH_ALERTS, "snitch"),
            Map.entry(Feature.PINGS, "pings"),
            Map.entry(Feature.PRESENCE, "presence"),
            Map.entry(Feature.EVENT_FEED, "feed"),
            Map.entry(Feature.CROSSHAIR, "crosshair"),
            Map.entry(Feature.RADAR, "radar"),
            Map.entry(Feature.VISUALIZATIONS, "viz"),
            Map.entry(Feature.INVESTIGATE, "investigate"),
            Map.entry(Feature.WAR_TABLE, "wartable"),
            Map.entry(Feature.ARMOR_HUD, "hud"),
            Map.entry(Feature.RELIC_MAPS, "relics"),
            Map.entry(Feature.MACROS, "macros"),
            Map.entry(Feature.JUNK_REJECT, "inventory")));

    /** Settings category id for a tile, or null when the feature is a plain switch. */
    public static String pageFor(Feature f) { return PAGES.get(f); }

    public static Map<Feature, String> pages() { return PAGES; }

    /** A General tile: no switch, just opens a page. */
    public enum Link {
        RELAYS(null),
        HUD_EDITOR(null),
        KEYBINDS("keybinds");

        /** Settings category it opens, or null for a dedicated screen. */
        public final String page;

        Link(String page) { this.page = page; }

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
        public String labelKey() { return "openintel.features.link." + id(); }
        public String tooltipKey() { return labelKey() + ".tooltip"; }
    }

    public static List<Link> links() { return List.of(Link.values()); }
}
