package dev.openintel.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.config.OIConfig;

/**
 * Headless checks for live positions: what each relay is sent, delta rows,
 * forwarding, and the
 * upload-rate config migration. No sockets or Minecraft instance.
 */
public final class PositionPipelineTest {
    public static void main(String[] args) {
        uplink();
        rows();
        config();
        System.out.println("PositionPipelineTest passed");
    }

    private static void uplink() {
        PositionUplink delta = new PositionUplink();
        JsonArray first = reports(report("Self", 0, 0), report("Other", 100, 0));
        assert size(delta.select(first, true, 1000)) == 2 : "first sample sends everyone";
        assert delta.select(reports(report("Self", 0.01, 0), report("Other", 100, 0)), true, 1050) == null
                : "nothing moved enough: nothing sent";
        JsonArray moved = delta.select(reports(report("Self", 0.3, 0), report("Other", 100, 0)), true, 1100);
        assert size(moved) == 1 && name(moved, 0).equals("Self") : "only the mover is sent";
        assert delta.select(reports(report("Self", 0.3, 0), report("Other", 100, 0)), true, 1150) == null
                : "a player who stopped is not resent until the keepalive";
        JsonArray keepalive = delta.select(reports(report("Self", 0.3, 0), report("Other", 100, 0)), true, 2000);
        assert size(keepalive) == 1 && name(keepalive, 0).equals("Other") : "idle players are refreshed every second";
        delta.select(reports(report("Self", 0.3, 0)), true, 2050);
        assert size(delta.select(reports(report("Self", 0.3, 0), report("Other", 100, 0)), true, 2090)) == 1
                : "a player who left view is sent in full on return";
        JsonArray dim = reports(report("Self", 0.3, 0));
        dim.get(0).getAsJsonObject().addProperty("dim", "minecraft:the_nether");
        assert size(delta.select(dim, true, 2150)) == 1 : "dimension change is sent";

        PositionUplink legacy = new PositionUplink();
        assert size(legacy.select(first, false, 0)) == 2 : "older relay: whole list";
        assert legacy.select(first, false, 200) == null : "older relay: no faster than 250 ms";
        assert size(legacy.select(first, false, 250)) == 2;
        legacy.reset();
        assert size(legacy.select(first, false, 260)) == 2 : "reset after a new welcome";
        assert RelayCoordinator.shortId().length() == 12 : "short observation IDs";
        assert !RelayCoordinator.shortId().equals(RelayCoordinator.shortId());
    }

    private static void rows() {
        JsonObject pos = JsonParser.parseString("{\"type\":\"pos\",\"s\":[\"minecraft:overworld\",\"Scout\"],"
                + "\"p\":[[\"Target\",10.5,64,-3.25,0,120,1,\"obs-1\",1,4.5,-2],"
                + "[\"Still\",1,2,3,0,0,1,\"obs-2\",0],[\"Broken\",1,2],[\"BadDim\",1,2,3,9,0,1,\"x\",0]]}").getAsJsonObject();
        long now = 50_000;
        PositionRow row = PositionRow.parse(pos.getAsJsonArray("p").get(0), pos.getAsJsonArray("s"), now);
        assert row != null && row.name().equals("Target") && row.x() == 10.5 && row.z() == -3.25;
        assert row.dim().equals("minecraft:overworld") && row.reporter().equals("Scout");
        assert row.observedAt() == now - 120 : "age becomes a local observation time";
        assert row.hops() == 1 && row.bridgeId().equals("obs-1") : "values past the ninth (an older relay's) are ignored";
        PositionRow still = PositionRow.parse(pos.getAsJsonArray("p").get(1), pos.getAsJsonArray("s"), now);
        assert still != null && still.name().equals("Still") && still.hops() == 0;
        assert PositionRow.parse(pos.getAsJsonArray("p").get(2), pos.getAsJsonArray("s"), now) == null;
        assert PositionRow.parse(pos.getAsJsonArray("p").get(3), pos.getAsJsonArray("s"), now) == null;

        var events = RelayBridge.events(pos);
        assert events.size() == 2 : "valid rows become forwardable position events";
        JsonObject event = events.getFirst();
        assert event.get("type").getAsString().equals("position") && event.get("name").getAsString().equals("Target");
        assert event.getAsJsonObject("bridge").get("id").getAsString().equals("obs-1");
        assert !event.has("vx") && !event.has("vz") : "forwarded positions carry no velocity";
        assert RelayBridge.forward(event, "member") != null : "pos rows forward like full-list entries";
    }

    private static void config() {
        OIConfig c = new OIConfig();
        assert c.reportIntervalMs == 50 : "new configs sample every tick";
        c.reportIntervalMs = 250;
        assert OIConfig.applyReportIntervalDefault(c, true) && c.reportIntervalMs == 50 : "old saved default moves to 50";
        c.reportIntervalMs = 500;
        assert !OIConfig.applyReportIntervalDefault(c, true) && c.reportIntervalMs == 500 : "a chosen rate is kept";
        c.reportIntervalMs = 10;
        assert OIConfig.applyReportIntervalDefault(c, true) && c.reportIntervalMs == 50 : "never faster than a tick";
        c.reportIntervalMs = 0;
        assert OIConfig.applyReportIntervalDefault(c, false) && c.reportIntervalMs == 50 : "missing key gets the default";
    }

    private static JsonObject report(String subject, double x, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("subject", subject);
        o.addProperty("x", x);
        o.addProperty("y", 64);
        o.addProperty("z", z);
        o.addProperty("dim", "minecraft:overworld");
        return o;
    }

    private static JsonArray reports(JsonObject... items) {
        JsonArray a = new JsonArray();
        for (JsonObject item : items) a.add(item);
        return a;
    }

    private static int size(JsonArray a) { return a == null ? 0 : a.size(); }
    private static String name(JsonArray a, int i) { return a.get(i).getAsJsonObject().get("subject").getAsString(); }
    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-6; }
}
