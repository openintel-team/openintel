package dev.openintel.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.config.OIConfig;
import dev.openintel.feature.Feature;
import dev.openintel.feature.Features;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

import static dev.openintel.gui.OiStyle.*;

/**
 * The main OpenIntel settings screen, a copy of Jade's Plugin Settings. A
 * black sidebar holds a search box and the headings (the current one marked
 * with a white bar; clicking jumps there). The right side scrolls through
 * each heading's centered title and a grid of feature tiles: item icon, name
 * and a two-part switch. Clicking a tile opens that feature's settings page.
 * Tiles with sub-options carry a small arrow. Hovering the tile shows its
 * dropdown for as long as the pointer stays on the tile or the dropdown;
 * clicking the arrow strip pins it open. Either way the dropdown drops
 * a panel of sub-switches over the tiles below without moving them, as Jade
 * does. Cancel and Save &amp; Quit sit in a bottom bar with Reset on the left.
 *
 * Switches edit the live config, but nothing takes effect until Save &amp; Quit
 * (off-hooks and save). Cancel, ESC or any other close restores the switches
 * stored when the screen opened.
 */
public final class FeaturesScreen extends Screen {

    // ----------------------------------------------------- layout (px) ----
    private static final int SIDE_LIST_TOP = 26;
    private static final int SECTION_GAP = 10;
    private static final int TILE_H = 36;
    private static final int ARROW_STRIP = 10;
    private static final int DETAIL_H = 20;
    private static final int DROP_PAD = 4;
    private static final int MIN_TILE_W = 170;
    /** Two rows per notch, so each notch is a long, visible glide (Jade moves three entries). */
    private static final double SCROLL_STEP = 2 * (TILE_H + GAP);

    private final Screen parent;
    private final OIConfig cfg;
    private final EnumMap<Feature, Boolean> before;
    private final EnumMap<Feature, Boolean> storedAtOpen;
    private final FeatureListModel model;
    private EditBox searchField;
    /** Scroll target; {@link #smooth} eases the drawn offset toward it. */
    private double scrollOffset;
    private final SmoothScroll smooth = new SmoothScroll();
    private boolean finished;
    /** Set while a settings page opened from here is showing; it returns to this screen. */
    private boolean navigating;
    /** Tile whose dropdown is showing because the pointer is on the tile or its dropdown. */
    private Feature hovering;
    /** Tiles whose dropdowns are pinned open (arrow click or search). Searching can open several. */
    private final java.util.Set<Feature> open = java.util.EnumSet.noneOf(Feature.class);
    /** Setting labels and tooltips per settings page, built on first search. */
    private java.util.Map<String, List<String>> pageText;

    public FeaturesScreen(Screen parent) {
        super(Component.translatable("openintel.features.title"));
        this.parent = parent;
        this.cfg = OpenIntelClient.config();
        this.before = Features.snapshot(cfg);
        this.storedAtOpen = Features.stored(cfg);
        this.model = new FeatureListModel(k -> Component.translatable(k).getString());
        this.model.setSettingsText(f -> {
            String page = FeatureSettings.pageFor(f);
            return page == null ? List.of() : pageText().getOrDefault(page, List.of());
        });
    }

    // --------------------------------------------------------- layout -----

    private int sidebarW() { return Math.max(96, Math.min(132, width / 7)); }
    private int contentLeft() { return sidebarW() + 6; }
    private int contentRight() { return width - 8; }
    private int viewTop() { return 0; }
    private int viewBottom() { return height - BAR_H; }

    private record Title(String titleKey, int y) { }
    /** A feature tile ({@code link == null}) or a General tile that only opens a page. */
    private record Tile(Feature feature, FeatureSettings.Link link, int x, int y, int w, int h) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        Tile at(int newY) { return new Tile(feature, link, x, newY, w, h); }
    }
    /** The open dropdown, hanging below its tile and drawn over whatever is there. */
    private record Drop(Tile owner, List<Feature> rows, int x, int y, int w, int h) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record Layout(List<Title> titles, List<Tile> tiles, List<Drop> drops, int height) { }

    /** Every settings page's option labels and tooltips, flattened as label, tooltip pairs. */
    private java.util.Map<String, List<String>> pageText() {
        if (pageText == null) {
            pageText = new java.util.HashMap<>();
            ClickGui.RebindHandler none = new ClickGui.RebindHandler() {
                @Override public void begin(net.minecraft.client.KeyMapping kb) { }
                @Override public void reset(net.minecraft.client.KeyMapping kb) { }
            };
            try {
                for (ClickGui.Category cat : ClickGui.build(this, none)) {
                    List<String> text = new ArrayList<>();
                    for (ClickGui.Item item : cat.items()) {
                        if (item instanceof ClickGui.Option o) {
                            text.add(o.label());
                            text.add(o.tooltip() == null ? "" : o.tooltip());
                        }
                    }
                    pageText.put(cat.id(), text);
                }
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger("OpenIntel").warn("Settings search index failed", e);
            }
        }
        return pageText;
    }

    private String linkSettingMatch(FeatureSettings.Link link) {
        String q = model.query();
        if (q.isEmpty() || link.page == null) return null;
        List<String> text = pageText().getOrDefault(link.page, List.of());
        for (int i = 0; i + 1 < text.size(); i += 2) {
            if (text.get(i).toLowerCase(Locale.ROOT).contains(q)
                    || text.get(i + 1).toLowerCase(Locale.ROOT).contains(q)) return text.get(i);
        }
        return null;
    }

    /** Positions in content coordinates (before scrolling). Every tile has the same height. */
    private Layout layout() {
        int left = contentLeft(), w = contentRight() - left;
        int cols = Math.max(1, Math.min(3, (w + GAP) / (MIN_TILE_W + GAP)));
        int tileW = (w - GAP * (cols - 1)) / cols;
        List<Title> titles = new ArrayList<>();
        List<Tile> tiles = new ArrayList<>();
        List<Drop> drops = new ArrayList<>();
        // Bottom edge of an open dropdown in each column, so a later open tile is not hidden under it.
        int[] occupied = new int[cols];
        int y = 4;
        List<FeatureSettings.Link> links = new ArrayList<>();
        for (FeatureSettings.Link link : FeatureSettings.links()) if (linkMatches(link)) links.add(link);
        if (!links.isEmpty()) {
            titles.add(new Title("openintel.features.group.general", y));
            y += TITLE_H;
            y = grid(tiles, drops, occupied, links.size(), cols, left, tileW, y, i -> null, links::get);
            y += SECTION_GAP;
        }
        for (FeatureListModel.Section s : model.sections()) {
            titles.add(new Title(s.group().titleKey(), y));
            y += TITLE_H;
            y = grid(tiles, drops, occupied, s.tiles().size(), cols, left, tileW, y, s.tiles()::get, i -> null);
            y += SECTION_GAP;
        }

        // Folded away by search: forget it.
        java.util.Set<Feature> shown = java.util.EnumSet.noneOf(Feature.class);
        for (Drop d : drops) shown.add(d.owner().feature());
        open.retainAll(shown);
        if (hovering != null && !shown.contains(hovering)) hovering = null;
        for (Drop d : drops) y = Math.max(y, d.y() + d.h() + SECTION_GAP);
        return new Layout(titles, tiles, drops, y);
    }

    /**
     * Places one section's tiles. Open dropdowns hang over the tiles below
     * without moving them, as in Jade. The one exception: a row is pushed down
     * when one of its own open tiles would be hidden under an earlier dropdown.
     */
    private int grid(List<Tile> out, List<Drop> drops, int[] occupied, int n, int cols, int left, int tileW, int y,
                     java.util.function.IntFunction<Feature> feature,
                     java.util.function.IntFunction<FeatureSettings.Link> link) {
        for (int i = 0; i < n; i += cols) {
            int rowY = y;
            for (int c = 0; c < cols && i + c < n; c++) {
                Feature f = feature.apply(i + c);
                if (f != null && open.contains(f) && occupied[c] > rowY) rowY = occupied[c] + GAP;
            }
            for (int c = 0; c < cols && i + c < n; c++) {
                Feature f = feature.apply(i + c);
                Tile t = new Tile(f, link.apply(i + c), left + c * (tileW + GAP), rowY, tileW, TILE_H);
                out.add(t);
                if (f != null && (open.contains(f) || f == hovering) && FeatureListModel.expandable(f)) {
                    List<Feature> rows = FeatureListModel.details(f);
                    Drop d = new Drop(t, rows, t.x(), t.y() + t.h() - 1, t.w(), rows.size() * DETAIL_H + DROP_PAD * 2);
                    drops.add(d);
                    occupied[c] = Math.max(occupied[c], d.y() + d.h());
                }
            }
            y = rowY + TILE_H + GAP;
        }
        return y;
    }

    private boolean linkMatches(FeatureSettings.Link link) {
        String q = model.query();
        if (q.isEmpty()) return true;
        return Component.translatable(link.labelKey()).getString().toLowerCase(Locale.ROOT).contains(q)
                || Component.translatable(link.tooltipKey()).getString().toLowerCase(Locale.ROOT).contains(q)
                || linkSettingMatch(link) != null;
    }

    private int maxScroll(Layout layout) {
        return Math.max(0, layout.height() - (viewBottom() - viewTop()));
    }

    // ----------------------------------------------------------- init -----

    @Override
    protected void init() {
        navigating = false;
        String query = searchField != null ? searchField.getValue() : "";
        searchField = new EditBox(font, 8, 7, sidebarW() - 14, 12,
                Component.translatable("openintel.features.search"));
        searchField.setBordered(false);
        searchField.setHint(Component.translatable("openintel.features.search").withStyle(ChatFormatting.DARK_GRAY));
        searchField.setValue(query);
        searchField.setResponder(q -> {
            model.setQueryResettingFolds(q);
            scrollOffset = 0;
            smooth.snap(0);
            // A search that only hits a sub-option opens that tile's dropdown so the match shows.
            open.clear();
            for (FeatureListModel.Section s : model.sections()) {
                for (Feature f : s.tiles()) {
                    if (model.isExpanded(f)) open.add(f);
                }
            }
        });
        addRenderableWidget(searchField);

        int by = barButtonY(height);
        addRenderableWidget(Button.builder(Component.translatable("openintel.features.reset"),
                b -> Features.resetDefaults(cfg)).bounds(10, by, 100, 20).build());
        int bw = 90;
        addRenderableWidget(Button.builder(Component.translatable("openintel.features.done"),
                b -> done()).bounds(width - 10 - bw, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("openintel.features.cancel"),
                b -> cancel()).bounds(width - 10 - bw * 2 - 6, by, bw, 20).build());
    }

    // --------------------------------------------------------- render -----

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // In-world screens must not call extractBackground: its blur pass runs once per frame.
        extractTransparentBackground(ctx);

        Layout layout = layout();
        scrollOffset = Math.max(0, Math.min(maxScroll(layout), scrollOffset));
        smooth.update(scrollOffset);
        if (updateHover(layout, mouseX, mouseY)) layout = layout();
        // Laid out at whole pixels, then shifted by the sub-pixel remainder of the glide.
        int scroll = smooth.shownFloor(scrollOffset);
        float sub = smooth.fraction(scrollOffset);
        int top = viewTop(), bottom = viewBottom();
        boolean inView = mouseX >= contentLeft() - 3 && mouseY >= top && mouseY < bottom;
        List<Drop> drops = new ArrayList<>();
        for (Drop d : layout.drops()) drops.add(shifted(d, scroll));
        // Dropdowns cover the tiles under them, so those must not light up through them.
        boolean overDrop = false;
        for (Drop d : drops) overDrop |= inView && d.contains(mouseX, mouseY);
        int hx = inView && !overDrop ? mouseX : -1, hy = inView && !overDrop ? mouseY : -1;

        sidebar(ctx, sidebarW(), bottom);
        bottomBar(ctx, width, height);
        drawSidebar(ctx, layout, mouseX, mouseY);

        ctx.enableScissor(sidebarW(), top, width, bottom);
        ctx.pose().pushMatrix();
        ctx.pose().translate(0, -sub);
        int cx = (contentLeft() + contentRight()) / 2;
        for (Title t : layout.titles()) {
            int y = t.y() - scroll;
            if (y + TITLE_H < top || y > bottom) continue;
            sectionTitle(ctx, font, Component.translatable(t.titleKey()), cx, y);
        }
        Feature hovered = null;
        boolean hoveredTileHead = false;
        FeatureSettings.Link hoveredLink = null;
        for (Tile t : layout.tiles()) {
            int y = t.y() - scroll;
            if (y + t.h() < top || y > bottom) continue;
            Tile drawn = t.at(y);
            if (t.link() != null) {
                if (drawLink(ctx, drawn, hx, hy)) hoveredLink = t.link();
            } else if (drawTile(ctx, drawn, hx, hy)) {
                hovered = t.feature();
                hoveredTileHead = true;
            }
        }
        // Later dropdowns draw on top; only the topmost one under the pointer reacts.
        Drop top0 = topDrop(drops, inView ? mouseX : -1, inView ? mouseY : -1);
        for (Drop d : drops) {
            boolean live = d == top0;
            Feature over = drawDrop(ctx, d, live ? mouseX : -1, live ? mouseY : -1);
            if (over != null) {
                hovered = over;
                hoveredTileHead = false;
            }
        }
        if (layout.titles().isEmpty()) {
            ctx.centeredText(font, Component.translatable("openintel.features.no_match"), cx, top + 30, TEXT_DIM);
        }
        ctx.pose().popMatrix();
        ctx.disableScissor();

        scrollbar(ctx, width, top, bottom, layout.height(), smooth.shown(scrollOffset), maxScroll(layout));

        super.extractRenderState(ctx, mouseX, mouseY, delta);

        if (hovered != null) {
            List<FormattedCharSequence> lines = new ArrayList<>(
                    font.split(Component.translatable(hovered.tooltipKey()), 220));
            String reason = Features.blockedReason(cfg, hovered);
            if (reason != null) {
                lines.addAll(font.split(Component.translatable(reason).withStyle(ChatFormatting.GRAY), 220));
            }
            if (hoveredTileHead && FeatureSettings.pageFor(hovered) != null) {
                lines.add(Component.translatable("openintel.features.open_settings")
                        .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
            }
            ctx.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        } else if (hoveredLink != null) {
            ctx.setTooltipForNextFrame(font, font.split(Component.translatable(hoveredLink.tooltipKey()), 220),
                    mouseX, mouseY);
        }
    }

    /**
     * Hovering an expandable tile shows its dropdown; it stays while the
     * pointer is on that tile or the dropdown. Returns whether it changed.
     */
    private boolean updateHover(Layout layout, int mouseX, int mouseY) {
        Feature before = hovering;
        boolean inView = mouseX >= contentLeft() - 3 && mouseX < width && mouseY >= viewTop() && mouseY < viewBottom();
        double cy = mouseY + smooth.shownPx(scrollOffset);
        if (!inView) {
            hovering = null;
            return before != null;
        }
        if (hovering != null) {
            boolean keep = false;
            for (Drop d : layout.drops()) {
                if (d.owner().feature() == hovering && (d.contains(mouseX, cy) || d.owner().contains(mouseX, cy))) keep = true;
            }
            if (!keep) hovering = null;
        }
        if (hovering == null && topDrop(layout.drops(), mouseX, cy) == null) {
            for (Tile t : layout.tiles()) {
                if (t.feature() != null && FeatureListModel.expandable(t.feature()) && t.contains(mouseX, cy)) {
                    hovering = t.feature();
                }
            }
        }
        return before != hovering;
    }

    private static Drop topDrop(List<Drop> drops, double x, double y) {
        Drop hit = null;
        for (Drop d : drops) if (d.contains(x, y)) hit = d;
        return hit;
    }

    private static Drop shifted(Drop d, int scroll) {
        return new Drop(d.owner().at(d.owner().y() - scroll), d.rows(), d.x(), d.y() - scroll, d.w(), d.h());
    }

    private void drawSidebar(GuiGraphicsExtractor ctx, Layout layout, int mouseX, int mouseY) {
        int sbW = sidebarW();
        String current = currentGroup(layout);
        int y = SIDE_LIST_TOP;
        ctx.enableScissor(0, 0, sbW, viewBottom());
        for (Title t : layout.titles()) {
            boolean hov = mouseX >= 0 && mouseX < sbW && mouseY >= y && mouseY < y + SIDE_LINE_H;
            sidebarEntry(ctx, font, Component.translatable(t.titleKey()), y, sbW, t.titleKey().equals(current), hov);
            y += SIDE_LINE_H;
        }
        ctx.disableScissor();
    }

    /** The heading whose title has scrolled to (or above) the top of the view. */
    private String currentGroup(Layout layout) {
        String current = null;
        for (Title t : layout.titles()) {
            if (current == null || t.y() <= scrollOffset + 8) current = t.titleKey();
        }
        return current;
    }

    /** A General tile: icon, name and a chevron where a switch would be. Returns whether it is hovered. */
    private boolean drawLink(GuiGraphicsExtractor ctx, Tile t, int mouseX, int mouseY) {
        boolean hov = t.contains(mouseX, mouseY);
        int x0 = t.x(), y0 = t.y(), x1 = t.x() + t.w(), y1 = t.y() + t.h();
        tile(ctx, x0, y0, x1, y1, hov, false);
        int midY = y0 + t.h() / 2;
        ctx.item(FeatureIcons.of(t.link()), x0 + 8, midY - 8);
        ctx.text(font, Component.translatable(t.link().labelKey()), x0 + 30, midY - 4, TEXT);
        chevron(ctx, x1 - 14, midY, hov ? TEXT : ARROW);
        return hov;
    }

    /** Draws a feature tile; returns whether it is hovered. */
    private boolean drawTile(GuiGraphicsExtractor ctx, Tile t, int mouseX, int mouseY) {
        Feature f = t.feature();
        boolean hov = t.contains(mouseX, mouseY) || open.contains(f) || f == hovering;
        boolean expandable = FeatureListModel.expandable(f);
        int x0 = t.x(), y0 = t.y(), x1 = t.x() + t.w(), y1 = t.y() + t.h();
        tile(ctx, x0, y0, x1, y1, hov, expandable);

        boolean active = toggleActive(f);
        String reason = Features.blockedReason(cfg, f);
        boolean dim = !active || reason != null;
        int midY = y0 + t.h() / 2;

        int textX = x0 + 8;
        var icon = FeatureIcons.of(f);
        if (!icon.isEmpty()) {
            ctx.item(icon, x0 + 8, midY - 8);
            textX = x0 + 30;
        }
        int swX = x1 - 8 - SW_W;
        String text = font.plainSubstrByWidth(Component.translatable(f.labelKey()).getString(),
                Math.max(10, swX - 6 - textX));
        ctx.text(font, text, textX, midY - 4, dim ? TEXT_DIM : TEXT);
        String match = model.settingMatch(f);
        String note = match != null && !model.matches(f) ? "› " + match
                : reason != null ? Component.translatable(reason).getString() : null;
        if (note != null) {
            int noteX = textX + font.width(text) + 6;
            String fit = font.plainSubstrByWidth(note, Math.max(0, swX - 6 - noteX));
            if (!fit.isEmpty()) ctx.text(font, fit, noteX, midY - 4, match != null ? TEXT_DIM : TEXT_FAINT);
        }
        drawSwitch(ctx, swX, midY - SW_H / 2, f.get(cfg), active);

        if (expandable) {
            boolean overArrow = t.contains(mouseX, mouseY) && mouseY >= y1 - ARROW_STRIP;
            arrow(ctx, (x0 + x1) / 2, y1 - 6, open.contains(f) || f == hovering, overArrow ? TEXT : ARROW);
        }
        return t.contains(mouseX, mouseY);
    }

    /** Draws the open dropdown over the tiles below; returns the sub-option under the pointer. */
    private Feature drawDrop(GuiGraphicsExtractor ctx, Drop d, int mouseX, int mouseY) {
        int x0 = d.x(), y0 = d.y(), x1 = d.x() + d.w(), y1 = d.y() + d.h();
        panel(ctx, x0, y0, x1, y1);
        Feature hovered = null;
        int y = y0 + DROP_PAD;
        int swX = x1 - 10 - SW_W;
        for (Feature f : d.rows()) {
            boolean active = toggleActive(f);
            boolean dim = !active || Features.blockedReason(cfg, f) != null;
            boolean rowHov = d.contains(mouseX, mouseY) && mouseY >= y && mouseY < y + DETAIL_H;
            if (rowHov) {
                ctx.fill(x0 + 2, y, x1 - 2, y + DETAIL_H, ROW_HOVER);
                hovered = f;
            }
            int lx = x0 + 12 + (FeatureListModel.depth(f) - 1) * 10;
            String label = font.plainSubstrByWidth(Component.translatable(f.labelKey()).getString(),
                    Math.max(10, swX - 6 - lx));
            ctx.text(font, label, lx, y + (DETAIL_H - 8) / 2, dim ? TEXT_DIM : TEXT);
            drawSwitch(ctx, swX, y + (DETAIL_H - SW_H) / 2, f.get(cfg), active);
            y += DETAIL_H;
        }
        return hovered;
    }

    /** Sub-options are inert while an ancestor's stored switch is off; missing mods are inert too. */
    private boolean toggleActive(Feature f) {
        for (Feature p = f.parent; p != null; p = p.parent) {
            if (!p.get(cfg)) return false;
        }
        return !"openintel.features.not_installed".equals(Features.blockedReason(cfg, f));
    }

    // ---------------------------------------------------------- input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        double mx = click.x(), my = click.y();
        Layout layout = layout();

        // Sidebar: jump to a heading.
        if (mx < sidebarW() && my >= SIDE_LIST_TOP && my < viewBottom()) {
            int i = (int) ((my - SIDE_LIST_TOP) / SIDE_LINE_H);
            if (i >= 0 && i < layout.titles().size()) {
                scrollOffset = Math.min(maxScroll(layout), layout.titles().get(i).y() - 4);
                playClick();
                return true;
            }
            return false;
        }
        if (my < viewTop() || my >= viewBottom()) return false;

        double cy = my + smooth.shownPx(scrollOffset);
        Drop drop = topDrop(layout.drops(), mx, cy);
        if (drop != null) {
            int row = (int) ((cy - drop.y() - DROP_PAD) / DETAIL_H);
            if (row >= 0 && row < drop.rows().size()) {
                Feature d = drop.rows().get(row);
                if (toggleActive(d)) {
                    d.set(cfg, !d.get(cfg));
                    playClick();
                }
            }
            return true;
        }

        for (Tile t : layout.tiles()) {
            if (!t.contains(mx, cy)) continue;
            if (t.link() != null) {
                playClick();
                openLink(t.link());
                return true;
            }
            Feature f = t.feature();
            int swX = t.x() + t.w() - 8 - SW_W;
            boolean onSwitch = mx >= swX - 4 && mx < swX + SW_W + 4;
            boolean expandable = FeatureListModel.expandable(f);
            boolean onArrow = expandable && cy >= t.y() + t.h() - ARROW_STRIP;
            String page = FeatureSettings.pageFor(f);
            if (onSwitch || (!expandable && page == null)) {
                if (toggleActive(f)) {
                    f.set(cfg, !f.get(cfg));
                    playClick();
                }
            } else if (onArrow || page == null) {
                if (!open.remove(f)) open.add(f);
                playClick();
            } else {
                playClick();
                openPage(page, model.settingMatch(f) != null ? searchField.getValue() : null);
            }
            return true;
        }
        // A click on empty space closes the dropdowns.
        if (!open.isEmpty()) {
            open.clear();
            return true;
        }
        return false;
    }

    /** Opens a settings page that returns here; pending switches stay pending. */
    private void openPage(String page, String query) {
        navigating = true;
        minecraft.setScreenAndShow(ClickGuiScreen.forCategory(this, page, query));
    }

    private void openLink(FeatureSettings.Link link) {
        navigating = true;
        Screen next = switch (link) {
            case RELAYS -> new RelaySettingsScreen(this);
            case HUD_EDITOR -> new HudEditorScreen(this);
            case KEYBINDS -> ClickGuiScreen.forCategory(this, link.page,
                    linkSettingMatch(link) != null ? searchField.getValue() : null);
        };
        minecraft.setScreenAndShow(next);
    }

    private void playClick() {
        if (minecraft == null) return;
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= sidebarW() && mouseY >= viewTop() && mouseY < viewBottom()) {
            // Notches add to the target, so quick scrolling piles onto one glide.
            scrollOffset = Math.max(0, Math.min(maxScroll(layout()), scrollOffset - vertical * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == InputConstants.KEY_ESCAPE) {
            // ESC folds an open dropdown first, then cancels.
            // Hover dropdowns would reappear under the pointer, so ESC only folds pinned ones.
            if (!open.isEmpty()) open.clear();
            else cancel();
            return true;
        }
        return super.keyPressed(input);
    }

    // ------------------------------------------------------ lifecycle -----

    private void done() {
        finished = true;
        cfg.save();
        Features.applyChanges(before, cfg);
        ApiBridge.settingsChanged();
        minecraft.gui.setScreen(parent);
    }

    private void cancel() {
        finished = true;
        Features.restore(cfg, storedAtOpen);
        // Settings pages save the config when they close; drop pending switches from the file too.
        cfg.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void removed() {
        super.removed();
        // Closed some other way (disconnect, another screen): treat as Cancel.
        if (!finished && !navigating) {
            finished = true;
            Features.restore(cfg, storedAtOpen);
            cfg.save();
        }
    }
}
