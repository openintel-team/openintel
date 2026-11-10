package dev.openintel.gui;

import dev.openintel.feature.Feature;
import dev.openintel.feature.FeatureGroup;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Minecraft-free row model for the Features screen: headings per
 * {@link FeatureGroup}, feature rows indented by parent depth, a search
 * filter over label and tooltip text, and collapsible headings.
 */
public final class FeatureListModel {

    /** One visible row: a heading (feature == null) or a feature. */
    public record Row(FeatureGroup group, Feature feature, int depth, boolean collapsed) {
        public boolean heading() { return feature == null; }
    }

    private final Function<String, String> translate;
    private final Set<FeatureGroup> collapsed = EnumSet.noneOf(FeatureGroup.class);
    private final Set<Feature> expanded = EnumSet.noneOf(Feature.class);
    private String query = "";

    public FeatureListModel(Function<String, String> translate) {
        this.translate = translate;
    }

    public void setQuery(String q) {
        query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    }

    public String query() { return query; }

    public void toggle(FeatureGroup group) {
        if (!collapsed.remove(group)) collapsed.add(group);
    }

    public boolean isCollapsed(FeatureGroup group) { return collapsed.contains(group); }

    public static int depth(Feature f) {
        int d = 0;
        for (Feature p = f.parent; p != null; p = p.parent) d++;
        return d;
    }

    public boolean matches(Feature f) {
        if (query.isEmpty()) return true;
        return translate.apply(f.labelKey()).toLowerCase(Locale.ROOT).contains(query)
                || translate.apply(f.tooltipKey()).toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * Visible rows in display order. While searching, headings with no match
     * are dropped and matching groups are shown expanded.
     */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        boolean searching = !query.isEmpty();
        for (FeatureGroup g : FeatureGroup.values()) {
            List<Feature> members = new ArrayList<>();
            for (Feature f : ordered(g)) if (matches(f)) members.add(f);
            if (searching && members.isEmpty()) continue;
            boolean folded = !searching && collapsed.contains(g);
            out.add(new Row(g, null, 0, folded));
            if (folded) continue;
            for (Feature f : members) out.add(new Row(g, f, depth(f), false));
        }
        return out;
    }

    /** Features of a group with each child placed right after its parent. */
    static List<Feature> ordered(FeatureGroup g) {
        List<Feature> out = new ArrayList<>();
        for (Feature f : Feature.values()) {
            if (f.group == g && f.parent == null) addTree(f, out);
        }
        return out;
    }

    private static void addTree(Feature f, List<Feature> out) {
        out.add(f);
        for (Feature c : f.children()) addTree(c, out);
    }

    // ------------------------------------------------------- tile grid ----

    /** One purpose heading with its top-level feature tiles, as drawn on the Jade-style grid. */
    public record Section(FeatureGroup group, List<Feature> tiles) { }

    /**
     * Sections in group order. A tile is a top-level feature; it is shown when
     * it or any of its sub-options matches the search. Empty sections are dropped.
     */
    public List<Section> sections() {
        List<Section> out = new ArrayList<>();
        for (FeatureGroup g : FeatureGroup.values()) {
            List<Feature> tiles = new ArrayList<>();
            for (Feature f : Feature.values()) {
                if (f.group == g && f.parent == null && matchesTree(f)) tiles.add(f);
            }
            if (!tiles.isEmpty()) out.add(new Section(g, List.copyOf(tiles)));
        }
        return out;
    }

    /** The feature or any descendant matches the search. */
    public boolean matchesTree(Feature f) {
        if (matches(f) || settingMatch(f) != null) return true;
        for (Feature c : f.children()) if (matchesTree(c)) return true;
        return false;
    }

    private Function<Feature, List<String>> settingsText = f -> List.of();

    /** Labels (and tooltips) of the settings on each feature's page, so search finds the owning tile. */
    public void setSettingsText(Function<Feature, List<String>> text) {
        settingsText = text == null ? f -> List.of() : text;
    }

    /**
     * First setting on this tile's page that matches the search, as {label, ...}
     * pairs from {@link #setSettingsText}: even entries are labels, odd are tooltips.
     * Returns the label, or null with no search or no match.
     */
    public String settingMatch(Feature f) {
        if (query.isEmpty()) return null;
        List<String> text = settingsText.apply(f);
        for (int i = 0; i + 1 < text.size(); i += 2) {
            if (text.get(i).toLowerCase(Locale.ROOT).contains(query)
                    || text.get(i + 1).toLowerCase(Locale.ROOT).contains(query)) return text.get(i);
        }
        return null;
    }

    /** Sub-options listed inside an expanded tile, depth first. */
    public static List<Feature> details(Feature tile) {
        List<Feature> out = new ArrayList<>();
        for (Feature c : tile.children()) addTree(c, out);
        return out;
    }

    public static boolean expandable(Feature tile) { return !tile.children().isEmpty(); }

    public void toggleExpanded(Feature tile) {
        if (!expandable(tile)) return;
        if (isExpanded(tile)) {
            expanded.remove(tile);
            autoExpandSuppressed.add(tile);
        } else {
            expanded.add(tile);
            autoExpandSuppressed.remove(tile);
        }
    }

    private final Set<Feature> autoExpandSuppressed = EnumSet.noneOf(Feature.class);

    /**
     * Expanded by the user, or opened automatically while a search matches
     * only a sub-option (so the match is visible) unless the user folded it.
     */
    public boolean isExpanded(Feature tile) {
        if (!expandable(tile)) return false;
        if (expanded.contains(tile)) return true;
        if (query.isEmpty() || autoExpandSuppressed.contains(tile)) return false;
        for (Feature c : details(tile)) if (matches(c)) return true;
        return false;
    }

    /** New search text clears folds made during the previous search. */
    public void setQueryResettingFolds(String q) {
        autoExpandSuppressed.clear();
        setQuery(q);
    }
}
