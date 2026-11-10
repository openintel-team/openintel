package dev.openintel.feature;

import dev.openintel.config.OIConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Every switchable OpenIntel feature. Each entry reads and writes an existing
 * {@link OIConfig} flag, so older config files keep their choices.
 *
 * A feature is effectively on ({@link Features#isOn}) only when its own flag
 * is set, its parent is on, the relay is on when it needs the relay, and the
 * mod it integrates with is installed.
 */
public enum Feature {
    // ------------------------------------------------------- Intel & Relay
    RELAY(FeatureGroup.INTEL, "relay", null, false, null, true,
            c -> c.relayEnabled, (c, v) -> c.relayEnabled = v),
    PLAYER_MARKERS(FeatureGroup.INTEL, "player_markers", null, true, null, true,
            c -> c.relayRendering, (c, v) -> c.relayRendering = v),
    LOCAL_ENEMY_ALERT(FeatureGroup.INTEL, "local_enemy_alert", PLAYER_MARKERS, false, null, true,
            c -> c.localEnemyAlert, (c, v) -> c.localEnemyAlert = v),
    EDGE_CHEVRONS(FeatureGroup.INTEL, "edge_chevrons", PLAYER_MARKERS, false, null, true,
            c -> c.edgeChevrons, (c, v) -> c.edgeChevrons = v),
    SNITCH_ALERTS(FeatureGroup.INTEL, "snitch_alerts", null, false, null, true,
            c -> c.snitchAlertsEnabled, (c, v) -> c.snitchAlertsEnabled = v),
    SNITCH_SHARE(FeatureGroup.INTEL, "snitch_share", SNITCH_ALERTS, true, null, true,
            c -> c.snitchRelay, (c, v) -> c.snitchRelay = v),
    PINGS(FeatureGroup.INTEL, "pings", null, true, null, true,
            c -> c.pingWheelEnabled, (c, v) -> c.pingWheelEnabled = v),
    PRESENCE(FeatureGroup.INTEL, "presence", null, true, null, true,
            c -> c.presenceEnabled, (c, v) -> c.presenceEnabled = v),
    EVENT_FEED(FeatureGroup.INTEL, "event_feed", null, false, null, true,
            c -> c.eventFeedEnabled, (c, v) -> c.eventFeedEnabled = v),

    // ----------------------------------------------------- Visuals & World
    CROSSHAIR(FeatureGroup.VISUALS, "crosshair", null, false, null, false,
            c -> c.crosshair != null && c.crosshair.enabled, (c, v) -> {
                if (c.crosshair == null) c.crosshair = new dev.openintel.render.CrosshairStyle();
                c.crosshair.enabled = v;
            }),
    RADAR(FeatureGroup.VISUALS, "radar", null, false, null, true,
            c -> c.radarEnabled, (c, v) -> c.radarEnabled = v),
    VISUALIZATIONS(FeatureGroup.VISUALS, "visualizations", null, false, null, true,
            c -> c.vizEnabled, (c, v) -> c.vizEnabled = v),
    VIZ_SNITCH(FeatureGroup.VISUALS, "viz_snitch", VISUALIZATIONS, false, null, true,
            c -> c.vizSnitch, (c, v) -> c.vizSnitch = v),
    VIZ_BASTION(FeatureGroup.VISUALS, "viz_bastion", VISUALIZATIONS, false, null, true,
            c -> c.vizBastion, (c, v) -> c.vizBastion = v),
    VIZ_SANCTUARY(FeatureGroup.VISUALS, "viz_sanctuary", VISUALIZATIONS, false, null, true,
            c -> c.vizSanctuary, (c, v) -> c.vizSanctuary = v),
    VIZ_LIVE_SANCTUARIES(FeatureGroup.VISUALS, "viz_live_sanctuaries", VISUALIZATIONS, false, null, true,
            c -> c.vizLiveSanctuaries, (c, v) -> c.vizLiveSanctuaries = v),
    VIZ_SEE_THROUGH(FeatureGroup.VISUALS, "viz_see_through", VISUALIZATIONS, false, null, false,
            c -> c.vizSeeThrough, (c, v) -> c.vizSeeThrough = v),
    INVESTIGATE(FeatureGroup.VISUALS, "investigate", null, false, null, true,
            c -> c.investigateEnabled, (c, v) -> c.investigateEnabled = v),
    WAR_TABLE(FeatureGroup.VISUALS, "war_table", null, false, null, true,
            c -> c.warTableEnabled, (c, v) -> c.warTableEnabled = v),
    WAR_TABLE_SANCTUARIES(FeatureGroup.VISUALS, "war_table_sanctuaries", WAR_TABLE, false, null, true,
            c -> c.warTableSanctuaries, (c, v) -> c.warTableSanctuaries = v),
    WAR_TABLE_HUD_PINS(FeatureGroup.VISUALS, "war_table_hud_pins", WAR_TABLE, false, null, true,
            c -> c.warTablePinMarkers, (c, v) -> c.warTablePinMarkers = v),

    // ----------------------------------------------------------------- HUD
    ARMOR_HUD(FeatureGroup.HUD, "armor_hud", null, false, null, true,
            c -> c.armorHudEnabled, (c, v) -> c.armorHudEnabled = v),
    POTION_HUD(FeatureGroup.HUD, "potion_hud", null, false, null, true,
            c -> c.potionHudEnabled, (c, v) -> c.potionHudEnabled = v),
    LOGO(FeatureGroup.HUD, "logo", null, false, null, false,
            c -> c.logoHudEnabled, (c, v) -> c.logoHudEnabled = v),
    CLEAN_FONT(FeatureGroup.HUD, "clean_font", null, false, null, false,
            c -> c.cleanFont, (c, v) -> c.cleanFont = v),

    // ------------------------------------------------------- Maps & Relics
    JOURNEYMAP(FeatureGroup.MAPS, "journeymap", null, false, "journeymap", true,
            c -> c.jmMarkers, (c, v) -> c.jmMarkers = v),
    XAERO_WORLD_MAP(FeatureGroup.MAPS, "xaero_world_map", null, false, "xaeroworldmap", true,
            c -> c.xaeroWorldMap, (c, v) -> c.xaeroWorldMap = v),
    WORLD_MAP_INTEL(FeatureGroup.MAPS, "world_map_intel", null, false, null, true,
            c -> c.worldMapIntel, (c, v) -> c.worldMapIntel = v),
    WORLD_MAP_RELICS(FeatureGroup.MAPS, "world_map_relics", null, false, null, true,
            c -> c.worldMapRelics, (c, v) -> c.worldMapRelics = v),
    WORLD_MAP_WAR_TABLE_PINS(FeatureGroup.MAPS, "world_map_war_table_pins", null, false, null, true,
            c -> c.warTablePinWorldMap, (c, v) -> c.warTablePinWorldMap = v),
    WORLD_MAP_PLACE_LABELS(FeatureGroup.MAPS, "world_map_place_labels", null, false, null, true,
            c -> c.warTableLabelWorldMap, (c, v) -> c.warTableLabelWorldMap = v),
    WORLD_MAP_SANCTUARY_NAMES(FeatureGroup.MAPS, "world_map_sanctuary_names", null, false, null, true,
            c -> c.warTableSanctuaryWorldMap, (c, v) -> c.warTableSanctuaryWorldMap = v),
    RELIC_MAPS(FeatureGroup.MAPS, "relic_maps", null, false, null, true,
            c -> c.relicMapsEnabled, (c, v) -> c.relicMapsEnabled = v),
    RELIC_HUD_PINS(FeatureGroup.MAPS, "relic_hud_pins", RELIC_MAPS, false, null, true,
            c -> c.relicHudPins, (c, v) -> c.relicHudPins = v),
    RELIC_CHEST_SORT(FeatureGroup.MAPS, "relic_chest_sort", RELIC_MAPS, false, null, true,
            c -> c.sortRelicMaps, (c, v) -> c.sortRelicMaps = v),
    RELIC_LIBRARY(FeatureGroup.MAPS, "relic_library", RELIC_MAPS, false, null, true,
            c -> c.relicLibraryEnabled, (c, v) -> c.relicLibraryEnabled = v),

    // ---------------------------------------------------------------- Chat
    CHAT_FILTER(FeatureGroup.CHAT, "chat_filter", null, false, null, true,
            c -> c.chatFilterEnabled, (c, v) -> c.chatFilterEnabled = v),

    // ------------------------------------------------------------- Utility
    MACROS(FeatureGroup.UTILITY, "macros", null, false, null, true,
            c -> c.macrosEnabled, (c, v) -> c.macrosEnabled = v),
    JUNK_REJECT(FeatureGroup.UTILITY, "junk_reject", null, false, null, false,
            c -> c.junkReject, (c, v) -> c.junkReject = v),
    UPDATE_CHECK(FeatureGroup.UTILITY, "update_check", null, false, null, true,
            c -> c.updateCheckEnabled, (c, v) -> c.updateCheckEnabled = v);

    public final FeatureGroup group;
    public final String id;
    /** Child rows are indented under their parent and dimmed while it is off. */
    public final Feature parent;
    /** Does nothing useful without a relay connection. */
    public final boolean needsRelay;
    /** Mod id of the integration target, or null. */
    public final String requiredMod;
    public final boolean defaultOn;
    private final Predicate<OIConfig> getter;
    private final BiConsumer<OIConfig, Boolean> setter;

    Feature(FeatureGroup group, String id, Feature parent, boolean needsRelay, String requiredMod,
            boolean defaultOn, Predicate<OIConfig> getter, BiConsumer<OIConfig, Boolean> setter) {
        this.group = group;
        this.id = id;
        this.parent = parent;
        this.needsRelay = needsRelay;
        this.requiredMod = requiredMod;
        this.defaultOn = defaultOn;
        this.getter = getter;
        this.setter = setter;
    }

    public String labelKey() { return "openintel.features." + id; }

    public String tooltipKey() { return "openintel.features." + id + ".tooltip"; }

    /** The stored switch, ignoring parent, relay and mod requirements. */
    public boolean get(OIConfig config) { return config != null && getter.test(config); }

    public void set(OIConfig config, boolean value) { setter.accept(config, value); }

    public List<Feature> children() {
        List<Feature> out = new ArrayList<>();
        for (Feature f : values()) if (f.parent == this) out.add(f);
        return out;
    }
}
