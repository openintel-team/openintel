package dev.openintel.xaero;

/**
 * Coordinate math for vanilla-style explorer maps. Civ+ relic maps use the same
 * X marker: either absolute world coordinates on the item, or a map-icon byte
 * relative to the map center.
 */
public final class RelicMapMath {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final int MARKER_Y = 64;
    public static final int COLOR = 0xFFE8B84A;

    /** Inclusive icon-space range for map decoration bytes (−128..127). */
    public static final double ICON_COORD_ABS_MAX = 128.0;

    private RelicMapMath() { }

    /** Vanilla clamps an icon to these bytes once it reaches the map edge. */
    public static boolean iconClampedToEdge(int mapByte) {
        return mapByte <= -128 || mapByte >= 127;
    }

    /**
     * Icon bytes are {@code round(local * 2)}, where {@code local} is blocks from
     * the map center divided by {@code 1 << scale}.
     */
    public static double worldFromIcon(double center, int scale, int mapByte) {
        return center + (mapByte / 2.0) * (1 << scale);
    }

    public static double centerFromPlayer(double playerWorld, int mapByte, int scale) {
        return playerWorld - (mapByte / 2.0) * (1 << scale);
    }

    /**
     * Centers that could produce {@code mapByte} for this player position.
     * Vanilla stores {@code (int)(local * 2 + 0.5)} with truncation toward zero,
     * so the bin is not symmetric around the byte.
     */
    static double[] centerRange(double playerWorld, int mapByte, int scale) {
        double span = 1 << scale;
        double vLo;
        double vHi;
        if (mapByte > 0) {
            vLo = mapByte;
            vHi = mapByte + 1.0;
        } else if (mapByte < 0) {
            vLo = mapByte - 1.0;
            vHi = mapByte;
        } else {
            vLo = -1.0;
            vHi = 1.0;
        }
        double low = playerWorld - ((vHi - 0.5) / 2.0) * span;
        double high = playerWorld - ((vLo - 0.5) / 2.0) * span;
        if (low > high) {
            double swap = low;
            low = high;
            high = swap;
        }
        // Float icon math and packet delay can sit a hair outside the ideal bin.
        double slop = span * 1.0e-4 + 1.0e-3;
        return new double[] {low - slop, high + slop};
    }

    /**
     * Map center learned from the player arrow. Samples inside one map pixel
     * narrow the possible center, but the published point stays put until the
     * arrow steps to the next pixel. Rewriting it from the live position on
     * every sample makes relic pins slide with the player.
     */
    public static final class CenterLock {
        private double minX, maxX, minZ, maxZ;
        private double x, z;
        private int iconX = Integer.MIN_VALUE;
        private int iconZ = Integer.MIN_VALUE;
        private boolean known;

        public void observe(double playerX, double playerZ, int iconX, int iconZ, int scale) {
            if (iconClampedToEdge(iconX) || iconClampedToEdge(iconZ) || !validScale(scale)) return;
            double[] rx = centerRange(playerX, iconX, scale);
            double[] rz = centerRange(playerZ, iconZ, scale);
            if (!known) {
                minX = rx[0];
                maxX = rx[1];
                minZ = rz[0];
                maxZ = rz[1];
                x = mid(minX, maxX);
                z = mid(minZ, maxZ);
                known = true;
                this.iconX = iconX;
                this.iconZ = iconZ;
                return;
            }
            boolean samePixel = iconX == this.iconX && iconZ == this.iconZ;
            boolean agreeX = overlaps(minX, maxX, rx);
            boolean agreeZ = overlaps(minZ, maxZ, rz);
            if (samePixel) {
                // Keep tightening, but do not move the published center. A fresh
                // sample that misses the locked bin is a stale icon byte.
                if (agreeX && agreeZ) {
                    minX = Math.max(minX, rx[0]);
                    maxX = Math.min(maxX, rx[1]);
                    minZ = Math.max(minZ, rz[0]);
                    maxZ = Math.min(maxZ, rz[1]);
                }
                return;
            }
            if (agreeX) {
                minX = Math.max(minX, rx[0]);
                maxX = Math.min(maxX, rx[1]);
            } else {
                minX = rx[0];
                maxX = rx[1];
            }
            if (agreeZ) {
                minZ = Math.max(minZ, rz[0]);
                maxZ = Math.min(maxZ, rz[1]);
            } else {
                minZ = rz[0];
                maxZ = rz[1];
            }
            x = mid(minX, maxX);
            z = mid(minZ, maxZ);
            this.iconX = iconX;
            this.iconZ = iconZ;
        }

        public boolean known() { return known; }

        public double x() { return x; }

        public double z() { return z; }

        private static double mid(double min, double max) { return (min + max) * 0.5; }

        private static boolean overlaps(double min, double max, double[] range) {
            return Math.max(min, range[0]) <= Math.min(max, range[1]);
        }
    }

    /**
     * Whether {@code MAP_DECORATIONS} x/z look like absolute world blocks rather
     * than the same icon-space range as map bytes (−128..127).
     */
    public static boolean looksLikeWorldCoords(double x, double z) {
        return Math.abs(x) > ICON_COORD_ABS_MAX || Math.abs(z) > ICON_COORD_ABS_MAX;
    }

    /**
     * Whether component decoration coordinates may be treated as world X/Z when
     * the saved-map center conversion did not produce a position. In-range values
     * are rejected while a map id is present (center may still be unknown).
     */
    public static boolean useComponentAsWorld(double x, double z, boolean hasMapId) {
        return !hasMapId || looksLikeWorldCoords(x, z);
    }

    public static boolean validScale(int scale) {
        return scale >= 0 && scale <= 4;
    }

    /** Player arrow while it still sits on the map, not the edge clamp. */
    public static boolean isInteriorPlayerIcon(String path, int mapX, int mapZ) {
        return "player".equals(path) && !iconClampedToEdge(mapX) && !iconClampedToEdge(mapZ);
    }

    /**
     * Destination icons. Those are item-frame marks that do not track
     * a count. The red X and target marks are also matched by path, and
     * custom-namespace icons are included because Civ+ can register its own.
     */
    public static boolean isRelicIcon(String namespace, String path, boolean explorationElement) {
        if (path == null || path.isEmpty() || isPlayerBannerOrFrame(path)) return false;
        if (explorationElement) return true;
        if ("red_x".equals(path) || "target_x".equals(path) || "target_point".equals(path)) return true;
        return namespace != null && !namespace.isEmpty() && !"minecraft".equals(namespace);
    }

    private static boolean isPlayerBannerOrFrame(String path) {
        return "player".equals(path) || "player_off_map".equals(path) || "player_off_limits".equals(path)
                || "frame".equals(path) || path.startsWith("banner_");
    }
}
