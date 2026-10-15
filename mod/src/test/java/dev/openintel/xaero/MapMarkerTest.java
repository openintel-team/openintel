package dev.openintel.xaero;

import dev.openintel.wartable.Sanctuaries;
import dev.openintel.wartable.Stroke;

import java.util.ArrayList;
import java.util.List;

/** Standalone assertions, requiring neither a Minecraft client nor Xaero. */
public final class MapMarkerTest {
    public static void main(String[] args) {
        MapMarker marker = new MapMarker("test", -24, 65, 120, "minecraft:overworld",
                0xFF123456, "Snitch: Vault", "Player (via Reporter)", 2000);
        assert marker.visibleIn("minecraft:overworld", 1999);
        assert marker.visibleIn("minecraft:overworld", 2000);
        assert !marker.visibleIn("minecraft:overworld", 2001);
        assert !marker.visibleIn("minecraft:the_nether", 1000);
        assert !marker.visibleIn(null, 1000);
        assert !new MapMarker("unknown", 0, 0, 0, "openintel:unknown", 0, "", "", 2000)
                .visibleIn("openintel:unknown", 1000);
        assert !new MapMarker("bad", Double.NaN, 0, 0, "minecraft:overworld", 0, "", "", 2000)
                .visibleIn("minecraft:overworld", 1000);
        assert !new MapMarker("bad", 0, 0, Double.POSITIVE_INFINITY, "minecraft:overworld", 0, "", "", 2000)
                .visibleIn("minecraft:overworld", 1000);
        warTableNames();
        System.out.println("MapMarkerTest passed");
    }

    private static void warTableNames() {
        Stroke pin = new Stroke("pin", Stroke.Tool.MARKER, 0x80FFAA00, 4f,
                new float[]{10.2f, -4.4f}, "Vault", "ada", 0);
        Stroke place = new Stroke("city", Stroke.Tool.LABEL, 0x5533CCFF, 4f,
                new float[]{100f, 200f}, "Mt Augusta", "ada", 0);
        Stroke blank = new Stroke("blank", Stroke.Tool.LABEL, 0xFFFFFFFF, 4f,
                new float[]{1f, 1f}, "  ", null, 0);
        Stroke line = new Stroke("road", Stroke.Tool.LINE, 0xFFFF0000, 4f,
                new float[]{0f, 0f, 5f, 5f}, null, null, 0);
        List<MapMarker> markers = new ArrayList<>();
        List<Stroke> strokes = List.of(pin, place, blank, line);
        List<Sanctuaries.Sanctuary> sanctuaries = List.of(
                new Sanctuaries.Sanctuary("Haven", 50.2, -80.6, 48.4, "world"),
                new Sanctuaries.Sanctuary("End Haven", 1, 2, 10, "world_the_end"));
        WarTableMapMarkers.add(markers, strokes, sanctuaries, true, true);

        assert markers.size() == 3 : markers.size();
        MapMarker drawnPin = find(markers, "wartable:pin");
        assert drawnPin.argb() == 0xFFFFAA00 : Integer.toHexString(drawnPin.argb());
        assert drawnPin.title().equals("Vault");
        assert drawnPin.label().isEmpty();
        assert WarTableMapMarkers.nameMarker(drawnPin);

        MapMarker drawnPlace = find(markers, "wartable-label:city");
        assert drawnPlace.argb() == 0xFF33CCFF : Integer.toHexString(drawnPlace.argb());
        assert drawnPlace.title().equals("Mt Augusta");
        assert drawnPlace.label().isEmpty();
        assert drawnPlace.x() == 100.5 && drawnPlace.z() == 200.5;
        assert WarTableMapMarkers.nameMarker(drawnPlace);

        MapMarker sanctuary = find(markers, "sanctuary:Haven@50,-81");
        assert sanctuary.argb() == WarTableMapMarkers.SANCTUARY_COLOR;
        assert sanctuary.title().equals("Haven");
        assert sanctuary.label().isEmpty();
        assert sanctuary.x() == 50.2 && sanctuary.z() == -80.6;
        assert WarTableMapMarkers.nameMarker(sanctuary);

        List<MapMarker> pinsOnly = new ArrayList<>();
        WarTableMapMarkers.add(pinsOnly, strokes, List.of(), true, false);
        assert pinsOnly.size() == 1 && pinsOnly.get(0).id().equals("wartable:pin");

        List<MapMarker> labelsOnly = new ArrayList<>();
        WarTableMapMarkers.add(labelsOnly, strokes, List.of(), false, true);
        assert labelsOnly.size() == 1 && labelsOnly.get(0).id().equals("wartable-label:city");

        List<MapMarker> namesOnly = new ArrayList<>();
        WarTableMapMarkers.add(namesOnly, strokes, sanctuaries, false, false);
        assert namesOnly.size() == 1 && namesOnly.get(0).title().equals("Haven");
    }

    private static MapMarker find(List<MapMarker> markers, String id) {
        for (MapMarker marker : markers) {
            if (marker.id().equals(id)) return marker;
        }
        throw new AssertionError("missing " + id);
    }
}
