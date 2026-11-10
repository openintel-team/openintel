package dev.openintel.feature.hooks;

import dev.openintel.feature.Feature;
import dev.openintel.feature.Features;
import dev.openintel.gui.InvestigateScreen;
import dev.openintel.gui.VisualizationsScreen;
import dev.openintel.relic.RelicLibraryScreen;
import dev.openintel.relic.RelicLibraryVisit;
import dev.openintel.render.WorldOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Off-hooks for radar, visualizations, investigate, War Table, maps and relics. */
public final class VisualHooks {
    private VisualHooks() { }

    public static void register() {
        // Range shapes, sanctuary rings and investigate playback share the overlay
        // layers; dropping them all frees the GPU buffers and the next tick rebuilds
        // whatever is still on.
        for (Feature f : new Feature[]{Feature.VISUALIZATIONS, Feature.VIZ_SNITCH, Feature.VIZ_BASTION,
                Feature.VIZ_SANCTUARY, Feature.VIZ_LIVE_SANCTUARIES, Feature.INVESTIGATE}) {
            Features.onDisabled(f, VisualHooks::releaseOverlay);
        }
        Features.onDisabled(Feature.VISUALIZATIONS, () -> closeIf(VisualizationsScreen.class));
        Features.onDisabled(Feature.INVESTIGATE, () -> closeIf(InvestigateScreen.class));
        Features.onDisabled(Feature.RELIC_LIBRARY, () -> {
            RelicLibraryVisit.clear();
            closeIf(RelicLibraryScreen.class);
        });
        Features.onDisabled(Feature.RELIC_MAPS, RelicLibraryVisit::clear);
    }

    private static void releaseOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.isSameThread()) WorldOverlayRenderer.release();
        else mc.execute(WorldOverlayRenderer::release);
    }

    /** Leaves a disabled feature's screen; screens above it (such as Features) stay put. */
    private static void closeIf(Class<? extends Screen> type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Screen open = mc.screen;
        if (type.isInstance(open)) open.onClose();
    }
}
