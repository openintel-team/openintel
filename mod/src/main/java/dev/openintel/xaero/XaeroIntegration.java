package dev.openintel.xaero;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.util.List;

/** The entry point intentionally references no Xaero types, keeping Xaero optional. */
public final class XaeroIntegration {
    private static boolean registered;
    private static boolean initialized;
    private static boolean failed;
    static long frames;
    static String hoveredLibrarySite;
    static List<MapMarker> lastRendered = List.of();

    private XaeroIntegration() { }

    /** Call after all client initializers have finished (CLIENT_STARTED). */
    public static void initialize() {
        if (initialized || !FabricLoader.getInstance().isModLoaded("xaeroworldmap")) return;
        initialized = true;
        // Xaero defers render setup beyond CLIENT_STARTED on some versions.
        // A missing handler is normal startup state, not an integration failure.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!registered && !failed) tryRegister();
            if (client.level == null) {
                lastRendered = List.of();
                hoveredLibrarySite = null;
                RelicMapScanner.clear();
            }
        });
        tryRegister();
    }

    private static void tryRegister() {
        try {
            registered = XaeroMarkerRenderer.registerWhenReady();
            if (!registered) return;
            LoggerFactory.getLogger("openintel").info("Xaero World Map markers enabled");
        } catch (LinkageError | RuntimeException error) {
            failed = true;
            LoggerFactory.getLogger("openintel").warn("Xaero World Map integration unavailable", error);
        }
    }

    /** Integration diagnostics; these do not include any persistent map state. */
    public static String hoveredLibrarySite() { return hoveredLibrarySite; }
    public static boolean isRegistered() { return registered; }
    public static long renderedFrames() { return frames; }
    public static List<MapMarker> lastRenderedMarkers() { return lastRendered; }
}
