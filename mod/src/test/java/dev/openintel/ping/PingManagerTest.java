package dev.openintel.ping;

import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.gui.VisualizationsInteractionTest;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Inbound pings ignore a missing id, keep the original expiry, and replace a player's where mark. */
public final class PingManagerTest {
    public static void main(String[] args) throws Exception {
        Object previousClient = installClient();
        Object previousConfig = staticField("config");
        try {
            OIConfig config = new OIConfig();
            config.pingSeconds = 45;
            setStatic("config", config);
            PingManager.clear();

            JsonObject missingId = located("Loot", 1, 2, 3);
            PingManager.receive(missingId);
            equal(0, active().size());

            JsonObject ping = located("Loot", 4, 5, 6);
            ping.addProperty("id", "ping-1");
            ping.addProperty("t", 1000);
            ping.addProperty("from", "Ada");
            PingManager.receive(ping);
            PingManager.Ping first = only();
            equal(0xFFFFAA00, first.color);
            equal(1000L + 45_000L, first.expiresAt);
            PingManager.receive(ping);
            PingManager.Ping replayed = only();
            equal("ping-1", replayed.id);
            equal(1000L + 45_000L, replayed.expiresAt);

            PingManager.clear();
            JsonObject steve = where("Steve", 1000, 10);
            PingManager.receiveWhere(steve);
            steve.addProperty("t", 2000);
            steve.addProperty("x", 99);
            PingManager.receiveWhere(steve);
            PingManager.receiveWhere(where("Pat", 1500, 3));
            List<PingManager.Ping> marks = active();
            equal(2, marks.size());
            PingManager.Ping steveMark = find("where-steve-2000");
            PingManager.Ping patMark = find("where-pat-1500");
            if (steveMark == null || patMark == null) throw new AssertionError(ids(marks));
            equal(99.0, steveMark.x);
            equal(3.0, patMark.x);
            if (find("where-steve-1000") != null) throw new AssertionError("Previous where mark kept");
        } finally {
            PingManager.clear();
            setStatic("config", previousConfig);
            restoreClient(previousClient);
        }
        System.out.println("PingManagerTest passed");
    }

    private static JsonObject located(String label, double x, double y, double z) {
        JsonObject msg = new JsonObject();
        msg.addProperty("label", label);
        msg.addProperty("x", x);
        msg.addProperty("y", y);
        msg.addProperty("z", z);
        return msg;
    }

    private static JsonObject where(String player, long t, double x) {
        JsonObject msg = new JsonObject();
        msg.addProperty("player", player);
        msg.addProperty("t", t);
        msg.addProperty("x", x);
        msg.addProperty("y", 64);
        msg.addProperty("z", 20);
        msg.addProperty("dim", "minecraft:overworld");
        return msg;
    }

    private static List<PingManager.Ping> active() {
        List<PingManager.Ping> pings = new ArrayList<>();
        PingManager.active().forEach(pings::add);
        return pings;
    }

    private static PingManager.Ping only() {
        List<PingManager.Ping> pings = active();
        if (pings.size() != 1) throw new AssertionError("Expected one ping, got " + ids(pings));
        return pings.getFirst();
    }

    private static PingManager.Ping find(String id) {
        for (PingManager.Ping ping : active()) if (id.equals(ping.id)) return ping;
        return null;
    }

    private static String ids(List<PingManager.Ping> pings) {
        return pings.stream().map(ping -> ping.id).toList().toString();
    }

    private static Object installClient() throws Exception {
        var instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        Object client = allocate(VisualizationsInteractionTest.FocusOnlyClient.class);
        var gameThread = Minecraft.class.getDeclaredField("gameThread");
        gameThread.setAccessible(true);
        gameThread.set(client, Thread.currentThread());
        instance.set(null, client);
        if (!Minecraft.getInstance().isSameThread()) throw new AssertionError("client thread");
        return previous;
    }

    private static void restoreClient(Object previous) throws Exception {
        var instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, previous);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var unsafeType = Class.forName("sun.misc.Unsafe");
        var field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static Object staticField(String name) throws Exception {
        var field = OpenIntelClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStatic(String name, Object value) throws Exception {
        var field = OpenIntelClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
