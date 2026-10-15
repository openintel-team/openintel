package dev.openintel.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads relic-map targets out of the local inventory. Markers last only while
 * the map item is carried; nothing is written into Xaero.
 */
public final class RelicMapScanner {
    private static final Map<Integer, RelicMapMath.CenterLock> centers = new HashMap<>();
    private static final Set<Integer> announced = new HashSet<>();

    private RelicMapScanner() { }

    public static void clear() {
        centers.clear();
        announced.clear();
    }

    public static List<MapMarker> markers(Minecraft client) {
        Player player = client.player;
        if (player == null || client.level == null) return List.of();
        LinkedHashMap<String, MapMarker> found = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            consider(found, inventory.getItem(slot), client, 0);
        }
        consider(found, player.containerMenu.getCarried(), client, 0);
        return List.copyOf(found.values());
    }

    private static void consider(Map<String, MapMarker> found, ItemStack stack, Minecraft client, int depth) {
        if (stack == null || stack.isEmpty() || depth > 4) return;
        if (!isWorldMap(stack)
                && (stack.get(DataComponents.MAP_ID) != null || stack.get(DataComponents.MAP_DECORATIONS) != null)) {
            readMap(found, stack, client);
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            container.nonEmptyItemCopyStream().forEach(inner -> consider(found, inner, client, depth + 1));
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.itemCopies().forEach(inner -> consider(found, inner, client, depth + 1));
        }
    }

    /**
     * Block position of the first relic mark on this stack. Null when the stack
     * is not a relic map, or the X has no world coordinates yet.
     */
    public static int[] blockPos(ItemStack stack, Minecraft client) {
        if (stack == null || stack.isEmpty() || isWorldMap(stack)) return null;
        if (stack.get(DataComponents.MAP_ID) == null && stack.get(DataComponents.MAP_DECORATIONS) == null) return null;
        List<Placed> hits = placed(stack, client, false);
        if (hits.isEmpty()) return null;
        Placed first = hits.get(0);
        return new int[]{(int) Math.round(first.x()), (int) Math.round(first.z())};
    }

    /** Treasure map (not a World Map locator) with map data or decorations. */
    public static boolean isTreasureMap(ItemStack stack) {
        if (stack == null || stack.isEmpty() || isWorldMap(stack)) return false;
        return stack.get(DataComponents.MAP_ID) != null || stack.get(DataComponents.MAP_DECORATIONS) != null;
    }

    /** Relic name and enchantment from lore / item name, same rules as map markers. */
    public static RelicNames.Label labelOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new RelicNames.Label("Relic", "");
        return label(stack, null);
    }

    private static void readMap(Map<String, MapMarker> found, ItemStack stack, Minecraft client) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        String mapKey = mapId == null ? "unbound" : Integer.toString(mapId.id());
        RelicNames.Label label = label(stack, null);
        for (Placed hit : placed(stack, client, true)) {
            String caption = hit.decorationName();
            RelicNames.Label iconLabel = caption == null || caption.isBlank() ? label : label(stack, caption);
            put(found, mapKey, hit.iconKey(), hit.x(), hit.z(), iconLabel);
        }
    }

    /**
     * Relic marks with a known world position, in the same order the world map uses.
     * Prefer saved-map center + icon bytes; only fall back to {@code MAP_DECORATIONS}
     * x/z when those look like absolute world coordinates (or there is no map id).
     */
    private static List<Placed> placed(ItemStack stack, Minecraft client, boolean announceMissing) {
        List<Placed> hits = new ArrayList<>();
        MapId mapId = stack.get(DataComponents.MAP_ID);
        boolean sawRelic = false;

        // Prefer saved data: icon bytes are map-local until converted with the center.
        if (mapId != null && client.level != null) {
            MapItemSavedData data = client.level.getMapData(mapId);
            if (data != null && RelicMapMath.validScale(data.scale)) {
                Center center = resolveCenter(mapId.id(), data, client);
                int index = 0;
                for (MapDecoration icon : data.getDecorations()) {
                    if (relic(icon.type())) {
                        sawRelic = true;
                        if (center != null
                                && !RelicMapMath.iconClampedToEdge(icon.x())
                                && !RelicMapMath.iconClampedToEdge(icon.y())) {
                            double x = RelicMapMath.worldFromIcon(center.x, data.scale, icon.x());
                            double z = RelicMapMath.worldFromIcon(center.z, data.scale, icon.y());
                            String caption = icon.name().map(Component::getString).orElse("");
                            hits.add(new Placed(path(icon.type()) + "@" + index, x, z, caption));
                        }
                    }
                    index++;
                }
                if (!hits.isEmpty()) return hits;
            }
        }

        // Component values are sometimes world X/Z and sometimes the same icon space.
        MapDecorations decorations = stack.get(DataComponents.MAP_DECORATIONS);
        if (decorations != null) {
            boolean hasMapId = mapId != null;
            for (var entry : decorations.decorations().entrySet()) {
                MapDecorations.Entry icon = entry.getValue();
                if (!relic(icon.type()) || !Double.isFinite(icon.x()) || !Double.isFinite(icon.z())) continue;
                sawRelic = true;
                if (!RelicMapMath.useComponentAsWorld(icon.x(), icon.z(), hasMapId)) continue;
                hits.add(new Placed(entry.getKey(), icon.x(), icon.z(), null));
            }
        }

        if (announceMissing && sawRelic && hits.isEmpty() && mapId != null && announced.add(mapId.id())) {
            LoggerFactory.getLogger("openintel").info(
                    "Relic map {} ({}) has an X, but no world coordinates yet. It will show once your arrow is inside that map.",
                    mapId.id(), label(stack, null).title());
        }
        return hits;
    }

    /**
     * Learn the map center from an on-map player arrow. Off-map arrows are clamped
     * and useless. The same arrow byte is kept while the player walks inside one
     * map pixel, so the center must not be rewritten from the live position or
     * relic pins slide around underfoot.
     */
    private static Center resolveCenter(int mapId, MapItemSavedData data, Minecraft client) {
        RelicMapMath.CenterLock known = centers.get(mapId);
        Player player = client.player;
        if (player == null || client.level == null || !Level.OVERWORLD.equals(client.level.dimension())) {
            return snapshot(known);
        }
        for (MapDecoration icon : data.getDecorations()) {
            if (!RelicMapMath.isInteriorPlayerIcon(path(icon.type()), icon.x(), icon.y())) continue;
            if (known == null) {
                known = new RelicMapMath.CenterLock();
                centers.put(mapId, known);
            }
            known.observe(player.getX(), player.getZ(), icon.x(), icon.y(), data.scale);
            return snapshot(known);
        }
        return snapshot(known);
    }

    private static Center snapshot(RelicMapMath.CenterLock lock) {
        if (lock == null || !lock.known()) return null;
        return new Center(lock.x(), lock.z());
    }

    private static void put(Map<String, MapMarker> found, String mapKey, String iconKey,
                            double x, double z, RelicNames.Label label) {
        String id = "relic:" + mapKey + ":" + iconKey;
        if (found.containsKey(id)) return;
        int blockX = (int) Math.round(x);
        int blockZ = (int) Math.round(z);
        String subtitle = label.enchantment().isEmpty() ? blockX + ", " + blockZ : label.enchantment();
        found.put(id, new MapMarker(id, blockX + 0.5, RelicMapMath.MARKER_Y, blockZ + 0.5,
                RelicMapMath.OVERWORLD, RelicMapMath.COLOR, label.title(), subtitle, Long.MAX_VALUE));
    }

    /** Filled maps renamed "World Map" are locators, not relic targets. */
    private static boolean isWorldMap(ItemStack stack) {
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom != null && RelicCatalog.isWorldMapName(custom.getString())) return true;
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        if (itemName != null && RelicCatalog.isWorldMapName(itemName.getString())) return true;
        return RelicCatalog.isWorldMapName(stack.getHoverName().getString());
    }

    /** Lore first: every treasure map is titled "Treasure Map", with the relic on the next line. */
    private static RelicNames.Label label(ItemStack stack, String decorationName) {
        List<String> lines = new ArrayList<>();
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) lore.lines().forEach(line -> lines.add(line.getString()));
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        if (itemName != null) lines.add(itemName.getString());
        if (decorationName != null && !decorationName.isBlank()) lines.add(decorationName);
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom != null) lines.add(custom.getString());
        lines.add(stack.getHoverName().getString());
        return RelicNames.resolve(lines);
    }

    private static boolean relic(Holder<MapDecorationType> type) {
        MapDecorationType value = type.value();
        boolean destination = value.showOnItemFrame() && !value.trackCount();
        return RelicMapMath.isRelicIcon(namespace(type), path(type), destination);
    }

    private static String namespace(Holder<MapDecorationType> type) {
        return type.unwrapKey().map(key -> key.identifier().getNamespace()).orElse("");
    }

    private static String path(Holder<MapDecorationType> type) {
        return type.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
    }

    private record Center(double x, double z) { }

    private record Placed(String iconKey, double x, double z, String decorationName) { }
}
