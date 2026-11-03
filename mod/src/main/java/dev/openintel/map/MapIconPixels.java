package dev.openintel.map;

/**
 * Marker icons as ARGB pixel grids, matching the shapes Xaero draws with
 * fills. Maps that need a baked image (JourneyMap) build theirs from these.
 * White areas take the marker color through the map's tint; dark outlines
 * and backings stay dark under any tint.
 */
public final class MapIconPixels {
    public static final int OUTLINE = 0xFF202020;
    public static final int INK = 0xFFFFFFFF;
    public static final int NAME_BACKING = 0xA0101014;
    public static final int RELIC_BACKING = 0xC0101014;
    /** Side of the relic icon: an 18 px backing inside room for the 22 px selected highlight. */
    public static final int RELIC_SIZE = 22;

    /** Row-major ARGB pixels; the marker point is the image center. */
    public record Icon(int width, int height, int[] argb) {
        public int at(int x, int y) {
            return argb[y * width + x];
        }
    }

    private MapIconPixels() { }

    public static Icon of(MapMarkerStyle.Shape shape) {
        return switch (shape) {
            case CIRCLE -> circle();
            case DIAMOND -> diamond();
            case NAME_DIAMOND -> nameDiamond();
            case CROSS -> cross();
            case RELIC -> relic(null, 0, false);
        };
    }

    /** Relay player: dark ring of radius 5 around a filled radius-3 disk. */
    public static Icon circle() {
        Canvas c = new Canvas(11, 11, 5, 5);
        for (int y = -5; y <= 5; y++) {
            int outer = circleHalfWidth(5, y);
            int inner = circleHalfWidth(3, y);
            if (outer >= 0) c.row(-outer, outer, y, OUTLINE);
            if (inner >= 0) c.row(-inner, inner, y, INK);
        }
        return c.icon();
    }

    /** Snitch hit or ping: outlined diamond, 11 px tall. */
    public static Icon diamond() {
        Canvas c = new Canvas(13, 11, 6, 5);
        for (int y = -5; y <= 5; y++) {
            int width = 5 - Math.abs(y);
            c.row(-width - 1, width + 1, y, OUTLINE);
            if (width > 0) c.row(-width, width, y, INK);
        }
        return c.icon();
    }

    /** War Table pin, place label, or sanctuary name: 9 px dark diamond around a 7 px colored one. */
    public static Icon nameDiamond() {
        Canvas c = new Canvas(9, 9, 4, 4);
        for (int dy = -4; dy <= 4; dy++) {
            int hw = 4 - Math.abs(dy);
            c.row(-hw, hw, dy, NAME_BACKING);
        }
        for (int dy = -3; dy <= 3; dy++) {
            int hw = 3 - Math.abs(dy);
            c.row(-hw, hw, dy, INK);
        }
        return c.icon();
    }

    /** Relic map without a bundled texture: outlined X. */
    public static Icon cross() {
        Canvas c = new Canvas(13, 13, 6, 6);
        for (int i = -5; i <= 5; i++) {
            c.rect(i - 1, i - 1, i + 1, i + 1, OUTLINE);
            c.rect(-i - 1, i - 1, -i + 1, i + 1, OUTLINE);
        }
        for (int i = -4; i <= 4; i++) {
            c.set(i, i, INK);
            c.set(-i, i, INK);
        }
        return c.icon();
    }

    /**
     * Relic texture on its dark 18 px backing, scaled to 16 px. A selected
     * library pin gets the green 22 px highlight behind it. {@code texture} is
     * a square ARGB grid of side {@code size}; null leaves only the backing.
     */
    public static Icon relic(int[] texture, int size, boolean selected) {
        Canvas c = new Canvas(RELIC_SIZE, RELIC_SIZE, 11, 11);
        if (selected) c.rect(-11, -11, 10, 10, MapMarkerStyle.SELECTED_FILL);
        c.blend(-9, -9, 8, 8, RELIC_BACKING);
        if (texture != null && size > 0 && texture.length >= size * size) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    c.blendAt(x - 8, y - 8, sample(texture, size, x, y));
                }
            }
        }
        return c.icon();
    }

    /** Box filter from a size x size texture down to one of 16 x 16 output pixels. */
    static int sample(int[] texture, int size, int x, int y) {
        int x0 = x * size / 16;
        int x1 = Math.max(x0 + 1, (x + 1) * size / 16);
        int y0 = y * size / 16;
        int y1 = Math.max(y0 + 1, (y + 1) * size / 16);
        long a = 0, r = 0, g = 0, b = 0;
        int n = 0;
        for (int sy = y0; sy < y1; sy++) {
            for (int sx = x0; sx < x1; sx++) {
                int p = texture[sy * size + sx];
                int pa = p >>> 24;
                a += pa;
                r += (long) ((p >> 16) & 0xFF) * pa;
                g += (long) ((p >> 8) & 0xFF) * pa;
                b += (long) (p & 0xFF) * pa;
                n++;
            }
        }
        if (a == 0) return 0;
        int outA = (int) (a / n);
        return outA << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
    }

    private static int circleHalfWidth(int radius, int y) {
        int span = radius * radius - y * y;
        if (span < 0) return -1;
        return (int) Math.round(Math.sqrt(span));
    }

    /** Pixel grid addressed relative to its center point. */
    private static final class Canvas {
        final int width;
        final int height;
        final int cx;
        final int cy;
        final int[] px;

        Canvas(int width, int height, int cx, int cy) {
            this.width = width;
            this.height = height;
            this.cx = cx;
            this.cy = cy;
            this.px = new int[width * height];
        }

        void set(int x, int y, int argb) {
            int ix = x + cx;
            int iy = y + cy;
            if (ix < 0 || iy < 0 || ix >= width || iy >= height) return;
            px[iy * width + ix] = argb;
        }

        /** Inclusive span on one row. */
        void row(int fromX, int toX, int y, int argb) {
            for (int x = fromX; x <= toX; x++) set(x, y, argb);
        }

        /** Inclusive rectangle. */
        void rect(int x0, int y0, int x1, int y1, int argb) {
            for (int y = y0; y <= y1; y++) row(x0, x1, y, argb);
        }

        /** Inclusive rectangle composited over what is there. */
        void blend(int x0, int y0, int x1, int y1, int argb) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) blendAt(x, y, argb);
            }
        }

        void blendAt(int x, int y, int src) {
            int ix = x + cx;
            int iy = y + cy;
            if (ix < 0 || iy < 0 || ix >= width || iy >= height) return;
            int i = iy * width + ix;
            px[i] = over(src, px[i]);
        }

        Icon icon() {
            return new Icon(width, height, px);
        }
    }

    /** Straight-alpha "source over destination". */
    static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 0xFF) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int r = channel(src >> 16, sa, dst >> 16, da, outA);
        int g = channel(src >> 8, sa, dst >> 8, da, outA);
        int b = channel(src, sa, dst, da, outA);
        return outA << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(int s, int sa, int d, int da, int outA) {
        s &= 0xFF;
        d &= 0xFF;
        return Math.min(255, (s * sa + d * da * (255 - sa) / 255) / outA);
    }
}
