package dev.openintel.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Shared look for every OpenIntel screen, taken from the Jade-style Features
 * screen: a black sidebar with plain white entries and a white bar for the
 * current one, dark bordered tiles (blue wash toward the bottom on tiles that
 * open more), centered white section titles, and a black bottom bar.
 *
 * Rules and references for staying faithful to this style: docs/UI_STYLE.md.
 */
public final class OiStyle {
    private OiStyle() { }

    // ----------------------------------------------------------- layout ----
    public static final int BAR_H = 30;
    public static final int SIDE_LINE_H = 18;
    public static final int TITLE_H = 26;
    public static final int GAP = 3;

    // ----------------------------------------------------------- colors ----
    public static final int SIDEBAR_BG   = 0xC0000000;
    public static final int BAR_BG       = 0xC0000000;
    public static final int BAR_LINE     = 0x40FFFFFF;
    public static final int TILE_BG      = 0xB0101014;
    public static final int TILE_HOVER   = 0xC01C1C24;
    public static final int TILE_BORDER  = 0x70A0A0A8;
    public static final int TILE_BORDER_HOVER = 0xD0E0E0E8;
    public static final int TILE_TINT    = 0x2A4A78C8;
    public static final int PANEL_BG     = 0xF0101016;
    public static final int PANEL_BORDER = 0xFF5A78B8;
    public static final int ROW_HOVER    = 0x14FFFFFF;
    public static final int SELECTED_BG  = 0x404A78C8;
    public static final int DIVIDER      = 0x30FFFFFF;
    public static final int TEXT         = 0xFFFFFFFF;
    public static final int TEXT_SUB     = 0xFFD0D0D0;
    public static final int TEXT_DIM     = 0xFF8A8A8A;
    public static final int TEXT_FAINT   = 0xFF666666;
    public static final int TEXT_HOVER   = 0xFFFFFFA0;
    public static final int ARROW        = 0xFFB0B0B0;
    public static final int GOOD         = 0xFF7FD67F;
    public static final int BAD          = 0xFFFF6B6B;

    /** Black sidebar from the top of the screen down to the bottom bar. */
    public static void sidebar(GuiGraphics ctx, int w, int bottom) {
        ctx.fill(0, 0, w, bottom, SIDEBAR_BG);
    }

    /** Black bottom bar with a faint top line; returns its top edge. */
    public static int bottomBar(GuiGraphics ctx, int width, int height) {
        int top = height - BAR_H;
        ctx.fill(0, top, width, height, BAR_BG);
        ctx.fill(0, top, width, top + 1, BAR_LINE);
        return top;
    }

    /** Y of a 20 px button centered in the bottom bar. */
    public static int barButtonY(int height) { return height - BAR_H + (BAR_H - 20) / 2; }

    /** Plain sidebar entry; the current one gets a white bar at the left edge. */
    public static void sidebarEntry(GuiGraphics ctx, Font font, Component text, int y, int w,
                                    boolean current, boolean hovered) {
        if (current) ctx.fill(2, y + 3, 4, y + SIDE_LINE_H - 3, TEXT);
        String s = font.plainSubstrByWidth(text.getString(), Math.max(10, w - 14));
        ctx.drawString(font, s, 10, y + (SIDE_LINE_H - 8) / 2, hovered ? TEXT_HOVER : TEXT);
    }

    /** Centered white section title in a {@link #TITLE_H} band. */
    public static void sectionTitle(GuiGraphics ctx, Font font, Component title, int cx, int y) {
        ctx.drawCenteredString(font, title, cx, y + (TITLE_H - 8) / 2, TEXT);
    }

    /** Dark bordered tile; {@code tinted} adds the blue wash used on tiles that open more. */
    public static void tile(GuiGraphics ctx, int x0, int y0, int x1, int y1, boolean hovered, boolean tinted) {
        ctx.fill(x0, y0, x1, y1, hovered ? TILE_HOVER : TILE_BG);
        if (tinted) wash(ctx, x0, y0, x1, y1);
        ctx.renderOutline(x0, y0, x1 - x0, y1 - y0, hovered ? TILE_BORDER_HOVER : TILE_BORDER);
    }

    /** Faint blue gradient over the lower half. */
    public static void wash(GuiGraphics ctx, int x0, int y0, int x1, int y1) {
        int bands = 6, span = Math.max(6, (y1 - y0) / 2);
        for (int i = 0; i < bands; i++) {
            int a = ((TILE_TINT >>> 24) * (i + 1)) / bands;
            int by0 = y1 - span + span * i / bands, by1 = y1 - span + span * (i + 1) / bands;
            ctx.fill(x0 + 1, by0, x1 - 1, by1, (a << 24) | (TILE_TINT & 0xFFFFFF));
        }
    }

    /** Opaque panel for dropdowns and dialogs, with the accent border. */
    public static void panel(GuiGraphics ctx, int x0, int y0, int x1, int y1) {
        ctx.fill(x0, y0, x1, y1, PANEL_BG);
        wash(ctx, x0, y0, x1, y1);
        ctx.renderOutline(x0, y0, x1 - x0, y1 - y0, PANEL_BORDER);
    }

    /** Small triangle; points up when {@code up}. */
    public static void arrow(GuiGraphics ctx, int cx, int y, boolean up, int color) {
        for (int i = 0; i < 3; i++) {
            int half = up ? i : 2 - i;
            ctx.fill(cx - half, y + i, cx + half + 1, y + i + 1, color);
        }
    }

    /** Right-pointing chevron for entries that open another screen. */
    public static void chevron(GuiGraphics ctx, int x, int midY, int color) {
        for (int i = 0; i < 4; i++) {
            ctx.fill(x + i, midY - 3 + i, x + i + 2, midY - 2 + i, color);
            ctx.fill(x + i, midY + 2 - i, x + i + 2, midY + 3 - i, color);
        }
    }

    /** Slim scrollbar on the right of a view. */
    public static void scrollbar(GuiGraphics ctx, int right, int top, int bottom,
                                 int contentH, double scroll, int maxScroll) {
        int viewH = bottom - top;
        if (contentH <= viewH) return;
        int thumbH = Math.max(16, viewH * viewH / contentH);
        int thumbY = top + (maxScroll <= 0 ? 0 : (int) Math.round((viewH - thumbH) * scroll / maxScroll));
        ctx.fill(right - 4, top, right, bottom, 0x40000000);
        ctx.fill(right - 4, thumbY, right, thumbY + thumbH, 0xFFC0C0C0);
        ctx.fill(right - 1, thumbY, right, thumbY + thumbH, 0xFF808080);
    }

    // ----------------------------------------------------------- switch ----
    public static final int SW_W = 24;
    public static final int SW_H = 12;

    /**
     * Jade's two-part switch. On: green track with a light mark on the left,
     * knob on the right. Off: knob on the left, grey track with a hollow mark.
     */
    public static void drawSwitch(GuiGraphics ctx, int x, int y, boolean on, boolean active) {
        int half = SW_W / 2;
        ctx.fill(x, y, x + SW_W, y + SW_H, 0xFF1E1E1E);
        int trackX = on ? x + 1 : x + half;
        int knobX = on ? x + half : x + 1;
        ctx.fill(trackX, y + 1, trackX + half - 1, y + SW_H - 1, on ? 0xFF3C8A2E : 0xFF7A7A7A);
        int mx = trackX + (half - 1) / 2;
        if (on) ctx.fill(mx - 1, y + 3, mx, y + SW_H - 3, 0xFFE0FFE0);
        else ctx.renderOutline(mx - 2, y + 4, 4, 4, 0xFF303030);
        ctx.fill(knobX, y + 1, knobX + half - 1, y + SW_H - 1, 0xFFE6E6E6);
        ctx.fill(knobX, y + 1, knobX + half - 1, y + 2, 0xFFFFFFFF);
        ctx.fill(knobX, y + SW_H - 2, knobX + half - 1, y + SW_H - 1, 0xFFA8A8A8);
        if (!active) ctx.fill(x, y, x + SW_W, y + SW_H, 0x99000000);
    }
}
