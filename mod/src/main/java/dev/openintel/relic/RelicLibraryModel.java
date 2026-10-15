package dev.openintel.relic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Headless helpers for filtering, ordering, and selecting stored relic maps. */
public final class RelicLibraryModel {
    /** How a selection compares with the empty slots in the player inventory. */
    public enum Carry { ROOM, FULL, OVER }

    /** Count shown on the map, capped at the free slots unless the selection is already over. */
    public record CarryStatus(int shown, Carry level) { }
    private RelicLibraryModel() { }

    public static List<RelicLibraryEntry> filter(List<RelicLibraryEntry> entries, String query) {
        if (entries == null || entries.isEmpty()) return List.of();
        if (query == null || query.isBlank()) return List.copyOf(entries);
        String needle = normalize(query);
        List<RelicLibraryEntry> out = new ArrayList<>();
        for (RelicLibraryEntry entry : entries) {
            if (normalize(entry.title()).contains(needle)
                    || normalize(entry.enchantment()).contains(needle)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Located maps first, closest to the player; unlocated maps last in title order. */
    public static List<RelicLibraryEntry> sortByDistance(List<RelicLibraryEntry> entries,
                                                          double playerX, double playerZ) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<RelicLibraryEntry> copy = new ArrayList<>(entries);
        copy.sort(Comparator
                .comparing((RelicLibraryEntry e) -> !e.located())
                .thenComparingDouble(e -> e.distanceSq(playerX, playerZ))
                .thenComparing(RelicLibraryEntry::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RelicLibraryEntry::id));
        return copy;
    }

    /**
     * One representative entry per located site. Later duplicates of the same
     * block column are dropped from the pin list.
     */
    public static List<RelicLibraryEntry> sitePins(List<RelicLibraryEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        LinkedHashMap<String, RelicLibraryEntry> pins = new LinkedHashMap<>();
        for (RelicLibraryEntry entry : entries) {
            if (!entry.located()) continue;
            pins.putIfAbsent(entry.siteKey(), entry);
        }
        return List.copyOf(pins.values());
    }

    /** All located entries whose site falls inside the inclusive axis-aligned rectangle. */
    public static Set<String> selectInRect(List<RelicLibraryEntry> entries,
                                           double x1, double z1, double x2, double z2) {
        if (entries == null || entries.isEmpty()) return Set.of();
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);
        Set<String> ids = new HashSet<>();
        for (RelicLibraryEntry entry : entries) {
            if (!entry.located()) continue;
            double x = entry.siteX();
            double z = entry.siteZ();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                ids.add(entry.id());
            }
        }
        return ids;
    }

    public static List<RelicLibraryEntry> byIds(List<RelicLibraryEntry> entries, Set<String> ids) {
        if (entries == null || entries.isEmpty() || ids == null || ids.isEmpty()) return List.of();
        List<RelicLibraryEntry> out = new ArrayList<>();
        for (RelicLibraryEntry entry : entries) {
            if (ids.contains(entry.id())) out.add(entry);
        }
        return out;
    }

    /** Focus point for panning the world map: mean of selected located sites, else first located. */
    public static double[] focus(List<RelicLibraryEntry> selected, List<RelicLibraryEntry> all) {
        List<RelicLibraryEntry> located = new ArrayList<>();
        for (RelicLibraryEntry entry : selected) {
            if (entry.located()) located.add(entry);
        }
        if (located.isEmpty() && all != null) {
            for (RelicLibraryEntry entry : all) {
                if (entry.located()) {
                    located.add(entry);
                    break;
                }
            }
        }
        if (located.isEmpty()) return null;
        double sx = 0;
        double sz = 0;
        for (RelicLibraryEntry entry : located) {
            sx += entry.siteX();
            sz += entry.siteZ();
        }
        return new double[]{sx / located.size(), sz / located.size()};
    }

    /**
     * ROOM while the selection still fits, FULL when it uses every free slot,
     * OVER when it needs more slots than are empty.
     */
    public static Carry carry(int selected, int freeSlots) {
        if (selected > freeSlots) return Carry.OVER;
        if (selected == freeSlots) return Carry.FULL;
        return Carry.ROOM;
    }

    /**
     * @param selected maps already committed
     * @param pending  committed maps plus the in-progress lasso
     * @param freeSlots empty main-inventory slots; each treasure map needs one
     */
    public static CarryStatus carryStatus(int selected, int pending, int freeSlots) {
        if (selected > freeSlots) return new CarryStatus(selected, Carry.OVER);
        int shown = Math.min(Math.max(pending, selected), freeSlots);
        return new CarryStatus(shown, carry(shown, freeSlots));
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
