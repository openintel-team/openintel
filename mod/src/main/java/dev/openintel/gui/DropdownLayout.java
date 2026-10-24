package dev.openintel.gui;

/** Anchored popup bounds, kept within the GUI viewport and opened upwards when needed. */
public record DropdownLayout(int x, int y, int width, int height, int visibleRows, int searchHeight) {
    public static final int ROW_HEIGHT = 20;
    public static DropdownLayout fit(int ax, int ay, int aw, int ah, int viewportW, int viewportH, int count, boolean searchable) {
        int search = searchable ? 22 : 0;
        int desiredRows = Math.min(8, Math.max(1, count));
        int desiredHeight = 4 + search + desiredRows * ROW_HEIGHT;
        int below = Math.max(0, viewportH - 4 - (ay + ah + 2));
        int above = Math.max(0, ay - 6);
        boolean down = desiredHeight <= below || below >= above;
        int available = down ? below : above;
        int rows = Math.max(1, Math.min(desiredRows, (available - search - 4) / ROW_HEIGHT));
        int height = 4 + search + rows * ROW_HEIGHT;
        int width = Math.min(Math.max(160, aw), viewportW - 8);
        int x = Math.max(4, Math.min(ax, viewportW - width - 4));
        int y = down ? ay + ah + 2 : ay - height - 2;
        y = Math.max(4, Math.min(y, viewportH - height - 4));
        return new DropdownLayout(x, y, width, height, rows, search);
    }
    public boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    public int rowsY() { return y + 2 + searchHeight; }
    public int rowAt(double mx, double my) {
        if (mx < x + 2 || mx >= x + width - 2 || my < rowsY() || my >= rowsY() + visibleRows * ROW_HEIGHT) return -1;
        return (int) ((my - rowsY()) / ROW_HEIGHT);
    }
}
