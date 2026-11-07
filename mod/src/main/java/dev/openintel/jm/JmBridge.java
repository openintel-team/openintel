package dev.openintel.jm;

import dev.openintel.OpenIntelClient;
import dev.openintel.map.MapMarkerSource;
import dev.openintel.map.MapMarkerStyle;
import dev.openintel.relic.RelicLibraryVisit;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.client.util.UIState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors {@link MapMarkerSource} onto JourneyMap's fullscreen map as
 * MarkerOverlays. Runs from the client tick and reconciles the wanted list
 * against what is shown: every tick while the fullscreen map is open (and at
 * once when it opens), every 20 ticks otherwise, since the overlays are
 * fullscreen-only. A marker that only moved or changed text is updated in
 * place; a new icon, color, or dimension gets a new overlay. Loaded players
 * are left to JourneyMap's own radar. Nothing is written as a waypoint.
 */
final class JmBridge implements Runnable {

    private final IClientAPI api;
    private final JmIcons icons = new JmIcons();
    private final Map<String, MarkerOverlay> shown = new HashMap<>();
    private final Map<String, String> sigs = new HashMap<>();
    private final Map<String, String> iconSigs = new HashMap<>();
    private int ticks;
    private boolean wasOpen;
    private boolean failed;

    JmBridge(IClientAPI api) {
        this.api = api;
    }

    @Override
    public void run() {
        // The visit must still end when its map closes, even after a marker failure.
        JmLibraryMap.tick();
        if (failed) return;
        try {
            ticks++;
            boolean open = fullscreenActive();
            boolean opened = open && !wasOpen;
            wasOpen = open;
            int cadence = open ? 1 : 20;
            if (!JmLibraryMap.consumeDirty() && !opened && ticks % cadence != 0) return;
            sync();
        } catch (Throwable t) {
            // Never let an API hiccup hurt the client: warn once and stop, like the Xaero path.
            failed = true;
            LoggerFactory.getLogger("openintel").warn("JourneyMap integration disabled after an error", t);
            for (MarkerOverlay overlay : shown.values()) removeQuietly(overlay);
            shown.clear();
            sigs.clear();
            iconSigs.clear();
        }
    }

    private boolean fullscreenActive() {
        UIState state = api.getUIState(Context.UI.Fullscreen);
        return state != null && state.active;
    }

    private void sync() throws Exception {
        var cfg = OpenIntelClient.config();
        Map<String, JmOverlayPlan.Entry> want = new LinkedHashMap<>();
        if (cfg != null && cfg.jmMarkers) {
            long now = System.currentTimeMillis();
            Set<String> selected = MapMarkerSource.selectedLibrarySites(RelicLibraryVisit.current());
            for (JmOverlayPlan.Entry entry : JmOverlayPlan.plan(MapMarkerSource.all(Minecraft.getInstance(), cfg, now,
                    MapMarkerSource.PlayerMode.SKIP_LOADED), selected)) {
                want.put(entry.key(), entry);
            }
        }

        // Drop markers that expired, cleared, or lost the toggle.
        Iterator<Map.Entry<String, MarkerOverlay>> it = shown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MarkerOverlay> e = it.next();
            if (!want.containsKey(e.getKey())) {
                removeQuietly(e.getValue());
                sigs.remove(e.getKey());
                iconSigs.remove(e.getKey());
                it.remove();
            }
        }

        // Add new markers; move or retitle in place while the icon holds; recreate
        // on a new icon, color, or dimension. A selection change only flips which
        // of a library pin's two overlays is visible.
        for (Map.Entry<String, JmOverlayPlan.Entry> e : want.entrySet()) {
            JmOverlayPlan.Entry d = e.getValue();
            MarkerOverlay overlay = shown.get(e.getKey());
            if (overlay == null || !d.iconSig().equals(iconSigs.get(e.getKey()))) {
                shown.remove(e.getKey());
                sigs.remove(e.getKey());
                iconSigs.remove(e.getKey());
                if (overlay != null) removeQuietly(overlay);
                overlay = show(e.getKey(), d);
                if (overlay == null) continue;
                shown.put(e.getKey(), overlay);
                sigs.put(e.getKey(), d.sig());
                iconSigs.put(e.getKey(), d.iconSig());
            } else {
                if (!d.sig().equals(sigs.get(e.getKey()))) {
                    // Same overlay object, so JourneyMap 6.0.0's hovered list stays valid.
                    overlay.setPoint(BlockPos.containing(d.marker().x(), d.marker().y(), d.marker().z()));
                    String text = d.style().text("\n");
                    overlay.setTitle(text);
                    overlay.setLabel(text);
                    overlay.flagForRerender();
                    sigs.put(e.getKey(), d.sig());
                }
                setVisible(overlay, d.visible());
            }
        }
    }

    private MarkerOverlay show(String key, JmOverlayPlan.Entry d) throws Exception {
        ResourceKey<Level> dim;
        try {
            dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(d.marker().dimension()));
        } catch (Exception e) {
            return null;   // a custom world id that is not a resource location — skip
        }
        MapMarkerStyle.Style style = d.style();
        BlockPos pos = BlockPos.containing(d.marker().x(), d.marker().y(), d.marker().z());
        MarkerOverlay overlay = new MarkerOverlay("openintel", pos, icons.image(style));
        // JourneyMap splits overlay text on newlines: title, then caption.
        String text = style.text("\n");
        overlay.setDimension(dim);
        overlay.setTitle(text);
        overlay.setLabel(text);
        overlay.setOverlayGroupName("OpenIntel");
        overlay.setActiveUIs(Context.UI.Fullscreen);
        overlay.setActiveMapTypes(Context.MapType.all());
        overlay.setTextProperties(new TextProperties()
                .setColor(style.argb() & 0xFFFFFF)
                .setFontShadow(false)
                .setBackgroundColor(0x101014)
                .setBackgroundOpacity(0.6f)
                .setScale(1.0f)
                .setOffsetY(JmIcons.textOffset(style.shape())));
        if (d.site() != null) {
            // JourneyMap 6.0.0 keeps calling a removed overlay's listener; only the shown one may act.
            overlay.setOverlayListener(JmLibraryMap.pinListener(d.site(), () -> shown.get(key) == overlay));
        }
        setVisible(overlay, d.visible());
        api.show(overlay);
        return overlay;
    }

    /**
     * Shows or hides an overlay without replacing it. A hidden overlay has an
     * empty zoom range, so JourneyMap still lists it, stops drawing it, and
     * clears its hover on the next mouse move.
     */
    private static void setVisible(MarkerOverlay overlay, boolean visible) {
        if (visible == (overlay.getMinZoom() <= overlay.getMaxZoom())) return;
        if (visible) {
            overlay.setMinZoom(UIState.FULLSCREEN_ZOOM_MIN);
            overlay.setMaxZoom(UIState.ZOOM_IN_MAX);
        } else {
            overlay.setMinZoom(UIState.ZOOM_IN_MAX);
            overlay.setMaxZoom(UIState.FULLSCREEN_ZOOM_MIN);
        }
        // Positions are refreshed only on a rerender, and a hidden overlay skipped them.
        overlay.flagForRerender();
    }

    private void removeQuietly(MarkerOverlay overlay) {
        try {
            api.remove(overlay);
        } catch (Throwable ignored) { }
    }
}
