package dev.openintel.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.OpenIntelClient;
import dev.openintel.chat.ChatChannel;
import dev.openintel.chat.ChatChannelBar;
import dev.openintel.config.OIConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Channel chips on the vanilla chat screen, pinned to the top of the focused
 * chat box. The name toggles that category in the open box. The bell toggles
 * whether new lines of that type fade in while chat is closed.
 */
@Mixin(ChatScreen.class)
public abstract class ChatChannelBarMixin extends Screen {
    protected ChatChannelBarMixin(Component title) {
        super(title);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void openintel$drawChannelBar(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        OIConfig config = OpenIntelClient.config();
        if (config == null || this.font == null) return;
        List<ChatChannelBar.Chip> chips = openintel$chips();
        for (ChatChannelBar.Chip chip : chips) {
            boolean on = config.views(chip.channel());
            boolean hover = chip.plate().contains(mouseX, mouseY);
            int x0 = chip.plate().x();
            int y0 = chip.plate().y();
            int x1 = x0 + chip.plate().width();
            int y1 = y0 + chip.plate().height();
            int fill = on ? (hover ? 0xF0242E3A : 0xE01A2430) : (hover ? 0xD0141A22 : 0xC0101418);
            graphics.fill(x0, y0, x1, y1, fill);
            if (on) graphics.fill(x0, y1 - 1, x1, y1, 0xFFD5DCE6);
            int textY = y0 + (chip.plate().height() - this.font.lineHeight) / 2;
            graphics.drawString(this.font, ChatChannelBar.label(chip.channel()), chip.name().x(), textY,
                    on ? 0xFFF4F7FB : 0xFF9AA3B0, false);
            openintel$bell(graphics, chip.bell(), config.showsNew(chip.channel()));
        }
    }

    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true)
    private void openintel$channelClick(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        OIConfig config = OpenIntelClient.config();
        if (config == null || this.font == null) return;
        ChatChannelBar.Target target = ChatChannelBar.hit(openintel$chips(), event.x(), event.y());
        if (target == null) return;
        if (target.hit() == ChatChannelBar.Hit.CHANNEL) {
            switch (target.chatChannel()) {
                case CHAT -> config.chatViewChat = !config.chatViewChat;
                case SNITCHES -> config.chatViewSnitches = !config.chatViewSnitches;
                case MINING -> config.chatViewMining = !config.chatViewMining;
            }
            config.save();
            Minecraft.getInstance().gui.getChat().resetChatScroll();
        } else {
            switch (target.chatChannel()) {
                case CHAT -> config.chatShowChat = !config.chatShowChat;
                case SNITCHES -> config.chatShowSnitches = !config.chatShowSnitches;
                case MINING -> config.chatShowMining = !config.chatShowMining;
            }
            config.save();
        }
        cir.setReturnValue(true);
    }

    @Unique
    private List<ChatChannelBar.Chip> openintel$chips() {
        var chat = Minecraft.getInstance().gui.getChat();
        double scale = this.minecraft.options.chatScale().get();
        double spacing = this.minecraft.options.chatLineSpacing().get();
        int row = ChatChannelBar.rowY(this.height, scale, spacing, chat.getLinesPerPage(), openintel$chatShift());
        return ChatChannelBar.layout(this.width, row, this.font::width);
    }

    /** ChatPatches moves the box up by this many chat-scaled pixels. */
    @Unique
    private static int openintel$chatShift() {
        if (!FabricLoader.getInstance().isModLoaded("chatpatches")) return 0;
        try {
            Class<?> type = Class.forName("obro1961.chatpatches.ChatPatches");
            Object config = type.getField("config").get(null);
            if (config == null) return 0;
            Object shift = config.getClass().getMethod("calcDynamicChatShift").invoke(config);
            return shift instanceof Integer value ? value : 0;
        } catch (ReflectiveOperationException ignored) {
            return 0;
        }
    }

    @Unique
    private static void openintel$bell(GuiGraphics graphics, ChatChannelBar.Bounds bell, boolean ringing) {
        int color = ringing ? 0xFFF4F7FB : 0xFF8E97A6;
        for (int y = 0; y < bell.height(); y++) {
            for (int x = 0; x < bell.width(); x++) {
                if (ChatChannelBar.bellPixel(ringing, x, y)) {
                    graphics.fill(bell.x() + x, bell.y() + y, bell.x() + x + 1, bell.y() + y + 1, color);
                }
            }
        }
    }
}
