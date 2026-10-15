package dev.openintel.xaero;

import dev.openintel.OpenIntelClient;
import dev.openintel.ping.PingManager;
import dev.openintel.relic.RelicLibraryEntry;
import dev.openintel.relic.RelicLibraryModel;
import dev.openintel.relic.RelicLibraryVisit;
import dev.openintel.relic.RelicWhereIsIt;
import dev.openintel.render.UiFont;
import dev.openintel.tracker.Tracker;
import dev.openintel.wartable.Sanctuaries;
import dev.openintel.wartable.WarTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.MultiBufferSource;
import xaero.lib.client.graphics.XaeroRenderType;
import xaero.map.WorldMap;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.*;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Uses Xaero's map element API; independent of Xaero's Minimap and saved waypoints. */
final class XaeroMarkerRenderer extends ElementRenderer<MapMarker, XaeroMarkerRenderer.Context, XaeroMarkerRenderer> {
    static final class Context {
        List<MapMarker> markers = List.of();
        Iterator<MapMarker> iterator = markers.iterator();
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
        XaeroIntegration.lastRendered = List.of();
        XaeroIntegration.hoveredLibrarySite = null;
        context.drawn.clear();
        XaeroIntegration.frames++;
    }

    private static List<MapMarker> snapshot(String dimension) {
        var client = Minecraft.getInstance();
        var cfg = OpenIntelClient.config();
        if (client.level == null || cfg == null) {
            RelicMapScanner.clear();
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<MapMarker> result = new ArrayList<>();
        var tracker = OpenIntelClient.tracker();
        if (cfg.xaeroMarkers && tracker != null) {
            addSharedPlayers(result, tracker, client, dimension, now, cfg.staleAfterMs);
            for (var h : tracker.snitchHits()) {
                MapMarker marker = new MapMarker("snitch:" + h.player + "@" + h.snitch,
                        h.x, h.y, h.z, h.dimension,
                        OpenIntelClient.allegiances().of(h.player).argb | 0xFF000000,
                        "Snitch: " + h.snitch, h.player + " (via " + h.reporter + ")",
                        h.t + cfg.snitchMarkerSeconds * 1000L);
                if (marker.visibleIn(dimension, now)) result.add(marker);
            }
            for (var p : PingManager.active()) {
                MapMarker marker = new MapMarker("ping:" + p.id, p.x, p.y, p.z, p.dimension,
                        p.color | 0xFF000000, "Ping: " + p.label, p.sender, p.expiresAt);
                if (marker.visibleIn(dimension, now)) result.add(marker);
            }
        }
        if (cfg.xaeroRelicMaps) {
            for (var marker : RelicMapScanner.markers(client)) {
                if (marker.visibleIn(dimension, now)) result.add(marker);
            }
        }
        addLibraryRelics(result, dimension, now);
        if (RelicMapMath.OVERWORLD.equals(dimension)
                && (cfg.warTablePinWorldMap || cfg.warTableLabelWorldMap || cfg.warTableSanctuaryWorldMap)) {
            if (cfg.warTableSanctuaryWorldMap) Sanctuaries.ensureLoaded();
            WarTableMapMarkers.add(result, WarTable.waypointStrokes(),
                    cfg.warTableSanctuaryWorldMap ? Sanctuaries.all() : List.of(),
                    cfg.warTablePinWorldMap, cfg.warTableLabelWorldMap);
        }
        return List.copyOf(result);
    }

    /**
     * Located Chest Tracker relics only while the map was opened from the relic
     * library (mapVisit). Normal world-map opens stay clear of the library.
     * Sites are Overworld coords regardless of which memory key held the map.
     */
    private static void addLibraryRelics(List<MapMarker> result, String dimension, long now) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isMapVisit() || visit.entries().isEmpty()) return;
        if (dimension != null && !RelicMapMath.OVERWORLD.equals(dimension)) return;

        Set<String> selectedSites = new HashSet<>();
        for (RelicLibraryEntry entry : visit.selectedEntries()) {
            if (entry.located()) selectedSites.add(entry.siteKey());
        }
        for (RelicLibraryEntry pin : RelicLibraryModel.sitePins(visit.entries())) {
            if (!pin.located()) continue;
            boolean selected = selectedSites.contains(pin.siteKey());
            int color = selected ? 0xFF7CFF4A : RelicMapMath.COLOR;
            String subtitle = pin.enchantment().isEmpty()
                    ? pin.siteX() + ", " + pin.siteZ()
                    : pin.enchantment();
            MapMarker marker = new MapMarker(
                    "relic:library:" + pin.siteKey(),
                    pin.siteX() + 0.5, RelicMapMath.MARKER_Y, pin.siteZ() + 0.5,
                    RelicMapMath.OVERWORLD,
                    color, pin.title(), subtitle, Long.MAX_VALUE);
            if (marker.visibleIn(dimension, now)) result.add(marker);
        }
    }

    private static void showChestsForMarker(MapMarker marker) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null) return;
        List<ItemStack> stacks = new ArrayList<>();
        List<Object> selected = visit.selectedStacks();
        if (!selected.isEmpty()) {
            for (Object raw : selected) {
                if (raw instanceof ItemStack stack) stacks.add(stack);
            }
        } else if (marker.id().startsWith("relic:library:")) {
            String site = marker.id().substring("relic:library:".length());
            for (RelicLibraryEntry entry : visit.entries()) {
                if (!site.equals(entry.siteKey())) continue;
                Object raw = visit.stackById().get(entry.id());
                if (raw instanceof ItemStack stack) stacks.add(stack);
            }
        }
        RelicWhereIsIt.showChests(stacks);
    }

    /** Relay positions for the dimension the map is showing. Live entities win over the last report. */
    private static void addSharedPlayers(List<MapMarker> result, Tracker tracker, Minecraft client,
                                         String dimension, long now, int staleAfterMs) {
        boolean viewingHere = dimension != null
                && dimension.equals(client.level.dimension().identifier().toString());
        Map<String, AbstractClientPlayer> loaded = new HashMap<>();
        if (viewingHere) {
            for (AbstractClientPlayer entity : client.level.players()) {
                loaded.put(entity.getGameProfile().name(), entity);
            }
        }
        String self = client.player == null ? null : client.player.getGameProfile().name();
        Vec3 selfPos = viewingHere && client.player != null ? client.player.position() : null;
        float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Tracker.RemotePlayer player : tracker.all()) {
            if (player.name.equalsIgnoreCase(self)) continue;
            double x = player.x;
            double y = player.y;
            double z = player.z;
            AbstractClientPlayer entity = loaded.get(player.name);
            if (entity != null) {
                Vec3 live = entity.getPosition(tickDelta);
                x = live.x;
                y = live.y;
                z = live.z;
            }
            String via = player.reporter == null || player.reporter.isBlank() ? "" : "via " + player.reporter;
            String label = via;
            if (selfPos != null && dimension.equals(player.dimension)) {
                long meters = Math.round(Math.hypot(selfPos.x - x, selfPos.z - z));
                label = via.isEmpty() ? meters + "m" : meters + "m " + via;
            }
            int color = OpenIntelClient.allegiances().of(player.name).argb | 0xFF000000;
            MapMarker marker = new MapMarker("player:" + player.name, x, y, z, player.dimension,
                    color, player.name, label, player.lastSeen + staleAfterMs);
            if (marker.visibleIn(dimension, now)) result.add(marker);
        }
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
        if (hovered && marker.id().startsWith("relic:library:") && RelicLibraryVisit.mapVisitActive()) {
            XaeroIntegration.hoveredLibrarySite = marker.id().substring("relic:library:".length());
        }
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(offsetX, offsetZ, depth);
            pose.scale(scale, scale, 1);
            if (WarTableMapMarkers.nameMarker(marker)) {
                drawNameMarker(graphics, marker.argb(), marker.title());
            } else {
                final int textTop;
                if (marker.id().startsWith("relic:")) {
                    boolean selected = marker.id().startsWith("relic:library:")
                            && RelicLibraryVisit.current() != null
                            && RelicLibraryVisit.current().selectedEntries().stream()
                            .anyMatch(e -> e.located()
                                    && marker.id().equals("relic:library:" + e.siteKey()));
                    if (selected) {
                        graphics.fill(-11, -11, 11, 11, 0xA07CFF4A);
                    }
                    drawRelicIcon(graphics, marker.title(), marker.argb());
                    textTop = 10;
                } else if (marker.id().startsWith("player:")) {
                    drawCircle(graphics, marker.argb());
                    textTop = 8;
                } else {
                    drawDiamond(graphics, marker.argb());
                    textTop = 8;
                }
                label(() -> {
                    var font = Minecraft.getInstance().font;
                    String caption = marker.label() == null ? "" : marker.label();
                    int width = Math.max(font.width(marker.title()), font.width(caption));
                    graphics.fill(-width / 2 - 3, textTop, (width + 1) / 2 + 3, textTop + 22, 0xB0101014);
                    graphics.drawCenteredString(font, marker.title(), 0, textTop + 2, marker.argb());
                    graphics.drawCenteredString(font, caption, 0, textTop + 12, 0xFFFFFFFF);
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

    private static void drawRelicIcon(MapElementGraphics graphics, String title, int argb) {
        RelicCatalog.Relic relic = RelicCatalog.find(title);
        if (relic == null) {
            drawCross(graphics, argb);
            return;
        }
        var texture = Identifier.fromNamespaceAndPath("openintel", "textures/relic/" + relic.texture());
        graphics.fill(-9, -9, 9, 9, 0xC0101014);
        // RP_POSITION_TEX_ALPHA needs a discard-alpha uniform that is unset during
        // world-map extract and crashes. The translucent color+texture pipeline is
        // the one Xaero uses for its own map icons.
        graphics.blit(texture, -8, -8, 0, relic.size(), 16, 16, relic.size(), 0, relic.size(),
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
                    showChestsForMarker(marker);
                }
            });
            return options;
        }
    }
}

