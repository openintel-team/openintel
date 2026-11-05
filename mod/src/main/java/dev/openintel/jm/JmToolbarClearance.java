package dev.openintel.jm;

import dev.openintel.relic.RelicLibraryModel;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.LoggerFactory;

import java.awt.geom.Rectangle2D;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Where the relic carry hint goes on JourneyMap's fullscreen map so the top
 * toolbar and the player-location line under it do not cover it.
 *
 * <p>JourneyMap's API has no toolbar bounds, so this reads the same internals its
 * own status line uses: {@code Fullscreen.getOptionsToolbarBounds()} (physical
 * pixels), {@code Fullscreen.isButtonsVisable()}, and the fullscreen
 * {@code showPlayerLoc} / {@code getFontScale()} properties. The status line sits
 * one theme margin below the toolbar (the same margin above the toolbar) and is
 * {@value #STATUS_LINE_PX} physical pixels tall at font scale 1. These targets are
 * checked against an installed jar by {@code mapFontTest -PjourneyMapJar=...}.
 * When they are missing the hint drops by {@value #FALLBACK_COVERED_GUI} GUI units,
 * which clears the bundled themes' toolbar and status line.
 */
final class JmToolbarClearance {
    static final String FULLSCREEN = JmLibraryMap.FULLSCREEN;
    static final String CLIENT = "journeymap.client.JourneymapClient";
    /** {@code DrawUtil.getLabelHeight}: 9-pixel line plus up to 6 pixels of padding. */
    static final int STATUS_LINE_PX = 15;
    /** Bundled themes: 10 margin + 24 toolbar + 10 margin + status line, in GUI units. */
    static final int FALLBACK_COVERED_GUI = 60;

    private static boolean looked;
    private static Method toolbarBounds;
    private static Method buttonsVisible;
    private static Method clientInstance;
    private static Method fullMapProperties;
    private static Field showPlayerLoc;
    private static Method fontScale;

    private JmToolbarClearance() { }

    /** GUI y for the carry hint text on this fullscreen map. */
    static int hintTop(IFullscreen fullscreen) {
        Screen screen = fullscreen.getScreen();
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return RelicLibraryModel.carryHintTop(coveredBottomPx(screen, guiScale), guiScale, screen.height);
    }

    /** Lowest physical-pixel row of JourneyMap's top toolbar plus its status line. */
    static double coveredBottomPx(Screen screen, double guiScale) {
        double fallback = FALLBACK_COVERED_GUI * guiScale;
        lookup();
        if (toolbarBounds == null) return fallback;
        try {
            if (buttonsVisible != null && !(Boolean) buttonsVisible.invoke(screen)) return 0;
            if (!(toolbarBounds.invoke(screen) instanceof Rectangle2D bounds) || bounds.isEmpty()) return fallback;
            double bottom = bounds.getMaxY();
            Object properties = fullMapProperties();
            if (statusShown(properties)) {
                bottom += Math.max(0, bounds.getMinY()) + STATUS_LINE_PX * statusFontScale(properties, guiScale);
            }
            return bottom;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // The toolbar is built on JourneyMap's first layout pass, after the first render event.
            return fallback;
        }
    }

    private static Object fullMapProperties() {
        if (clientInstance == null || fullMapProperties == null) return null;
        try {
            return fullMapProperties.invoke(clientInstance.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static boolean statusShown(Object properties) {
        if (properties == null || showPlayerLoc == null) return true;
        try {
            Object field = showPlayerLoc.get(properties);
            Object value = field.getClass().getMethod("get").invoke(field);
            return !(value instanceof Boolean shown) || shown;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return true;
        }
    }

    /** JourneyMap's label scale; without it, assume the line is as tall as GUI text. */
    private static double statusFontScale(Object properties, double guiScale) {
        if (properties != null && fontScale != null) {
            try {
                if (fontScale.invoke(properties) instanceof Float scale && scale > 0 && Float.isFinite(scale)) return scale;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through
            }
        }
        return Math.max(1, guiScale);
    }

    private static void lookup() {
        if (looked) return;
        looked = true;
        try {
            Class<?> fullscreen = Class.forName(FULLSCREEN);
            toolbarBounds = fullscreen.getMethod("getOptionsToolbarBounds");
            buttonsVisible = fullscreen.getMethod("isButtonsVisable");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            toolbarBounds = null;
            buttonsVisible = null;
            LoggerFactory.getLogger("openintel").warn(
                    "JourneyMap toolbar bounds unavailable; relic carry hint uses a fixed offset", error);
            return;
        }
        try {
            Class<?> client = Class.forName(CLIENT);
            clientInstance = client.getMethod("getInstance");
            fullMapProperties = client.getMethod("getFullMapProperties");
            Class<?> properties = fullMapProperties.getReturnType();
            showPlayerLoc = properties.getField("showPlayerLoc");
            fontScale = properties.getMethod("getFontScale");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            clientInstance = null;
            fullMapProperties = null;
            showPlayerLoc = null;
            fontScale = null;
        }
    }
}
