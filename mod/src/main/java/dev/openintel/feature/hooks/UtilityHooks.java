package dev.openintel.feature.hooks;

import dev.openintel.chat.ChatChannelFilter;
import dev.openintel.feature.Feature;
import dev.openintel.feature.Features;

/** Off-hooks for this feature area. */
public final class UtilityHooks {
    private UtilityHooks() { }

    public static void register() {
        // Drop per-message channels and the visible-line cache; chat redraws unfiltered.
        Features.onDisabled(Feature.CHAT_FILTER, UtilityHooks::resetChat);
    }

    private static void resetChat() {
        ChatChannelFilter.invalidate();
        try {
            var client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.gui != null) client.gui.getChat().resetChatScroll();
        } catch (RuntimeException | LinkageError ignored) {
            // Headless tests have no client.
        }
    }
}
