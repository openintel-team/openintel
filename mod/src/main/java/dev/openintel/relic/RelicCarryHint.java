package dev.openintel.relic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Map readout for how many selected treasure maps fit in the main inventory.
 * Filled maps take one slot each, so the limit is the number of empty slots.
 */
public final class RelicCarryHint {
    private RelicCarryHint() { }

    public static void draw(GuiGraphics graphics, int screenWidth) {
        RelicLibraryVisit visit = RelicLibraryVisit.current();
        if (visit == null || !visit.isMapVisit()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) return;

        int free = freeSlots(client.player);
        // Re-read the inventory every frame. A selection made when more slots were
        // empty has to shrink once those slots fill, or the lasso stays locked.
        if (!visit.isLassoDragging() && client.player != null) {
            visit.enforceCapacity(free, client.player.getX(), client.player.getZ());
        }
        RelicLibraryModel.CarryStatus status = RelicLibraryModel.carryStatus(
                visit.selectedIds().size(), visit.pendingSelectionCount(), free);
        if (visit.crossedFull(status.level()) && client.player != null) {
            client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 1.6f);
        }

        Component line = switch (status.level()) {
            case FULL -> Component.translatable("openintel.relic.library.carry_full", status.shown());
            case OVER -> Component.translatable("openintel.relic.library.carry_over", status.shown(), free);
            case ROOM -> Component.translatable("openintel.relic.library.carry", status.shown(), free);
        };
        int color = switch (status.level()) {
            case FULL -> 0xFF7CFF4A;
            case OVER -> 0xFFFF6B6B;
            case ROOM -> 0xFFE8EAF0;
        };
        String text = line.getString();
        int width = client.font.width(text);
        int x = (screenWidth - width) / 2;
        int y = 8;
        graphics.fill(x - 6, y - 3, x + width + 6, y + 12, 0xC0101014);
        graphics.fill(x - 6, y - 3, x + width + 6, y - 2, color);
        graphics.drawString(client.font, text, x, y, color);
    }

    /**
     * Empty slots in the live 36-slot main inventory (storage and hotbar).
     * Armor and the offhand are left out. Called on every frame and every
     * selection, so a pickup or drop changes the cap immediately.
     */
    public static int freeSlots(Player player) {
        if (player == null) return 0;
        var items = player.getInventory().getNonEquipmentItems();
        int free = 0;
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack == null || stack.isEmpty()) free++;
        }
        return free;
    }
}
