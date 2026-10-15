package dev.openintel.relic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.criteria.Criterion;
import red.jackf.whereisit.api.criteria.builtin.AllOfCriterion;
import red.jackf.whereisit.api.criteria.builtin.AnyOfCriterion;
import red.jackf.whereisit.api.criteria.builtin.ComponentsCriterion;
import red.jackf.whereisit.api.criteria.builtin.ItemCriterion;
import red.jackf.whereisit.client.api.events.SearchInvoker;

import java.util.ArrayList;
import java.util.List;

/** Starts a Where Is It search for exact stored treasure-map stacks. */
public final class RelicWhereIsIt {
    private RelicWhereIsIt() { }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("whereisit")
                || FabricLoader.getInstance().isModLoaded("chesttracker");
    }

    /**
     * Highlight chests that contain any of the selected stacks. Uses the same
     * precise component match Chest Tracker uses for a normal inventory click.
     * Returns whether a search was started.
     */
    public static boolean showChests(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty() || !available()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return false;

        List<Criterion> matchers = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            List<Criterion> one = new ArrayList<>();
            one.add(new ItemCriterion(stack.getItem()));
            one.add(new ComponentsCriterion(stack.getComponentsPatch()));
            matchers.add(new AllOfCriterion(one).compact());
        }
        if (matchers.isEmpty()) return false;

        SearchRequest request = new SearchRequest();
        request.accept(new AnyOfCriterion(matchers).compact());
        if (!request.hasCriteria()) return false;

        client.gui.setScreen(null);
        return SearchInvoker.doSearch(request);
    }
}
