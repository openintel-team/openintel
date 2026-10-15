package dev.openintel.xaero;

/**
 * When a carried relic map is close enough to point at. Height is unknown
 * (maps only store X and Z), so the check is horizontal.
 */
public final class RelicProximity {
    private RelicProximity() { }

    public static boolean near(double playerX, double playerZ, double relicX, double relicZ, double range) {
        double dx = playerX - relicX;
        double dz = playerZ - relicZ;
        return dx * dx + dz * dz <= range * range;
    }

    /** Relic name, plus enchantment or coordinates when the map has one. */
    public static String label(String title, String subtitle) {
        if (subtitle == null || subtitle.isBlank()) return title;
        return title + " | " + subtitle;
    }
}
