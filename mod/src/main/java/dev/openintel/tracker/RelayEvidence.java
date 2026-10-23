package dev.openintel.tracker;

import com.google.gson.JsonObject;

/** Receipt-time fallback for legacy relays, without renewing timestamped evidence. */
public final class RelayEvidence {
    private RelayEvidence() { }

    public static long timestamp(JsonObject message, long now) {
        try {
            if (!message.has("t") || !message.get("t").isJsonPrimitive()
                    || !message.getAsJsonPrimitive("t").isNumber()) return now;
            double value = message.get("t").getAsDouble();
            return Double.isFinite(value) && value >= 0 && value <= now ? (long) value : now;
        } catch (RuntimeException invalid) {
            return now;
        }
    }

    public static long expiry(JsonObject message, long now, long defaultTtl) {
        long ttl = defaultTtl;
        try {
            if (message.has("ttl") && message.get("ttl").isJsonPrimitive()
                    && message.getAsJsonPrimitive("ttl").isNumber()) {
                double value = message.get("ttl").getAsDouble();
                if (Double.isFinite(value) && value >= 0 && value <= Long.MAX_VALUE) ttl = (long) value;
            }
        } catch (RuntimeException invalid) { /* Legacy default for malformed durations. */ }
        long time = timestamp(message, now);
        return ttl > Long.MAX_VALUE - time ? Long.MAX_VALUE : time + ttl;
    }
}
