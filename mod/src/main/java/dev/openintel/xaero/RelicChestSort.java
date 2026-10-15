package dev.openintel.xaero;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Reorders relic maps inside an opened chest through the normal container-click
 * path. Other items stay in their slots. Each exchange is three pickup clicks
 * sent on one tick; the next exchange waits a tick so a server correction can
 * land before it.
 */
public final class RelicChestSort {
    private static final int SETTLE_TICKS = 2;

    private static Object openScreen;
    private static int openId = Integer.MIN_VALUE;
    private static int age;
    private static boolean done;
    private static int[] clicks = new int[0];
    private static int cursor;

    private RelicChestSort() { }

    public static void tick(Minecraft client) {
        OIConfig cfg = OpenIntelClient.config();
        Player player = client.player;
        if (cfg == null || !cfg.sortRelicMaps || player == null || client.gameMode == null || client.level == null) {
            reset();
            return;
        }
        var screen = client.gui.screen();
        AbstractContainerMenu menu = player.containerMenu;
        if (screen == null || !storageMenu(menu)) {
            reset();
            return;
        }
        if (screen != openScreen || menu.containerId != openId) {
            openScreen = screen;
            openId = menu.containerId;
            age = 0;
            done = false;
            clicks = new int[0];
            cursor = 0;
        }
        if (done) return;
        age++;
        if (cursor < clicks.length) {
            exchange(client, menu, player);
            return;
        }
        if (age <= SETTLE_TICKS) return;
        if (!menu.getCarried().isEmpty()) {
            done = true;
            return;
        }
        clicks = plan(menu, player, client);
        cursor = 0;
        if (clicks.length == 0) done = true;
        else exchange(client, menu, player);
    }

    /** Swap two slots: pick up the first, pick up the second, put the remainder back. */
    private static void exchange(Minecraft client, AbstractContainerMenu menu, Player player) {
        if (!menu.getCarried().isEmpty() || cursor + 2 >= clicks.length) {
            done = true;
            return;
        }
        var input = client.gameMode;
        input.handleContainerInput(menu.containerId, clicks[cursor], 0, ContainerInput.PICKUP, player);
        input.handleContainerInput(menu.containerId, clicks[cursor + 1], 0, ContainerInput.PICKUP, player);
        input.handleContainerInput(menu.containerId, clicks[cursor + 2], 0, ContainerInput.PICKUP, player);
        cursor += 3;
        if (cursor >= clicks.length) done = true;
    }

    private static int[] plan(AbstractContainerMenu menu, Player player, Minecraft client) {
        int storage = storageSlots(menu, player);
        int[] positions = new int[storage];
        double[] distanceSq = new double[storage];
        int count = 0;
        int playerX = player.getBlockX();
        int playerZ = player.getBlockZ();
        for (int slot = 0; slot < storage; slot++) {
            Slot chestSlot = menu.slots.get(slot);
            if (!chestSlot.mayPickup(player)) continue;
            int[] block = RelicMapScanner.blockPos(chestSlot.getItem(), client);
            if (block == null) continue;
            long dx = (long) block[0] - playerX;
            long dz = (long) block[1] - playerZ;
            positions[count] = slot;
            distanceSq[count] = (double) (dx * dx + dz * dz);
            count++;
        }
        if (count < 2) return new int[0];
        int[] usedSlots = new int[count];
        double[] usedDist = new double[count];
        System.arraycopy(positions, 0, usedSlots, 0, count);
        System.arraycopy(distanceSq, 0, usedDist, 0, count);
        var swaps = RelicMapOrder.swaps(usedSlots, usedDist);
        int[] sequence = new int[swaps.size() * 3];
        for (int i = 0; i < swaps.size(); i++) {
            int[] swap = swaps.get(i);
            sequence[i * 3] = swap[0];
            sequence[i * 3 + 1] = swap[1];
            sequence[i * 3 + 2] = swap[0];
        }
        return sequence;
    }

    /** Slots that belong to the opened container, which are listed before the player's inventory. */
    private static int storageSlots(AbstractContainerMenu menu, Player player) {
        var inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container == inventory) break;
            count++;
        }
        return count;
    }

    private static boolean storageMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu;
    }

    private static void reset() {
        openScreen = null;
        openId = Integer.MIN_VALUE;
        age = 0;
        done = false;
        clicks = new int[0];
        cursor = 0;
    }
}
