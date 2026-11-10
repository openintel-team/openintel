package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.feature.Feature;
import dev.openintel.feature.Features;
import dev.openintel.radar.RadarHud;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One feature's settings page, in the same Jade-style look as the main
 * Features screen ({@link OiStyle}): a black sidebar with a search box and a
 * jump list of the page's sections, centered section titles over one tile per
 * option (label left, control right), and a bottom bar with Back and Done.
 * Searching flattens matching options into one list.
 *
 * The option model comes from {@link ClickGui#build} — Groups render as
 * section headers, Options as label + right-aligned widget rows. Every
 * Option widget (and its aux) is a real drawable child, so vanilla input
 * handles slider drags, button clicks and text-field focus for free.
 * Rendering is manual: each frame the screen computes every row's Y from
 * the scroll offset, repositions its widgets and draws them inside the
 * pane's scissor rect. {@code widget.visible} doubles as the input gate —
 * rows outside the clip can't be clicked.
 *
 * The screen also implements {@link ClickGui.RebindHandler}: clicking a
 * keybind row's button starts listening, the next key press or mouse
 * click becomes the binding, and ESC cancels.
 */
public class ClickGuiScreen extends Screen implements ClickGui.RebindHandler {

    // ----------------------------------------------------- layout (px) ----
    private static final int SIDE_LIST_TOP = 26;
    private static final int OPTION_H = 30;        // option tile height
    private static final int OPTION_STEP = OPTION_H + OiStyle.GAP;
    private static final int GROUP_H = OiStyle.TITLE_H;
    private static final int WIDGET_W = 150;
    private static final int WIDGET_H = 18;
    private static final int AUX_W = 60;
    private static final int AUX_GAP = 6;
    /** Two options per notch, so each notch is a long, visible glide (Jade moves three entries). */
    private static final double SCROLL_STEP = 2 * (OPTION_H + OiStyle.GAP);
    private static final int TOOLTIP_W = 220;

    private final Screen parent;
    /** Categories of enabled features; rebuilt on every {@link #init()}, e.g. after the Features screen. */
    private List<ClickGui.Category> categories = List.of();
    private final String version;
    private final EditBox searchField;
    private Button doneButton;
    private Button featuresButton;
    /** Category to show after the next rebuild, by id. */
    private String selectedId;

    private int selected;
    private List<ClickGui.Item> rows = List.of();
    private boolean viewDirty = true;
    /** Scroll target; {@link #smooth} eases the drawn offset toward it. */
    private double scrollOffset;
    private final SmoothScroll smooth = new SmoothScroll();

    /** Non-null while a keybind row is capturing the next input. */
    private KeyMapping listeningFor;

    public ClickGuiScreen(Screen parent) {
        this(parent, null);
    }

    /** Non-null on a single feature's settings page opened from the main Features screen. */
    private String focusId;

    private int sidebarW() { return Math.max(96, Math.min(132, width / 7)); }

    /**
     * One category's settings page, as opened from a feature tile. Its own
     * sub-sections stay visible even while their switches are off, so the
     * feature can be set up before it is turned on. Back returns to parent.
     */
    public static ClickGuiScreen forCategory(Screen parent, String categoryId) {
        return forCategory(parent, categoryId, null);
    }

    /** Opens a page already filtered by {@code query}, e.g. the main screen's search that found it. */
    public static ClickGuiScreen forCategory(Screen parent, String categoryId, String query) {
        ClickGuiScreen screen = new ClickGuiScreen(parent, categoryId);
        screen.focusId = categoryId;
        if (query != null && !query.isBlank()) screen.searchField.setValue(query.trim());
        return screen;
    }

    public ClickGuiScreen(Screen parent, String categoryId) {
        super(Component.literal("OpenIntel"));
        this.parent = parent;
        this.selectedId = categoryId;

        this.version = FabricLoader.getInstance().getModContainer("openintel")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");

        searchField = new EditBox(
                net.minecraft.client.Minecraft.getInstance().font,
                0, 0, 134, 16, Component.translatable("options.openintel.settings.search"));
        searchField.setHint(Component.translatable("options.openintel.settings.search"));
        searchField.setResponder(q -> {
            viewDirty = true;
            scrollOffset = 0;
            smooth.snap(0);
            blurOptionFocus();
        });
    }

    // --------------------------------------------------------- layout -----

    @Override
    protected void init() {
        searchField.setX(8);
        searchField.setY(7);
        searchField.setWidth(sidebarW() - 14);
        searchField.setHeight(12);
        searchField.setBordered(false);
        searchField.setHint(Component.translatable("options.openintel.settings.search")
                .withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(searchField);

        featuresButton = addRenderableWidget(Button.builder(
                        Component.translatable(focusId != null ? "options.openintel.settings.back"
                                : "options.openintel.features.button"),
                        b -> {
                            if (focusId != null) onClose();
                            else minecraft.setScreenAndShow(new FeaturesScreen(this));
                        })
                .bounds(10, OiStyle.barButtonY(height), 90, 20).build());

        // Rebuilt each time so sections of features switched off on the
        // Features screen disappear when it returns here.
        listeningFor = null;
        if (!categories.isEmpty()) selectedId = categories.get(selected).id();
        if (focusId != null) {
            // Keybinds still list only enabled features' keys; any other page shows all of its settings.
            java.util.function.Predicate<Feature> on = "keybinds".equals(focusId) ? Features::isOn : f -> true;
            categories = ClickGui.visible(ClickGui.build(this, this).stream()
                    .filter(c -> c.id().equals(focusId)).toList(), on);
        } else {
            categories = ClickGui.visible(ClickGui.build(this, this), Features::isOn);
        }
        selected = 0;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id().equals(selectedId)) {
                selected = i;
                break;
            }
        }

        doneButton = addRenderableWidget(Button.builder(Component.translatable("options.openintel.settings.done"),
                        b -> onClose())
                .bounds(width - 100, OiStyle.barButtonY(height), 90, 20).build());

        // Every option widget is a real child — vanilla input gives us
        // drags, clicks and text focus. They start hidden; render() turns
        // visibility back on for the rows that intersect the pane clip.
        hideOptionWidgets();
        for (ClickGui.Category cat : categories) {
            for (ClickGui.Item item : cat.items()) {
                if (item instanceof ClickGui.Option opt) {
                    addRenderableWidget(opt.widget());
                    if (opt.aux() != null) addRenderableWidget(opt.aux());
                }
            }
        }
        viewDirty = true;
    }

    private int paneLeft()      { return sidebarW() + 6; }
    private int paneTop()       { return 0; }
    private int paneRight()     { return width - 8; }
    private int paneBottom()    { return height - OiStyle.BAR_H; }
    /** Marker for the crosshair preview tile placed after the crosshair page's options. */
    private static final ClickGui.Group PREVIEW = new ClickGui.Group("preview");
    private static final int PREVIEW_H = 96;

    /**
     * The crosshair page ends with a preview of the custom style. It shows
     * even while the feature is off, so the crosshair can be set up first.
     */
    private boolean showsCrosshairPreview() {
        return !searching() && "crosshair".equals(selectedCategoryId());
    }

    private String selectedCategoryId() {
        return selected < categories.size() ? categories.get(selected).id() : null;
    }

    private int contentTop()    { return paneTop() + 2; }
    private int contentBottom() { return paneBottom() - 2; }
    private int viewportHeight() {
        return Math.max(0, contentBottom() - contentTop());
    }

    private void hideOptionWidgets() {
        for (ClickGui.Category cat : categories) {
            for (ClickGui.Item item : cat.items()) {
                if (item instanceof ClickGui.Option opt) {
                    opt.widget().visible = false;
                    if (opt.aux() != null) opt.aux().visible = false;
                }
            }
        }
    }

    // ----------------------------------------------------------- view -----

    private boolean searching() {
        return !searchField.getValue().isBlank();
    }

    /** Rebuilds the row list for the selected category — or, while the
     *  search box is non-blank, a flat cross-category result list headed
     *  by each matching option's category title. */
    private void refreshView() {
        viewDirty = false;
        List<ClickGui.Item> out = new ArrayList<>();
        if (searching()) {
            String q = searchField.getValue().trim().toLowerCase(Locale.ROOT);
            for (ClickGui.Category cat : categories) {
                boolean header = false;
                for (ClickGui.Item item : cat.items()) {
                    if (item instanceof ClickGui.Option opt && matches(opt, q)) {
                        if (!header) {
                            out.add(new ClickGui.Group(cat.title()));
                            header = true;
                        }
                        out.add(opt);
                    }
                }
            }
        } else {
            if (selected < categories.size()) out.addAll(categories.get(selected).items());
        }
        rows = out;
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
    }

    private static boolean matches(ClickGui.Option opt, String q) {
        return opt.label().toLowerCase(Locale.ROOT).contains(q)
                || (opt.tooltip() != null
                    && opt.tooltip().toLowerCase(Locale.ROOT).contains(q));
    }

    /** Where each row sits in content coordinates: section titles full width, options in a grid. */
    private record Placed(ClickGui.Item item, int x, int y, int w, int h) { }

    /** One centered column, about as wide as a column of the old two-column grid. */
    private static final int COLUMN_W = 430;

    private int columnLeft() {
        int room = paneRight() - 6 - paneLeft();
        return paneLeft() + Math.max(0, (room - Math.min(COLUMN_W, room)) / 2);
    }

    private int columnRight() {
        return columnLeft() + Math.min(COLUMN_W, paneRight() - 6 - paneLeft());
    }

    /** Lays rows out from y = 0 in a single column. */
    /** place() runs for content height, max scroll, the sidebar and drawing; build it once per change. */
    private List<Placed> placedCache;
    private Object placedKey;

    private List<Placed> place() {
        Object key = List.of(System.identityHashCode(rows), rows.size(), columnLeft(), columnRight(), showsCrosshairPreview());
        if (placedCache == null || !key.equals(placedKey)) {
            placedCache = placeUncached();
            placedKey = key;
        }
        return placedCache;
    }

    private List<Placed> placeUncached() {
        int cL = columnLeft(), cR = columnRight();
        int cols = 1;
        int tileW = (cR - cL - OiStyle.GAP * (cols - 1)) / cols;
        List<Placed> out = new ArrayList<>(rows.size());
        int y = 0, col = 0;
        for (ClickGui.Item item : rows) {
            if (item instanceof ClickGui.Group) {
                if (col > 0) { y += OPTION_STEP; col = 0; }
                out.add(new Placed(item, cL, y, cR - cL, GROUP_H));
                y += GROUP_H;
            } else {
                out.add(new Placed(item, cL + col * (tileW + OiStyle.GAP), y, tileW, OPTION_H));
                if (++col == cols) { col = 0; y += OPTION_STEP; }
            }
        }
        if (col > 0) y += OPTION_STEP;
        if (showsCrosshairPreview()) {
            y += 4;
            out.add(new Placed(PREVIEW, cL, y, cR - cL, PREVIEW_H));
            y += PREVIEW_H + OiStyle.GAP;
        }
        out.add(new Placed(null, cL, y, 0, 0));  // end marker carries the total height
        return out;
    }

    private int contentHeight() {
        List<Placed> placed = place();
        return placed.get(placed.size() - 1).y() + 6;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    private void selectCategory(int idx) {
        if (idx == selected && !searching()) return;
        selected = idx;
        // A pick while searching means "jump to that category".
        if (searching()) searchField.setValue("");
        scrollOffset = 0;
        smooth.snap(0);
        viewDirty = true;
        blurOptionFocus();
    }

    /** Keeps a hidden option widget (e.g. a scrolled-out text field) from
     *  silently holding keyboard focus. The search field keeps its own. */
    private void blurOptionFocus() {
        if (getFocused() != null && getFocused() != searchField) {
            setFocused(null);
        }
    }

    /**
     * Sidebar entries. On a single feature's page the entries jump to its
     * sections; otherwise (or while searching across pages) they pick a page.
     */
    private record SideEntry(Component title, int categoryIndex, int offset) { }

    private List<SideEntry> sideEntries() {
        List<SideEntry> out = new ArrayList<>();
        if (focusId != null && !searching()) {
            for (Placed p : place()) {
                if (p.item() instanceof ClickGui.Group g && g != PREVIEW) out.add(new SideEntry(Component.literal(g.title()), -1, p.y()));
            }
            if (out.isEmpty() && !categories.isEmpty()) {
                out.add(new SideEntry(Component.literal(categories.get(0).title()), -1, 0));
            }
        } else {
            for (int i = 0; i < categories.size(); i++) {
                out.add(new SideEntry(Component.literal(categories.get(i).title()), i, 0));
            }
        }
        return out;
    }

    private boolean isCurrent(SideEntry e, List<SideEntry> all) {
        if (e.categoryIndex() >= 0) return e.categoryIndex() == selected;
        SideEntry current = null;
        for (SideEntry x : all) if (current == null || x.offset() <= scrollOffset + 8) current = x;
        return current == e;
    }

    // --------------------------------------------------------- render -----

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // In-world screens must not call renderBackground — its blur pass can
        // only run once per frame. renderTransparentBackground darkens without it.
        renderTransparentBackground(ctx);

        // Live dial preview behind the translucent UI.
        if ("radar".equals(selectedCategoryId()) && Features.isOn(Feature.RADAR)
                && minecraft.level != null && minecraft.player != null) {
            RadarHud.render(ctx, delta);
        }

        if (viewDirty) refreshView();
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
        smooth.update(scrollOffset);

        renderSidebar(ctx, mouseX, mouseY, delta);
        ClickGui.Option hovered = renderPane(ctx, mouseX, mouseY, delta);

        if (doneButton != null) doneButton.render(ctx, mouseX, mouseY, delta);
        if (featuresButton != null) featuresButton.render(ctx, mouseX, mouseY, delta);

        // Tooltips ride above everything else.
        if (hovered != null && hovered.tooltip() != null) {
            // Wrapped, so long descriptions read as a short paragraph instead of one wide line.
            ctx.setTooltipForNextFrame(font,
                    font.split(Component.literal(hovered.tooltip()), TOOLTIP_W), mouseX, mouseY);
        }
    }

    private void renderSidebar(GuiGraphics ctx, int mouseX, int mouseY,
                               float delta) {
        int sbW = sidebarW(), bottom = paneBottom();
        OiStyle.sidebar(ctx, sbW, bottom);
        int barTop = OiStyle.bottomBar(ctx, width, height);
        searchField.render(ctx, mouseX, mouseY, delta);

        List<SideEntry> entries = sideEntries();
        ctx.enableScissor(0, SIDE_LIST_TOP, sbW, bottom);
        int y = SIDE_LIST_TOP;
        for (SideEntry e : entries) {
            boolean hov = mouseX >= 0 && mouseX < sbW && mouseY >= y && mouseY < y + OiStyle.SIDE_LINE_H;
            OiStyle.sidebarEntry(ctx, font, e.title(), y, sbW, isCurrent(e, entries), hov);
            y += OiStyle.SIDE_LINE_H;
        }
        ctx.disableScissor();

        // Page name in the middle of the bottom bar, the version dim after Back.
        String page = focusId != null && !categories.isEmpty() ? categories.get(0).title() : "OpenIntel";
        ctx.drawCenteredString(font, page, width / 2, barTop + (OiStyle.BAR_H - 8) / 2, OiStyle.TEXT);
        ctx.drawString(font, "v" + version, 108, barTop + (OiStyle.BAR_H - 8) / 2, OiStyle.TEXT_FAINT);
    }

    /** Draws the options pane and returns the Option row under the mouse
     *  (for tooltip duty), or null. */
    private ClickGui.Option renderPane(GuiGraphics ctx, int mouseX, int mouseY,
                                       float delta) {
        int pL = paneLeft(), pT = paneTop();
        int pR = paneRight(), pB = paneBottom();
        int cL = pL, cR = pR - 6;

        int cx = (columnLeft() + columnRight()) / 2;

        // Visible rows re-show their widgets below; everything else stays
        // dark — visible=false is also the input gate.
        hideOptionWidgets();

        ClickGui.Option hovered = null;

        int clipTop = pT + 1;
        // Laid out at whole pixels, then shifted by the sub-pixel remainder of the glide.
        // Widget hitboxes stay on whole pixels; the difference is under one GUI pixel.
        int base = contentTop() - smooth.shownFloor(scrollOffset);
        ctx.enableScissor(pL - 6, clipTop, width, pB);
        ctx.pose().pushMatrix();
        ctx.pose().translate(0, -smooth.fraction(scrollOffset));
        for (Placed p : place()) {
            ClickGui.Item item = p.item();
            if (item == null) continue;
            int rowTop = base + p.y();
            boolean intersects = rowTop + p.h() > clipTop && rowTop < pB;

            if (item == PREVIEW) {
                if (intersects) drawCrosshairPreview(ctx, p.x(), rowTop, p.x() + p.w(), rowTop + p.h(), clipTop, pB);
            } else if (item instanceof ClickGui.Group group) {
                if (intersects) OiStyle.sectionTitle(ctx, font, Component.literal(group.title()), cx, rowTop);
            } else if (item instanceof ClickGui.Option opt) {
                int tL = p.x(), tR = p.x() + p.w();
                int t0 = rowTop, t1 = rowTop + OPTION_H;
                int wy = t0 + (OPTION_H - WIDGET_H) / 2;
                AbstractWidget widget = opt.widget();
                AbstractWidget aux = opt.aux();

                // Controls hug the tile's right edge; switches take only their own width,
                // like the tiles on the main screen. Narrow tiles shrink controls before labels.
                int auxW = aux != null ? AUX_W + AUX_GAP : 0;
                boolean toggle = widget instanceof OiWidgets.Toggle;
                boolean field = widget instanceof EditBox;
                int widgetW = toggle ? OiStyle.SW_W + 2
                        : Math.max(60, Math.min(field ? 180 : WIDGET_W, (tR - tL) * 11 / 20 - auxW));
                int widgetLeft = tR - 8 - auxW - widgetW;
                if (field) {
                    // Borderless field inside a drawn box: inset the text.
                    widget.setX(widgetLeft + 5);
                    widget.setY(wy + 5);
                    widget.setWidth(widgetW - 10);
                } else {
                    widget.setX(widgetLeft);
                    widget.setY(wy);
                    widget.setWidth(widgetW);
                }
                widget.setHeight(field ? 10 : WIDGET_H);
                widget.visible = intersects;
                if (aux != null) {
                    aux.setX(widgetLeft + widgetW + AUX_GAP);
                    aux.setY(wy);
                    aux.setWidth(AUX_W);
                    aux.setHeight(WIDGET_H);
                    aux.visible = intersects;
                }

                if (intersects) {
                    boolean hov = mouseX >= tL && mouseX < tR
                            && mouseY >= Math.max(t0, clipTop) && mouseY < Math.min(t1, pB);
                    if (hov) hovered = opt;
                    OiStyle.tile(ctx, tL, t0, tR, t1, hov, false);
                    String label = font.plainSubstrByWidth(opt.label(), Math.max(10, widgetLeft - tL - 14));
                    ctx.drawString(font, label, tL + 8, t0 + (OPTION_H - 8) / 2, OiStyle.TEXT);
                    if (field) {
                        boolean fh = widget.isFocused() || (mouseX >= widgetLeft && mouseX < widgetLeft + widgetW
                                && mouseY >= wy && mouseY < wy + WIDGET_H);
                        OiWidgets.box(ctx, widgetLeft, wy, widgetW, WIDGET_H, fh, true);
                    }
                    widget.render(ctx, mouseX, mouseY, delta);
                    if (aux != null) aux.render(ctx, mouseX, mouseY, delta);
                }
            }
        }
        ctx.pose().popMatrix();
        ctx.disableScissor();

        OiStyle.scrollbar(ctx, width, contentTop(), contentBottom(), contentHeight(), smooth.shown(scrollOffset), maxScroll());

        if (rows.isEmpty() && searching()) {
            ctx.drawCenteredString(font, Component.translatable("options.openintel.settings.no_matches"),
                    (pL + pR) / 2, pT + 24, OiStyle.TEXT_DIM);
        }
        return hovered;
    }

    /** Tile with the custom crosshair drawn at 1:1 over a mid-grey backdrop. */
    private void drawCrosshairPreview(GuiGraphics ctx, int x0, int y0, int x1, int y1, int clipTop, int clipBottom) {
        OiStyle.tile(ctx, x0, y0, x1, y1, false, true);
        int cx = (x0 + x1) / 2;
        ctx.drawCenteredString(font, Component.translatable("options.openintel.settings.preview"), cx, y0 + 6, OiStyle.TEXT_DIM);
        int bx0 = x0 + 8, by0 = y0 + 18, bx1 = x1 - 8, by1 = y1 - 8;
        // A sky-and-ground backdrop so light and dark crosshairs both read.
        int mid = (by0 + by1) / 2;
        ctx.fill(bx0, by0, bx1, mid, 0xFF5C7EA8);
        ctx.fill(bx0, mid, bx1, by1, 0xFF4A5A3A);
        ctx.renderOutline(bx0, by0, bx1 - bx0, by1 - by0, OiStyle.TILE_BORDER);
        ctx.enableScissor(bx0 + 1, Math.max(by0 + 1, clipTop), bx1 - 1, Math.min(by1 - 1, clipBottom));
        // Always the custom style, so it can be tuned before the feature is switched on.
        dev.openintel.render.CrosshairRenderer.draw(ctx, OpenIntelClient.config().crosshair, cx, (by0 + by1) / 2);
        ctx.disableScissor();
        if (!Features.isOn(Feature.CROSSHAIR)) {
            String note = Component.translatable("options.openintel.crosshair.preview_off").getString();
            ctx.drawString(font, font.plainSubstrByWidth(note, bx1 - bx0 - 8), bx0 + 4, by1 - 12, OiStyle.TEXT_SUB);
        }
    }

    // ---------------------------------------------------------- input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (listeningFor != null) {
            listeningFor.setKey(
                    InputConstants.Type.MOUSE.getOrCreate(click.button()));
            finishRebind();
            return true;
        }

        // Sidebar entries: pick a page, or jump to a section of this page.
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT
                && click.x() < sidebarW() && click.y() >= SIDE_LIST_TOP && click.y() < paneBottom()) {
            List<SideEntry> entries = sideEntries();
            int idx = (int) (click.y() - SIDE_LIST_TOP) / OiStyle.SIDE_LINE_H;
            if (idx >= 0 && idx < entries.size()) {
                SideEntry e = entries.get(idx);
                if (e.categoryIndex() >= 0) selectCategory(e.categoryIndex());
                else scrollOffset = Math.max(0, Math.min(maxScroll(), e.offset()));
                return true;
            }
        }

        // A click inside the pane's X span but outside its vertical clip
        // must not reach a widget peeking out from under the scissor.
        // render() re-shows legitimately visible widgets next frame.
        if (click.x() >= paneLeft() && click.x() < paneRight()
                && (click.y() < paneTop() || click.y() >= paneBottom())) {
            hideOptionWidgets();
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        if (mouseX >= paneLeft() && mouseX < paneRight()
                && mouseY >= paneTop() && mouseY < paneBottom()) {
            scrollOffset = Math.max(0, Math.min(maxScroll(),
                    scrollOffset - vertical * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (listeningFor != null) {
            if (input.key() != InputConstants.KEY_ESCAPE) {
                listeningFor.setKey(InputConstants.getKey(input));
            }
            finishRebind();
            return true;
        }
        return super.keyPressed(input);
    }

    // --------------------------------------------------------- rebind -----

    @Override
    public void begin(KeyMapping kb) {
        listeningFor = kb;
        ClickGui.Option opt = findOption(kb);
        if (opt != null) {
            opt.widget().setMessage(Component.literal("> ")
                    .append(kb.getTranslatedKeyMessage())
                    .append(" <")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    @Override
    public void reset(KeyMapping kb) {
        kb.setKey(kb.getDefaultKey());
        commitRebind(kb);
    }

    private void finishRebind() {
        KeyMapping kb = listeningFor;
        listeningFor = null;
        if (kb != null) commitRebind(kb);
    }

    private void commitRebind(KeyMapping kb) {
        KeyMapping.resetMapping();
        if (minecraft != null) minecraft.options.save();
        ClickGui.Option opt = findOption(kb);
        if (opt != null) {
            opt.widget().setMessage(kb.getTranslatedKeyMessage());
        }
    }

    private ClickGui.Option findOption(KeyMapping kb) {
        for (ClickGui.Category cat : categories) {
            for (ClickGui.Item item : cat.items()) {
                if (item instanceof ClickGui.Option opt && opt.keybind() == kb) {
                    return opt;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------ lifecycle -----

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
        OpenIntelClient.config().save();
    }
}
