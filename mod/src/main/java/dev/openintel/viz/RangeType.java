package dev.openintel.viz;

/** Civ+ coverage field kind for in-world visualizations. */
public enum RangeType {
    SNITCH,
    BASTION,
    SANCTUARY;

    public static RangeType byName(String name) {
        if (name == null) return SNITCH;
        for (RangeType t : values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return SNITCH;
    }
}
