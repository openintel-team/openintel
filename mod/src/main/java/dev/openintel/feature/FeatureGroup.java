package dev.openintel.feature;

/** Purpose headings on the Features screen, in display order. */
public enum FeatureGroup {
    INTEL("intel"),
    VISUALS("visuals"),
    HUD("hud"),
    MAPS("maps"),
    CHAT("chat"),
    UTILITY("utility");

    public final String id;

    FeatureGroup(String id) { this.id = id; }

    public String titleKey() { return "openintel.features.group." + id; }
}
