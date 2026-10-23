package dev.openintel.net;

/** Scoped provenance for synchronous inbound relay processing. Never represents permissions. */
public final class RelaySource {
    public static final String LEGACY = "legacy";
    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> LEGACY);

    private RelaySource() { }

    public static String current() { return CURRENT.get(); }
    public static String normalize(String source) { return source == null ? LEGACY : source; }

    public static void run(String source, Runnable action) {
        String previous = CURRENT.get();
        CURRENT.set(normalize(source));
        try { action.run(); }
        finally { CURRENT.set(previous); }
    }
}
