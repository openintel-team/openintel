package dev.openintel.gui;

import dev.openintel.viz.RangeGroup;
import dev.openintel.viz.RangeShape;
import java.util.*;

/** Grouped/filtered row order and multi-selection, independent of rendering and Minecraft. */
public final class CoverageListModel {
    public record Row(String groupId, RangeShape shape, String label, int count) {
        public boolean header() { return shape == null; }
    }
    public final Set<String> selected = new LinkedHashSet<>();
    public final Set<String> collapsed = new HashSet<>();
    public List<Row> rows = List.of();
    private String anchor;

    public void rebuild(List<RangeShape> shapes, List<RangeGroup> groups, String query) {
        Set<String> existing = new HashSet<>();
        for (RangeShape s : shapes) existing.add(s.id);
        selected.retainAll(existing);
        String term = query.trim().toLowerCase(Locale.ROOT);
        List<Row> next = new ArrayList<>();
        append(next, "", "Ungrouped", shapes, term);
        for (RangeGroup g : groups) append(next, g.id, g.name, shapes, term);
        rows = List.copyOf(next);
    }
    private void append(List<Row> target, String id, String name, List<RangeShape> shapes, String term) {
        List<RangeShape> matches = shapes.stream().filter(s -> s.groupId.equals(id))
                .filter(s -> (s.label() + " " + s.x + " " + s.y + " " + s.z + " " + s.dimension + " " + name)
                        .toLowerCase(Locale.ROOT).contains(term)).toList();
        if (!term.isEmpty() && matches.isEmpty()) return;
        target.add(new Row(id, null, name, matches.size()));
        // Search reveals matches even in collapsed groups; the collapse preference is retained.
        if (!collapsed.contains(id) || !term.isEmpty()) for (RangeShape s : matches)
            target.add(new Row(id, s, "", 0));
    }
    public List<RangeShape> visibleEntries() {
        return rows.stream().filter(r -> !r.header()).map(Row::shape).toList();
    }
    public void select(String id, boolean additive, boolean range) {
        List<RangeShape> entries = visibleEntries();
        int a = index(entries, anchor), b = index(entries, id);
        if (range && a >= 0 && b >= 0) {
            if (!additive) selected.clear();
            for (int i = Math.min(a, b); i <= Math.max(a, b); i++) selected.add(entries.get(i).id);
        } else {
            if (!additive) selected.clear();
            if (additive && selected.contains(id)) selected.remove(id); else selected.add(id);
            anchor = id;
        }
    }
    public void selectAll() {
        selected.clear();
        for (RangeShape s : visibleEntries()) selected.add(s.id);
    }
    public void selectAfterDeletion(int oldIndex) {
        selected.clear();
        List<RangeShape> entries = visibleEntries();
        if (!entries.isEmpty()) {
            anchor = entries.get(Math.max(0, Math.min(oldIndex, entries.size() - 1))).id;
            selected.add(anchor);
        }
    }
    private static int index(List<RangeShape> entries, String id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) return i;
        return -1;
    }
}
