package dev.openintel.tracker;

import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager;
import dev.openintel.gui.VisualizationsInteractionTest;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Snitch-name inference, tripper dimension precedence, and friend-marker suppression. */
public final class TrackerSnitchIntelTest {
    public static void main(String[] args) throws Exception {
        Object previousClient = installClient();
        Object previousAllegiances = staticField("allegiances");
        try {
            neutralNamesDoNotBecomeTheEnd();
            tripperBeatsReporter();
            friendTripperGetsNoMarker();
        } finally {
            setStatic("allegiances", previousAllegiances);
            restoreClient(previousClient);
        }
        System.out.println("TrackerSnitchIntelTest passed");
    }

    private static void neutralNamesDoNotBecomeTheEnd() {
        Tracker tracker = new Tracker();
        tracker.addSnitchHit("Endurance", "Ada", "Reporter", 1, 2, 3, null, 1);
        tracker.addSnitchHit("Defend Point", "Bea", "Reporter", 4, 5, 6, null, 2);
        if (find(tracker, "Endurance") != null) {
            throw new AssertionError("Endurance became " + find(tracker, "Endurance").dimension);
        }
        if (find(tracker, "Defend Point") != null) {
            throw new AssertionError("Defend Point became " + find(tracker, "Defend Point").dimension);
        }
    }

    private static void tripperBeatsReporter() throws Exception {
        Tracker tracker = new Tracker();
        Tracker.RemotePlayer tripper = new Tracker.RemotePlayer("Tripper");
        tripper.dimension = "minecraft:the_nether";
        Tracker.RemotePlayer reporter = new Tracker.RemotePlayer("Reporter");
        reporter.dimension = "minecraft:the_end";
        Map<String, Tracker.RemotePlayer> players = new ConcurrentHashMap<>();
        players.put(tripper.name, tripper);
        players.put(reporter.name, reporter);
        playersBySource(tracker).put("legacy", players);

        tracker.addSnitchHit("Vault", "Tripper", "Reporter", 10, 64, 20, null, 3);
        Tracker.SnitchHit hit = find(tracker, "Vault");
        if (hit == null) throw new AssertionError("Tripper hit created no marker");
        equal("minecraft:the_nether", hit.dimension);
    }

    private static void friendTripperGetsNoMarker() throws Exception {
        AllegianceManager allegiances = new AllegianceManager();
        setStatic("allegiances", allegiances);
        allegiances.replaceAll(List.of("FriendGuy"), List.of(), List.of(), List.of());
        equal(AllegianceManager.Allegiance.FRIEND, allegiances.of("FriendGuy"));

        Tracker tracker = new Tracker();
        invokeApplySnitch(tracker, snitch("FriendGuy", "FriendVault", "minecraft:overworld"));
        if (find(tracker, "FriendVault") != null) throw new AssertionError("Friend tripper got a marker");

        invokeApplySnitch(tracker, snitch("Raider", "RaiderVault", "minecraft:overworld"));
        Tracker.SnitchHit hit = find(tracker, "RaiderVault");
        if (hit == null) throw new AssertionError("Neutral tripper created no marker");
        equal("Raider", hit.player);
        equal("minecraft:overworld", hit.dimension);
    }

    private static JsonObject snitch(String player, String name, String world) {
        JsonObject msg = new JsonObject();
        msg.addProperty("player", player);
        msg.addProperty("snitch", name);
        msg.addProperty("x", 1);
        msg.addProperty("y", 2);
        msg.addProperty("z", 3);
        msg.addProperty("world", world);
        msg.addProperty("from", "Reporter");
        return msg;
    }

    private static void invokeApplySnitch(Tracker tracker, JsonObject msg) throws Exception {
        var method = Tracker.class.getDeclaredMethod("applySnitch", JsonObject.class);
        method.setAccessible(true);
        method.invoke(tracker, msg);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Tracker.RemotePlayer>> playersBySource(Tracker tracker) throws Exception {
        var field = Tracker.class.getDeclaredField("playersBySource");
        field.setAccessible(true);
        return (Map<String, Map<String, Tracker.RemotePlayer>>) field.get(tracker);
    }

    private static Tracker.SnitchHit find(Tracker tracker, String snitch) {
        for (Tracker.SnitchHit hit : tracker.snitchHits()) {
            if (snitch.equals(hit.snitch)) return hit;
        }
        return null;
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
