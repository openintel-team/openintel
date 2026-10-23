package dev.openintel.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Headless protocol checks; no sockets or Minecraft instance required. */
public final class RelayBridgeTest {
    public static void main(String[] args) {
        JsonObject alert = json("{\"type\":\"snitch\",\"player\":\"Example\",\"bridge\":{\"id\":\"origin-event\",\"hops\":1}}");
        assert RelayBridge.forward(alert, "member") != null : "Members may forward snitch intel";
        assert RelayBridge.forward(json("{\"type\":\"snitch\"}"), "admin") == null : "Legacy intel cannot be safely bridged";
        assert RelayBridge.forward(json("{\"type\":\"intel_reset\",\"bridge\":{\"id\":\"reset\",\"hops\":0}}"), "admin") == null : "Never forward resets";
        assert RelayBridge.forward(json("{\"type\":\"allegiances\",\"bridge\":{\"id\":\"roles\",\"hops\":0}}"), "admin") == null : "Never forward roles";
        assert RelayBridge.forward(json("{\"type\":\"snitch\",\"bridge\":{\"id\":\"old\",\"hops\":8}}"), "admin") == null : "Bound forwarding hops";
        assert RelayBridge.forward(json("{\"type\":\"wartable\",\"action\":\"add\",\"bridge\":{\"id\":\"stroke\",\"hops\":0}}"), "member") == null : "Destination admin permission required";
        assert alert.getAsJsonObject("bridge").get("hops").getAsInt() == 1 : "Forwarding never mutates input";
        assert RelayBridge.forward(json("{\"type\":\"wartable\",\"action\":\"clear\",\"bridge\":{\"id\":\"clear\",\"hops\":0}}"), "admin") == null : "Do not propagate destructive whole-relay clears";
        var state = json("{\"type\":\"state\",\"players\":[{\"name\":\"Example\",\"t\":123,\"bridge\":{\"id\":\"position\",\"hops\":0}}]}");
        var events = RelayBridge.events(state);
        assert events.size() == 1 && events.getFirst().get("type").getAsString().equals("position");
        assert events.getFirst().get("t").getAsLong() == 123 : "Preserve observation time";
        assert RelayBridge.forward(events.getFirst(), "member") != null;
        assert state.getAsJsonArray("players").get(0).getAsJsonObject().get("type") == null : "Do not mutate source snapshot";
        for (String type : java.util.List.of("positions", "snitch", "ping", "wartable", "cut", "focus", "bridge")) {
            assert !RelayCoordinator.allowsSend(true, false, true, "admin", type) : "Receive-only blocks " + type;
            assert !RelayCoordinator.allowsSend(false, true, true, "admin", type) : "Disabled blocks " + type;
            assert !RelayCoordinator.allowsSend(true, true, false, "admin", type) : "Unauthenticated blocks " + type;
        }
        assert RelayCoordinator.allowsSend(true, true, true, "member", "positions");
        assert !RelayCoordinator.allowsSend(true, true, true, "member", "wartable");
        assert !RelayCoordinator.allowsSend(true, true, true, "operator", "cut");
        assert RelayCoordinator.allowsSend(true, true, true, "admin", "wartable");
        assert RelayCoordinator.allowsSend(true, true, true, "operator", "focus");
        assert !RelayCoordinator.allowsSend(true, true, true, "admin", "hello") : "No raw handshake through sharing facade";
        System.out.println("RelayBridgeTest passed");
    }
    private static JsonObject json(String input) { return JsonParser.parseString(input).getAsJsonObject(); }
}
