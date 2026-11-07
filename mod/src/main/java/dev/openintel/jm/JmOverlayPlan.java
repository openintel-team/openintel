package dev.openintel.jm;

import dev.openintel.map.MapMarker;
import dev.openintel.map.MapMarkerStyle;
import dev.openintel.xaero.RelicMapMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The MarkerOverlays {@link JmBridge} keeps for a list of markers. Has no
 * JourneyMap types, so it runs headless in {@code mapParityTest}.
 *
 * <p>JourneyMap 6.0.0 for 1.21.11 never drops a removed overlay from the
 * fullscreen map's hovered list, and gives each click to the first hovered
 * overlay's listener. Replacing a library pin whenever its selection changed
 * left the old pin's listener at the front of that list, so later clicks
 * toggled that old site instead of the one under the mouse. Each library pin
 * therefore keeps one overlay per selection state for the whole visit: the
 * keys and signatures here do not depend on the selection, and a selection
 * change only flips which of the two is visible.
 */
public final class JmOverlayPlan {
    static final String UNSELECTED = "#unselected";
    static final String SELECTED = "#selected";

    /**
     * One overlay to keep shown.
     *
     * @param key     stable overlay key
     * @param sig     update key; a change moves or retitles the overlay
     * @param iconSig rebuild key; the overlay is recreated only when this changes
     * @param visible drawn now; a hidden overlay stays registered with JourneyMap
     * @param site    library site for a pin overlay, otherwise null
     */
    public record Entry(String key, MapMarker marker, MapMarkerStyle.Style style, String sig,
                        String iconSig, boolean visible, String site) { }

    private JmOverlayPlan() { }

    public static List<Entry> plan(List<MapMarker> markers, Set<String> selectedSites) {
        List<Entry> entries = new ArrayList<>(markers.size());
        for (MapMarker marker : markers) {
            String site = MapMarkerStyle.librarySite(marker);
            if (site == null) {
                MapMarkerStyle.Style style = MapMarkerStyle.of(marker, false);
                entries.add(new Entry(marker.id(), marker, style, MapMarkerStyle.signature(marker, style),
                        MapMarkerStyle.iconSignature(marker, style), true, null));
                continue;
            }
            boolean selected = selectedSites.contains(site);
            entries.add(variant(marker, site, false, !selected));
            entries.add(variant(marker, site, true, selected));
        }
        return entries;
    }

    private static Entry variant(MapMarker marker, String site, boolean selected, boolean visible) {
        // The source colors a pin by the live selection; each variant uses its own
        // color instead, so a selection change cannot alter either signature.
        MapMarker colored = new MapMarker(marker.id(), marker.x(), marker.y(), marker.z(), marker.dimension(),
                selected ? MapMarkerStyle.SELECTED_COLOR : RelicMapMath.COLOR,
                marker.title(), marker.label(), marker.expiresAt());
        MapMarkerStyle.Style style = MapMarkerStyle.of(colored, selected);
        return new Entry(marker.id() + (selected ? SELECTED : UNSELECTED), colored, style,
                MapMarkerStyle.signature(colored, style), MapMarkerStyle.iconSignature(colored, style), visible, site);
    }
}
