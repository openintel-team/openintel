package dev.openintel.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One row of a relay {@code pos} message:
 * {@code [name, x, y, z, dim#, ageMs, reporter#, bridgeId, hops, vx?, vz?]},
 * where {@code #} indexes the message's string table {@code s}.
 * {@code observedAt} is on this client's clock (receipt time minus age).
 */
public record PositionRow(String name, double x, double y, double z, String dim, long observedAt,
                          String reporter, String bridgeId, int hops, double vx, double vz) {

    static final long MAX_AGE_MS = 60_000;

    /** Null when the row is unusable. */
    public static PositionRow parse(JsonElement element, JsonArray strings, long now) {
        if (element == null || !element.isJsonArray()) return null;
        JsonArray row = element.getAsJsonArray();
        if (row.size() < 9) return null;
        String name = row.get(0).getAsString();
        double x = row.get(1).getAsDouble(), y = row.get(2).getAsDouble(), z = row.get(3).getAsDouble();
        if (name.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        String dim = string(strings, row.get(4));
        String reporter = string(strings, row.get(6));
        if (dim == null) return null;
        double age = row.get(5).getAsDouble();
        long observedAt = Double.isFinite(age) && age >= 0 ? now - (long) Math.min(age, MAX_AGE_MS) : now;
        double vx = row.size() > 10 ? row.get(9).getAsDouble() : 0;
        double vz = row.size() > 10 ? row.get(10).getAsDouble() : 0;
        if (!Double.isFinite(vx) || !Double.isFinite(vz)) { vx = 0; vz = 0; }
        return new PositionRow(name, x, y, z, dim, observedAt, reporter == null ? "?" : reporter,
                row.get(7).getAsString(), row.get(8).getAsInt(), vx, vz);
    }

    /** The same observation as a bridge {@code position} event, for forwarding to other relays. */
    public JsonObject toPositionEvent() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "position");
        event.addProperty("name", name);
        event.addProperty("x", x);
        event.addProperty("y", y);
        event.addProperty("z", z);
        event.addProperty("dim", dim);
        event.addProperty("t", observedAt);
        event.addProperty("reporter", reporter);
        event.addProperty("vx", vx);
        event.addProperty("vz", vz);
        if (!bridgeId.isBlank()) {
            JsonObject bridge = new JsonObject();
            bridge.addProperty("id", bridgeId);
            bridge.addProperty("hops", hops);
            event.add("bridge", bridge);
        }
        return event;
    }

    private static String string(JsonArray strings, JsonElement index) {
        int i = index.getAsInt();
        return i >= 0 && i < strings.size() ? strings.get(i).getAsString() : null;
    }
}
