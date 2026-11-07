package dev.openintel.tracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Horizontal velocity per observed player, from successive tick positions.
 * Receivers use it to move markers between updates. A dimension change, a
 * long gap, or a jump (teleport, pearl) restarts the estimate at zero.
 */
public final class PositionSampler {
    static final long MAX_GAP_MS = 1000;
    static final double MAX_JUMP = 50;
    static final double MAX_SPEED = 100;
    /** Weight of the newest sample; smooths tick timing jitter. */
    static final double SMOOTHING = 0.5;

    private static final class Sample {
        String dim;
        double x, z, vx, vz;
        long at;
    }

    private final Map<String, Sample> samples = new HashMap<>();
    private final Set<String> seen = new HashSet<>();

    /** Velocity (blocks per second) as {vx, vz}. */
    public double[] sample(String name, String dim, double x, double z, long now) {
        seen.add(name);
        Sample s = samples.get(name);
        if (s == null) {
            s = new Sample();
            samples.put(name, s);
        } else {
            long dt = now - s.at;
            if (dt <= 0) return new double[]{s.vx, s.vz};
            double dx = x - s.x, dz = z - s.z;
            boolean continuous = java.util.Objects.equals(s.dim, dim) && dt <= MAX_GAP_MS
                    && Math.hypot(dx, dz) <= MAX_JUMP;
            if (!continuous || Math.hypot(dx, dz) < 0.005) {
                // Restart after a jump; a player who did not move this sample has stopped.
                s.vx = 0;
                s.vz = 0;
            } else {
                s.vx = clamp(SMOOTHING * (dx * 1000.0 / dt) + (1 - SMOOTHING) * s.vx);
                s.vz = clamp(SMOOTHING * (dz * 1000.0 / dt) + (1 - SMOOTHING) * s.vz);
                // A stopped player reads as stopped, not as a slowly decaying drift.
                if (Math.hypot(s.vx, s.vz) < 0.05) { s.vx = 0; s.vz = 0; }
            }
        }
        s.dim = dim;
        s.x = x;
        s.z = z;
        s.at = now;
        return new double[]{s.vx, s.vz};
    }

    /** Forget players not sampled since the previous call. */
    public void endRound() {
        samples.keySet().retainAll(seen);
        seen.clear();
    }

    public void clear() {
        samples.clear();
        seen.clear();
    }

    private static double clamp(double v) {
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }
}
