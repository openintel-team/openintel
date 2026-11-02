package dev.openintel.ping;

import java.lang.reflect.Method;

/** The six ping-wheel wedges and the inner dead zone. */
public final class PingWheelLayoutTest {
    public static void main(String[] args) throws Exception {
        Method slotAt = PingWheelScreen.class.getDeclaredMethod("slotAt", float.class, float.class);
        slotAt.setAccessible(true);
        equal(-1, slot(slotAt, 0, 0));
        equal(-1, slot(slotAt, 10, 10));
        equal(-1, slot(slotAt, 0, -25));
        equal(0, slot(slotAt, 0, -26));

        float radius = 40f;
        for (int slot = 0; slot < 6; slot++) {
            double rel = slot * 60.0 + 30.0;
            double deg = Math.toRadians(rel - 90.0);
            int actual = slot(slotAt, (float) (Math.cos(deg) * radius), (float) (Math.sin(deg) * radius));
            equal(slot, actual);
        }
        System.out.println("PingWheelLayoutTest passed");
    }

    private static int slot(Method slotAt, float dx, float dy) throws Exception {
        return (int) slotAt.invoke(null, dx, dy);
    }

    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
}
