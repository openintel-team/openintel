package dev.openintel.gui;

/** Eased scrolling: frame-rate independence, settling, snapping and the pre-render state. */
public final class SmoothScrollTest {
    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        // Before the first frame, the shown offset is the target (headless tests, first draw).
        SmoothScroll s = new SmoothScroll();
        check(s.shown(120) == 120 && s.shownPx(120) == 120 && !s.moving(120), "unrendered shows target");

        // A wheel step glides: part of the way after one tick, there within ~half a second.
        s = new SmoothScroll();
        s.update(0, 1);
        double afterTick = s.update(100, 1 + 50 * MS);
        check(afterTick > 40 && afterTick < 50, "Jade's glide: about 45% of the way after one tick: " + afterTick);
        long t = 1 + 50 * MS;
        for (int i = 0; i < 60; i++) s.update(100, t += 16 * MS);
        check(s.shown(100) == 100 && !s.moving(100), "settles exactly on the target");

        // Same real time, different frame rates: same position.
        SmoothScroll slow = new SmoothScroll(), fast = new SmoothScroll();
        slow.update(0, 1); fast.update(0, 1);
        long a = 1, b = 1;
        for (int i = 0; i < 5; i++) slow.update(200, a += 20 * MS);
        for (int i = 0; i < 25; i++) fast.update(200, b += 4 * MS);
        check(Math.abs(slow.shown(200) - fast.shown(200)) < 0.01, "frame-rate independent");

        // A long stall cannot jump all the way in one frame.
        SmoothScroll stall = new SmoothScroll();
        stall.update(0, 1);
        double after = stall.update(1000, 1 + 5_000 * MS);
        check(after < 1000, "pause is capped: " + after);

        // Sub-pixel parts: floor + fraction reproduce the shown offset.
        SmoothScroll sub = new SmoothScroll();
        sub.update(0, 1);
        sub.update(10, 1 + 20 * MS);
        double shown = sub.shown(10);
        check(Math.abs(sub.shownFloor(10) + sub.fraction(10) - shown) < 1e-4 && sub.fraction(10) >= 0
                && sub.fraction(10) < 1 && shown != Math.rint(shown), "sub-pixel split: " + shown);

        // Snap jumps straight there.
        s.snap(0);
        check(s.shown(0) == 0 && !s.moving(0), "snap");

        // Moving down then back up reverses smoothly without overshooting either end.
        SmoothScroll back = new SmoothScroll();
        back.update(0, 1);
        double v = back.update(300, 1 + 30 * MS);
        double w = back.update(0, 1 + 60 * MS);
        check(w < v && w >= 0, "reverses toward the new target without overshoot");
        System.out.println("SmoothScrollTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
