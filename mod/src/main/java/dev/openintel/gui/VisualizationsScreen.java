package dev.openintel.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.viz.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Grouped coverage browser with an inspector and immediate list visibility controls. */
public final class VisualizationsScreen extends Screen {
    private final Screen parent;
    private final RangeStore store;
    private final CoverageListModel model = new CoverageListModel();
    private String query = "", selectedId, activeGroup = "";
    private EntryKind kind = EntryKind.SNITCH;
    private String draftX = "0", draftY = "0", draftZ = "0", draftDim = "minecraft:overworld", draftGroup = "", draftRadius = "50";
    private boolean initialized;
    private EditBox xField, yField, zField, dimensionField, radiusField;
    private Button typeButton, dimensionButton, groupButton, autoButton, targetButton, saveButton, deleteButton, undoButton, visibilityButton, moveButton, groupsButton, exportButton, groupCreateButton, bulkDeleteButton;
    private Dropdown dropdown;
    private int listW, listH, editorX, editorW, scroll;
    private int fieldY = 119, dimY = 150, groupY = 181, radiusY = 212, saveY = 234, plainSaveY = 212;
    private boolean roomyLabels = true;
    /** Test hook. Production reads chunks the client already has. */
    private NearbyBlockSearch.World nearbyOverride;
    private static final int LIST_X = 8, LIST_Y = 82, ROW_H = 30;
    private String status = "Click a row to edit. Ctrl/Cmd-click or Shift-click to select several.";

    public enum EntryKind {
        SNITCH("Snitch", RangeType.SNITCH, SnitchKind.NOTIFIER),
        LOGGING("Logging Snitch", RangeType.SNITCH, SnitchKind.LOGGING),
        BASTION("Bastion", RangeType.BASTION, SnitchKind.NOTIFIER),
        SANCTUARY("Sanctuary", RangeType.SANCTUARY, SnitchKind.NOTIFIER);
        final String label;
        final RangeType type;
        final SnitchKind snitchKind;
        EntryKind(String label, RangeType type, SnitchKind snitchKind) { this.label = label; this.type = type; this.snitchKind = snitchKind; }
        static EntryKind of(RangeShape s) {
            return s.type == RangeType.SNITCH ? (s.snitchKind == SnitchKind.LOGGING ? LOGGING : SNITCH)
                    : s.type == RangeType.BASTION ? BASTION : SANCTUARY;
        }
    }
    public VisualizationsScreen(Screen parent) { this(parent, RangeStore.get()); }
    VisualizationsScreen(Screen parent, RangeStore store) {
        super(Component.literal("Coverage shapes")); this.parent = parent; this.store = store;
    }
    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private EditBox field(String label, int x, int y, int w, int max, String value, Consumer<String> changed) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.literal(label));
        box.setMaxLength(max); box.setValue(value); box.setResponder(changed);
        return addRenderableWidget(box);
    }
    @Override protected void init() {
        closeDropdown();
        editorW = Math.min(250, Math.max(150, (width - 28) * 2 / 5));
        editorX = width - editorW - 8;
        listW = editorX - 20;
        listH = Math.max(30, height - LIST_Y - 82);
        measureEditor();
        button("New entry", 8, 28, 60, this::newEntry);
        groupsButton = button("Groups…", 72, 28, 64, this::manageGroups);
        button("Import…", 140, 28, 54, () -> openTransfer(false));
        exportButton = button("Export…", 198, 28, 54, () -> openTransfer(true));
        button("Done", width - 62, height - 28, 54, this::onClose);
        EditBox search = field("Search entries and groups", LIST_X, 57, listW, 160, query, value -> {
            query = value; scroll = 0; model.selected.clear(); selectedId = null; refreshRows(); updateButtons();
        });
        search.setHint(Component.literal("Search entries or groups…"));
        typeButton = button("", editorX, 64, editorW, this::chooseType);
        int gap = 2, autoW = 64;
        if (autoW + gap + 96 > editorW) autoW = Math.max(52, editorW - 96 - gap);
        autoButton = button("Auto find", editorX, 86, autoW, this::applyAutoFind);
        autoButton.setTooltip(Tooltip.create(Component.literal("Nearest obsidian, sponge, or beacon in loaded chunks, matching the selected type.")));
        targetButton = button("Target looking at", editorX + autoW + gap, 86, editorW - autoW - gap, this::useLookTarget);
        targetButton.setTooltip(Tooltip.create(Component.literal("Use the block you're looking at, or your position when no block is targeted.")));
        int col = (editorW - 8) / 3;
        xField = field("X coordinate", editorX, fieldY, col, 11, draftX, v -> draftX = v);
        yField = field("Y coordinate", editorX + col + 4, fieldY, col, 11, draftY, v -> draftY = v);
        zField = field("Z coordinate", editorX + 2 * (col + 4), fieldY, editorW - 2 * (col + 4), 11, draftZ, v -> draftZ = v);
        xField.setHint(Component.literal("X")); yField.setHint(Component.literal("Y")); zField.setHint(Component.literal("Z"));
        dimensionButton = button("", editorX, dimY, editorW, this::chooseDimension);
        dimensionField = field("Custom dimension identifier", editorX + 68, dimY + 1, editorW - 68, 160, draftDim, v -> draftDim = v);
        groupButton = button("", editorX, groupY, editorW - 24, () -> chooseGroup(false));
        groupCreateButton = button("+", editorX + editorW - 20, groupY, 20, this::createGroup);
        groupCreateButton.setTooltip(Tooltip.create(Component.literal("Create a named group.")));
        radiusField = field("Sanctuary radius in blocks", editorX, radiusY, editorW, 12, draftRadius, v -> draftRadius = v);
        radiusField.setHint(Component.literal("Radius"));
        saveButton = button("", editorX, saveY, (editorW - 4) / 2, this::saveEntry);
        deleteButton = button("Delete", editorX + (editorW + 4) / 2, saveY, (editorW - 4) / 2, this::deleteEntry);
        int colW = (listW - 8) / 3, by = height - 76;
        button("All", LIST_X, by, colW, () -> { model.selectAll(); syncSelection(); });
        visibilityButton = button("", LIST_X + colW + 4, by, listW - colW - 4, this::toggleSelectedVisibility);
        visibilityButton.setTooltip(Tooltip.create(Component.literal("Toggle selected entries. A mixed selection is shown first; group switches stay unchanged.")));
        moveButton = button("Move…", LIST_X, by + 24, colW, () -> chooseGroup(true));
        bulkDeleteButton = button("Delete", LIST_X + colW + 4, by + 24, colW, this::deleteEntry);
        undoButton = button("Undo", LIST_X + 2 * (colW + 4), by + 24, listW - 2 * (colW + 4), this::undoDeletion);
        undoButton.setTooltip(Tooltip.create(Component.literal("Restore the last deleted batch (Ctrl/Cmd+Z).")));
        if (!initialized) { initialized = true; newEntry(); }
        refreshRows(); updateButtons();
    }
    private void refreshRows() { model.rebuild(store.shapes(), store.groups(), query); clampScroll(); }
    private RangeShape selected() { return selectedId == null ? null : store.find(selectedId); }
    private void newEntry() {
        model.selected.clear(); selectedId = null; kind = EntryKind.SNITCH; draftRadius = "50";
        draftGroup = store.group(activeGroup) == null ? "" : activeGroup;
        status = locate(true); updateButtons();
    }
    /** Short windows drop the caption lines so Done still fits under the form. */
    private void measureEditor() {
        roomyLabels = height >= 300;
        fieldY = roomyLabels ? 119 : 108;
        dimY = roomyLabels ? 150 : 130;
        groupY = roomyLabels ? 181 : 154;
        radiusY = roomyLabels ? 212 : 178;
        saveY = roomyLabels ? 234 : 200;
        plainSaveY = roomyLabels ? 212 : 178;
    }
    private void useLookTarget() {
        fillTarget(); writeFields(); status = "Target copied. Save to apply.";
    }
    private void applyAutoFind() { status = locate(false); }
    /** Fills coordinates from the nearest loaded block for the selected type. */
    private String locate(boolean fallBackToLook) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null) draftDim = mc.level.dimension().identifier().toString();
        NearbyBlockSearch.Material material = NearbyBlockSearch.material(kind);
        NearbyBlockSearch.World world = nearbyWorld();
        BlockPos origin = searchOrigin();
        NearbyBlockSearch.Hit hit = world == null ? null
                : NearbyBlockSearch.nearest(origin.getX(), origin.getY(), origin.getZ(), material, world);
        if (hit != null) {
            draftX = Integer.toString(hit.x()); draftY = Integer.toString(hit.y()); draftZ = Integer.toString(hit.z());
            writeFields();
            return "Nearest " + material.noun + " at " + hit.x() + ", " + hit.y() + ", " + hit.z() + ". Save to apply.";
        }
        if (fallBackToLook) {
            fillTarget(); writeFields();
            return "No " + material.noun + " in loaded chunks. Using the block you're looking at.";
        }
        return "No " + material.noun + " in loaded chunks.";
    }
    private NearbyBlockSearch.World nearbyWorld() {
        if (nearbyOverride != null) return nearbyOverride;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        return new NearbyBlockSearch.ClientWorld(mc);
    }
    private BlockPos searchOrigin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) return mc.player.blockPosition();
        return BlockPos.ZERO;
    }
    private void fillTarget() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) pos = hit.getBlockPos();
        draftX = Integer.toString(pos.getX()); draftY = Integer.toString(pos.getY()); draftZ = Integer.toString(pos.getZ());
        draftDim = mc.level == null ? "minecraft:overworld" : mc.level.dimension().identifier().toString();
    }
    private void writeFields() {
        xField.setValue(draftX); yField.setValue(draftY); zField.setValue(draftZ);
        dimensionField.setValue(draftDim); radiusField.setValue(draftRadius);
    }
    private void loadEditor(RangeShape s) {
        selectedId = s.id; kind = EntryKind.of(s); draftGroup = s.groupId;
        draftX = Integer.toString(s.x); draftY = Integer.toString(s.y); draftZ = Integer.toString(s.z);
        draftDim = s.dimension; draftRadius = Double.toString(s.sanctuaryRadius);
        writeFields(); updateButtons();
    }
    private void syncSelection() {
        selectedId = model.selected.isEmpty() ? null : model.selected.iterator().next();
        RangeShape s = selected();
        if (s != null) loadEditor(s); else updateButtons();
        status = model.selected.size() > 1 ? model.selected.size() + " entries selected. Use Visibility, Move or Delete." : "Selection updated.";
    }
    private static boolean standardDimension(String id) {
        return List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end").contains(id);
    }
    private String groupName(String id) { RangeGroup group = store.group(id); return group == null ? "Ungrouped" : group.name; }
    private void updateButtons() {
        boolean single = model.selected.size() <= 1;
        typeButton.setMessage(Component.literal(pickerLabel(kind.label, typeButton))); typeButton.active = single;
        autoButton.active = targetButton.active = single;
        for (EditBox field : List.of(xField, yField, zField, dimensionField, radiusField)) field.active = single;
        dimensionButton.setWidth(standardDimension(draftDim) ? editorW : 64);
        dimensionButton.setMessage(Component.literal(pickerLabel(standardDimension(draftDim) ? friendlyDim(draftDim) : "Custom", dimensionButton)));
        dimensionButton.active = single; dimensionField.visible = !standardDimension(draftDim);
        groupButton.setMessage(Component.literal(pickerLabel(groupName(draftGroup), groupButton))); groupButton.active = single; groupCreateButton.active = single;
        radiusField.visible = kind == EntryKind.SANCTUARY;
        int actionsY = kind == EntryKind.SANCTUARY ? saveY : plainSaveY;
        saveButton.setY(actionsY); deleteButton.setY(actionsY);
        saveButton.setMessage(Component.literal(selectedId == null ? "Add entry" : "Save")); saveButton.active = single;
        deleteButton.active = bulkDeleteButton.active = !model.selected.isEmpty();
        undoButton.active = store.canUndo();
        visibilityButton.active = moveButton.active = !model.selected.isEmpty();
        List<RangeShape> selection = store.shapes().stream().filter(s -> model.selected.contains(s.id)).toList();
        String visibility = selection.isEmpty() ? "Off" : selection.stream().allMatch(s -> s.enabled) ? "On"
                : selection.stream().noneMatch(s -> s.enabled) ? "Off" : "Mixed";
        visibilityButton.setMessage(Component.literal("Visible: " + visibility));
    }
    private void chooseType() {
        List<Choice> choices = Arrays.stream(EntryKind.values()).map(k -> new Choice(k.name(), k.label)).toList();
        choose(typeButton, choices, kind.name(), value -> { kind = EntryKind.valueOf(value); status = locate(false); updateButtons(); });
    }
    private void chooseDimension() {
        choose(dimensionButton, List.of(new Choice("minecraft:overworld", "Overworld"), new Choice("minecraft:the_nether", "Nether"),
                new Choice("minecraft:the_end", "End"), new Choice("custom", "Custom dimension…")), standardDimension(draftDim) ? draftDim : "custom", value -> {
            draftDim = value.equals("custom") ? (standardDimension(draftDim) ? "custom:dimension" : draftDim) : value;
            dimensionField.setValue(draftDim); updateButtons();
        });
    }
    private List<Choice> groupChoices(boolean create) {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice("", "Ungrouped"));
        for (RangeGroup g : store.groups()) choices.add(new Choice(g.id, g.name));
        if (create) choices.add(new Choice("#new", "Create group…"));
        return choices;
    }
    private void chooseGroup(boolean bulk) {
        choose(bulk ? moveButton : groupButton, groupChoices(true), bulk ? null : draftGroup, id -> {
            if (id.equals("#new")) { createGroup(bulk); return; }
            assignGroup(id, bulk);
        });
    }
    private void assignGroup(String id, boolean bulk) {
        if (bulk) { store.mutateAll(Set.copyOf(model.selected), s -> s.groupId = id); refreshRows(); syncSelection(); status = "Selection moved to " + groupName(id) + "."; }
        else { draftGroup = id; updateButtons(); }
    }
    private void createGroup() { createGroup(false); }
    private void createGroup(boolean bulk) {
        minecraft.gui.setScreen(new NameScreen(this, "New group", "", name -> {
            RangeGroup g = store.createGroup(name); activeGroup = g.id; assignGroup(g.id, bulk); refreshRows();
        }, null));
    }
    private void manageGroups() {
        List<Choice> choices = new ArrayList<>(); choices.add(new Choice("#new", "Create group…"));
        for (RangeGroup g : store.groups()) choices.add(new Choice(g.id, g.name));
        choose(groupsButton, choices, activeGroup, id -> {
            if (id.equals("#new")) { createGroup(); return; }
            RangeGroup g = store.group(id);
            if (g != null) minecraft.gui.setScreen(new NameScreen(this, "Rename group", g.name,
                    name -> { store.renameGroup(id, name); refreshRows(); updateButtons(); },
                    () -> { store.removeGroup(id); if (draftGroup.equals(id)) draftGroup = ""; refreshRows(); updateButtons(); }));
        });
    }
    private void saveEntry() {
        try {
            int x = Integer.parseInt(draftX.trim()), y = Integer.parseInt(draftY.trim()), z = Integer.parseInt(draftZ.trim());
            String dim = draftDim.trim();
            if (!dim.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) { status = "Use a dimension ID, e.g. minecraft:overworld."; return; }
            double radius = kind == EntryKind.SANCTUARY ? Double.parseDouble(draftRadius.trim()) : 50;
            if (!Double.isFinite(radius) || radius < 1 || radius > 512) { status = "Sanctuary radius must be 1–512 blocks."; return; }
            if (selectedId == null) {
                RangeShape draft = new RangeShape(kind.type, x, y, z, dim);
                draft.snitchKind = kind.snitchKind; draft.sanctuaryRadius = radius; draft.groupId = draftGroup;
                store.add(draft); selectedId = draft.id;
            } else store.mutate(selectedId, s -> {
                s.type = kind.type; s.snitchKind = kind.snitchKind; s.x = x; s.y = y; s.z = z;
                s.dimension = dim; s.groupId = draftGroup; s.sanctuaryRadius = radius;
            });
            model.selected.clear(); model.selected.add(selectedId); model.collapsed.remove(draftGroup);
            refreshRows(); ensureSelectionVisible(); updateButtons(); status = "Entry saved.";
        } catch (NumberFormatException ex) { status = "Enter whole-number coordinates and a numeric radius."; }
    }
    private void deleteEntry() {
        if (model.selected.isEmpty()) return;
        List<RangeShape> before = model.visibleEntries();
        int index = 0;
        for (int i = 0; i < before.size(); i++) if (model.selected.contains(before.get(i).id)) { index = i; break; }
        int count = store.removeAll(Set.copyOf(model.selected));
        refreshRows(); model.selectAfterDeletion(index); syncSelection(); ensureSelectionVisible(); updateButtons();
        status = "Deleted " + count + " entries. Undo is available.";
    }
    private void undoDeletion() {
        if (!store.canUndo()) return;
        List<String> restored = store.undoDelete();
        model.selected.clear(); model.selected.addAll(restored);
        for (String id : restored) { RangeShape s = store.find(id); if (s != null) model.collapsed.remove(s.groupId); }
        refreshRows(); syncSelection(); ensureSelectionVisible(); updateButtons();
        status = "Restored " + restored.size() + " entries.";
    }
    private String pickerLabel(String label, Button button) {
        if (font != null && font.width(label) > button.getWidth() - 24)
            label = font.plainSubstrByWidth(label, Math.max(0, button.getWidth() - 24 - font.width("…"))) + "…";
        return label + "   ";
    }
    private void toggleSelectedVisibility() {
        if (model.selected.isEmpty()) return;
        boolean allShown = store.shapes().stream().filter(s -> model.selected.contains(s.id)).allMatch(s -> s.enabled);
        setSelectedVisible(!allShown);
    }
    private void setSelectedVisible(boolean visible) {
        store.mutateAll(Set.copyOf(model.selected), s -> s.enabled = visible);
        refreshRows(); updateButtons(); status = visible ? "Selected entries shown (unless their group is hidden)." : "Selected entries hidden.";
    }
    private void ensureSelectionVisible() {
        for (int i = 0; i < model.rows.size(); i++) if (!model.rows.get(i).header() && model.selected.contains(model.rows.get(i).shape().id)) {
            if (i * ROW_H < scroll) scroll = i * ROW_H;
            else if ((i + 1) * ROW_H > scroll + listH) scroll = (i + 1) * ROW_H - listH;
            break;
        }
        clampScroll();
    }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, Math.max(0, model.rows.size() * ROW_H - listH))); }
    private boolean inList(double x, double y) { return x >= LIST_X && x < LIST_X + listW && y >= LIST_Y && y < LIST_Y + listH; }
    @Override public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (dropdown != null) return dropdown.click(click, doubled);
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && inList(click.x(), click.y())) {
            int index = (int) ((click.y() - LIST_Y + scroll) / ROW_H);
            if (index < 0 || index >= model.rows.size()) return true;
            var row = model.rows.get(index);
            setFocused(null);
            if (click.x() >= LIST_X + 22 && click.x() < LIST_X + 38) {
                if (row.header()) {
                    if (row.groupId().isEmpty()) {
                        Set<String> ids = new HashSet<>(); boolean show = store.shapes().stream().anyMatch(s -> s.groupId.isEmpty() && !s.enabled);
                        for (RangeShape s : store.shapes()) if (s.groupId.isEmpty()) ids.add(s.id);
                        store.mutateAll(ids, s -> s.enabled = show);
                    } else store.toggleGroup(row.groupId());
                } else store.mutate(row.shape().id, s -> s.enabled = !s.enabled);
                refreshRows(); updateButtons(); status = "Visibility updated.";
            } else if (row.header()) {
                activeGroup = row.groupId();
                if (!model.collapsed.add(row.groupId())) model.collapsed.remove(row.groupId());
                refreshRows();
            } else {
                model.select(row.shape().id, (click.hasControlDown() || (click.modifiers() & InputConstants.MOD_SUPER) != 0), click.hasShiftDown()); syncSelection();
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (dropdown != null) { dropdown.scroll((int) -vertical); return true; }
        if (inList(x, y)) { scroll -= (int) (vertical * ROW_H); clampScroll(); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (dropdown != null) return dropdown.key(event);
        if (!(getFocused() instanceof EditBox)) {
            if (event.key() == InputConstants.KEY_DELETE) { deleteEntry(); return true; }
            if ((event.hasControlDown() || (event.modifiers() & InputConstants.MOD_SUPER) != 0) && event.key() == InputConstants.KEY_Z) { undoDeletion(); return true; }
            if (event.isSelectAll()) { model.selectAll(); syncSelection(); return true; }
        }
        return super.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if (dropdown != null) { if (dropdown.search != null) dropdown.search.charTyped(event); return true; }
        return super.charTyped(event);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (dropdown != null) { setDragging(false); return true; }
        return super.mouseReleased(event);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dropdown != null) { if (dropdown.search != null) dropdown.search.mouseDragged(event, dx, dy); return true; }
        return super.mouseDragged(event, dx, dy);
    }
    private void text(GuiGraphicsExtractor ctx, String value, int x, int y, int maxWidth, int color) {
        ctx.text(font, font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…", x, y, color);
    }
    private void checkbox(GuiGraphicsExtractor ctx, int x, int y, boolean checked, boolean effective) {
        ctx.fill(x, y, x + 12, y + 12, 0xFF94A1B7);
        ctx.fill(x + 1, y + 1, x + 11, y + 11, 0xFF1A202C);
        if (checked) ctx.fill(x + 3, y + 3, x + 9, y + 9, effective ? 0xFF86B6FF : 0xFF59657A);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0xED12151D);
        ctx.text(font, "Coverage shapes", 8, 10, 0xFFE8EAF0);
        String count = store.size() + " entries · " + model.selected.size() + " selected";
        text(ctx, count, Math.max(150, width - 8 - font.width(count)), 10, width - 158, 0xFF9CA8BD);
        ctx.fill(LIST_X, LIST_Y, LIST_X + listW, LIST_Y + listH, 0xFF1A202C);
        int paneBottom = (kind == EntryKind.SANCTUARY ? saveY : plainSaveY) + 26;
        ctx.fill(editorX - 4, 50, width - 4, paneBottom, 0xFF1A202C);
        ctx.text(font, model.selected.size() > 1 ? model.selected.size() + " entries selected" : selectedId == null ? "New entry" : "Edit entry", editorX, 52, 0xFFBACAE5);
        ctx.enableScissor(LIST_X, LIST_Y, LIST_X + listW, LIST_Y + listH);
        int first = scroll / ROW_H, last = Math.min(model.rows.size(), (scroll + listH + ROW_H - 1) / ROW_H);
        for (int i = first; i < last; i++) {
            var row = model.rows.get(i); int y = LIST_Y + i * ROW_H - scroll;
            boolean selected = row.header() ? row.groupId().equals(activeGroup) : model.selected.contains(row.shape().id);
            ctx.fill(LIST_X, y, LIST_X + listW - 4, y + ROW_H - 1, selected ? 0xFF33456A
                    : inList(mx, my) && my >= y && my < y + ROW_H ? 0xFF29344A : 0xFF202735);
            if (row.header()) {
                triangle(ctx, LIST_X + 5, y + 11, model.collapsed.contains(row.groupId()) && query.isBlank() ? 1 : 0, 0xFFABB8CF);
                RangeGroup g = store.group(row.groupId());
                boolean shown = g == null ? store.shapes().stream().filter(s -> s.groupId.isEmpty()).allMatch(s -> s.enabled) : g.enabled;
                checkbox(ctx, LIST_X + 23, y + 8, shown, true);
                text(ctx, row.label() + " · " + row.count(), LIST_X + 44, y + 10, listW - 54, 0xFFE8EAF0);
            } else {
                RangeShape s = row.shape(); boolean visible = store.isVisible(s);
                checkbox(ctx, LIST_X + 23, y + 8, s.enabled, visible);
                text(ctx, EntryKind.of(s).label + (s.enabled && !visible ? " · Group hidden" : !s.enabled ? " · Hidden" : ""), LIST_X + 44, y + 4, listW - 54, visible ? 0xFFE8EAF0 : 0xFF98A1B2);
                text(ctx, s.x + ", " + s.y + ", " + s.z + " · " + friendlyDim(s.dimension), LIST_X + 44, y + 17, listW - 54, 0xFFABB8CF);
            }
        }
        ctx.disableScissor();
        if (model.rows.size() * ROW_H > listH) {
            int thumb = Math.max(10, (int) ((long) listH * listH / ((long) model.rows.size() * ROW_H)));
            int y = LIST_Y + (int) ((long) scroll * (listH - thumb) / (model.rows.size() * ROW_H - listH));
            ctx.fill(LIST_X + listW - 3, y, LIST_X + listW, y + thumb, 0xFF7992BC);
        }
        int col = (editorW - 8) / 3;
        if (roomyLabels) {
            ctx.text(font, "X", editorX, fieldY - 10, 0xFFABB8CF); ctx.text(font, "Y", editorX + col + 4, fieldY - 10, 0xFFABB8CF); ctx.text(font, "Z", editorX + 2 * (col + 4), fieldY - 10, 0xFFABB8CF);
            ctx.text(font, "Dimension", editorX, dimY - 10, 0xFFABB8CF); ctx.text(font, "Group", editorX, groupY - 10, 0xFFABB8CF);
            if (kind == EntryKind.SANCTUARY) text(ctx, "Radius (blocks, 1–512)", editorX, radiusY - 10, editorW, 0xFFABB8CF);
            else text(ctx, "Coverage uses visualization settings.", editorX, plainSaveY - 10, editorW, 0xFFABB8CF);
        }
        int y = height - 27, lines = 0;
        for (var line : font.split(Component.literal(status), listW)) { if (lines++ == 2) break; ctx.text(font, line, 8, y, 0xFFBACAE5); y += 10; }
        super.extractRenderState(ctx, mx, my, delta);
        ctx.nextStratum();
        for (Button picker : List.of(typeButton, dimensionButton, groupButton)) {
            if (picker.visible) triangle(ctx, picker.getX() + picker.getWidth() - 14, picker.getY() + 8,
                    dropdown != null && dropdown.anchor == picker ? 2 : 0, picker.active ? 0xFFE8EAF0 : 0xFF8B92A0);
        }
        if (dropdown != null) { ctx.nextStratum(); dropdown.render(ctx, mx, my, delta); }
        if (dropdown == null && inList(mx, my) && mx >= LIST_X + 22 && mx < LIST_X + 38)
            ctx.setTooltipForNextFrame(font, Component.literal("Show or hide coverage. Group switches preserve individual settings."), mx, my);
    }
    private static String friendlyDim(String id) {
        return switch (id) { case "minecraft:overworld" -> "Overworld"; case "minecraft:the_nether" -> "Nether"; case "minecraft:the_end" -> "End"; default -> id; };
    }
    /** Nine-pixel-wide arrows avoid the tiny font triangle glyph. 0 down, 1 right, 2 up. */
    private static void triangle(GuiGraphicsExtractor ctx, int x, int y, int direction, int color) {
        for (int i = 0; i < 5; i++) {
            if (direction == 1) ctx.fill(x + i, y + i - 2, x + i + 1, y + 7 - i, color);
            else { int row = direction == 2 ? 4 - i : i; ctx.fill(x + i, y + row, x + 9 - i, y + row + 1, color); }
        }
    }
    private record Choice(String id, String label) { }
    private void choose(Button anchor, List<Choice> choices, String selected, Consumer<String> action) {
        if (dropdown != null && dropdown.anchor == anchor) { closeDropdown(); return; }
        closeDropdown(); setFocused(null);
        dropdown = new Dropdown(anchor, choices, selected, action);
    }
    private void closeDropdown() {
        if (dropdown != null) { if (dropdown.search != null) dropdown.search.setFocused(false); dropdown = null; }
    }
    private void openTransfer(boolean exporting) {
        if (!exporting) { minecraft.gui.setScreen(new TransferScreen(this, false, null)); return; }
        List<Choice> choices = new ArrayList<>(); choices.add(new Choice("#all", "All entries and groups")); choices.addAll(groupChoices(false));
        choose(exportButton, choices, "#all", id -> minecraft.gui.setScreen(new TransferScreen(this, true, id.equals("#all") ? null : id)));
    }
    @Override public void onClose() { if (dropdown != null) { closeDropdown(); return; } minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    /** Popup event handling consumes all input so covered widgets cannot activate. */
    private final class Dropdown {
        final Button anchor; final List<Choice> choices; final Consumer<String> action;
        final DropdownLayout layout; final EditBox search;
        List<Choice> filtered; int highlighted, offset;
        Dropdown(Button anchor, List<Choice> choices, String selected, Consumer<String> action) {
            this.anchor = anchor; this.choices = choices; this.action = action; filtered = choices;
            layout = DropdownLayout.fit(anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight(), width, height, choices.size(), choices.size() > 8);
            if (layout.searchHeight() > 0) {
                search = new EditBox(font, layout.x() + 4, layout.y() + 4, layout.width() - 8, 18, Component.literal("Filter choices"));
                search.setMaxLength(160); search.setHint(Component.literal("Search…")); search.setFocused(true);
                search.setResponder(value -> {
                    filtered = choices.stream().filter(c -> c.label.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))).toList();
                    highlighted = 0; offset = 0;
                });
            } else search = null;
            for (int i = 0; i < choices.size(); i++) if (choices.get(i).id.equals(selected)) highlighted = i;
            reveal();
        }
        void reveal() {
            if (highlighted < offset) offset = highlighted;
            if (highlighted >= offset + layout.visibleRows()) offset = highlighted - layout.visibleRows() + 1;
            offset = Math.max(0, Math.min(offset, Math.max(0, filtered.size() - layout.visibleRows())));
        }
        void select(int index) {
            if (index < 0 || index >= filtered.size()) return;
            Choice choice = filtered.get(index); closeDropdown(); setFocused(anchor); action.accept(choice.id);
        }
        boolean click(MouseButtonEvent event, boolean doubled) {
            if (!layout.contains(event.x(), event.y())) { closeDropdown(); return true; }
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
            if (search != null && event.y() < layout.rowsY()) { search.mouseClicked(event, doubled); return true; }
            int row = layout.rowAt(event.x(), event.y());
            if (row >= 0) select(offset + row);
            return true;
        }
        void scroll(int rows) { offset = Math.max(0, Math.min(offset + rows, Math.max(0, filtered.size() - layout.visibleRows()))); }
        boolean key(KeyEvent event) {
            if (event.isEscape()) { closeDropdown(); setFocused(anchor); return true; }
            if (event.isUp() || event.isDown()) {
                if (!filtered.isEmpty()) highlighted = Math.floorMod(highlighted + (event.isUp() ? -1 : 1), filtered.size());
                reveal(); return true;
            }
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) { select(highlighted); return true; }
            if (search != null) search.keyPressed(event);
            return true;
        }
        void render(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
            int x = layout.x(), y = layout.y(), w = layout.width(), h = layout.height();
            ctx.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x99000000);
            ctx.fill(x, y, x + w, y + h, 0xFF91A4C0); ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF1A202C);
            int hover = layout.rowAt(mx, my);
            for (int row = 0; row < layout.visibleRows() && offset + row < filtered.size(); row++) {
                int index = offset + row, top = layout.rowsY() + row * DropdownLayout.ROW_HEIGHT;
                if (row == hover || index == highlighted) ctx.fill(x + 2, top, x + w - 2, top + DropdownLayout.ROW_HEIGHT, 0xFF33456A);
                text(ctx, filtered.get(index).label, x + 7, top + 6, w - 20, 0xFFE8EAF0);
            }
            if (filtered.isEmpty()) text(ctx, "No matches", x + 7, layout.rowsY() + 6, w - 14, 0xFFABB8CF);
            if (filtered.size() > layout.visibleRows()) {
                int track = layout.visibleRows() * DropdownLayout.ROW_HEIGHT;
                int thumb = Math.max(8, track * layout.visibleRows() / filtered.size());
                int top = layout.rowsY() + offset * (track - thumb) / (filtered.size() - layout.visibleRows());
                ctx.fill(x + w - 4, top, x + w - 2, top + thumb, 0xFF91A4C0);
            }
            if (search != null) { ctx.nextStratum(); search.extractRenderState(ctx, mx, my, delta); }
        }
    }
    static String groupNameCounter(String value) { return value.length() + "/80"; }
    private static final class NameScreen extends Screen {
        final VisualizationsScreen parent; final Consumer<String> save; final Runnable remove;
        String value, message = "";
        NameScreen(VisualizationsScreen parent, String title, String value, Consumer<String> save, Runnable remove) {
            super(Component.literal(title)); this.parent = parent; this.value = value; this.save = save; this.remove = remove;
        }
        @Override protected void init() {
            int w = Math.min(320, width - 32), x = (width - w) / 2, y = height / 2;
            EditBox name = new EditBox(font, x, y - 30, w - 46, 20, Component.literal("Group name")); name.setMaxLength(80); name.setValue(value); name.setResponder(v -> { value = name.getValue(); message = ""; }); addRenderableWidget(name);
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
                try { save.accept(value); onClose(); } catch (IllegalArgumentException ex) { message = ex.getMessage(); }
            }).bounds(x, y, (w - 4) / 2, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + (w + 4) / 2, y, (w - 4) / 2, 20).build());
            if (remove != null) addRenderableWidget(Button.builder(Component.literal("Remove group (keep entries)"), b -> { remove.run(); onClose(); }).bounds(x, y + 28, w, 20).build());
            setInitialFocus(name);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, 0xF012151D); int x = (width - Math.min(320, width - 32)) / 2;
            ctx.text(font, title, x, height / 2 - 55, 0xFFE8EAF0);
            String counter = groupNameCounter(value);
            int right = x + Math.min(320, width - 32);
            ctx.text(font, counter, right - font.width(counter), height / 2 - 24, value.length() == 80 ? 0xFFFFCE7A : 0xFFABB8CF);
            int y = height / 2 + 58; for (var line : font.split(Component.literal(message), width - 2 * x)) { ctx.text(font, line, x, y, 0xFFBACAE5); y += 11; }
            super.extractRenderState(ctx, mx, my, delta);
        }
        @Override public void onClose() { minecraft.gui.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
    }
    private static final class TransferScreen extends Screen {
        final VisualizationsScreen parent; final boolean exporting; final String groupId;
        String value = FabricLoader.getInstance().getConfigDir().resolve("openintel-ranges-export.json").toAbsolutePath().toString();
        String message;
        TransferScreen(VisualizationsScreen parent, boolean exporting, String groupId) {
            super(Component.literal(exporting ? "Export coverage" : "Import coverage")); this.parent = parent; this.exporting = exporting; this.groupId = groupId;
            message = exporting ? "Export: " + (groupId == null ? "all entries and groups" : parent.groupName(groupId)) : "Merge entries and groups. Existing duplicates are kept.";
        }
        @Override protected void init() {
            int w = Math.min(width - 32, 540), x = (width - w) / 2, y = height / 2;
            EditBox path = new EditBox(font, x, y - 10, w, 20, Component.literal("JSON file path")); path.setMaxLength(4096); path.setValue(value); path.setResponder(v -> value = v); addRenderableWidget(path);
            addRenderableWidget(Button.builder(Component.literal(exporting ? "Export" : "Import"), b -> transfer()).bounds(x, y + 20, (w - 8) / 2, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + (w + 8) / 2, y + 20, (w - 8) / 2, 20).build()); setInitialFocus(path);
        }
        void transfer() {
            try {
                Path path = Path.of(value.trim()).toAbsolutePath(); String result;
                if (exporting) { parent.store.exportFile(path, groupId); result = "Coverage exported."; }
                else result = "Imported " + parent.store.importFile(path) + " entries and merged groups.";
                onClose(); parent.refreshRows(); parent.updateButtons(); parent.status = result;
            } catch (java.nio.file.FileAlreadyExistsException ex) { message = "File exists. Choose a new filename.";
            } catch (Exception ex) { message = exporting ? "Export failed. Check the path and permissions." : "Import failed. Check the path and coverage JSON."; }
        }
        @Override public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, 0xF012151D); int x = (width - Math.min(width - 32, 540)) / 2;
            ctx.text(font, title, x, height / 2 - 62, 0xFFE8EAF0); ctx.text(font, "JSON file path (paste or type)", x, height / 2 - 26, 0xFFABB8CF);
            int y = height / 2 + 50; for (var line : font.split(Component.literal(message), width - 2 * x)) { ctx.text(font, line, x, y, 0xFFBACAE5); y += 11; }
            super.extractRenderState(ctx, mx, my, delta);
        }
        @Override public void onClose() { minecraft.gui.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
