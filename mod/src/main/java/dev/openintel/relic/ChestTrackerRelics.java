package dev.openintel.relic;

import dev.openintel.xaero.RelicMapScanner;
import dev.openintel.xaero.RelicNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;
import red.jackf.chesttracker.api.memory.MemoryKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Civ+ treasure maps out of the loaded Chest Tracker memory bank.
 * Soft-fails when Chest Tracker is missing or no bank is loaded.
 */
public final class ChestTrackerRelics {
    public record Loaded(List<RelicLibraryEntry> entries, Map<String, ItemStack> stacks) { }

    private ChestTrackerRelics() { }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("chesttracker");
    }

    public static Loaded load(Minecraft client) {
        if (!available() || client == null) return new Loaded(List.of(), Map.of());
        Optional<MemoryBank> bank = MemoryBankAccess.INSTANCE.getLoaded();
        if (bank.isEmpty()) return new Loaded(List.of(), Map.of());

        List<RelicLibraryEntry> entries = new ArrayList<>();
        LinkedHashMap<String, ItemStack> stacks = new LinkedHashMap<>();
        for (Map.Entry<Identifier, MemoryKey> keyEntry : bank.get().getAllMemories().entrySet()) {
            String memoryKey = keyEntry.getKey().toString();
            for (Map.Entry<BlockPos, Memory> memoryEntry : keyEntry.getValue().getMemories().entrySet()) {
                BlockPos chest = memoryEntry.getKey();
                Memory memory = memoryEntry.getValue();
                List<ItemStack> items = memory.fullItems();
                if (items == null) continue;
                for (int i = 0; i < items.size(); i++) {
                    collect(entries, stacks, items.get(i), memoryKey, chest, i + ":", client, 0);
                }
            }
        }
        return new Loaded(List.copyOf(entries), Map.copyOf(stacks));
    }

    private static void collect(List<RelicLibraryEntry> entries, Map<String, ItemStack> stacks,
                                ItemStack stack, String memoryKey, BlockPos chest, String path,
                                Minecraft client, int depth) {
        if (stack == null || stack.isEmpty() || depth > 4) return;
        if (RelicMapScanner.isTreasureMap(stack)) {
            RelicNames.Label label = RelicMapScanner.labelOf(stack);
            int[] site = RelicMapScanner.blockPos(stack, client);
            String mapKey = stack.get(DataComponents.MAP_ID) == null
                    ? "unbound"
                    : Integer.toString(stack.get(DataComponents.MAP_ID).id());
            String id = memoryKey + "@" + chest.getX() + "," + chest.getY() + "," + chest.getZ()
                    + ":" + path + mapKey;
            RelicLibraryEntry entry = new RelicLibraryEntry(
                    id,
                    label.title(),
                    label.enchantment(),
                    site == null ? null : site[0],
                    site == null ? null : site[1],
                    memoryKey,
                    chest.getX(),
                    chest.getY(),
                    chest.getZ());
            entries.add(entry);
            stacks.put(id, stack.copy());
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            int index = 0;
            for (ItemStack inner : container.nonEmptyItemsCopy()) {
                collect(entries, stacks, inner, memoryKey, chest, path + "c" + index++ + ":", client, depth + 1);
            }
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            int index = 0;
            for (ItemStack inner : bundle.itemsCopy()) {
                collect(entries, stacks, inner, memoryKey, chest, path + "b" + index++ + ":", client, depth + 1);
            }
        }
    }
}
