package dev.openintel.relic;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Press, drag, and release handling for a relic-library map visit, shared by
 * the Xaero and JourneyMap fullscreen maps. Callers pass world block
 * coordinates under the mouse, GUI-scaled screen coordinates, the hovered
 * library site, the live empty-slot count, and the player position, so both
 * maps make the same {@link RelicLibraryVisit} calls.
 */
public final class LibraryMapInput {
    /** A press and release closer than this (GUI pixels) is a click, not a lasso. */
    public static final double CLICK_SLOP = 4;

    private String pressedSite;
    private double pressX;
    private double pressY;
    private boolean selecting;

    /**
     * Left press. Shift starts a lasso; a plain press only counts on a library
     * pin. Returns true when the library took the press, so the map must not
     * start panning.
     */
    public boolean press(RelicLibraryVisit visit, boolean shift, String hoveredSite,
                         int worldX, int worldZ, double screenX, double screenY) {
        if (visit == null || !visit.isMapVisit()) return false;
        if (!shift && hoveredSite == null) return false;
        pressedSite = hoveredSite;
        pressX = screenX;
        pressY = screenY;
        selecting = true;
        if (shift) visit.beginLasso(worldX, worldZ, (int) screenX, (int) screenY);
        return true;
    }

    /** Mouse moved with the button held. */
    public void drag(RelicLibraryVisit visit, int worldX, int worldZ, double screenX, double screenY) {
        if (visit == null || !selecting) return;
        visit.updateLasso(worldX, worldZ, (int) screenX, (int) screenY);
    }

    /**
     * Left release. A short press toggles the pressed site; a drag adds the
     * lasso hits, both capped by {@code freeSlots}. Returns true when this
     * release ended a press the library had taken.
     */
    public boolean release(RelicLibraryVisit visit, int worldX, int worldZ, double screenX, double screenY,
                           int freeSlots, double playerX, double playerZ) {
        if (visit == null || !visit.isMapVisit() || !selecting) {
            reset();
            return false;
        }
        visit.updateLasso(worldX, worldZ, (int) screenX, (int) screenY);
        visit.enforceCapacity(freeSlots, playerX, playerZ);
        boolean click = Math.hypot(screenX - pressX, screenY - pressY) < CLICK_SLOP;
        if (click) {
            visit.clearLasso();
            visit.clickSite(pressedSite, freeSlots);
        } else if (visit.isLassoDragging()) {
            visit.finishLasso(freeSlots, playerX, playerZ);
        }
        reset();
        return true;
    }

    /** A press the library took is still held. */
    public boolean selecting() {
        return selecting;
    }

    public void reset() {
        selecting = false;
        pressedSite = null;
    }

    /** One click on a pin when the map reports the click itself (JourneyMap overlay listener). */
    public static boolean click(RelicLibraryVisit visit, String site, int freeSlots, double playerX, double playerZ) {
        if (visit == null || !visit.isMapVisit() || site == null) return false;
        visit.enforceCapacity(freeSlots, playerX, playerZ);
        return visit.clickSite(site, freeSlots);
    }

    /**
     * Stacks for "Show chests" on a pin: the whole selection when there is one,
     * otherwise every copy stored for that site.
     */
    public static List<Object> chestStacks(RelicLibraryVisit visit, String site) {
        if (visit == null) return List.of();
        List<Object> selected = visit.selectedStacks();
        if (!selected.isEmpty()) return selected;
        if (site == null) return List.of();
        List<Object> stacks = new ArrayList<>();
        for (RelicLibraryEntry entry : visit.entries()) {
            if (!site.equals(entry.siteKey())) continue;
            Object raw = visit.stackById().get(entry.id());
            if (raw != null) stacks.add(raw);
        }
        return stacks;
    }

    /** Highlights the chests for {@link #chestStacks} through Where Is It. */
    public static void showChests(RelicLibraryVisit visit, String site) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Object raw : chestStacks(visit, site)) {
            if (raw instanceof ItemStack stack) stacks.add(stack);
        }
        RelicWhereIsIt.showChests(stacks);
    }
}
