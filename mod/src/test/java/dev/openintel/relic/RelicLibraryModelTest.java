package dev.openintel.relic;

import java.util.List;
import java.util.Set;

/** Standalone assertions for the relic library model. */
public final class RelicLibraryModelTest {
    public static void main(String[] args) {
        RelicLibraryEntry a = entry("a", "Lunar Wand", "Fire Protection", 100, 0);
        RelicLibraryEntry b = entry("b", "Gold Coin", "Looting", 200, 0);
        RelicLibraryEntry c = entry("c", "Lunar Wand", "Fire Protection", 100, 0);
        RelicLibraryEntry d = entry("d", "Vorpal Sword", "Vorpal", null, null);
        RelicLibraryEntry e = entry("e", "Pixie Dust", "Efficiency", 50, 50);

        List<RelicLibraryEntry> all = List.of(a, b, c, d, e);

        List<RelicLibraryEntry> lunar = RelicLibraryModel.filter(all, "lunar");
        assert lunar.size() == 2;
        assert lunar.get(0).id().equals("a");
        assert RelicLibraryModel.filter(all, "fire").size() == 2;
        assert RelicLibraryModel.filter(all, "nope").isEmpty();
        assert RelicLibraryModel.filter(all, "").size() == 5;

        List<RelicLibraryEntry> ordered = RelicLibraryModel.sortByDistance(all, 0, 0);
        assert ordered.get(0).id().equals("e");
        assert ordered.get(1).id().equals("a") || ordered.get(1).id().equals("c");
        assert ordered.get(ordered.size() - 1).id().equals("d");

        List<RelicLibraryEntry> pins = RelicLibraryModel.sitePins(all);
        assert pins.size() == 3;
        assert pins.stream().noneMatch(p -> p.id().equals("c"));
        assert pins.stream().noneMatch(p -> !p.located());

        Set<String> box = RelicLibraryModel.selectInRect(all, 90, -10, 110, 10);
        assert box.contains("a");
        assert box.contains("c");
        assert !box.contains("b");
        assert !box.contains("d");
        assert !box.contains("e");

        Set<String> emptyBox = RelicLibraryModel.selectInRect(all, 1000, 1000, 1001, 1001);
        assert emptyBox.isEmpty();

        double[] focus = RelicLibraryModel.focus(List.of(a, b), all);
        assert focus != null;
        assert Math.abs(focus[0] - 150) < 0.01;
        assert Math.abs(focus[1] - 0) < 0.01;
        assert RelicLibraryModel.focus(List.of(d), List.of(d)) == null;

        RelicLibraryVisit visit = RelicLibraryVisit.start(all, java.util.Map.of());
        visit.selectOnly("a");
        visit.toggle("b");
        assert visit.selectedIds().contains("a");
        assert visit.selectedIds().contains("b");
        visit.toggle("a");
        assert !visit.selectedIds().contains("a");
        visit.beginLasso(90, -10, 0, 0);
        visit.updateLasso(110, 10, 20, 20);
        Set<String> lasso = visit.finishLasso();
        assert lasso.contains("c");
        assert visit.selectedIds().contains("c");
        assert visit.selectedIds().contains("b");

        RelicLibraryVisit picks = RelicLibraryVisit.start(all, java.util.Map.of());
        picks.clickRow("a", false);
        assert picks.selectedIds().equals(Set.of("a"));
        picks.clickRow("a", false);
        assert picks.selectedIds().isEmpty();
        picks.clickRow("a", false);
        picks.clickRow("b", true);
        picks.clickRow("c", true);
        assert picks.selectedIds().equals(Set.of("a", "b", "c"));
        picks.clickRow("b", false);
        assert !picks.isSelected("b");
        assert picks.isSelected("a");
        assert picks.isSelected("c");
        picks.clickRow("d", false);
        assert picks.selectedIds().equals(Set.of("d"));
        picks.clearSelection();
        assert picks.selectedIds().isEmpty();

        assert RelicLibraryModel.carry(0, 27) == RelicLibraryModel.Carry.ROOM;
        assert RelicLibraryModel.carry(26, 27) == RelicLibraryModel.Carry.ROOM;
        assert RelicLibraryModel.carry(27, 27) == RelicLibraryModel.Carry.FULL;
        assert RelicLibraryModel.carry(0, 0) == RelicLibraryModel.Carry.FULL;
        assert RelicLibraryModel.carry(28, 27) == RelicLibraryModel.Carry.OVER;

        RelicLibraryModel.CarryStatus under = RelicLibraryModel.carryStatus(0, 12, 27);
        assert under.shown() == 12 && under.level() == RelicLibraryModel.Carry.ROOM;
        RelicLibraryModel.CarryStatus capped = RelicLibraryModel.carryStatus(4, 40, 27);
        assert capped.shown() == 27 && capped.level() == RelicLibraryModel.Carry.FULL;
        RelicLibraryModel.CarryStatus over = RelicLibraryModel.carryStatus(30, 30, 27);
        assert over.shown() == 30 && over.level() == RelicLibraryModel.Carry.OVER;

        RelicLibraryVisit hunt = RelicLibraryVisit.start(all, java.util.Map.of());
        hunt.selectOnly("b");
        hunt.beginLasso(40, -10, 0, 0);
        hunt.updateLasso(120, 60, 30, 30);
        assert hunt.pendingSelectionCount() == 4;
        assert hunt.selectedIds().size() == 1;
        assert hunt.crossedFull(RelicLibraryModel.Carry.ROOM) == false;
        assert hunt.crossedFull(RelicLibraryModel.Carry.FULL);
        assert hunt.crossedFull(RelicLibraryModel.Carry.FULL) == false;
        Set<String> kept = hunt.finishLasso(3, 0, 0);
        assert kept.size() == 2;
        assert hunt.selectedIds().size() == 3;
        assert hunt.isSelected("e");
        assert hunt.isSelected("b");

        // An oversized selection is cut back to the live empty-slot count, closest first.
        RelicLibraryVisit packed = RelicLibraryVisit.start(all, java.util.Map.of());
        packed.selectIds(Set.of("a", "b", "c", "e"));
        packed.enforceCapacity(2, 0, 0);
        assert packed.selectedIds().size() == 2;
        assert packed.isSelected("e");
        assert packed.isSelected("a") || packed.isSelected("c");
        assert !packed.isSelected("b");

        // Shift-click stops when no empty slot is left.
        RelicLibraryVisit clicks = RelicLibraryVisit.start(all, java.util.Map.of());
        assert clicks.clickRow("a", true, 1);
        assert !clicks.clickRow("b", true, 1);
        assert clicks.selectedIds().equals(Set.of("a"));
        assert clicks.clickRow("a", true, 1);
        assert clicks.selectedIds().isEmpty();

        // A full inventory still accepts a new lasso by swapping in the maps in that box.
        RelicLibraryVisit swap = RelicLibraryVisit.start(all, java.util.Map.of());
        swap.selectIds(Set.of("a", "e"));
        swap.beginLasso(180, -10, 0, 0);
        swap.updateLasso(220, 10, 10, 10);
        swap.finishLasso(2, 0, 0);
        assert swap.selectedIds().size() == 2;
        assert swap.isSelected("b");

        System.out.println("RelicLibraryModelTest OK");
    }

    private static RelicLibraryEntry entry(String id, String title, String enchant,
                                           Integer x, Integer z) {
        return new RelicLibraryEntry(id, title, enchant, x, z, "minecraft:overworld", 0, 64, 0);
    }
}
