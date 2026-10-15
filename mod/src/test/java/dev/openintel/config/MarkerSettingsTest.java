package dev.openintel.config;

/**
 * Marker opacity is a 0–100 percent. 59 is the nearest percent to the old
 * 151/255 alpha floor, and that percent is the default. Marker scale defaults
 * to 60%. Saved configs that still store the old 151–255 alpha convert on load.
 */
public final class MarkerSettingsTest {
    public static void main(String[] args) {
        defaults();
        percentRange();
        legacyAlpha();
        savedScaleSurvives();
        System.out.println("MarkerSettingsTest passed");
    }

    private static void defaults() {
        OIConfig fresh = new OIConfig();
        assert fresh.relayOpacity == 59 : fresh.relayOpacity;
        assert fresh.markerScale == 0.6f : fresh.markerScale;
        assert OIConfig.markerAlpha(fresh.relayOpacity) == 150 : OIConfig.markerAlpha(fresh.relayOpacity);
    }

    private static void percentRange() {
        assert OIConfig.markerOpacityPercent(0) == 0;
        assert OIConfig.markerOpacityPercent(59) == 59;
        assert OIConfig.markerOpacityPercent(100) == 100;
        assert OIConfig.markerOpacityPercent(-4) == 0;
        assert OIConfig.markerAlpha(0) == 0;
        assert OIConfig.markerAlpha(100) == 255;
        assert OIConfig.markerAlpha(59) == 150;
    }

    private static void legacyAlpha() {
        assert OIConfig.markerOpacityPercent(151) == 59 : OIConfig.markerOpacityPercent(151);
        assert OIConfig.markerOpacityPercent(230) == 90 : OIConfig.markerOpacityPercent(230);
        assert OIConfig.markerOpacityPercent(255) == 100;

        OIConfig loaded = new OIConfig();
        loaded.relayOpacity = 151;
        loaded.markerScale = 1.0f;
        OIConfig.applyMarkerDefaults(loaded, true, true);
        assert loaded.relayOpacity == 59 : loaded.relayOpacity;
        assert loaded.markerScale == 1.0f : loaded.markerScale;

        OIConfig missing = new OIConfig();
        missing.relayOpacity = 0;
        missing.markerScale = 0f;
        OIConfig.applyMarkerDefaults(missing, false, false);
        assert missing.relayOpacity == 59 : missing.relayOpacity;
        assert missing.markerScale == 0.6f : missing.markerScale;
    }

    private static void savedScaleSurvives() {
        OIConfig saved = new OIConfig();
        saved.markerScale = 0.6f;
        saved.relayOpacity = 40;
        OIConfig.applyMarkerDefaults(saved, true, true);
        assert saved.markerScale == 0.6f : saved.markerScale;
        assert saved.relayOpacity == 40 : saved.relayOpacity;
    }
}
