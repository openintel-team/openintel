package dev.openintel.investigate;

import dev.openintel.config.OIConfig.InvestigateCameraMode;

/** Frame-rate-independent look transition. No world, input, or renderer dependencies. */
public final class InvestigateCameraMotion {
    public record Angles(float yaw, float pitch) { }
    private long revision = Long.MIN_VALUE;
    private InvestigateCameraMode mode;
    private int duration;
    private long started;
    private float startYaw, startPitch, targetYaw;
    private Angles last;
    private boolean active;

    public void reset() {
        revision = Long.MIN_VALUE;
        mode = null;
        cancel();
    }

    /** Cancel this focus request; it stays cancelled until another selection or camera setting change. */
    public void cancel() { active = false; last = null; }

    /** Returns null when control belongs to the user. Yaw/pitch use Minecraft's degrees convention. */
    public Angles update(long focusRevision, InvestigateCameraMode cameraMode, int durationMs, long nowNanos,
                         float yaw, float pitch, double dx, double dy, double dz) {
        if (cameraMode == null) cameraMode = InvestigateCameraMode.PAN;
        durationMs = Math.clamp(durationMs, 100, 2000);
        boolean changed = revision != focusRevision || mode != cameraMode || duration != durationMs;
        if (changed) {
            revision = focusRevision;
            mode = cameraMode;
            duration = durationMs;
            startYaw = yaw;
            startPitch = pitch;
            started = nowNanos;
            active = mode != InvestigateCameraMode.OFF;
            last = null;
        }
        if (!active) return null;
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) { cancel(); return null; }
        // Mouse look (or another camera controller) wins immediately, without a tug-of-war.
        if (last != null && (Math.abs(wrap(yaw - last.yaw)) > 0.05f || Math.abs(pitch - last.pitch) > 0.05f)) {
            cancel();
            return null;
        }
        Angles target = aim(dx, dy, dz, yaw);
        if (target == null) { cancel(); return null; }
        targetYaw = changed ? startYaw + wrap(target.yaw - startYaw)
                : targetYaw + wrap(target.yaw - targetYaw);
        double t = mode == InvestigateCameraMode.SNAP ? 1
                : Math.clamp((nowNanos - started) / (duration * 1_000_000.0), 0, 1);
        double ease = t * t * (3 - 2 * t);
        last = new Angles(startYaw + (targetYaw - startYaw) * (float) ease,
                Math.clamp(startPitch + (target.pitch - startPitch) * (float) ease, -90, 90));
        if (t >= 1) active = false;
        return last;
    }

    public static Angles aim(double dx, double dy, double dz, float fallbackYaw) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return null;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1e-7 && Math.abs(dy) < 1e-7) return null;
        float yaw = horizontal < 1e-7 ? fallbackYaw : (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Angles(yaw, Math.clamp(pitch, -90, 90));
    }

    public static float wrap(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
}
