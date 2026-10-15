package dev.openintel.xaero;

import dev.openintel.wartable.Sanctuaries;
import dev.openintel.wartable.Stroke;

import java.util.List;

/** War Table pins, place labels, and sanctuary names as transient world-map markers. */
final class WarTableMapMarkers {
    /** Gold used for sanctuary disks on the War Table. Names on the map use the same color. */
    static final int SANCTUARY_COLOR = 0xFFB8860B;

    private WarTableMapMarkers() { }

    /**
     * One colored name per pin, place label, or sanctuary. The map draws a
     * small diamond in that color with the name underneath, matching the
     * earlier world-map markers.
     */
    static void add(List<MapMarker> result, Iterable<Stroke> strokes,
                    Iterable<Sanctuaries.Sanctuary> sanctuaries,
                    boolean pins, boolean labels) {
        if (strokes != null) {
            for (Stroke s : strokes) {
                if (s == null || s.points == null || s.points.length < 2) continue;
                if (!Double.isFinite(s.points[0]) || !Double.isFinite(s.points[1])) continue;
                if (s.tool != Stroke.Tool.MARKER && s.tool != Stroke.Tool.LABEL) continue;
                boolean label = s.tool == Stroke.Tool.LABEL;
                if (label ? !labels : !pins) continue;
                if (label && (s.label == null || s.label.isBlank())) continue;
                String name = s.label != null && !s.label.isBlank() ? s.label
                        : (s.author != null ? s.author + "'s pin" : "pin");
                result.add(new MapMarker(
                        (label ? "wartable-label:" : "wartable:") + s.id,
                        s.points[0] + 0.5, RelicMapMath.MARKER_Y, s.points[1] + 0.5,
                        RelicMapMath.OVERWORLD, s.color | 0xFF000000,
                        name, "", Long.MAX_VALUE));
            }
        }
        if (sanctuaries == null) return;
        for (Sanctuaries.Sanctuary s : sanctuaries) {
            if (s == null || !s.overworld() || s.name() == null || s.name().isBlank()) continue;
            result.add(new MapMarker(
                    "sanctuary:" + s.name() + "@" + Math.round(s.x()) + "," + Math.round(s.z()),
                    s.x(), RelicMapMath.MARKER_Y, s.z(),
                    RelicMapMath.OVERWORLD, SANCTUARY_COLOR,
                    s.name(), "", Long.MAX_VALUE));
        }
    }

    /** Pin, place label, or sanctuary name: small diamond plus one colored line. */
    static boolean nameMarker(MapMarker marker) {
        String id = marker.id();
        return id.startsWith("wartable:") || id.startsWith("wartable-label:")
                || id.startsWith("sanctuary:");
    }
}
