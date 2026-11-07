package dev.openintel.tracker;

/**
 * Where a remote player probably is right now, from their last report.
 * <ul>
 *   <li>Projects horizontally along the reported velocity from when the
 *   position was observed, for at most {@link #MAX_PROJECTION_MS}. Height is
 *   not projected (jumps and falls would overshoot).</li>
 *   <li>When a new report lands, the marker eases from where it was drawn to
 *   the new estimate over {@link #BLEND_MS} instead of jumping.</li>
 *   <li>A dimension change or a jump over {@link #SNAP_DISTANCE} snaps.</li>
 * </ul>
 * Times are this client's clock: observed = receipt time minus the age the relay reported.
 */
public final class MotionEstimate {
    public static final long MAX_PROJECTION_MS = 500;
    public static final long BLEND_MS = 150;
    public static final double SNAP_DISTANCE = 16;

    private String dim;
    private double x, y, z, vx, vz;
    private long observedAt;
    private double offX, offY, offZ;
    private long blendStart;
    private boolean known;

    /** Record a report. {@code now} is when it is applied (for blending). */
    public synchronized void update(double x, double y, double z, String dim, double vx, double vz,
                                    long observedAt, long now) {
        double[] shown = known ? at(now) : null;
        boolean snap = !known || !java.util.Objects.equals(this.dim, dim);
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = Double.isFinite(vx) ? vx : 0;
        this.vz = Double.isFinite(vz) ? vz : 0;
        this.observedAt = observedAt;
        this.known = true;
        offX = offY = offZ = 0;
        if (snap || shown == null) return;
        double[] target = at(now);
        double dx = shown[0] - target[0], dy = shown[1] - target[1], dz = shown[2] - target[2];
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > SNAP_DISTANCE) return;
        offX = dx;
        offY = dy;
        offZ = dz;
        blendStart = now;
    }

    /** Estimated position {x, y, z} at {@code now}. */
    public synchronized double[] at(long now) {
        long span = Math.max(0, Math.min(MAX_PROJECTION_MS, now - observedAt));
        double px = x + vx * span / 1000.0;
        double pz = z + vz * span / 1000.0;
        double py = y;
        long blended = now - blendStart;
        if (blended < BLEND_MS && (offX != 0 || offY != 0 || offZ != 0)) {
            double left = 1 - Math.max(0, blended) / (double) BLEND_MS;
            px += offX * left;
            py += offY * left;
            pz += offZ * left;
        }
        return new double[]{px, py, pz};
    }

    public synchronized boolean known() { return known; }
}
