package dev.openintel.allegiance;

import dev.openintel.gui.VisualizationsInteractionTest;
import dev.openintel.net.RelaySource;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Focus outranks a friend, a user who is also an enemy stays hostile, and one relay roster can drop. */
public final class AllegianceTest {
    public static void main(String[] args) throws Exception {
        Object previous = installClient();
        try {
            AllegianceManager allegiances = new AllegianceManager();
            allegiances.clearRelay();
            allegiances.replaceAll(List.of("Nina"), List.of(), List.of(), List.of("Nina"));
            equal(AllegianceManager.Allegiance.FOCUS, allegiances.of("nina"));
            equal(true, allegiances.isEnemy("Nina"));

            allegiances.clearRelay();
            allegiances.replaceAll(List.of("Omar"), List.of(), List.of("Omar"), List.of());
            equal(AllegianceManager.Allegiance.ENEMY, allegiances.of("Omar"));
            equal(true, allegiances.isEnemy("Omar"));
            equal(true, allegiances.isRelayUser("Omar"));

            allegiances.clearRelay();
            allegiances.replaceAll(List.of("Cara"), List.of(), List.of(), List.of());
            equal(AllegianceManager.Allegiance.FRIEND, allegiances.of("Cara"));
            equal(false, allegiances.isEnemy("Cara"));

            allegiances.clearRelay();
            RelaySource.run("alpha", () -> allegiances.replaceAll(List.of("Dana"), List.of(), List.of(), List.of()));
            RelaySource.run("beta", () -> allegiances.replaceAll(List.of("Eve"), List.of(), List.of("Dana"), List.of()));
            equal(AllegianceManager.Allegiance.ENEMY, allegiances.of("Dana"));
            equal(AllegianceManager.Allegiance.FRIEND, allegiances.of("Eve"));
            allegiances.clearRelay("beta");
            equal(AllegianceManager.Allegiance.FRIEND, allegiances.of("Dana"));
            equal(AllegianceManager.Allegiance.NEUTRAL, allegiances.of("Eve"));
            equal(true, allegiances.isRelayUser("Dana"));
        } finally {
            restoreClient(previous);
        }
        System.out.println("AllegianceTest passed");
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

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
