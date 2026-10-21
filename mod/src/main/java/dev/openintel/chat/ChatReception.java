package dev.openintel.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Distinguishes a chat line the client inserted from one received from the
 * server. Server packets and the delayed chat queue raise the depth around
 * the add. Anything stored while the depth is zero was inserted locally.
 *
 * <p>Another mod can cancel the add and submit the same component again later.
 * The first observation is kept for that object so the replay stays with the
 * reception that originally produced it.
 */
public final class ChatReception {
    private enum Decision { SKIP, SERVER, LOCAL }

    private static final class Frame {
        final Decision decision;
        /** Present when this call should drop its own pending observation. */
        final Object key;

        Frame(Decision decision, Object key) {
            this.decision = decision;
            this.key = key;
        }
    }

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<Object, Decision> PENDING = new IdentityHashMap<>();
    private static final int PENDING_LIMIT = 4096;

    private ChatReception() { }

    public static void beginServer() {
        DEPTH.get()[0]++;
    }

    public static void endServer() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    public static boolean receiving() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Called at the start of storing a line. {@code contents} is the component
     * object about to be stored, and {@code logTag} is its indicator tag.
     */
    public static void observe(Object contents, String logTag) {
        if ("Restored".equals(logTag)) {
            STACK.get().push(new Frame(Decision.SKIP, null));
            return;
        }
        boolean serverNow = receiving();
        Decision earlier = contents == null ? null : PENDING.remove(contents);
        if (earlier != null && !serverNow) {
            STACK.get().push(new Frame(earlier, null));
            return;
        }
        Decision decision = serverNow ? Decision.SERVER : Decision.LOCAL;
        if (contents != null) {
            if (PENDING.size() >= PENDING_LIMIT) PENDING.clear();
            PENDING.put(contents, decision);
        }
        STACK.get().push(new Frame(decision, contents));
    }

    /** Called once the line has actually been stored. */
    public static boolean takeLocal() {
        Deque<Frame> stack = STACK.get();
        if (stack.isEmpty()) return false;
        Frame frame = stack.pop();
        if (frame.key != null) PENDING.remove(frame.key);
        return frame.decision == Decision.LOCAL;
    }
}
