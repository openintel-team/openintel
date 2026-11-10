package dev.openintel.feature;

import dev.openintel.config.OIConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime view of {@link Feature} switches. Subsystems ask {@link #isOn}
 * before doing any work and register an off-hook that drops their state when
 * the feature is switched off.
 */
public final class Features {
    private Features() { }

    private static volatile Supplier<OIConfig> config = () -> null;
    private static volatile Predicate<String> modLoaded = mod -> true;
    private static final Map<Feature, List<Runnable>> OFF_HOOKS = new EnumMap<>(Feature.class);

    /** Wired by the client entrypoint; tests pass their own config. */
    public static void bind(Supplier<OIConfig> source, Predicate<String> modCheck) {
        config = source;
        modLoaded = modCheck;
    }

    public static boolean isOn(Feature feature) {
        return isOn(config.get(), feature);
    }

    /** True when the switch, its parents, the relay (if needed) and the mod (if any) are all on. */
    public static boolean isOn(OIConfig cfg, Feature feature) {
        if (cfg == null) return feature.defaultOn;
        for (Feature f = feature; f != null; f = f.parent) {
            if (!f.get(cfg)) return false;
            if (f.needsRelay && !Feature.RELAY.get(cfg)) return false;
            if (f.requiredMod != null && !modLoaded.test(f.requiredMod)) return false;
        }
        return true;
    }

    /** Why a stored-on feature is still inactive, as a translation key; null when nothing blocks it. */
    public static String blockedReason(OIConfig cfg, Feature feature) {
        for (Feature f = feature; f != null; f = f.parent) {
            if (f.requiredMod != null && !modLoaded.test(f.requiredMod)) return "openintel.features.not_installed";
            if (f.needsRelay && !Feature.RELAY.get(cfg)) return "openintel.features.needs_relay";
            if (f != feature && !f.get(cfg)) return "openintel.features.parent_off";
        }
        return null;
    }

    public static boolean modLoaded(String mod) { return modLoaded.test(mod); }

    public static void onDisabled(Feature feature, Runnable hook) {
        synchronized (OFF_HOOKS) {
            OFF_HOOKS.computeIfAbsent(feature, f -> new CopyOnWriteArrayList<>()).add(hook);
        }
    }

    /** Effective on/off for every feature, for diffing before and after an edit. */
    public static EnumMap<Feature, Boolean> snapshot(OIConfig cfg) {
        EnumMap<Feature, Boolean> out = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) out.put(f, isOn(cfg, f));
        return out;
    }

    /** Stored switch values, used to restore on Cancel. */
    public static EnumMap<Feature, Boolean> stored(OIConfig cfg) {
        EnumMap<Feature, Boolean> out = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) out.put(f, f.get(cfg));
        return out;
    }

    public static void restore(OIConfig cfg, Map<Feature, Boolean> stored) {
        stored.forEach((f, v) -> f.set(cfg, v));
    }

    public static void resetDefaults(OIConfig cfg) {
        for (Feature f : Feature.values()) f.set(cfg, f.defaultOn);
    }

    /** Runs off-hooks for every feature that was effectively on before and is off now. Returns them. */
    public static List<Feature> applyChanges(Map<Feature, Boolean> before, OIConfig cfg) {
        List<Feature> turnedOff = new ArrayList<>();
        for (Feature f : Feature.values()) {
            if (Boolean.TRUE.equals(before.get(f)) && !isOn(cfg, f)) turnedOff.add(f);
        }
        for (Feature f : turnedOff) runOffHooks(f);
        return turnedOff;
    }

    public static void runOffHooks(Feature feature) {
        List<Runnable> hooks;
        synchronized (OFF_HOOKS) {
            hooks = OFF_HOOKS.getOrDefault(feature, List.of());
        }
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger("OpenIntel").warn("Feature off-hook failed for {}", feature, e);
            }
        }
    }

    /** Test-only: drop registered hooks. */
    static void clearHooksForTest() {
        synchronized (OFF_HOOKS) {
            OFF_HOOKS.clear();
        }
    }
}
