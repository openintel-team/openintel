package dev.openintel.gui;

/**
 * Eased scrolling for OpenIntel lists, after the feel of Jade's menus.
 *
 * A screen keeps its own scroll offset as the <em>target</em>: wheel steps,
 * jumps and clamps all change only that, exactly as before. Each frame the
 * shown offset moves a fixed fraction of the way to the target, scaled by real
 * elapsed time, so a wheel notch glides instead of snapping and quick notches
 * pile onto the same glide. Drawing and hit-testing both use {@link #shown},
 * so what is under the pointer is what gets clicked mid-glide.
 *
 * Until the first frame is drawn the shown offset is the target, so headless
 * tests and the first frame see no animation.
 */
public final class SmoothScroll {
    /**
     * Fraction of the distance still left after {@link #STEP_NANOS}. Jade moves
     * 0.6 × the gap per tick in small per-frame steps, which leaves e^-0.6 ≈ 0.55
     * of it after a tick at high frame rates; this matches that glide exactly.
     */
    static final double REMAINING_PER_STEP = Math.exp(-0.6);
    /** One Minecraft tick. */
    static final double STEP_NANOS = 50_000_000.0;
    /** Below this distance (1/16 GUI px, under one physical pixel) the glide ends on the target. */
    static final double SETTLE = 1.0 / 16;
    /** A long pause (screen hidden, game paused) never turns into one huge jump step. */
    static final long MAX_FRAME_NANOS = 100_000_000L;

    private double value = Double.NaN;
    private long lastNanos;

    /** Advances toward {@code target} for this frame and returns the offset to draw. */
    public double update(double target) {
        return update(target, System.nanoTime());
    }

    double update(double target, long now) {
        if (Double.isNaN(value) || lastNanos == 0) {
            value = target;
        } else {
            long dt = Math.min(MAX_FRAME_NANOS, Math.max(0, now - lastNanos));
            double remaining = Math.pow(REMAINING_PER_STEP, dt / STEP_NANOS);
            value = target + (value - target) * remaining;
            if (Math.abs(target - value) < SETTLE) value = target;
        }
        lastNanos = now;
        return value;
    }

    /** Offset currently on screen; the target until the first frame. */
    public double shown(double target) {
        return Double.isNaN(value) ? target : value;
    }

    /** Shown offset rounded to GUI pixels, for layout and hit-testing. */
    public int shownPx(double target) {
        return (int) Math.round(shown(target));
    }

    /** Whole GUI pixels of the shown offset, rounded down; pair with {@link #fraction}. */
    public int shownFloor(double target) {
        return (int) Math.floor(shown(target));
    }

    /**
     * Sub-pixel remainder of the shown offset, 0 ≤ f &lt; 1. Draw content at
     * {@link #shownFloor} and translate the pose up by this, so a glide moves by
     * physical pixels instead of whole GUI pixels (up to 4 physical px at GUI
     * scale 4), which is what makes the slow end of a glide look stepped.
     */
    public float fraction(double target) {
        double v = shown(target);
        return (float) (v - Math.floor(v));
    }

    /** Jumps straight to {@code target} (new content, a reset to the top). */
    public void snap(double target) {
        value = target;
    }

    /** Still gliding toward {@code target}. */
    public boolean moving(double target) {
        return !Double.isNaN(value) && Math.abs(target - value) >= SETTLE;
    }
}
