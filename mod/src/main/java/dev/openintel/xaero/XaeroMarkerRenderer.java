package dev.openintel.xaero;

import dev.openintel.feature.Feature;
import dev.openintel.feature.Features;
import dev.openintel.OpenIntelClient;
import dev.openintel.map.MapMarker;
import dev.openintel.map.MapMarkerSource;
import dev.openintel.map.MapMarkerStyle;
import dev.openintel.map.WarTableMapMarkers;
import dev.openintel.relic.LibraryMapInput;
import dev.openintel.relic.RelicLibraryVisit;
import dev.openintel.render.UiFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.MultiBufferSource;
import xaero.lib.client.graphics.XaeroRenderType;
import xaero.map.WorldMap;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.*;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Uses Xaero's map element API; independent of Xaero's Minimap and saved waypoints.
 * Markers and their look come from {@link MapMarkerSource} and {@link MapMarkerStyle}.
 */
final class XaeroMarkerRenderer extends ElementRenderer<MapMarker, XaeroMarkerRenderer.Context, XaeroMarkerRenderer> {
    static final class Context {
        List<MapMarker> markers = List.of();
        Iterator<MapMarker> iterator = markers.iterator();
        Set<String> selectedSites = Set.of();
        final List<MapMarker> drawn = new ArrayList<>();
    }

    private XaeroMarkerRenderer() { super(new Context(), new Provider(), new Reader()); }

    static boolean registerWhenReady() {
        var handler = WorldMap.mapElementRenderHandler;
        if (handler == null) return false;
        handler.add(new XaeroMarkerRenderer());
        return true;
    }

    @Override public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        return location == ElementRenderLocation.WORLD_MAP && !shadow;
    }

    // Coordinates already belong to the viewed dimension; never apply Nether scaling.
    @Override public boolean shouldBeDimScaled() { return false; }
    @Override public int getOrder() { return 100; }

    @Override public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                                    MultiTextureRenderTypeRendererProvider textures, boolean shadow) {
        context.markers = snapshot(info.mapDimension == null ? null : info.mapDimension.identifier().toString());
        context.selectedSites = MapMarkerSource.selectedLibrarySites(RelicLibraryVisit.current());
        XaeroIntegration.lastRendered = List.of();
        XaeroIntegration.hoveredLibrarySite = null;
        context.drawn.clear();
        XaeroIntegration.frames++;
    }

    /** Shared marker list, narrowed to the dimension Xaero is showing. */
    private static List<MapMarker> snapshot(String dimension) {
        var cfg = OpenIntelClient.config();
        if (cfg == null || !Features.isOn(cfg, Feature.XAERO_WORLD_MAP)) return List.of();
        long now = System.currentTimeMillis();
        List<MapMarker> result = new ArrayList<>();
        for (MapMarker marker : MapMarkerSource.all(Minecraft.getInstance(), cfg, now,
                MapMarkerSource.PlayerMode.LIVE_POSITIONS)) {
            if (marker.visibleIn(dimension, now)) result.add(marker);
        }
        return List.copyOf(result);
    }

    @Override public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider textures, boolean shadow) {
        XaeroIntegration.lastRendered = List.copyOf(context.drawn);
    }

    @Override public void renderElementShadow(MapMarker marker, boolean hovered, float scale,
            double offsetX, double offsetZ, ElementRenderInfo info, MapElementGraphics graphics,
            MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures) { }

    @Override public boolean renderElement(MapMarker marker, boolean hovered, double depth, float scale,
            double offsetX, double offsetZ, ElementRenderInfo info, MapElementGraphics graphics,
            MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures) {
        String site = MapMarkerStyle.librarySite(marker);
        if (hovered && site != null && RelicLibraryVisit.mapVisitActive()) {
            XaeroIntegration.hoveredLibrarySite = site;
        }
        MapMarkerStyle.Style style = MapMarkerStyle.of(marker, site != null && context.selectedSites.contains(site));
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(offsetX, offsetZ, depth);
            pose.scale(scale, scale, 1);
            if (style.shape() == MapMarkerStyle.Shape.NAME_DIAMOND) {
                drawNameMarker(graphics, style.argb(), style.title());
            } else {
                final int textTop;
                switch (style.shape()) {
                    case RELIC, CROSS -> {
                        if (style.selected()) {
                            graphics.fill(-11, -11, 11, 11, MapMarkerStyle.SELECTED_FILL);
                        }
                        if (style.shape() == MapMarkerStyle.Shape.RELIC) {
                            drawRelicIcon(graphics, style.relicTexture(), style.relicSize());
                        } else {
                            drawCross(graphics, style.argb());
                        }
                        textTop = 10;
                    }
                    case CIRCLE -> {
                        drawCircle(graphics, style.argb());
                        textTop = 8;
                    }
                    default -> {
                        drawDiamond(graphics, style.argb());
                        textTop = 8;
                    }
                }
                label(() -> {
                    var font = Minecraft.getInstance().font;
                    int width = Math.max(font.width(style.title()), font.width(style.caption()));
                    graphics.fill(-width / 2 - 3, textTop, (width + 1) / 2 + 3, textTop + 22, 0xB0101014);
                    graphics.drawCenteredString(font, style.title(), 0, textTop + 2, style.argb());
                    graphics.drawCenteredString(font, style.caption(), 0, textTop + 12, 0xFFFFFFFF);
                });
            }
            graphics.flush();
            context.drawn.add(marker);
        } finally {
            pose.popPose();
        }
        return false;
    }

    /** OpenIntel-owned map labels only — scopes clean-font selection. */
    static void label(Runnable draw) {
        UiFont.withMapFont("openintel", draw);
    }

    /**
     * Earlier world-map style: a small colored diamond and one line of the
     * same color underneath. Used for War Table pins, place labels, and
     * sanctuary names.
     */
    private static void drawNameMarker(MapElementGraphics graphics, int argb, String text) {
        for (int dy = -4; dy <= 4; dy++) {
            int hw = 4 - Math.abs(dy);
            graphics.fill(-hw, dy, hw + 1, dy + 1, 0xA0101014);
        }
        int color = argb | 0xFF000000;
        for (int dy = -3; dy <= 3; dy++) {
            int hw = 3 - Math.abs(dy);
            graphics.fill(-hw, dy, hw + 1, dy + 1, color);
        }
        label(() -> {
            var font = Minecraft.getInstance().font;
            int width = font.width(text);
            int y = 6;
            graphics.fill(-width / 2 - 2, y - 1, (width + 1) / 2 + 2, y + font.lineHeight + 1, 0xA0101014);
            graphics.drawCenteredString(font, text, 0, y, color);
        });
    }

    private static void drawCircle(MapElementGraphics graphics, int argb) {
        for (int y = -5; y <= 5; y++) {
            int outer = circleHalfWidth(5, y);
            int inner = circleHalfWidth(3, y);
            if (outer >= 0) graphics.fill(-outer, y, outer + 1, y + 1, 0xFF202020);
            if (inner >= 0) graphics.fill(-inner, y, inner + 1, y + 1, argb);
        }
    }

    private static int circleHalfWidth(int radius, int y) {
        int span = radius * radius - y * y;
        if (span < 0) return -1;
        return (int) Math.round(Math.sqrt(span));
    }

    private static void drawDiamond(MapElementGraphics graphics, int argb) {
        for (int y = -5; y <= 5; y++) {
            int width = 5 - Math.abs(y);
            graphics.fill(-width - 1, y, width + 2, y + 1, 0xFF202020);
            if (width > 0) graphics.fill(-width, y, width + 1, y + 1, argb);
        }
    }

    private static void drawRelicIcon(MapElementGraphics graphics, String texture, int size) {
        var id = Identifier.fromNamespaceAndPath("openintel", "textures/relic/" + texture);
        graphics.fill(-9, -9, 9, 9, 0xC0101014);
        // RP_POSITION_TEX_ALPHA needs a discard-alpha uniform that is unset during
        // world-map extract and crashes. The translucent color+texture pipeline is
        // the one Xaero uses for its own map icons.
        graphics.blit(id, -8, -8, 0, size, 16, 16, size, 0, size,
                XaeroRenderType.RP_POSITION_COLOR_TEX_TRANSLUCENT);
    }

    private static void drawCross(MapElementGraphics graphics, int argb) {
        for (int i = -5; i <= 5; i++) {
            graphics.fill(i - 1, i - 1, i + 2, i + 2, 0xFF202020);
            graphics.fill(-i - 1, i - 1, -i + 2, i + 2, 0xFF202020);
        }
        for (int i = -4; i <= 4; i++) {
            graphics.fill(i, i, i + 1, i + 1, argb);
            graphics.fill(-i, i, -i + 1, i + 1, argb);
        }
    }

    private static final class Provider extends ElementRenderProvider<MapMarker, Context> {
        @Override public void begin(ElementRenderLocation location, Context context) { context.iterator = context.markers.iterator(); }
        @Override public boolean hasNext(ElementRenderLocation location, Context context) { return context.iterator.hasNext(); }
        @Override public MapMarker getNext(ElementRenderLocation location, Context context) { return context.iterator.next(); }
        @Override public void end(ElementRenderLocation location, Context context) { context.iterator = List.<MapMarker>of().iterator(); }
    }

    private static final class Reader extends ElementReader<MapMarker, Context, XaeroMarkerRenderer> {
        @Override public boolean isHidden(MapMarker marker, Context context) { return false; }
        @Override public double getRenderX(MapMarker marker, Context context, float partialTicks) { return marker.x(); }
        @Override public double getRenderZ(MapMarker marker, Context context, float partialTicks) { return marker.z(); }
        @Override public double getRenderY(MapMarker marker, Context context, float partialTicks) { return marker.y(); }
        @Override public boolean hasYCoordinate() { return true; }
        @Override public int getInteractionBoxLeft(MapMarker marker, Context context, float partialTicks) { return -6; }
        @Override public int getInteractionBoxRight(MapMarker marker, Context context, float partialTicks) { return 6; }
        @Override public int getInteractionBoxTop(MapMarker marker, Context context, float partialTicks) { return -6; }
        @Override public int getInteractionBoxBottom(MapMarker marker, Context context, float partialTicks) { return 6; }
        @Override public int getRenderBoxLeft(MapMarker marker, Context context, float partialTicks) { return -getLeftSideLength(marker, Minecraft.getInstance()); }
        @Override public int getRenderBoxRight(MapMarker marker, Context context, float partialTicks) { return getLeftSideLength(marker, Minecraft.getInstance()); }
        @Override public int getRenderBoxTop(MapMarker marker, Context context, float partialTicks) {
            if (marker.id().startsWith("relic:")) return -10;
            return WarTableMapMarkers.nameMarker(marker) ? -5 : -6;
        }
        @Override public int getRenderBoxBottom(MapMarker marker, Context context, float partialTicks) {
            if (marker.id().startsWith("relic:")) return 34;
            if (WarTableMapMarkers.nameMarker(marker)) return 16;
            return 30;
        }
        @Override public int getLeftSideLength(MapMarker marker, Minecraft client) {
            int text = Math.max(client.font.width(marker.title()), client.font.width(marker.label())) / 2 + 4;
            return marker.id().startsWith("relic:") ? Math.max(text, 12) : text;
        }
        @Override public String getMenuName(MapMarker marker) { return marker.title(); }
        @Override public String getFilterName(MapMarker marker) { return marker.title() + " " + marker.label(); }
        @Override public int getMenuTextFillLeftPadding(MapMarker marker) { return 0; }
        @Override public int getRightClickTitleBackgroundColor(MapMarker marker) { return marker.argb(); }
        @Override public boolean shouldScaleBoxWithOptionalScale() { return true; }
        @Override public boolean isInteractable(ElementRenderLocation location, MapMarker marker) {
            return marker.id().startsWith("relic:") && RelicLibraryVisit.mapVisitActive();
        }
        @Override public boolean isRightClickValid(MapMarker marker) {
            return marker.id().startsWith("relic:") && RelicLibraryVisit.mapVisitActive();
        }
        @Override public ArrayList<RightClickOption> getRightClickOptions(MapMarker marker,
                                                                         IRightClickableElement target) {
            ArrayList<RightClickOption> options = new ArrayList<>();
            options.add(new RightClickOption("openintel.relic.library.show_chests", 0, target) {
                @Override
                public void onAction(Screen screen) {
                    LibraryMapInput.showChests(RelicLibraryVisit.current(), MapMarkerStyle.librarySite(marker));
                }
            });
            return options;
        }
    }
}

