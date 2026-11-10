package dev.openintel.feature.hooks;

import dev.openintel.OpenIntelClient;
import dev.openintel.SnitchRelay;
import dev.openintel.feature.Feature;
import dev.openintel.feature.Features;
import dev.openintel.ping.PingManager;
import dev.openintel.render.EventFeed;

/** Off-hooks for the Intel & Relay features. Turning a feature back on starts fresh. */
public final class IntelHooks {
    private IntelHooks() { }

    public static void register() {
        Features.onDisabled(Feature.SNITCH_ALERTS, () -> {
            SnitchRelay.reset();
            OpenIntelClient.tracker().clearSnitchHits();
        });
        Features.onDisabled(Feature.PINGS, PingManager::clear);
        Features.onDisabled(Feature.EVENT_FEED, EventFeed::clear);
    }
}
