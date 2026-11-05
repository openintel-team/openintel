package dev.openintel.jm;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.relic.LibraryMapInput;
import dev.openintel.relic.LibraryMapOverlay;
import dev.openintel.relic.RelicCarryHint;
import dev.openintel.relic.RelicLibraryVisit;
import journeymap.api.v2.client.display.IOverlayListener;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import journeymap.api.v2.client.fullscreen.ModPopupMenu;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import org.slf4j.LoggerFactory;

import java.awt.geom.Point2D;
import java.util.function.BooleanSupplier;

/**
 * Relic-library map visit on JourneyMap's fullscreen map, with the same
 * selection rules as Xaero ({@link LibraryMapInput}).
 *
 * <p>JourneyMap's API cannot open its fullscreen map, so {@link #open} calls
 * {@code journeymap.client.ui.UIManager.INSTANCE.open(Fullscreen.class)}
 * reflectively. That internal target is checked against the installed jar by
 * {@code mapFontTest -PjourneyMapJar=...}. If the lookup fails the library
 * reports that the map could not open and logs one warning.
 *
 * <p>JourneyMap fires a PRE drag event only while it is not panning, and
 * fires no release event for a drag it never started. A cancelled left press
 * (a pin click or a shift lasso) therefore keeps receiving drag events, and
 * the release is seen in the render event as the left button going up.
 */
public final class JmLibraryMap {
    static final String UI_MANAGER = "journeymap.client.ui.UIManager";
    static final String FULLSCREEN = "journeymap.client.ui.fullscreen.Fullscreen";

    private static final LibraryMapInput input = new LibraryMapInput();
    private static boolean subscribed;
    private static boolean warned;
    private static boolean visitOpen;
    private static boolean pendingCenter;
    private static double focusX;
    private static double focusZ;
    private static Screen returnTo;
    private static String hoveredSite;
    private static int lastWorldX;
    private static int lastWorldZ;
    private static boolean dirty;

    private JmLibraryMap() { }

    /** Called once from the JourneyMap plugin entrypoint. */
    static void initialize() {
        if (subscribed) return;
        FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe("openintel", JmLibraryMap::onClick);
        FullscreenEventRegistry.FULLSCREEN_MAP_DRAG_EVENT.subscribe("openintel", JmLibraryMap::onDrag);
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe("openintel", JmLibraryMap::onRender);
        subscribed = true;
    }

    /** Opens JourneyMap's fullscreen map centered on the focus and starts the visit. */
    public static boolean open(Screen parent, double x, double z) {
        Minecraft client = Minecraft.getInstance();
        if (!subscribed || client == null || client.player == null) return false;
        Object screen;
        try {
            Class<?> manager = Class.forName(UI_MANAGER);
            Class<?> fullscreen = Class.forName(FULLSCREEN);
            Object instance = manager.getField("INSTANCE").get(null);
            screen = manager.getMethod("open", Class.class).invoke(instance, fullscreen);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            warn("JourneyMap fullscreen map could not be opened for the relic library", error);
            return false;
        }
        if (!(screen instanceof IFullscreen)) {
            warn("JourneyMap did not open its fullscreen map for the relic library", null);
            return false;
        }
        // Mark the visit after the screen switch so the library's own close cannot clear it.
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit != null) visit.beginMapVisit();
        visitOpen = true;
        returnTo = parent;
        focusX = x;
        focusZ = z;
        pendingCenter = true;
        hoveredSite = null;
        input.reset();
        dirty = true;
        return true;
    }

    /** Client tick: center once after opening, end the visit once the map closes. */
    static void tick() {
        if (!visitOpen) return;
        try {
            tickVisit();
        } catch (Throwable error) {
            visitOpen = false;
            returnTo = null;
            RelicLibraryVisit visit = RelicLibraryVisit.current();
            if (visit != null) visit.endMapVisit();
            fail(error);
        }
    }

    private static void tickVisit() {
        Minecraft client = Minecraft.getInstance();
        Screen screen = client.screen;
        if (screen instanceof IFullscreen fullscreen && client.level != null) {
            if (pendingCenter) {
                pendingCenter = false;
                zoomToBlocks(fullscreen);
                fullscreen.centerOn(focusX, focusZ);
            }
            return;
        }
        visitOpen = false;
        pendingCenter = false;
        hoveredSite = null;
        input.reset();
        dirty = true;
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit != null) visit.endMapVisit();
        Screen back = returnTo;
        returnTo = null;
        // Closing the map returns to the library, the way Xaero's map does.
        if (screen == null && back != null && client.level != null) client.setScreen(back);
    }

    /** True once after a selection or visit change, so markers refresh on the next tick. */
    static boolean consumeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    /**
     * Hover and "Show chests" for one library pin; left clicks are taken in
     * {@link #onClick} from {@link #hoveredSite}. JourneyMap 6.0.0 keeps
     * sending events to a removed overlay's listener, ahead of the overlay now
     * under the mouse; once {@code live} is false this listener ignores them.
     */
    static IOverlayListener pinListener(String site, BooleanSupplier live) {
        return new IOverlayListener() {
            @Override
            public void onMouseMove(UIState state, Point2D.Double mouse, BlockPos block) {
                if (active() && live.getAsBoolean()) hoveredSite = site;
            }

            @Override
            public void onMouseOut(UIState state, Point2D.Double mouse, BlockPos block) {
                if (live.getAsBoolean() && site.equals(hoveredSite)) hoveredSite = null;
            }

            @Override
            public boolean onMouseClick(UIState state, Point2D.Double mouse, BlockPos block, int button, boolean doubleClick) {
                // Left clicks on a pin are taken in onClick from hoveredSite. One that
                // reaches here missed every pin, so it must not toggle this one.
                return true;
            }

            @Override
            public void onOverlayMenuPopup(UIState state, Point2D.Double mouse, BlockPos block, ModPopupMenu menu) {
                if (!active() || !live.getAsBoolean() || !site.equals(hoveredSite)) return;
                menu.addMenuItem(I18n.get("openintel.relic.library.show_chests"), pos -> {
                    // Where Is It closes the map to show the highlights; stay in the world.
                    returnTo = null;
                    LibraryMapInput.showChests(RelicLibraryVisit.current(), site);
                });
            }
        };
    }

    private static boolean active() {
        return visitOpen && RelicLibraryVisit.mapVisitActive();
    }

    /** Left press on a pin (plain or shift), or a shift lasso, from the hovered pin OpenIntel tracks. */
    private static void onClick(FullscreenMapEvent.ClickEvent event) {
        try {
            if (!active() || event.getStage() != FullscreenMapEvent.Stage.PRE) return;
            if (event.getButton() != InputConstants.MOUSE_BUTTON_LEFT) return;
            Minecraft client = Minecraft.getInstance();
            BlockPos at = event.getLocation();
            if (at == null) return;
            lastWorldX = at.getX();
            lastWorldZ = at.getZ();
            // JourneyMap 6.0.0 hands overlay clicks to stale hover entries, so plain
            // pin clicks use hoveredSite too and never reach its overlay layer.
            if (input.press(RelicLibraryVisit.current(), client.hasShiftDown(), hoveredSite, lastWorldX, lastWorldZ,
                    guiMouseX(client), guiMouseY(client))) {
                event.cancel();   // keeps JourneyMap from starting a pan
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void onDrag(FullscreenMapEvent.MouseDraggedEvent event) {
        try {
            if (!active() || !input.selecting()) return;
            Minecraft client = Minecraft.getInstance();
            BlockPos at = event.getLocation();
            if (at != null) {
                lastWorldX = at.getX();
                lastWorldZ = at.getZ();
            }
            if (event.getStage() == FullscreenMapEvent.Stage.PRE) {
                input.drag(RelicLibraryVisit.current(), lastWorldX, lastWorldZ, guiMouseX(client), guiMouseY(client));
                event.cancel();   // no pan while the lasso is open
            } else {
                finish(client, guiMouseX(client), guiMouseY(client));
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void onRender(FullscreenRenderEvent event) {
        try {
            if (!active()) return;
            Minecraft client = Minecraft.getInstance();
            if (input.selecting() && !client.mouseHandler.isLeftPressed()) {
                finish(client, event.getMouseX(), event.getMouseY());
            }
            LibraryMapOverlay.draw(event.getGraphics(), event.getFullscreen().getScreen().width,
                    JmToolbarClearance.hintTop(event.getFullscreen()));
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void finish(Minecraft client, double screenX, double screenY) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        int free = client.player != null ? RelicCarryHint.freeSlots(client.player) : Integer.MAX_VALUE;
        double px = client.player != null ? client.player.getX() : 0;
        double pz = client.player != null ? client.player.getZ() : 0;
        input.release(visit, lastWorldX, lastWorldZ, screenX, screenY, free, px, pz);
        dirty = true;
    }

    /**
     * Steps JourneyMap's zoom toward one block per pixel, like Xaero's library
     * open at scale 1. JourneyMap zoom levels are powers of two and
     * {@code blockSize = zoom / 512}.
     */
    private static void zoomToBlocks(IFullscreen fullscreen) {
        UIState state = fullscreen.getUiState();
        if (state == null || state.zoom <= 0) return;
        int steps = (int) Math.round(Math.log(512.0 / state.zoom) / Math.log(2));
        steps = Math.clamp(steps, -6, 6);
        for (int i = 0; i < steps; i++) fullscreen.zoomIn();
        for (int i = 0; i > steps; i--) fullscreen.zoomOut();
    }

    private static double guiMouseX(Minecraft client) {
        return client.mouseHandler.getScaledXPos(client.getWindow());
    }

    private static double guiMouseY(Minecraft client) {
        return client.mouseHandler.getScaledYPos(client.getWindow());
    }

    private static void fail(Throwable error) {
        input.reset();
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit != null) visit.clearLasso();
        warn("JourneyMap relic library input failed", error);
    }

    private static void warn(String message, Throwable error) {
        if (warned) return;
        warned = true;
        if (error == null) LoggerFactory.getLogger("openintel").warn(message);
        else LoggerFactory.getLogger("openintel").warn(message, error);
    }
}
