package dev.openintel.feature.hooks;

/** Registers subsystem off-hooks. Each area keeps its own class. */
public final class FeatureHooks {
    private FeatureHooks() { }

    public static void registerAll() {
        IntelHooks.register();
        VisualHooks.register();
        UtilityHooks.register();
    }
}
