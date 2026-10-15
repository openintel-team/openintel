package dev.openintel.mixin;

import dev.openintel.relic.RelicLibraryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import red.jackf.chesttracker.impl.gui.screen.ChestTrackerScreen;
import red.jackf.chesttracker.impl.gui.widget.ItemButton;

/**
 * Adds a Relic maps button under Chest Tracker's dimension-key column.
 */
@Mixin(ChestTrackerScreen.class)
public abstract class ChestTrackerScreenMixin extends Screen {
    private static final int MEMORY_ICON_OFFSET = 24;
    private static final int MEMORY_ICON_SPACING = 24;

    @Shadow private int left;
    @Shadow private int top;

    protected ChestTrackerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void openintel$relicButton(CallbackInfo ci) {
        int keyButtons = 0;
        for (var child : this.children()) {
            if (child instanceof ItemButton button
                    && button.getX() == this.left - MEMORY_ICON_OFFSET) {
                keyButtons++;
            }
        }
        Screen self = this;
        ItemButton relic = new ItemButton(
                new ItemStack(Items.FILLED_MAP),
                this.left - MEMORY_ICON_OFFSET,
                this.top + keyButtons * MEMORY_ICON_SPACING,
                button -> Minecraft.getInstance().gui.setScreen(new RelicLibraryScreen(self)),
                ItemButton.Background.CUSTOM);
        relic.setTooltip(Tooltip.create(Component.translatable("openintel.relic.library.button")));
        this.addRenderableWidget(relic);
    }
}
