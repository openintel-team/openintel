package dev.openintel.viz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class RangeGroupTest {
    public static void run() throws Exception {
        Path dir = Files.createTempDirectory("openintel-coverage-groups");
        try {
            RangeStore store = new RangeStore(dir.resolve("source.json"));
            RangeGroup group = store.createGroup("North border");
            RangeShape a = shape(1, group.id), b = shape(2, group.id), c = shape(3, "");
            b.enabled = false;
            store.add(a); store.add(b); store.add(c);
            check(store.visibleShapes().size() == 2, "Renderer receives individually visible fields");
            store.toggleGroup(group.id);
            check(a.enabled && !b.enabled, "Group toggle preserves individual flags");
            check(store.visibleShapes().equals(List.of(c)), "Hidden group removed from real overlay snapshot");
            store.toggleGroup(group.id);
            check(store.visibleShapes().equals(List.of(a, c)), "Showing group restores exact entry visibility");
            store.renameGroup(group.id, "Northern wall");
            store.toggleGroup(group.id);
            RangeStore loaded = new RangeStore(dir.resolve("source.json")); loaded.load();
            check(loaded.groups().size() == 1 && loaded.groups().get(0).name.equals("Northern wall"), "Group names persist");
            check(!loaded.groups().get(0).enabled && loaded.shapes().get(0).groupId.equals(group.id), "Group flag and membership persist");
            check(loaded.visibleShapes().size() == 1, "Hidden group stays hidden after restart");

            check(store.removeAll(Set.of(a.id, c.id)) == 2, "Bulk deletion counts entries");
            check(store.shapes().equals(List.of(b)) && store.canUndo(), "Deleted batch is undoable");
            check(store.undoDelete().size() == 2, "Undo restores the whole batch");
            check(store.shapes().stream().map(s -> s.x).toList().equals(List.of(1, 2, 3)), "Undo restores original ordering");
            check(store.find(a.id).groupId.equals(group.id) && !store.isVisible(store.find(a.id)), "Undo retains hidden group membership");
            check(!store.canUndo(), "Undo batch consumed");

            Path all = dir.resolve("all.json"); store.exportFile(all);
            RangeStore imported = new RangeStore(dir.resolve("target.json"));
            check(imported.importFile(all) == 3, "Import all entries");
            check(imported.groups().size() == 1 && !imported.groups().get(0).enabled, "Import group visibility");
            check(imported.importFile(all) == 0 && imported.groups().size() == 1, "Repeated import duplicates neither groups nor entries");
            Path single = dir.resolve("single.json"); store.exportFile(single, group.id);
            RangeStore subset = new RangeStore(dir.resolve("subset.json"));
            check(subset.importFile(single) == 2 && subset.groups().size() == 1, "Export just one group");
            check(subset.shapes().stream().allMatch(s -> s.groupId.equals(subset.groups().get(0).id)), "Group subset resolves membership");
            boolean overwrite = false;
            try { store.exportFile(single); } catch (java.nio.file.FileAlreadyExistsException expected) { overwrite = true; }
            check(overwrite, "Export protects existing files");

            RangeStore conflicts = new RangeStore(dir.resolve("conflicts.json"));
            RangeGroup sameName = conflicts.createGroup("Northern wall");
            conflicts.importFile(single);
            check(conflicts.groups().size() == 1 && conflicts.groups().get(0).enabled, "Name conflict reuses local group and preserves its flag");
            check(conflicts.shapes().stream().allMatch(s -> s.groupId.equals(sameName.id)), "Membership remaps to existing named group");
            RangeStore idCollision = new RangeStore(dir.resolve("collision.json"));
            RangeGroup differentName = idCollision.createGroup("Different place"); differentName.id = group.id;
            idCollision.importFile(single);
            check(idCollision.groups().size() == 2, "Different groups with colliding IDs both survive");
            check(idCollision.shapes().stream().noneMatch(s -> s.groupId.equals(differentName.id)), "Colliding group ID is remapped");

            String before = Files.readString(dir.resolve("target.json"));
            Path bad = dir.resolve("bad.json");
            Files.writeString(bad, "{\"version\":2,\"groups\":[],\"shapes\":[{\"type\":\"SNITCH\",\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:overworld\",\"groupId\":\"missing\"}]}");
            boolean rejected = false;
            try { imported.importFile(bad); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected && before.equals(Files.readString(dir.resolve("target.json"))), "Invalid membership cannot partially mutate persistence");
            check(imported.size() == 3 && imported.groups().size() == 1, "Invalid import preserves memory");
            Path legacy = dir.resolve("legacy.json");
            Files.writeString(legacy, "{\"version\":1,\"shapes\":[{\"type\":\"SNITCH\",\"x\":9,\"y\":64,\"z\":8,\"dimension\":\"minecraft:overworld\"}]}");
            check(imported.importFile(legacy) == 1 && imported.shapes().get(3).groupId.isEmpty(), "Version 1 imports as Ungrouped");
            imported.removeGroup(imported.groups().get(0).id);
            check(imported.size() == 4 && imported.shapes().stream().allMatch(s -> s.groupId.isEmpty()), "Removing a group preserves entries");

            Path blocked = Files.createDirectory(dir.resolve("blocked"));
            RangeStore fail = new RangeStore(blocked);
            boolean failed = false;
            try { fail.importFile(single); } catch (java.io.IOException expected) { failed = true; }
            check(failed && fail.size() == 0 && fail.groups().isEmpty(), "Failed disk write leaves import unapplied");
        } finally {
            try (var files = Files.walk(dir)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static RangeShape shape(int x, String group) {
        RangeShape shape = new RangeShape(RangeType.SNITCH, x, 64, 0, "minecraft:overworld"); shape.groupId = group; return shape;
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
