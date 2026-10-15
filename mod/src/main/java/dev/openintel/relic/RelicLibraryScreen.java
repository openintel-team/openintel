package dev.openintel.relic;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.xaero.RelicCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searchable relic list in a vanilla container window: stone panel, inset list,
 * item slots, and the standard scrollbar.
 */
public final class RelicLibraryScreen extends Screen {
    private static final int ROW_H = 40;
    private static final int SCROLL_W = 6;
    private static final int PANEL_FILL = 0xFFC6C6C6;
    private static final int PANEL_SHADE = 0xFF555555;
    private static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
    private static final int INSET_FILL = 0xFFAAAAAA;
    private static final int INSET_SHADE = 0xFF373737;
    private static final int TITLE_COLOR = 0xFF404040;
    private static final int ROW_HOVER = 0xFFB8B8B8;
    private static final int ROW_ALTERNATE = 0xFFA4A4A4;
    private static final int ROW_SELECTED = 0xFFD8DEC9;
    private static final int ACCENT = 0xFF52653A;
    private static final int SECONDARY_COLOR = 0xFF505050;
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BG = Identifier.withDefaultNamespace("widget/scroller_background");

    private final Screen parent;
    private RelicLibraryVisit visit;
    private EditBox search;
    private Button showChestsButton;
    private Button clearButton;
    private List<RelicLibraryEntry> view = List.of();
    private int panelX, panelY, panelW, panelH;
    private int insetX, insetY, insetW, insetH;
    private int listX, listY, listW, listH;
    private int scrollX;
    private int buttonY;
    private int scroll;
    private boolean scrollbarDrag;
    private int scrollGrab;

    public RelicLibraryScreen(Screen parent) {
        super(Component.translatable("openintel.relic.library.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout();
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, panelX + 10, panelY + 36, panelW - 20, 20,
                Component.translatable("openintel.relic.library.search"));
        search.setHint(Component.translatable("openintel.relic.library.search_hint"));
        search.setValue(query);
        search.setResponder(q -> refreshView());
        addRenderableWidget(search);

        int innerX = panelX + 10;
        int innerW = panelW - 20;
        int gap = 4;
        int actionW = (innerW - gap) / 2;
        Button mapButton = Button.builder(Component.translatable("openintel.relic.library.show_map"),
                b -> showOnMap()).bounds(innerX, buttonY, actionW, 20).build();
        mapButton.active = RelicMapBridge.available();
        addRenderableWidget(mapButton);
        showChestsButton = Button.builder(Component.translatable("openintel.relic.library.show_chests"),
                b -> showChests()).bounds(innerX + actionW + gap, buttonY, innerW - actionW - gap, 20).build();
        showChestsButton.setTooltip(Tooltip.create(Component.translatable("openintel.relic.library.chests_tooltip")));
        addRenderableWidget(showChestsButton);
        clearButton = Button.builder(Component.translatable("openintel.relic.library.clear"),
                b -> {
                    visit.clearSelection();
                    syncButtons();
                }).bounds(innerX, buttonY + 24, actionW, 20).build();
        clearButton.setTooltip(Tooltip.create(Component.translatable("openintel.relic.library.clear_tooltip")));
        addRenderableWidget(clearButton);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                b -> onClose()).bounds(innerX + actionW + gap, buttonY + 24, innerW - actionW - gap, 20).build());
        reload();
    }

    /** Portrait container with a flexible list and a fixed, two-row action area. */
    private void layout() {
        panelW = Math.min(268, Math.max(160, width - 16));
        panelH = Math.min(400, Math.max(180, height - 16));
        panelX = (width - panelW) / 2;
        panelY = Math.max(0, (height - panelH) / 2);
        insetX = panelX + 10;
        insetY = panelY + 64;
        insetW = panelW - 20;
        // Reserve a status line, two button rows, and the bottom container border.
        insetH = panelH - 138;
        listX = insetX + 2;
        listY = insetY + 2;
        listH = insetH - 4;
        scrollX = insetX + insetW - 2 - SCROLL_W;
        listW = Math.max(40, scrollX - 3 - listX);
        buttonY = panelY + panelH - 54;
    }

    private void reload() {
        ChestTrackerRelics.Loaded loaded = ChestTrackerRelics.load(Minecraft.getInstance());
        LinkedHashMap<String, Object> stacks = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> e : loaded.stacks().entrySet()) {
            stacks.put(e.getKey(), e.getValue());
        }
        Set<String> keep = RelicLibraryVisit.current() == null
                ? Set.of()
                : Set.copyOf(RelicLibraryVisit.current().selectedIds());
        visit = RelicLibraryVisit.start(loaded.entries(), RelicLibraryVisit.stackMap(stacks));
        visit.selectIds(keep);
        applySlotCap();
        refreshView();
        syncButtons();
    }

    /** Selection cannot outgrow the empty slots in the player's inventory right now. */
    private void applySlotCap() {
        Minecraft client = Minecraft.getInstance();
        if (visit == null || client.player == null) return;
        visit.enforceCapacity(RelicCarryHint.freeSlots(client.player),
                client.player.getX(), client.player.getZ());
    }

    private void refreshView() {
        String query = search == null ? "" : search.getValue();
        double px = 0;
        double pz = 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            px = client.player.getX();
            pz = client.player.getZ();
        }
        view = RelicLibraryModel.sortByDistance(RelicLibraryModel.filter(visit.entries(), query), px, pz);
        int max = maxScroll();
        if (scroll > max) scroll = max;
    }

    private void syncButtons() {
        if (showChestsButton == null || visit == null) return;
        showChestsButton.active = !visit.selectedStacks().isEmpty();
        clearButton.active = !visit.selectedIds().isEmpty();
    }

    private void showOnMap() {
        if (!RelicMapBridge.available()) return;
        Minecraft client = Minecraft.getInstance();
        List<RelicLibraryEntry> selected = visit.selectedEntries();
        double[] focus = RelicLibraryModel.focus(selected, visit.entries());
        double fx = focus != null ? focus[0] : (client.player != null ? client.player.getX() : 0);
        double fz = focus != null ? focus[1] : (client.player != null ? client.player.getZ() : 0);
        RelicMapBridge.openMap(this, fx, fz, 1.0);
    }

    private void showChests() {
        List<ItemStack> stacks = new ArrayList<>();
        for (Object raw : visit.selectedStacks()) {
            if (raw instanceof ItemStack stack) stacks.add(stack);
        }
        if (stacks.isEmpty()) return;
        RelicWhereIsIt.showChests(stacks);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        applySlotCap();
        syncButtons();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (overScrollbar(event.x(), event.y())) {
            beginScrollDrag(event.y());
            return true;
        }
        int index = rowAt(event.x(), event.y());
        if (index < 0) return false;
        Minecraft client = Minecraft.getInstance();
        int free = RelicCarryHint.freeSlots(client.player);
        if (client.player != null) {
            visit.enforceCapacity(free, client.player.getX(), client.player.getZ());
        }
        visit.clickRow(view.get(index).id(), client.hasShiftDown(), free);
        syncButtons();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double offsetX, double offsetY) {
        if (scrollbarDrag && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragScroll(event.y());
            return true;
        }
        return super.mouseDragged(event, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && scrollbarDrag) {
            scrollbarDrag = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= listX && mouseX < scrollX + SCROLL_W && mouseY >= listY && mouseY < listY + listH) {
            scroll = (int) Math.clamp(scroll - (int) Math.signum(deltaY) * ROW_H, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        drawRaisedPanel(ctx, panelX, panelY, panelW, panelH);
        drawInset(ctx, insetX, insetY, insetW, insetH);
        ctx.text(font, title, panelX + 10, panelY + 10, TITLE_COLOR, false);
        ctx.text(font, fit(Component.translatable("openintel.relic.library.subtitle").getString(), panelW - 20),
                panelX + 10, panelY + 23, SECONDARY_COLOR, false);
        int statusY = insetY + insetH + 6;
        String count = Component.translatable("openintel.relic.library.shown", view.size()).getString();
        ctx.text(font, count, insetX, statusY, SECONDARY_COLOR, false);
        int selectedCount = visit.selectedIds().size();
        int free = RelicCarryHint.freeSlots(Minecraft.getInstance().player);
        String capacity = Component.translatable(selectedCount >= free
                ? "openintel.relic.library.selection_full" : "openintel.relic.library.selection",
                selectedCount, free).getString();
        ctx.text(font, capacity, insetX + insetW - font.width(capacity), statusY,
                selectedCount >= free ? 0xFF795321 : ACCENT, false);
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int y = listY - scroll;
        int textLimit = listW - 34;
        int row = 0;
        for (RelicLibraryEntry entry : view) {
            if (y + ROW_H >= listY && y < listY + listH) {
                boolean selected = visit.isSelected(entry.id());
                boolean hovered = mouseX >= listX && mouseX < listX + listW
                        && mouseY >= Math.max(listY, y) && mouseY < Math.min(listY + listH, y + ROW_H);
                int fill = selected ? ROW_SELECTED : hovered ? ROW_HOVER
                        : (row % 2 == 0 ? INSET_FILL : ROW_ALTERNATE);
                ctx.fill(listX, y, listX + listW, y + ROW_H, fill);
                ctx.fill(listX, y + ROW_H - 1, listX + listW, y + ROW_H, 0xFF969696);
                if (selected) {
                    ctx.fill(listX, y, listX + 2, y + ROW_H - 1, ACCENT);
                }
                // A small, explicit selection box complements the full-row highlight.
                int checkX = listX + listW - 10;
                ctx.fill(checkX, y + 6, checkX + 7, y + 13, INSET_SHADE);
                ctx.fill(checkX + 1, y + 7, checkX + 6, y + 12, selected ? ACCENT : PANEL_FILL);
                if (selected) ctx.fill(checkX + 2, y + 8, checkX + 5, y + 11, ROW_SELECTED);
                ctx.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, listX + 5, y + 5, 18, 18);
                RelicCatalog.Relic relic = RelicCatalog.find(entry.title());
                if (relic != null) {
                    var texture = Identifier.fromNamespaceAndPath("openintel", "textures/relic/" + relic.texture());
                    int tex = relic.size();
                    ctx.blit(RenderPipelines.GUI_TEXTURED, texture, listX + 6, y + 6,
                            0f, 0f, 16, 16, tex, tex, tex, tex);
                } else {
                    ctx.fill(listX + 7, y + 7, listX + 21, y + 21, 0xFFE8B84A);
                }
                String coords = entry.located()
                        ? entry.siteX() + ", " + entry.siteZ()
                        : Component.translatable("openintel.relic.library.unlocated").getString();
                ctx.text(font, fit(entry.title(), textLimit), listX + 28, y + 5, TITLE_COLOR, false);
                ctx.text(font, fit(entry.enchantment(), textLimit), listX + 28, y + 16, SECONDARY_COLOR, false);
                ctx.text(font, fit(coords, textLimit), listX + 28, y + 28, SECONDARY_COLOR, false);
            }
            y += ROW_H;
            row++;
        }
        ctx.disableScissor();

        if (view.isEmpty()) {
            Component message = visit.entries().isEmpty()
                    ? Component.translatable("openintel.relic.library.empty")
                    : Component.translatable("openintel.relic.library.no_matches");
            ctx.textWithWordWrap(font, message, listX + 4, listY + 6, listW - 8, TITLE_COLOR);
        }

        ctx.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BG, scrollX, listY, SCROLL_W, listH);
        if (maxScroll() > 0) {
            ctx.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, scrollX, thumbY(), SCROLL_W, thumbHeight());
        }
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) return -1;
        int index = ((int) mouseY - listY + scroll) / ROW_H;
        if (index < 0 || index >= view.size()) return -1;
        return index;
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        return mouseX >= scrollX && mouseX < scrollX + SCROLL_W
                && mouseY >= listY && mouseY < listY + listH;
    }

    private void beginScrollDrag(double mouseY) {
        scrollbarDrag = true;
        int thumbTop = thumbY();
        int thumb = thumbHeight();
        scrollGrab = mouseY >= thumbTop && mouseY < thumbTop + thumb ? (int) mouseY - thumbTop : thumb / 2;
        dragScroll(mouseY);
    }

    private void dragScroll(double mouseY) {
        int max = maxScroll();
        int travel = Math.max(1, listH - thumbHeight());
        double rel = mouseY - scrollGrab - listY;
        scroll = (int) Math.clamp(rel / travel * max, 0, max);
    }

    private int maxScroll() {
        return Math.max(0, view.size() * ROW_H - listH);
    }

    private int thumbHeight() {
        int content = view.size() * ROW_H;
        if (content <= listH) return listH;
        return Math.max(15, listH * listH / content);
    }

    private int thumbY() {
        int max = maxScroll();
        if (max <= 0) return listY;
        return listY + (listH - thumbHeight()) * scroll / max;
    }

    private String fit(String line, int maxWidth) {
        if (font.width(line) <= maxWidth) return line;
        String ellipsis = "...";
        int limit = Math.max(0, maxWidth - font.width(ellipsis));
        int end = line.length();
        while (end > 0 && font.width(line.substring(0, end)) > limit) end = line.offsetByCodePoints(end, -1);
        return line.substring(0, end) + ellipsis;
    }

    /**
     * Vanilla container bevel: black outer edge, white top-left, dark bottom-right,
     * and the notched corners from the villager and chest windows.
     */
    private static void drawRaisedPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 8 || h < 8) return;
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, PANEL_FILL);

        g.fill(x + 2, y, x + w - 3, y + 1, 0xFF000000);
        g.fill(x + 1, y + 1, x + 2, y + 2, 0xFF000000);
        g.fill(x + 2, y + 1, x + w - 3, y + 2, PANEL_HIGHLIGHT);
        g.fill(x + w - 3, y + 1, x + w - 2, y + 2, 0xFF000000);

        g.fill(x, y + 2, x + 1, y + 3, 0xFF000000);
        g.fill(x + 1, y + 2, x + w - 3, y + 3, PANEL_HIGHLIGHT);
        g.fill(x + w - 3, y + 2, x + w - 2, y + 3, PANEL_FILL);
        g.fill(x + w - 2, y + 2, x + w - 1, y + 3, 0xFF000000);

        g.fill(x, y + 3, x + 1, y + h - 3, 0xFF000000);
        g.fill(x + 1, y + 3, x + 3, y + h - 3, PANEL_HIGHLIGHT);
        g.fill(x + 3, y + 3, x + 4, y + 4, PANEL_HIGHLIGHT);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 3, PANEL_SHADE);
        g.fill(x + w - 1, y + 3, x + w, y + h - 3, 0xFF000000);

        int by = y + h - 3;
        g.fill(x + 1, by, x + 2, by + 1, 0xFF000000);
        g.fill(x + 2, by, x + 3, by + 1, PANEL_FILL);
        g.fill(x + 3, by, x + w - 1, by + 1, PANEL_SHADE);
        g.fill(x + w - 1, by, x + w, by + 1, 0xFF000000);

        g.fill(x + 2, by + 1, x + 3, by + 2, 0xFF000000);
        g.fill(x + 3, by + 1, x + w - 2, by + 2, PANEL_SHADE);
        g.fill(x + w - 2, by + 1, x + w - 1, by + 2, 0xFF000000);

        g.fill(x + 3, by + 2, x + w - 2, by + 3, 0xFF000000);
    }

    /** Recessed list well: dark along the top and left, light along the bottom and right. */
    private static void drawInset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, INSET_FILL);
        g.fill(x, y, x + w - 1, y + 1, INSET_SHADE);
        g.fill(x, y, x + 1, y + h - 1, INSET_SHADE);
        g.fill(x + 1, y + h - 1, x + w, y + h, PANEL_HIGHLIGHT);
        g.fill(x + w - 1, y + 1, x + w, y + h, PANEL_HIGHLIGHT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
