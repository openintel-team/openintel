package dev.openintel.relic;

import net.minecraft.client.gui.GuiGraphics;

/** Screen-space drawing over a fullscreen map during a library visit: carry hint and lasso box. */
public final class LibraryMapOverlay {
    private static final int LASSO_EDGE = 0xE8E8B84A;
    private static final int LASSO_FILL = 0x44E8B84A;

    private LibraryMapOverlay() { }

    public static void draw(GuiGraphics graphics, int screenWidth) {
        RelicCarryHint.draw(graphics, screenWidth);
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isLassoDragging()) return;
        int[] r = visit.lassoScreenRect();
        int left = r[0];
        int top = r[1];
        int right = r[2];
        int bottom = r[3];
        if (right - left < 1 || bottom - top < 1) return;
        graphics.fill(left, top, right, top + 1, LASSO_EDGE);
        graphics.fill(left, bottom - 1, right, bottom, LASSO_EDGE);
        graphics.fill(left, top, left + 1, bottom, LASSO_EDGE);
        graphics.fill(right - 1, top, right, bottom, LASSO_EDGE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, LASSO_FILL);
    }
}
