package dev.openintel.map;

/** Immutable, transient map data. No map-mod waypoints or map files are modified. */
public record MapMarker(String id, double x, double y, double z, String dimension,
                        int argb, String title, String label, long expiresAt) {
    /** Known dimension, finite position, and not yet expired. */
    public boolean drawable(long now) {
        return dimension != null && !dimension.equals("openintel:unknown") && now <= expiresAt
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public boolean visibleIn(String viewedDimension, long now) {
        return drawable(now) && dimension.equals(viewedDimension);
    }
}
