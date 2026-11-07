package dev.openintel.map;

import dev.openintel.xaero.RelicCatalog;

/**
 * How a marker looks on any fullscreen world map. Both map adapters draw from
 * this, so a marker kind cannot drift between JourneyMap and Xaero.
 */
public final class MapMarkerStyle {
    /** Background behind a selected library relic. */
    public static final int SELECTED_FILL = 0xA07CFF4A;
    /** Marker color of a selected library relic. */
    public static final int SELECTED_COLOR = 0xFF7CFF4A;
    public static final String LIBRARY_PREFIX = "relic:library:";

    public enum Shape {
        /** Relay player. */
        CIRCLE,
        /** Snitch hit or shared ping. */
        DIAMOND,
        /** War Table pin, place label, or sanctuary name: small diamond plus one colored line. */
        NAME_DIAMOND,
        /** Relic with a bundled texture. */
        RELIC,
        /** Relic map whose relic has no bundled texture. */
        CROSS
    }

    /**
     * @param relicTexture file under {@code assets/openintel/textures/relic/}, only for {@link Shape#RELIC}
     * @param relicSize    pixel size of that texture
     * @param caption      second line; empty for name markers
     * @param selected     selected library relic (green backing)
     */
    public record Style(Shape shape, String relicTexture, int relicSize,
                        String title, String caption, int argb, boolean selected) {
        /** One line for name markers, title then caption for the rest. */
        public String text(String separator) {
            if (shape == Shape.NAME_DIAMOND || caption.isEmpty()) return title;
            return title + separator + caption;
        }
    }

    private MapMarkerStyle() { }

    public static Style of(MapMarker marker, boolean selected) {
        String title = marker.title() == null ? "" : marker.title();
        String caption = marker.label() == null ? "" : marker.label();
        int argb = marker.argb() | 0xFF000000;
        if (WarTableMapMarkers.nameMarker(marker)) {
            return new Style(Shape.NAME_DIAMOND, null, 0, title, "", argb, false);
        }
        String id = marker.id();
        if (id.startsWith("relic:")) {
            boolean library = id.startsWith(LIBRARY_PREFIX);
            RelicCatalog.Relic relic = RelicCatalog.find(title);
            if (relic == null) {
                return new Style(Shape.CROSS, null, 0, title, caption, argb, library && selected);
            }
            return new Style(Shape.RELIC, relic.texture(), relic.size(), title, caption, argb, library && selected);
        }
        if (id.startsWith("player:")) return new Style(Shape.CIRCLE, null, 0, title, caption, argb, false);
        return new Style(Shape.DIAMOND, null, 0, title, caption, argb, false);
    }

    /** Library site key for a relic-library pin, or null for every other marker. */
    public static String librarySite(MapMarker marker) {
        String id = marker == null ? null : marker.id();
        if (id == null || !id.startsWith(LIBRARY_PREFIX)) return null;
        return id.substring(LIBRARY_PREFIX.length());
    }

    /**
     * Stable appearance key: block position, dimension, and everything the style
     * draws. A map that caches overlays rebuilds one whenever this changes.
     */
    public static String signature(MapMarker marker, Style style) {
        return style.shape() + "|" + style.relicTexture() + "|" + style.selected()
                + "|" + Integer.toHexString(style.argb())
                + "|" + style.title() + "|" + style.caption()
                + "|" + (long) Math.floor(marker.x()) + "," + (long) Math.floor(marker.y())
                + "," + (long) Math.floor(marker.z())
                + "|" + marker.dimension();
    }

    /**
     * The part of {@link #signature} that needs a new overlay: icon, color, and
     * dimension. While this holds, a moved marker or new text updates in place.
     */
    public static String iconSignature(MapMarker marker, Style style) {
        return style.shape() + "|" + style.relicTexture() + "|" + style.selected()
                + "|" + Integer.toHexString(style.argb()) + "|" + marker.dimension();
    }
}
