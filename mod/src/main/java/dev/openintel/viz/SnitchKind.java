package dev.openintel.viz;

/**
 * Snitch vs Logging Snitch is chosen by the player. Civ+ snitches are usually
 * disguised, so the block type is not a reliable signal.
 */
public enum SnitchKind {
    /** Persisted name retained for compatibility; displayed as Snitch. */
    NOTIFIER,
    LOGGING;

    public SnitchKind next() {
        return this == NOTIFIER ? LOGGING : NOTIFIER;
    }

    public static SnitchKind byName(String name) {
        if (name == null) return NOTIFIER;
        for (SnitchKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) return kind;
        }
        return NOTIFIER;
    }
}
