package dev.openintel.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which position reports go to one relay. The tracker samples every
 * tick it is allowed to (default 50 ms):
 * <ul>
 *   <li>A relay that advertises position deltas ({@code positionVersion} 2) gets
 *   a player only when they moved, changed dimension or speed, or every
 *   {@link #KEEPALIVE_MS} so the relay keeps them fresh.</li>
 *   <li>An older relay gets the whole list at most every {@link #LEGACY_INTERVAL_MS},
 *   the rate it was built for.</li>
 * </ul>
 */
public final class PositionUplink {
    public static final int POSITION_PROTOCOL = 2;
    public static final long LEGACY_INTERVAL_MS = 250;
    public static final long KEEPALIVE_MS = 1000;
    static final double MOVE_EPSILON = 0.05;
    static final double SPEED_EPSILON = 0.1;

    private record Sent(String dim, double x, double y, double z, double vx, double vz, long at) { }

    private final Map<String, Sent> sent = new HashMap<>();
    private long lastLegacy = Long.MIN_VALUE;

    /** Reports to send now (shared JSON elements, not copies), or null for nothing. */
    public JsonArray select(JsonArray reports, boolean deltas, long now) {
        if (reports == null || reports.isEmpty()) return null;
        if (!deltas) {
            if (lastLegacy != Long.MIN_VALUE && now - lastLegacy < LEGACY_INTERVAL_MS) return null;
            lastLegacy = now;
            return reports;
        }
        JsonArray out = new JsonArray();
        Set<String> present = new HashSet<>();
        for (JsonElement element : reports) {
            if (!element.isJsonObject()) continue;
            JsonObject report = element.getAsJsonObject();
            String subject = string(report, "subject");
            if (subject == null) continue;
            present.add(subject);
            Sent next = new Sent(string(report, "dim"), number(report, "x"), number(report, "y"), number(report, "z"),
                    number(report, "vx"), number(report, "vz"), now);
            Sent last = sent.get(subject);
            if (last == null || changed(last, next) || now - last.at() >= KEEPALIVE_MS) {
                out.add(report);
                sent.put(subject, next);
            }
        }
        // Someone who left view is sent again in full as soon as they come back.
        sent.keySet().retainAll(present);
        return out.isEmpty() ? null : out;
    }

    public void reset() {
        sent.clear();
        lastLegacy = Long.MIN_VALUE;
    }

    private static boolean changed(Sent a, Sent b) {
        return !java.util.Objects.equals(a.dim(), b.dim())
                || Math.abs(a.x() - b.x()) > MOVE_EPSILON || Math.abs(a.y() - b.y()) > MOVE_EPSILON
                || Math.abs(a.z() - b.z()) > MOVE_EPSILON
                || Math.abs(a.vx() - b.vx()) > SPEED_EPSILON || Math.abs(a.vz() - b.vz()) > SPEED_EPSILON;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static double number(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
