package dev.openintel.gui;

import dev.openintel.feature.Feature;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Map;

/** Item icons drawn at the left of each Features tile, like Jade's plugin tiles. */
final class FeatureIcons {
    private FeatureIcons() { }

    private static final Map<Feature, ItemStack> CACHE = new EnumMap<>(Feature.class);

    static ItemStack of(Feature f) {
        return CACHE.computeIfAbsent(f, k -> {
            Item item = item(k);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    private static final Map<FeatureSettings.Link, ItemStack> LINKS = new EnumMap<>(FeatureSettings.Link.class);

    static ItemStack of(FeatureSettings.Link link) {
        return LINKS.computeIfAbsent(link, k -> new ItemStack(switch (k) {
            case RELAYS -> Items.ENDER_PEARL;
            case HUD_EDITOR -> Items.PAINTING;
            case KEYBINDS -> Items.TRIPWIRE_HOOK;
        }));
    }

    private static Item item(Feature f) {
        return switch (f) {
            case RELAY -> Items.ENDER_EYE;
            case PLAYER_MARKERS -> Items.PLAYER_HEAD;
            case SNITCH_ALERTS -> Items.SCULK_SENSOR;
            case PINGS -> Items.BELL;
            case PRESENCE -> Items.NAME_TAG;
            case EVENT_FEED -> Items.WRITABLE_BOOK;
            case CROSSHAIR -> Items.BOW;
            case RADAR -> Items.COMPASS;
            case VISUALIZATIONS -> Items.GLASS;
            case INVESTIGATE -> Items.SPYGLASS;
            case WAR_TABLE -> Items.CARTOGRAPHY_TABLE;
            case ARMOR_HUD -> Items.DIAMOND_CHESTPLATE;
            case POTION_HUD -> Items.POTION;
            case LOGO -> Items.ITEM_FRAME;
            case CLEAN_FONT -> Items.BOOK;
            case JOURNEYMAP -> Items.MAP;
            case XAERO_WORLD_MAP -> Items.FILLED_MAP;
            case WORLD_MAP_INTEL -> Items.RECOVERY_COMPASS;
            case WORLD_MAP_RELICS -> Items.PAPER;
            case WORLD_MAP_WAR_TABLE_PINS -> Items.TARGET;
            case WORLD_MAP_PLACE_LABELS -> Items.OAK_SIGN;
            case WORLD_MAP_SANCTUARY_NAMES -> Items.BEACON;
            case RELIC_MAPS -> Items.AMETHYST_SHARD;
            case CHAT_FILTER -> Items.COMPARATOR;
            case MACROS -> Items.REPEATER;
            case JUNK_REJECT -> Items.LAVA_BUCKET;
            case UPDATE_CHECK -> Items.CLOCK;
            default -> null;
        };
    }
}
