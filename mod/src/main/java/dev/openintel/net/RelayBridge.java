package dev.openintel.net;

import com.google.gson.JsonObject;

/** Explicit bridge allowlist. Control/permission frames are never portable between relays. */
public final class RelayBridge {
    private RelayBridge() { }
    public static final int MAX_HOPS = 8;

    public static java.util.List<JsonObject> events(JsonObject message) {
        var events = new java.util.ArrayList<JsonObject>();
        String type = message.has("type") ? message.get("type").getAsString() : "";
        if (type.equals("state") && message.has("players") && message.get("players").isJsonArray()) {
            for (var value : message.getAsJsonArray("players")) {
                if (!value.isJsonObject()) continue;
                JsonObject event = value.getAsJsonObject().deepCopy();
                event.addProperty("type", "position");
                events.add(event);
            }
        } else if (type.equals("wartable_sync") && message.has("strokes") && message.get("strokes").isJsonArray()) {
            for (var value : message.getAsJsonArray("strokes")) {
                if (!value.isJsonObject() || !value.getAsJsonObject().has("bridge")) continue;
                JsonObject event = new JsonObject();
                event.addProperty("type", "wartable");
                event.addProperty("action", "add");
                event.add("stroke", value.deepCopy());
                event.add("bridge", value.getAsJsonObject().get("bridge").deepCopy());
                if (value.getAsJsonObject().has("t")) event.add("t", value.getAsJsonObject().get("t").deepCopy());
                events.add(event);
            }
        } else events.add(message);
        return events;
    }

    public static JsonObject forward(JsonObject incoming, String destinationRole) {
        try {
            String type = incoming.get("type").getAsString();
            if (!type.equals("snitch") && !type.equals("ping") && !type.equals("where")
                    && !type.equals("wartable") && !type.equals("position")) return null;
            if (type.equals("wartable")) {
                if (!"admin".equalsIgnoreCase(destinationRole)) return null;
                String action = incoming.has("action") ? incoming.get("action").getAsString() : "";
                if (!action.equals("add") && !action.equals("delete")) return null;
            }
            if (!incoming.has("bridge") || !incoming.get("bridge").isJsonObject()) return null;
            JsonObject bridge = incoming.getAsJsonObject("bridge");
            String id = bridge.get("id").getAsString();
            double suppliedHops = bridge.get("hops").getAsDouble();
            int hops = bridge.get("hops").getAsInt();
            if (id.isBlank() || id.length() > 160 || id.chars().anyMatch(Character::isISOControl)
                    || suppliedHops != hops || hops < 0 || hops >= MAX_HOPS) return null;
            JsonObject result = new JsonObject();
            result.addProperty("type", "bridge");
            result.add("event", incoming.deepCopy());
            return result;
        } catch (RuntimeException malformed) { return null; }
    }
}
