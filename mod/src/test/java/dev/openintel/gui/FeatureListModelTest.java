package dev.openintel.gui;

import dev.openintel.feature.Feature;
import dev.openintel.feature.FeatureGroup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Headless checks for the Features screen row model. */
public final class FeatureListModelTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    /** Fake translator: label keys map to a readable name, tooltips mention "tip". */
    private static String tr(String key) {
        if (key.equals(Feature.RADAR.tooltipKey())) return "Dial of nearby players";
        if (key.endsWith(".tooltip")) return "tip " + key;
        if (key.equals(Feature.RADAR.labelKey())) return "Radar";
        if (key.equals(Feature.VIZ_BASTION.labelKey())) return "Bastion fields";
        return key;
    }

    public static void main(String[] args) {
        FeatureListModel model = new FeatureListModel(FeatureListModelTest::tr);

        // Every feature appears exactly once, under one heading per group, in group order.
        List<FeatureListModel.Row> rows = model.rows();
        Set<Feature> seen = new HashSet<>();
        int headings = 0;
        FeatureGroup current = null;
        for (FeatureListModel.Row r : rows) {
            if (r.heading()) {
                headings++;
                check(current == null || r.group().ordinal() > current.ordinal(), "groups in order");
                current = r.group();
                check(!r.collapsed(), "headings start expanded");
            } else {
                check(r.group() == current, r.feature() + " under its own heading");
                check(r.feature().group == current, r.feature() + " group matches");
                check(seen.add(r.feature()), r.feature() + " listed once");
            }
        }
        check(headings == FeatureGroup.values().length, "one heading per group");
        check(seen.size() == Feature.values().length, "every feature listed");

        // Children sit directly after their parent (or a sibling subtree) with depth + 1.
        for (int i = 0; i < rows.size(); i++) {
            FeatureListModel.Row r = rows.get(i);
            if (r.heading() || r.feature().parent == null) {
                if (!r.heading()) check(r.depth() == 0, r.feature() + " top level depth 0");
                continue;
            }
            check(r.depth() == FeatureListModel.depth(r.feature().parent) + 1, r.feature() + " indented");
            int j = i - 1;
            while (j >= 0 && !rows.get(j).heading() && rows.get(j).feature() != r.feature().parent
                    && rows.get(j).depth() >= r.depth()) j--;
            check(j >= 0 && rows.get(j).feature() == r.feature().parent, r.feature() + " follows its parent");
        }
        check(FeatureListModel.depth(Feature.VIZ_SNITCH) == 1, "viz child depth");
        check(FeatureListModel.depth(Feature.RADAR) == 0, "radar depth");

        // Collapsing hides the group's features but keeps its heading.
        model.toggle(FeatureGroup.VISUALS);
        check(model.isCollapsed(FeatureGroup.VISUALS), "collapsed");
        rows = model.rows();
        boolean headingKept = false;
        for (FeatureListModel.Row r : rows) {
            if (r.heading() && r.group() == FeatureGroup.VISUALS) {
                headingKept = true;
                check(r.collapsed(), "heading marked collapsed");
            }
            if (!r.heading()) check(r.group() != FeatureGroup.VISUALS, "collapsed group hides rows");
        }
        check(headingKept, "collapsed heading still shown");
        model.toggle(FeatureGroup.VISUALS);
        check(!model.isCollapsed(FeatureGroup.VISUALS), "expanded again");

        // Search matches labels case-insensitively and keeps only matching headings.
        model.toggle(FeatureGroup.VISUALS);  // collapsed groups still show matches while searching
        model.setQuery("  RADAR ");
        rows = model.rows();
        check(rows.size() == 2, "radar search: heading + row, got " + rows.size());
        check(rows.get(0).heading() && rows.get(0).group() == FeatureGroup.VISUALS, "radar heading");
        check(!rows.get(0).collapsed(), "search shows matches expanded");
        check(rows.get(1).feature() == Feature.RADAR, "radar row");

        // Search matches tooltip text too.
        model.setQuery("nearby players");
        rows = model.rows();
        check(rows.size() == 2 && rows.get(1).feature() == Feature.RADAR, "tooltip search");

        // A matching child keeps its indentation without its parent row.
        model.setQuery("bastion");
        rows = model.rows();
        check(rows.size() == 2 && rows.get(1).feature() == Feature.VIZ_BASTION
                && rows.get(1).depth() == 1, "child match");

        model.setQuery("zzz-no-such-feature");
        check(model.rows().isEmpty(), "no match shows nothing");

        model.setQuery("");
        rows = model.rows();
        for (FeatureListModel.Row r : rows) {
            if (!r.heading()) check(r.group() != FeatureGroup.VISUALS, "collapse remembered after search");
        }

        tiles();
        System.out.println("FeatureListModelTest: " + checks + " checks passed");
    }

    /** Jade-style grid: top-level tiles per heading, sub-options inside expandable tiles. */
    private static void tiles() {
        FeatureListModel model = new FeatureListModel(FeatureListModelTest::tr);
        List<FeatureListModel.Section> sections = model.sections();
        check(sections.size() == FeatureGroup.values().length, "one section per heading");
        Set<Feature> shown = new HashSet<>();
        for (FeatureListModel.Section s : sections) {
            for (Feature f : s.tiles()) {
                check(f.parent == null && f.group == s.group(), "tile is top-level in its group: " + f);
                check(shown.add(f), "tile once: " + f);
                for (Feature d : FeatureListModel.details(f)) check(shown.add(d), "detail once: " + d);
            }
        }
        check(shown.size() == Feature.values().length, "every feature reachable on the grid");

        check(FeatureListModel.expandable(Feature.VISUALIZATIONS), "viz has sub-options");
        check(!FeatureListModel.expandable(Feature.RADAR), "radar has none");
        check(FeatureListModel.details(Feature.VISUALIZATIONS).contains(Feature.VIZ_BASTION), "bastion inside viz");
        check(!model.isExpanded(Feature.VISUALIZATIONS), "tiles start folded");
        model.toggleExpanded(Feature.VISUALIZATIONS);
        check(model.isExpanded(Feature.VISUALIZATIONS), "expanded");
        model.toggleExpanded(Feature.RADAR);
        check(!model.isExpanded(Feature.RADAR), "leaf tiles never expand");
        model.toggleExpanded(Feature.VISUALIZATIONS);

        // A search hitting only a sub-option shows its parent tile, opened, in that section only.
        model.setQueryResettingFolds("bastion");
        sections = model.sections();
        check(sections.size() == 1 && sections.get(0).tiles().equals(List.of(Feature.VISUALIZATIONS)), "parent tile shown");
        check(model.isExpanded(Feature.VISUALIZATIONS), "auto-opened for a sub-option match");
        model.toggleExpanded(Feature.VISUALIZATIONS);
        check(!model.isExpanded(Feature.VISUALIZATIONS), "user can fold an auto-opened tile");
        model.setQueryResettingFolds("bastion f");
        check(model.isExpanded(Feature.VISUALIZATIONS), "new search reopens it");
        model.setQueryResettingFolds("radar");
        check(!model.isExpanded(Feature.VISUALIZATIONS) && model.sections().get(0).tiles().contains(Feature.RADAR), "direct match");
        model.setQueryResettingFolds("zzz-none");
        check(model.sections().isEmpty(), "no match, no sections");

        // A setting on a tile's page finds the tile, and names the setting.
        model.setSettingsText(f -> f == Feature.RADAR ? List.of("North up", "Keep north at the top", "Range", "") : List.of());
        model.setQueryResettingFolds("north");
        sections = model.sections();
        check(sections.size() == 1 && sections.get(0).tiles().equals(List.of(Feature.RADAR)), "setting finds its tile");
        check("North up".equals(model.settingMatch(Feature.RADAR)), "matched setting named");
        model.setQueryResettingFolds("at the top");
        check("North up".equals(model.settingMatch(Feature.RADAR)), "tooltip text matches too");
        model.setQueryResettingFolds("");
        check(model.settingMatch(Feature.RADAR) == null, "no search, no setting match");

        // Two sub-option matches in different tiles open both.
        model.setSettingsText(null);
        model.setQueryResettingFolds("tip openintel.features.viz_bastion");
        check(model.isExpanded(Feature.VISUALIZATIONS), "viz opens for its sub-option");
    }
}
