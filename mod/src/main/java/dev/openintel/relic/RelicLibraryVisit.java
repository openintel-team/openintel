package dev.openintel.relic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client session for the relic library and an optional fullscreen-map visit.
 * Stacks for Where Is It searches are held separately from the headless entries.
 */
public final class RelicLibraryVisit {
    private static RelicLibraryVisit active;

    private final List<RelicLibraryEntry> entries;
    private final Map<String, Object> stackById;
    private final Set<String> selected = new HashSet<>();
    private boolean mapVisit;
    private boolean lassoDragging;
    private double lassoStartX;
    private double lassoStartZ;
    private double lassoEndX;
    private double lassoEndZ;
    private int lassoScreenX0;
    private int lassoScreenY0;
    private int lassoScreenX1;
    private int lassoScreenY1;
    private RelicLibraryModel.Carry announcedCarry;

    private RelicLibraryVisit(List<RelicLibraryEntry> entries, Map<String, Object> stackById) {
        this.entries = List.copyOf(entries);
        this.stackById = Map.copyOf(stackById);
    }

    public static RelicLibraryVisit start(List<RelicLibraryEntry> entries, Map<String, Object> stackById) {
        active = new RelicLibraryVisit(entries, stackById == null ? Map.of() : stackById);
        return active;
    }

    public static RelicLibraryVisit current() {
        return active;
    }

    public static boolean mapVisitActive() {
        return active != null && active.mapVisit;
    }

    public static void clear() {
        active = null;
    }

    public List<RelicLibraryEntry> entries() {
        return entries;
    }

    public Map<String, Object> stackById() {
        return stackById;
    }

    public Set<String> selectedIds() {
        return Collections.unmodifiableSet(selected);
    }

    public List<RelicLibraryEntry> selectedEntries() {
        return RelicLibraryModel.byIds(entries, selected);
    }

    public void selectOnly(String id) {
        selected.clear();
        if (id != null) selected.add(id);
    }

    public void toggle(String id) {
        if (id == null) return;
        if (!selected.add(id)) selected.remove(id);
    }

    /**
     * Plain click selects that map, or drops it when it is already selected.
     * Shift-click toggles one map without clearing the others.
     */
    public void clickRow(String id, boolean shift) {
        clickRow(id, shift, Integer.MAX_VALUE);
    }

    /**
     * @param maxTotal empty inventory slots; shift-click will not select past this
     * @return false when a shift-click could not add the map because every slot is taken
     */
    public boolean clickRow(String id, boolean shift, int maxTotal) {
        if (id == null) return false;
        if (maxTotal < 0) maxTotal = 0;
        if (!shift) {
            if (isSelected(id)) {
                selected.remove(id);
                return true;
            }
            if (maxTotal <= 0) return false;
            selectOnly(id);
            return true;
        }
        if (isSelected(id)) {
            selected.remove(id);
            return true;
        }
        if (selected.size() >= maxTotal) return false;
        selected.add(id);
        return true;
    }

    /**
     * Drops the farthest selected maps until {@code maxTotal} remain.
     * {@code maxTotal} is the current number of empty inventory slots.
     */
    public void enforceCapacity(int maxTotal, double playerX, double playerZ) {
        if (maxTotal < 0) maxTotal = 0;
        if (selected.size() <= maxTotal) return;
        List<RelicLibraryEntry> chosen = new ArrayList<>(selectedEntries());
        chosen.sort(nearest(playerX, playerZ));
        selected.clear();
        for (int i = 0; i < maxTotal && i < chosen.size(); i++) {
            selected.add(chosen.get(i).id());
        }
    }

    public void clearSelection() {
        selected.clear();
    }

    public void selectIds(Set<String> ids) {
        selected.clear();
        if (ids != null) selected.addAll(ids);
    }

    public void addIds(Set<String> ids) {
        if (ids != null) selected.addAll(ids);
    }

    public boolean isSelected(String id) {
        return id != null && selected.contains(id);
    }

    public void beginMapVisit() {
        mapVisit = true;
    }

    public void endMapVisit() {
        mapVisit = false;
        clearLasso();
    }

    public boolean isMapVisit() {
        return mapVisit;
    }

    public void beginLasso(double worldX, double worldZ, int screenX, int screenY) {
        lassoDragging = true;
        lassoStartX = lassoEndX = worldX;
        lassoStartZ = lassoEndZ = worldZ;
        lassoScreenX0 = lassoScreenX1 = screenX;
        lassoScreenY0 = lassoScreenY1 = screenY;
    }

    public void updateLasso(double worldX, double worldZ, int screenX, int screenY) {
        if (!lassoDragging) return;
        lassoEndX = worldX;
        lassoEndZ = worldZ;
        lassoScreenX1 = screenX;
        lassoScreenY1 = screenY;
    }

    /** Committed selection plus whatever the current lasso box would add. */
    public int pendingSelectionCount() {
        if (!lassoDragging) return selected.size();
        Set<String> combined = new HashSet<>(selected);
        combined.addAll(RelicLibraryModel.selectInRect(entries, lassoStartX, lassoStartZ, lassoEndX, lassoEndZ));
        return combined.size();
    }

    /** True only on the transition into a full inventory, so the chime plays once. */
    public boolean crossedFull(RelicLibraryModel.Carry level) {
        boolean crossed = level == RelicLibraryModel.Carry.FULL && announcedCarry != RelicLibraryModel.Carry.FULL;
        announcedCarry = level;
        return crossed;
    }

    public Set<String> finishLasso() {
        return finishLasso(Integer.MAX_VALUE, 0, 0);
    }

    /**
     * Adds lasso hits until {@code maxTotal} maps are selected. Extra hits are
     * dropped, closest to the player first, because each map needs its own slot.
     */
    public Set<String> finishLasso(int maxTotal, double playerX, double playerZ) {
        if (!lassoDragging) return Set.of();
        lassoDragging = false;
        if (maxTotal < 0) maxTotal = 0;
        Set<String> hit = RelicLibraryModel.selectInRect(entries, lassoStartX, lassoStartZ, lassoEndX, lassoEndZ);
        List<RelicLibraryEntry> fresh = new ArrayList<>();
        for (RelicLibraryEntry entry : entries) {
            if (hit.contains(entry.id()) && !selected.contains(entry.id())) fresh.add(entry);
        }
        fresh.sort(nearest(playerX, playerZ));

        // Already at the live slot count: this drag chooses which maps get the slots.
        if (!fresh.isEmpty() && selected.size() >= maxTotal) {
            List<RelicLibraryEntry> ranked = new ArrayList<>();
            for (RelicLibraryEntry entry : entries) {
                if (hit.contains(entry.id()) || selected.contains(entry.id())) ranked.add(entry);
            }
            ranked.sort(Comparator.comparing((RelicLibraryEntry entry) -> !hit.contains(entry.id()))
                    .thenComparing(nearest(playerX, playerZ)));
            Set<String> previous = new HashSet<>(selected);
            selected.clear();
            Set<String> keep = new LinkedHashSet<>();
            for (RelicLibraryEntry entry : ranked) {
                if (selected.size() >= maxTotal) break;
                selected.add(entry.id());
                if (hit.contains(entry.id()) && !previous.contains(entry.id())) keep.add(entry.id());
            }
            return keep;
        }

        enforceCapacity(maxTotal, playerX, playerZ);
        int room = maxTotal - selected.size();
        if (room <= 0) return Set.of();
        Set<String> keep = new LinkedHashSet<>();
        for (RelicLibraryEntry entry : fresh) {
            if (keep.size() >= room) break;
            keep.add(entry.id());
        }
        addIds(keep);
        return keep;
    }

    private static Comparator<RelicLibraryEntry> nearest(double playerX, double playerZ) {
        return Comparator.comparingDouble((RelicLibraryEntry entry) -> entry.distanceSq(playerX, playerZ))
                .thenComparing(RelicLibraryEntry::id);
    }

    public void clearLasso() {
        lassoDragging = false;
    }

    public boolean isLassoDragging() {
        return lassoDragging;
    }

    public int[] lassoScreenRect() {
        return new int[]{
                Math.min(lassoScreenX0, lassoScreenX1),
                Math.min(lassoScreenY0, lassoScreenY1),
                Math.max(lassoScreenX0, lassoScreenX1),
                Math.max(lassoScreenY0, lassoScreenY1)
        };
    }

    /** Stacks for the current selection, in stable entry order. */
    public List<Object> selectedStacks() {
        List<Object> stacks = new ArrayList<>();
        for (RelicLibraryEntry entry : selectedEntries()) {
            Object stack = stackById.get(entry.id());
            if (stack != null) stacks.add(stack);
        }
        return stacks;
    }

    public static Map<String, Object> stackMap(LinkedHashMap<String, Object> stacks) {
        return stacks == null ? Map.of() : Map.copyOf(stacks);
    }
}
