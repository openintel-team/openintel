package dev.openintel.render;

public final class CrosshairStyle {
    public boolean enabled = false;
    public int color = 0xFF50FF50;
    public int length = 6;
    public int width = 1;
    public int gap = 3;
    public boolean outline = true;
    public boolean dot = false;

    @FunctionalInterface
    public interface Fill {
        void fill(int x1, int y1, int x2, int y2, int color);
    }

    public void sanitize() {
        length = Math.clamp(length, 0, 20);
        width = Math.clamp(width, 1, 8);
        gap = Math.clamp(gap, 0, 20);
    }

    public void draw(Fill fill, int cx, int cy) {
        int l = Math.clamp(length, 0, 20);
        int w = Math.clamp(width, 1, 8);
        int g = Math.clamp(gap, 0, 20);
        if ((color >>> 24) == 0) return;
        int x = cx - w / 2, y = cy - w / 2;
        if (outline) drawParts(fill, x, y, l, w, g, 1, color & 0xFF000000);
        drawParts(fill, x, y, l, w, g, 0, color);
    }

    private void drawParts(Fill fill, int x, int y, int l, int w, int g, int border, int argb) {
        if (l > 0) {
            fill.fill(x - g - l - border, y - border, x - g + border, y + w + border, argb);
            fill.fill(x + w + g - border, y - border, x + w + g + l + border, y + w + border, argb);
            fill.fill(x - border, y - g - l - border, x + w + border, y - g + border, argb);
            fill.fill(x - border, y + w + g - border, x + w + border, y + w + g + l + border, argb);
        }
        if (dot) fill.fill(x - border, y - border, x + w + border, y + w + border, argb);
    }
}
