package dev.openintel.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.config.OIConfig;
import dev.openintel.tracker.MotionEstimate;
import dev.openintel.tracker.PositionSampler;

/**
 * Headless checks for live positions: what each relay is sent, delta rows,
 * forwarding, velocity sampling, marker projection and blending, and the
 * upload-rate config migration. No sockets or Minecraft instance.
 */
public final class PositionPipelineTest {
    public static void main(String[] args) {
        uplink();
        rows();
        sampler();
        motion();
        config();
        System.out.println("PositionPipelineTest passed");
    }

    private static void uplink() {
        PositionUplink delta = new PositionUplink();
        JsonArray first = reports(report("Self", 0, 0, 5, 0), report("Other", 100, 0, 0, 0));
        assert size(delta.select(first, true, 1000)) == 2 : "first sample sends everyone";
        assert delta.select(reports(report("Self", 0.01, 0, 5, 0), report("Other", 100, 0, 0, 0)), true, 1050) == null
                : "nothing moved enough: nothing sent";
        JsonArray moved = delta.select(reports(report("Self", 0.3, 0, 5, 0), report("Other", 100, 0, 0, 0)), true, 1100);
        assert size(moved) == 1 && name(moved, 0).equals("Self") : "only the mover is sent";
        JsonArray stopped = delta.select(reports(report("Self", 0.3, 0, 0, 0), report("Other", 100, 0, 0, 0)), true, 1150);
        assert size(stopped) == 1 && name(stopped, 0).equals("Self") : "stopping (velocity change) is sent at once";
        JsonArray keepalive = delta.select(reports(report("Self", 0.3, 0, 0, 0), report("Other", 100, 0, 0, 0)), true, 2000);
        assert size(keepalive) == 1 && name(keepalive, 0).equals("Other") : "idle players are refreshed every second";
        delta.select(reports(report("Self", 0.3, 0, 0, 0)), true, 2050);
        assert size(delta.select(reports(report("Self", 0.3, 0, 0, 0), report("Other", 100, 0, 0, 0)), true, 2100)) == 1
                : "a player who left view is sent in full on return";
        JsonArray dim = reports(report("Self", 0.3, 0, 0, 0));
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
        assert row.vx() == 4.5 && row.vz() == -2 && row.hops() == 1 && row.bridgeId().equals("obs-1");
        PositionRow still = PositionRow.parse(pos.getAsJsonArray("p").get(1), pos.getAsJsonArray("s"), now);
        assert still.vx() == 0 && still.vz() == 0 : "velocity is optional";
        assert PositionRow.parse(pos.getAsJsonArray("p").get(2), pos.getAsJsonArray("s"), now) == null;
        assert PositionRow.parse(pos.getAsJsonArray("p").get(3), pos.getAsJsonArray("s"), now) == null;

        var events = RelayBridge.events(pos);
        assert events.size() == 2 : "valid rows become forwardable position events";
        JsonObject event = events.getFirst();
        assert event.get("type").getAsString().equals("position") && event.get("name").getAsString().equals("Target");
        assert event.getAsJsonObject("bridge").get("id").getAsString().equals("obs-1");
        assert event.get("vx").getAsDouble() == 4.5;
        assert RelayBridge.forward(event, "member") != null : "pos rows forward like full-list entries";
    }

    private static void sampler() {
        PositionSampler sampler = new PositionSampler();
        double[] v = sampler.sample("A", "minecraft:overworld", 0, 0, 0);
        assert v[0] == 0 && v[1] == 0 : "first sample has no velocity";
        for (int i = 1; i <= 10; i++) v = sampler.sample("A", "minecraft:overworld", i * 0.28, 0, i * 50L);
        assert Math.abs(v[0] - 5.6) < 0.05 && v[1] == 0 : "steady sprint converges to 5.6 blocks/s, got " + v[0];
        v = sampler.sample("A", "minecraft:overworld", 2.8, 0, 550);
        assert v[0] == 0 : "a player who did not move has stopped";
        sampler.sample("A", "minecraft:overworld", 3.08, 0, 600);
        v = sampler.sample("A", "minecraft:overworld", 500, 0, 650);
        assert v[0] == 0 : "a teleport restarts the estimate";
        v = sampler.sample("A", "minecraft:the_nether", 501, 0, 700);
        assert v[0] == 0 : "a dimension change restarts the estimate";
        v = sampler.sample("A", "minecraft:the_nether", 501.5, 0, 700);
        assert v[0] == 0 : "same-millisecond samples are ignored";
        sampler.endRound();
        sampler.sample("B", "minecraft:overworld", 0, 0, 800);
        sampler.endRound();
        v = sampler.sample("A", "minecraft:the_nether", 502, 0, 850);
        assert v[0] == 0 : "players who left view are forgotten";
    }

    private static void motion() {
        MotionEstimate m = new MotionEstimate();
        m.update(0, 64, 0, "minecraft:overworld", 10, 0, 1000, 1100);
        double[] at = m.at(1100);
        assert close(at[0], 1.0) && at[1] == 64 : "projects from observation time: 100 ms at 10 b/s, got " + at[0];
        assert close(m.at(1300)[0], 3.0);
        assert close(m.at(5000)[0], 5.0) : "projection stops at 500 ms";
        // A new report that disagrees with what was drawn eases over instead of jumping.
        double[] before = m.at(1200);
        m.update(1.0, 64, 0, "minecraft:overworld", 10, 0, 1150, 1200);
        double[] after = m.at(1200);
        assert close(after[0], before[0]) : "no visible jump at the moment of the update";
        assert close(m.at(1275)[0], 2.25 + 0.25) : "half way through the blend, half the correction remains";
        assert close(m.at(1200 + MotionEstimate.BLEND_MS)[0], 1.0 + 10 * (50 + MotionEstimate.BLEND_MS) / 1000.0)
                : "blend finishes on the new estimate";
        m.update(100, 64, 0, "minecraft:overworld", 0, 0, 1400, 1400);
        assert close(m.at(1400)[0], 100) : "far corrections snap";
        m.update(100.5, 70, 0, "minecraft:the_nether", 0, 0, 1450, 1450);
        assert close(m.at(1450)[0], 100.5) && m.at(1450)[1] == 70 : "dimension change snaps";
        m.update(100.5, 70, 0, "minecraft:the_nether", 0, 0, 1500, 1500);
        assert close(m.at(9000)[0], 100.5) : "no velocity, no drift";
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

    private static JsonObject report(String subject, double x, double z, double vx, double vz) {
        JsonObject o = new JsonObject();
        o.addProperty("subject", subject);
        o.addProperty("x", x);
        o.addProperty("y", 64);
        o.addProperty("z", z);
        o.addProperty("dim", "minecraft:overworld");
        o.addProperty("vx", vx);
        o.addProperty("vz", vz);
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
