package dev.openintel.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.viz.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Executes real editor click/delete/undo paths against an isolated temporary store. */
public final class VisualizationsInteractionTest {
    public static void run() throws Exception {
        Path dir = Files.createTempDirectory("openintel-coverage-ui");
        try {
            RangeStore store = new RangeStore(dir.resolve("ranges.json"));
            RangeShape a = shape(1), b = shape(2), c = shape(3); c.snitchKind = SnitchKind.LOGGING;
            store.add(a); store.add(b); store.add(c);
            VisualizationsScreen screen = allocate(VisualizationsScreen.class);
            CoverageListModel model = new CoverageListModel();
            set(Screen.class, screen, "children", new ArrayList<>());
            set(screen, "store", store); set(screen, "model", model); set(screen, "query", ""); set(screen, "activeGroup", "");
            set(screen, "listW", 300); set(screen, "listH", 120); set(screen, "editorW", 250);
            set(screen, "draftDim", "minecraft:overworld"); set(screen, "draftGroup", "");
            set(screen, "kind", VisualizationsScreen.EntryKind.SNITCH);
            for (String name : List.of("xField", "yField", "zField", "dimensionField", "radiusField")) set(screen, name, allocate(TextField.class));
            for (String name : List.of("typeButton", "dimensionButton", "groupButton", "autoButton", "targetButton", "saveButton", "deleteButton", "undoButton", "visibilityButton", "moveButton", "groupsButton", "exportButton", "groupCreateButton", "bulkDeleteButton"))
                set(screen, name, Button.builder(Component.empty(), button -> {}).bounds(0, 0, 100, 20).build());
            call(screen, "refreshRows");
            // Ungrouped header is row 0; first entry is row 1. Actual version constants.
            check(click(screen, 70, 118, 0), "Left-click selects real row");
            check(a.id.equals(get(screen, "selectedId")), "Clicked entry selected");
            check(((Button) get(screen, "deleteButton")).active, "Selection enables Delete");
            check("1".equals(((EditBox) get(screen, "xField")).getValue()), "Editor populated");
            check("Snitch".equals(((Button) get(screen, "typeButton")).getMessage().getString().trim()), "Single type picker names Snitch");
            click(screen, 70, 178, InputConstants.MOD_CONTROL);
            check(model.selected.equals(new LinkedHashSet<>(List.of(a.id, c.id))), "Ctrl-click creates a multiple selection");
            check(!((Button) get(screen, "saveButton")).active, "Bulk selection cannot accidentally overwrite coordinates");
            call(screen, "deleteEntry");
            check(store.shapes().equals(List.of(b)), "Real bulk Delete removes selected entries only");
            check(model.selected.equals(Set.of(b.id)), "Delete selects the next remaining row");
            check(((Button) get(screen, "deleteButton")).active, "Delete stays enabled");
            call(screen, "undoDeletion");
            check(store.shapes().stream().map(s -> s.x).toList().equals(List.of(1, 2, 3)), "Real Undo restores order");
            check(model.selected.equals(Set.of(a.id, c.id)), "Undo selects restored batch");
            Set<String> restoredSelection = Set.copyOf(model.selected);
            call(screen, "undoDeletion");
            check(model.selected.equals(restoredSelection), "Undo with no history preserves selection");
            // Checkbox is immediate and does not alter selection.
            Set<String> selected = Set.copyOf(model.selected);
            click(screen, 34, 118, 0);
            check(!store.find(a.id).enabled && model.selected.equals(selected), "Row checkbox toggles visibility without selection changes");
            check("Visible: Mixed".equals(((Button) get(screen, "visibilityButton")).getMessage().getString()), "Combined toggle indicates mixed visibility");
            call(screen, "toggleSelectedVisibility");
            check(store.find(a.id).enabled && store.find(c.id).enabled, "Mixed selection toggles all selected entries on");
            check("Visible: On".equals(((Button) get(screen, "visibilityButton")).getMessage().getString()), "Toggle indicates On");
            call(screen, "toggleSelectedVisibility");
            check(!store.find(a.id).enabled && !store.find(c.id).enabled, "Second toggle hides all selected entries");
            call(screen, "toggleSelectedVisibility");
            check(store.find(a.id).enabled && store.find(c.id).enabled, "Off toggles back on");
            RangeGroup group = store.createGroup("North");
            store.mutateAll(Set.of(a.id, c.id), s -> s.groupId = group.id);
            call(screen, "refreshRows");
            int header = rowOfGroup(model, group.id);
            set(screen, "scroll", Math.max(0, header * 30 - 30));
            click(screen, 34, 82 + header * 30 - (int) get(screen, "scroll") + 8, 0);
            check(!group.enabled && store.find(c.id).enabled, "Group checkbox preserves child visibility flags");
            check(!store.isVisible(store.find(c.id)), "Group switch reaches real renderer visibility");
            call(screen, "refreshRows");
            check(a.label().startsWith("Snitch ") && c.label().startsWith("Logging Snitch "), "Exact requested names");
            model.selected.clear(); model.selected.add(b.id); set(screen, "selectedId", b.id);
            call(screen, "deleteEntry");
            check(!model.selected.isEmpty(), "Deleting with other grouped entries keeps a successor");
            model.selectAll(); call(screen, "deleteEntry");
            check(store.size() == 0 && model.selected.isEmpty(), "Deleting final batch clears selection");
            check(!((Button) get(screen, "deleteButton")).active, "Empty list disables Delete");
            modelChecks();
            layoutChecks(store);
            autoFindChecks();
            nameCounterChecks();
        } finally {
            try (var paths = Files.walk(dir)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static void layoutChecks(RangeStore store) throws Exception {
        for (int i = 0; i < 20; i++) store.createGroup("Group " + i);
        for (int[] size : new int[][]{{320, 270}, {480, 270}, {960, 300}, {320, 360}}) {
            int width = size[0], screenH = size[1];
            VisualizationsScreen screen = allocate(VisualizationsScreen.class);
            set(Screen.class, screen, "children", new ArrayList<>());
            set(Screen.class, screen, "narratables", new ArrayList<>());
            set(Screen.class, screen, "renderables", new ArrayList<>());
            set(Screen.class, screen, "width", width); set(Screen.class, screen, "height", screenH);
            set(screen, "store", store); set(screen, "model", new CoverageListModel());
            set(screen, "query", ""); set(screen, "activeGroup", ""); set(screen, "initialized", true);
            set(screen, "draftX", "0"); set(screen, "draftY", "64"); set(screen, "draftZ", "0");
            set(screen, "draftDim", "minecraft:overworld"); set(screen, "draftGroup", ""); set(screen, "draftRadius", "50");
            set(screen, "kind", VisualizationsScreen.EntryKind.SANCTUARY);
            // Vanilla EditBox supports a null font for headless layout; no renderer is called.
            screen.init();
            List<net.minecraft.client.gui.components.AbstractWidget> widgets = screen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).filter(w -> w.visible).toList();
            for (var widget : widgets) {
                check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getWidth() > 0
                        && widget.getX() + widget.getWidth() <= width && widget.getY() + widget.getHeight() <= screenH,
                        "Visible widgets fit minimum viewport: " + widget.getMessage().getString());
            }
            for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
                var a = widgets.get(i); var b = widgets.get(j);
                boolean overlap = a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                        && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY();
                check(!overlap, "Widgets must not overlap at width " + width + ": " + a.getMessage().getString() + " / " + b.getMessage().getString());
            }
            check((int) get(screen, "editorW") <= 250, "Inspector width is bounded on wide windows");
            placementChecks(screen, width, screenH);
            dropdownChecks(screen, store, width, screenH);
        }
    }
    private static void placementChecks(VisualizationsScreen screen, int width, int height) throws Exception {
        Button type = (Button) get(screen, "typeButton");
        Button auto = (Button) get(screen, "autoButton");
        Button look = (Button) get(screen, "targetButton");
        check("Auto find".equals(auto.getMessage().getString()), "Auto find sits with the type controls");
        check("Target looking at".equals(look.getMessage().getString()), "Look target is named");
        check(auto.getY() > type.getY() && look.getY() == auto.getY() && look.getX() >= auto.getX() + auto.getWidth(),
                "Auto find is between the type dropdown and the look-target button");
        Button done = null;
        for (var child : screen.children()) if (child instanceof Button button && "Done".equals(button.getMessage().getString())) done = button;
        check(done != null && done.getY() >= height - 32 && done.getX() + done.getWidth() >= width - 16 && done.getY() + done.getHeight() <= height,
                "Done is bottom right");
    }
    private static void dropdownChecks(VisualizationsScreen screen, RangeStore store, int width, int height) throws Exception {
        set(screen, "draftX", "12");
        String originalX = "12";
        call(screen, "chooseType");
        Object popup = get(screen, "dropdown");
        DropdownLayout bounds = popupLayout(popup);
        check(bounds.x() >= 4 && bounds.y() >= 4 && bounds.x() + bounds.width() <= width - 4
                && bounds.y() + bounds.height() <= height - 4, "Type popup fits minimum viewport");
        check(bounds.y() >= 84, "Type popup anchors below its button when space permits");
        check(click(screen, bounds.x() + 8, bounds.rowsY() + 26, 0), "Actual popup handles selection click");
        check(get(screen, "kind") == VisualizationsScreen.EntryKind.LOGGING && get(screen, "dropdown") == null, "Popup picks Logging Snitch without opening another screen");
        check(originalX.equals(get(screen, "draftX")), "Type change keeps coordinates when no matching block is loaded");
        call(screen, "chooseType");
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_DOWN, 0, 0));
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        check(get(screen, "kind") == VisualizationsScreen.EntryKind.BASTION && get(screen, "dropdown") == null, "Arrow keys and Enter select popup choices");
        call(screen, "chooseType");
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
        check(get(screen, "dropdown") == null && get(screen, "kind") == VisualizationsScreen.EntryKind.BASTION, "Escape dismisses without changing selection");
        call(screen, "chooseType");
        click(screen, 1, 1, 0);
        check(get(screen, "dropdown") == null && get(screen, "kind") == VisualizationsScreen.EntryKind.BASTION, "Outside click dismisses without click-through");
        call(screen, "chooseDimension");
        bounds = popupLayout(get(screen, "dropdown"));
        click(screen, bounds.x() + 8, bounds.rowsY() + 26, 0);
        check("minecraft:the_nether".equals(get(screen, "draftDim")), "Dimension dropdown selects Nether in-place");
        var chooseGroup = VisualizationsScreen.class.getDeclaredMethod("chooseGroup", boolean.class); chooseGroup.setAccessible(true); chooseGroup.invoke(screen, false);
        popup = get(screen, "dropdown"); bounds = popupLayout(popup);
        check(bounds.y() < ((Button) get(screen, "groupButton")).getY() && bounds.searchHeight() > 0, "Long group list opens upwards with search");
        var field = popup.getClass().getDeclaredField("search"); field.setAccessible(true);
        ((EditBox) field.get(popup)).setValue("Group 17");
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        check(store.group((String) get(screen, "draftGroup")).name.equals("Group 17"), "Search filters long dropdown lists");
        DropdownLayout end = DropdownLayout.fit(width - 10, 220, 100, 20, width, 240, 80, true);
        check(end.x() + end.width() <= width - 4 && end.y() + end.height() <= 236 && end.y() >= 4, "Popup clamps to screen edges");
    }
    private static DropdownLayout popupLayout(Object popup) throws Exception {
        var field = popup.getClass().getDeclaredField("layout"); field.setAccessible(true); return (DropdownLayout) field.get(popup);
    }
    private static void autoFindChecks() throws Exception {
        check(NearbyBlockSearch.material(VisualizationsScreen.EntryKind.SNITCH) == NearbyBlockSearch.Material.OBSIDIAN, "Snitch searches obsidian");
        check(NearbyBlockSearch.material(VisualizationsScreen.EntryKind.LOGGING) == NearbyBlockSearch.Material.OBSIDIAN, "Logging snitch searches obsidian");
        check(NearbyBlockSearch.material(VisualizationsScreen.EntryKind.BASTION) == NearbyBlockSearch.Material.SPONGE, "Bastion searches sponge");
        check(NearbyBlockSearch.material(VisualizationsScreen.EntryKind.SANCTUARY) == NearbyBlockSearch.Material.BEACON, "Sanctuary searches beacon");
        check(NearbyBlockSearch.matchesId(NearbyBlockSearch.Material.SPONGE, "minecraft:sponge"), "Dry sponge is a bastion block");
        check(NearbyBlockSearch.matchesId(NearbyBlockSearch.Material.SPONGE, "minecraft:wet_sponge"), "Wet sponge is a bastion block");
        check(NearbyBlockSearch.matchesId(NearbyBlockSearch.Material.OBSIDIAN, "minecraft:obsidian"), "Obsidian is a snitch block");
        check(!NearbyBlockSearch.matchesId(NearbyBlockSearch.Material.OBSIDIAN, "minecraft:crying_obsidian"), "Crying obsidian is not a snitch block");
        check(NearbyBlockSearch.matchesId(NearbyBlockSearch.Material.BEACON, "minecraft:beacon"), "Beacon block is a sanctuary");
        check(!NearbyBlockSearch.matchesId(NearbyBlockSearch.Material.BEACON, "minecraft:sponge"), "Sponge is not a sanctuary");
        MemoryWorld world = new MemoryWorld();
        world.place(NearbyBlockSearch.Material.SPONGE, 1, 0, 0);
        world.place(NearbyBlockSearch.Material.OBSIDIAN, 4, 0, 0);
        world.place(NearbyBlockSearch.Material.OBSIDIAN, 30, 0, 0);
        world.place(NearbyBlockSearch.Material.BEACON, 0, 5, 0);
        world.place(NearbyBlockSearch.Material.OBSIDIAN, 0, 10, 0);
        world.place(NearbyBlockSearch.Material.OBSIDIAN, 0, -10, 0);
        world.place(NearbyBlockSearch.Material.OBSIDIAN, -5, 1, 0);
        world.place(NearbyBlockSearch.Material.OBSIDIAN, 5, 1, 0);
        world.place(NearbyBlockSearch.Material.SPONGE, 0, -60, 0);
        NearbyBlockSearch.Hit obsidian = NearbyBlockSearch.nearest(0, 0, 0, NearbyBlockSearch.Material.OBSIDIAN, world);
        check(obsidian != null && obsidian.x() == 4 && obsidian.y() == 0 && obsidian.z() == 0, "Nearest obsidian skips closer sponge and farther obsidian");
        MemoryWorld ties = new MemoryWorld();
        ties.place(NearbyBlockSearch.Material.OBSIDIAN, 0, 10, 0);
        ties.place(NearbyBlockSearch.Material.OBSIDIAN, 0, -10, 0);
        NearbyBlockSearch.Hit lower = NearbyBlockSearch.nearest(0, 0, 0, NearbyBlockSearch.Material.OBSIDIAN, ties);
        check(lower != null && lower.x() == 0 && lower.y() == -10 && lower.z() == 0, "Equal distance prefers the lower block");
        ties.blocks.clear();
        ties.place(NearbyBlockSearch.Material.OBSIDIAN, -5, 1, 0);
        ties.place(NearbyBlockSearch.Material.OBSIDIAN, 5, 1, 0);
        NearbyBlockSearch.Hit left = NearbyBlockSearch.nearest(0, 0, 0, NearbyBlockSearch.Material.OBSIDIAN, ties);
        check(left != null && left.x() == -5 && left.y() == 1, "Equal distance then prefers the lesser x");
        MemoryWorld buried = new MemoryWorld();
        buried.place(NearbyBlockSearch.Material.SPONGE, 2, -60, -3);
        NearbyBlockSearch.Hit deep = NearbyBlockSearch.nearest(0, 64, 0, NearbyBlockSearch.Material.SPONGE, buried);
        check(deep != null && deep.x() == 2 && deep.y() == -60 && deep.z() == -3, "Search finds sponge below the origin section");
        MemoryWorld limited = new MemoryWorld();
        limited.radius = 1;
        limited.place(NearbyBlockSearch.Material.BEACON, 5, 0, 0);
        limited.place(NearbyBlockSearch.Material.BEACON, 48, 0, 0);
        NearbyBlockSearch.Hit near = NearbyBlockSearch.nearest(0, 0, 0, NearbyBlockSearch.Material.BEACON, limited);
        check(near != null && near.x() == 5, "Blocks outside the loaded radius are ignored");
        limited.unload(0, 0);
        check(NearbyBlockSearch.nearest(0, 0, 0, NearbyBlockSearch.Material.BEACON, limited) == null, "Unloaded chunks are not queried");
        limited.place(NearbyBlockSearch.Material.BEACON, 20, 0, 0);
        NearbyBlockSearch.Hit loadedOnly = NearbyBlockSearch.nearest(0, 0, 0, NearbyBlockSearch.Material.BEACON, limited);
        check(loadedOnly != null && loadedOnly.x() == 20, "A farther block in a loaded chunk wins over a closer unloaded one");

        Path dir = Files.createTempDirectory("openintel-autofind");
        try {
        RangeStore store = new RangeStore(dir.resolve("ranges.json"));
        RangeShape saved = new RangeShape(RangeType.SNITCH, 40, 64, 7, "minecraft:overworld");
        store.add(saved);
        VisualizationsScreen screen = allocate(VisualizationsScreen.class);
        set(Screen.class, screen, "children", new ArrayList<>());
        set(Screen.class, screen, "narratables", new ArrayList<>());
        set(Screen.class, screen, "renderables", new ArrayList<>());
        set(Screen.class, screen, "width", 480); set(Screen.class, screen, "height", 300);
        set(screen, "store", store); set(screen, "model", new CoverageListModel());
        set(screen, "query", ""); set(screen, "activeGroup", ""); set(screen, "initialized", true);
        set(screen, "draftX", "0"); set(screen, "draftY", "0"); set(screen, "draftZ", "0");
        set(screen, "draftDim", "minecraft:overworld"); set(screen, "draftGroup", ""); set(screen, "draftRadius", "50");
        set(screen, "kind", VisualizationsScreen.EntryKind.SANCTUARY);
        screen.init();
        set(screen, "nearbyOverride", world);
        call(screen, "newEntry");
        check("4".equals(get(screen, "draftX")) && "0".equals(get(screen, "draftY")) && "0".equals(get(screen, "draftZ")), "New entry starts on the nearest obsidian");
        check(String.valueOf(get(screen, "status")).contains("Nearest obsidian"), "New entry says which block it found");
        call(screen, "chooseType");
        DropdownLayout popup = popupLayout(get(screen, "dropdown"));
        check(click(screen, popup.x() + 8, popup.rowsY() + 46, 0), "Bastion row accepts the click");
        check(get(screen, "kind") == VisualizationsScreen.EntryKind.BASTION, "Dropdown selects bastion");
        check("1".equals(get(screen, "draftX")) && "0".equals(get(screen, "draftZ")), "Selecting bastion snaps to the nearest sponge");
        set(screen, "kind", VisualizationsScreen.EntryKind.SANCTUARY);
        call(screen, "applyAutoFind");
        check("0".equals(get(screen, "draftX")) && "5".equals(get(screen, "draftY")), "Auto find uses the nearest beacon for a sanctuary");
        set(screen, "kind", VisualizationsScreen.EntryKind.LOGGING);
        call(screen, "applyAutoFind");
        check("4".equals(get(screen, "draftX")), "Logging snitch auto find uses obsidian, not the closer sponge");
        set(screen, "nearbyOverride", new MemoryWorld());
        set(screen, "draftX", "12"); set(screen, "draftY", "64"); set(screen, "draftZ", "8");
        call(screen, "applyAutoFind");
        check("12".equals(get(screen, "draftX")) && "64".equals(get(screen, "draftY")) && "8".equals(get(screen, "draftZ")), "Auto find keeps coordinates when nothing is loaded");
        check(String.valueOf(get(screen, "status")).contains("No obsidian"), "Auto find says when the block is not loaded");
        set(screen, "nearbyOverride", world);
        click(screen, 70, 118, 0);
        check("40".equals(get(screen, "draftX")) && "64".equals(get(screen, "draftY")) && "7".equals(get(screen, "draftZ")), "Opening a saved entry keeps its coordinates");
        } finally {
            try (var paths = Files.walk(dir)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    /** Blocks already in client memory. Section indexes follow the search's world height. */
    private static final class MemoryWorld implements NearbyBlockSearch.World {
        final List<Placed> blocks = new ArrayList<>();
        final Set<String> unloaded = new HashSet<>();
        int radius = 8, minSection = -4, sections = 24;
        void place(NearbyBlockSearch.Material material, int x, int y, int z) { blocks.add(new Placed(material, x, y, z)); }
        void unload(int chunkX, int chunkZ) { unloaded.add(chunkX + "," + chunkZ); }
        public int chunkRadius() { return radius; }
        public int minSectionY() { return minSection; }
        public int sectionCount() { return sections; }
        public boolean loaded(int chunkX, int chunkZ) { return !unloaded.contains(chunkX + "," + chunkZ); }
        public boolean sectionMayMatch(int chunkX, int chunkZ, int sectionIndex, NearbyBlockSearch.Material material) {
            for (Placed block : blocks) if (block.material == material && (block.x >> 4) == chunkX && (block.z >> 4) == chunkZ
                    && (block.y >> 4) - minSection == sectionIndex) return true;
            return false;
        }
        public boolean matches(int x, int y, int z, NearbyBlockSearch.Material material) {
            for (Placed block : blocks) if (block.material == material && block.x == x && block.y == y && block.z == z) return true;
            return false;
        }
        record Placed(NearbyBlockSearch.Material material, int x, int y, int z) {}
    }
    private static void nameCounterChecks() throws Exception {
        Class<?> type = Class.forName("dev.openintel.gui.VisualizationsScreen$NameScreen");
        Object screen = allocate(type);
        set(Screen.class, screen, "children", new ArrayList<>());
        set(Screen.class, screen, "narratables", new ArrayList<>());
        set(Screen.class, screen, "renderables", new ArrayList<>());
        set(Screen.class, screen, "width", 320); set(Screen.class, screen, "height", 240);
        set(type, screen, "value", ""); set(type, screen, "message", "");
        var init = type.getDeclaredMethod("init"); init.setAccessible(true); init.invoke(screen);
        EditBox name = (EditBox) ((Screen) screen).children().stream().filter(c -> c instanceof EditBox).findFirst().orElseThrow();
        check("0/80".equals(VisualizationsScreen.groupNameCounter(name.getValue())), "Counter starts empty");
        name.setValue("North border");
        var value = type.getDeclaredField("value"); value.setAccessible(true);
        check("12/80".equals(VisualizationsScreen.groupNameCounter((String) value.get(screen))), "Counter follows live text changes");
        name.setValue("x".repeat(100));
        check("80/80".equals(VisualizationsScreen.groupNameCounter((String) value.get(screen))), "Counter agrees with enforced name limit");
        var message = type.getDeclaredField("message"); message.setAccessible(true);
        check("".equals(message.get(screen)), "Character-limit explanatory sentence removed");
    }
    private static void modelChecks() {
        CoverageListModel model = new CoverageListModel();
        RangeShape a = shape(1), b = shape(2), c = shape(3);
        RangeGroup group = new RangeGroup("Border"); b.groupId = group.id; c.groupId = group.id;
        model.rebuild(List.of(a, b, c), List.of(group), "");
        model.select(a.id, false, false); model.select(c.id, false, true);
        check(model.selected.size() == 3, "Shift-click selects a visible range across groups");
        model.collapsed.add(group.id); model.rebuild(List.of(a, b, c), List.of(group), "");
        check(model.visibleEntries().equals(List.of(a)), "Collapsed groups hide only their rows");
        model.rebuild(List.of(a, b, c), List.of(group), "Border");
        check(model.visibleEntries().equals(List.of(b, c)), "Search finds group names and reveals collapsed matches");
        model.selectAfterDeletion(8); check(model.selected.equals(Set.of(c.id)), "Last-row fallback selects previous visible entry");
        model.rebuild(List.of(a, b), List.of(group), "Border"); model.selectAfterDeletion(0);
        check(model.selected.equals(Set.of(b.id)), "Filtered successor stays in filtered list");
        model.rebuild(List.of(a), List.of(group), "Border"); model.selectAfterDeletion(0);
        check(model.selected.isEmpty(), "No filtered matches clears selection");
    }
    private static int rowOfGroup(CoverageListModel model, String id) {
        for (int i = 0; i < model.rows.size(); i++) if (model.rows.get(i).header() && model.rows.get(i).groupId().equals(id)) return i;
        throw new AssertionError("Group header missing");
    }
    private static RangeShape shape(int x) { return new RangeShape(RangeType.SNITCH, x, 64, 0, "minecraft:overworld"); }
    private static boolean click(VisualizationsScreen screen, double x, double y, int modifiers) {
        return screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, modifiers)), false);
    }
    private static void call(Object target, String name) throws Exception {
        var method = VisualizationsScreen.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object get(Object target, String name) throws Exception {
        var field = VisualizationsScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception { set(VisualizationsScreen.class, target, name, value); }
    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        var cls = Class.forName("sun.misc.Unsafe"); var field = cls.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(cls.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    // Minecraft 1.21.11 EditBox focus needs no native client focus callback.
    public static final class TextField extends EditBox {
        private String text;
        private TextField() { super(null, 0, 0, 100, 18, Component.empty()); }
        @Override public void setValue(String value) { text = value; }
        @Override public String getValue() { return text; }
    }
}
