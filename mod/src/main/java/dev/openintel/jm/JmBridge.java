package dev.openintel.jm;

import dev.openintel.OpenIntelClient;
import dev.openintel.map.MapMarker;
import dev.openintel.map.MapMarkerSource;
import dev.openintel.map.MapMarkerStyle;
import dev.openintel.relic.RelicLibraryVisit;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.Context;
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
 * against what is shown: every 20 ticks, or every 4 while the fullscreen map
 * is open. Loaded players are left to JourneyMap's own radar. Nothing is
 * written as a waypoint.
 */
final class JmBridge implements Runnable {

    /** Wanted overlay: the marker, its look, and the key that triggers a rebuild. */
    private record Desired(MapMarker marker, MapMarkerStyle.Style style, String sig) { }

    private final IClientAPI api;
    private final JmIcons icons = new JmIcons();
    private final Map<String, MarkerOverlay> shown = new HashMap<>();
    private final Map<String, String> sigs = new HashMap<>();
    private int ticks;
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
            int cadence = fullscreenActive() ? 4 : 20;
            if (!JmLibraryMap.consumeDirty() && ticks % cadence != 0) return;
            sync();
        } catch (Throwable t) {
            // Never let an API hiccup hurt the client: warn once and stop, like the Xaero path.
            failed = true;
            LoggerFactory.getLogger("openintel").warn("JourneyMap integration disabled after an error", t);
            for (MarkerOverlay overlay : shown.values()) removeQuietly(overlay);
            shown.clear();
            sigs.clear();
        }
    }

    private boolean fullscreenActive() {
        UIState state = api.getUIState(Context.UI.Fullscreen);
        return state != null && state.active;
    }

    private void sync() throws Exception {
        var cfg = OpenIntelClient.config();
        Map<String, Desired> want = new LinkedHashMap<>();
        if (cfg != null && cfg.jmMarkers) {
            long now = System.currentTimeMillis();
            Set<String> selected = MapMarkerSource.selectedLibrarySites(RelicLibraryVisit.current());
            for (MapMarker marker : MapMarkerSource.all(Minecraft.getInstance(), cfg, now,
                    MapMarkerSource.PlayerMode.SKIP_LOADED)) {
                String site = MapMarkerStyle.librarySite(marker);
                MapMarkerStyle.Style style = MapMarkerStyle.of(marker, site != null && selected.contains(site));
                want.put(marker.id(), new Desired(marker, style, MapMarkerStyle.signature(marker, style)));
            }
        }

        // Drop markers that expired, cleared, or lost the toggle.
        Iterator<Map.Entry<String, MarkerOverlay>> it = shown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MarkerOverlay> e = it.next();
            if (!want.containsKey(e.getKey())) {
                removeQuietly(e.getValue());
                sigs.remove(e.getKey());
                it.remove();
            }
        }

        // Add new markers; recreate any whose position or look changed.
        for (Map.Entry<String, Desired> e : want.entrySet()) {
            Desired d = e.getValue();
            if (d.sig.equals(sigs.get(e.getKey()))) continue;
            MarkerOverlay old = shown.remove(e.getKey());
            sigs.remove(e.getKey());
            if (old != null) removeQuietly(old);
            MarkerOverlay overlay = show(d);
            if (overlay != null) {
                shown.put(e.getKey(), overlay);
                sigs.put(e.getKey(), d.sig);
            }
        }
    }

    private MarkerOverlay show(Desired d) throws Exception {
        ResourceKey<Level> dim;
        try {
            dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(d.marker.dimension()));
        } catch (Exception e) {
            return null;   // a custom world id that is not a resource location — skip
        }
        MapMarkerStyle.Style style = d.style;
        BlockPos pos = BlockPos.containing(d.marker.x(), d.marker.y(), d.marker.z());
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
        String site = MapMarkerStyle.librarySite(d.marker);
        if (site != null) overlay.setOverlayListener(JmLibraryMap.pinListener(site));
        api.show(overlay);
        return overlay;
    }

    private void removeQuietly(MarkerOverlay overlay) {
        try {
            api.remove(overlay);
        } catch (Throwable ignored) { }
    }
}
