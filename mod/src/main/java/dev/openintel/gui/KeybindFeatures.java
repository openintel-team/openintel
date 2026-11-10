package dev.openintel.gui;

import dev.openintel.feature.Feature;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Which {@link Feature} owns each OpenIntel key mapping. The Keybinds
 * settings section lists a key only while its feature is on. Pure, so
 * headless tests can check the mapping without a Minecraft client.
 */
public final class KeybindFeatures {
    private KeybindFeatures() { }

    private static final Map<String, Feature> OWNERS = Map.ofEntries(
            Map.entry("key.openintel.radar", Feature.RADAR),
            Map.entry("key.openintel.viz", Feature.VISUALIZATIONS),
            Map.entry("key.openintel.attack_macro", Feature.MACROS),
            Map.entry("key.openintel.use_macro", Feature.MACROS),
            Map.entry("key.openintel.hold_attack", Feature.MACROS),
            Map.entry("key.openintel.hold_use", Feature.MACROS),
            Map.entry("key.openintel.ice_road", Feature.MACROS),
            Map.entry("key.openintel.ping", Feature.PINGS),
            Map.entry("key.openintel.wartable", Feature.WAR_TABLE),
            Map.entry("key.openintel.investigate_prev", Feature.INVESTIGATE),
            Map.entry("key.openintel.investigate_next", Feature.INVESTIGATE),
            Map.entry("key.openintel.investigate_play", Feature.INVESTIGATE));

    /** Owning feature of a key mapping id such as {@code key.openintel.ping}; null when no feature owns it. */
    public static Feature featureFor(String keyId) {
        return keyId == null ? null : OWNERS.get(keyId);
    }

    /** Whether the Keybinds section should list this key under the given feature state. */
    public static boolean visible(String keyId, Predicate<Feature> on) {
        Feature owner = featureFor(keyId);
        return owner == null || on.test(owner);
    }

    /** Every mapped key id, for tests that check each registered key has an owner. */
    public static java.util.Set<String> keyIds() {
        return OWNERS.keySet();
    }
}
