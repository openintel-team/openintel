package dev.openintel.xaero;

/** Immutable, transient map data. No Xaero waypoints or map files are modified. */
public record MapMarker(String id, double x, double y, double z, String dimension,
                        int argb, String title, String label, long expiresAt) {
    public boolean visibleIn(String viewedDimension, long now) {
        return dimension != null && !dimension.equals("openintel:unknown")
                && dimension.equals(viewedDimension) && now <= expiresAt
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
