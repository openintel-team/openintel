package dev.openintel.relic;

/**
 * One treasure-map copy remembered from a chest. Coordinates are optional until
 * the client has learned that map's world position.
 */
public record RelicLibraryEntry(
        String id,
        String title,
        String enchantment,
        Integer siteX,
        Integer siteZ,
        String memoryKey,
        int chestX,
        int chestY,
        int chestZ
) {
    public boolean located() {
        return siteX != null && siteZ != null;
    }

    public String siteKey() {
        if (!located()) return null;
        return siteX + "," + siteZ;
    }

    public double distanceSq(double fromX, double fromZ) {
        if (!located()) return Double.POSITIVE_INFINITY;
        double dx = siteX - fromX;
        double dz = siteZ - fromZ;
        return dx * dx + dz * dz;
    }
}
