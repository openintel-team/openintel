package dev.openintel.viz;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.wartable.Sanctuaries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * Submits block-aligned coverage gizmos during the client tick, while
 * Minecraft's per-tick gizmo collector is open.
 *
 * Snitches draw the outer shell of covered blocks. Bastions and sanctuaries
 * draw the ring of blocks in the horizontal disk. Blocks farther than
 * {@link #DRAW_RANGE} from the player are skipped so a large sanctuary does
 * not submit its whole ring every tick.
 */
public final class RangeOverlay {
    private RangeOverlay() { }

    private static final int SNITCH_STROKE = 0xFF00E5FF;
    private static final int SNITCH_FILL = 0x3300E5FF;
    private static final int LOGGING_STROKE = 0xFFD946EF;
    private static final int LOGGING_FILL = 0x33D946EF;
    private static final int BASTION_STROKE = 0xFFFF8A00;
    private static final int BASTION_FILL = 0x33FF8A00;
    private static final int SANCTUARY_STROKE = 0xFF3DDC84;
    private static final int SANCTUARY_FILL = 0x333DDC84;

    /** How far from the player a shell block is still submitted. */
    private static final double DRAW_RANGE = 256;
    private static final double DRAW_RANGE_SQ = DRAW_RANGE * DRAW_RANGE;
    /**
     * Faces sit just outside the block they describe so they do not share a
     * plane with that block (or with a neighbor's face) and z-fight.
     */
    private static final double SKIN = 0.02;
    private static final Direction[] FACES = Direction.values();

    /** Call once per client tick while in a world. */
    public static void tick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return;
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.vizEnabled) return;

        String dim = client.level.dimension().identifier().toString();
        boolean seeThrough = cfg.vizSeeThrough;
        Vec3 eye = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
        double snitchR = snitchRadius(cfg);
        double bastionR = bastionRadius(cfg);

        if (cfg.vizSnitch || cfg.vizBastion || cfg.vizSanctuary) {
            for (RangeShape shape : RangeStore.get().shapes()) {
                if (shape == null || !shape.enabled || shape.type == null) continue;
                if (!dimEquals(dim, shape.dimension)) continue;
                switch (shape.type) {
                    case SNITCH -> {
                        if (cfg.vizSnitch) {
                            boolean logging = shape.snitchKind == SnitchKind.LOGGING;
                            drawSnitch(shape.x, shape.y, shape.z, snitchR,
                                    logging ? LOGGING_STROKE : SNITCH_STROKE,
                                    logging ? LOGGING_FILL : SNITCH_FILL,
                                    eye, seeThrough);
                        }
                    }
                    case BASTION -> {
                        if (cfg.vizBastion) {
                            drawBastion(shape.x, shape.y, shape.z, bastionR, eye, seeThrough);
                        }
                    }
                    case SANCTUARY -> {
                        if (cfg.vizSanctuary) {
                            drawDisk(shape.x + 0.5, shape.y, shape.z + 0.5,
                                    shape.sanctuaryRadius, SANCTUARY_STROKE, SANCTUARY_FILL,
                                    eye, seeThrough, false);
                        }
                    }
                }
            }
        }

        if (cfg.vizLiveSanctuaries && isOverworld(dim)) {
            Sanctuaries.ensureLoaded();
            int groundY = client.player.blockPosition().getY();
            for (Sanctuaries.Sanctuary s : Sanctuaries.all()) {
                if (s == null || !s.overworld()) continue;
                drawDisk(s.x(), groundY, s.z(), s.radius(),
                        SANCTUARY_STROKE, SANCTUARY_FILL, eye, seeThrough, false);
            }
        }
    }

    static void drawSnitch(int bx, int by, int bz, double radius,
                           int stroke, int fill, Vec3 eye, boolean seeThrough) {
        if (!nearShape(eye, bx + 0.5, by + 0.5, bz + 0.5, radius)) return;
        int[] shell = RangeGeometry.snitchShellOffsets(radius);
        if (shell.length == 0) return;
        GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 1.5f, fill);
        for (int i = 0; i + 2 < shell.length; i += 3) {
            int dx = shell[i], dy = shell[i + 1], dz = shell[i + 2];
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (!near(eye, x + 0.5, y + 0.5, z + 0.5)) continue;
            for (Direction face : FACES) {
                if (RangeGeometry.snitchCoversBlock(bx, by, bz,
                        x + face.getStepX(), y + face.getStepY(), z + face.getStepZ(), radius)) {
                    continue;
                }
                drawFace(x, y, z, face, style, seeThrough);
            }
        }
    }

    static void drawBastion(int bx, int by, int bz, double radius, Vec3 eye, boolean seeThrough) {
        drawDisk(bx + 0.5, by, bz + 0.5, radius, BASTION_STROKE, BASTION_FILL,
                eye, seeThrough, true);
    }

    private static void drawDisk(double centerX, int y, double centerZ, double radius,
                                 int stroke, int fill, Vec3 eye, boolean seeThrough, boolean posts) {
        if (!nearShape(eye, centerX, y + 0.5, centerZ, radius)) return;
        int[] ring = RangeGeometry.diskRingOffsets(centerX, centerZ, radius);
        if (ring.length == 0) return;
        int baseX = (int) Math.floor(centerX);
        int baseZ = (int) Math.floor(centerZ);
        GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 2f, fill);
        int top = y + RangeGeometry.BASTION_COLUMN_HEIGHT;
        for (int i = 0; i + 1 < ring.length; i += 2) {
            int x = baseX + ring[i];
            int z = baseZ + ring[i + 1];
            if (!near(eye, x + 0.5, y + 0.5, z + 0.5)) continue;
            drawFace(x, y, z, Direction.UP, style, seeThrough);
            drawFace(x, y, z, Direction.DOWN, style, seeThrough);
            if (!RangeGeometry.diskCoversBlock(centerX, centerZ, radius, x + 1, z))
                drawFace(x, y, z, Direction.EAST, style, seeThrough);
            if (!RangeGeometry.diskCoversBlock(centerX, centerZ, radius, x - 1, z))
                drawFace(x, y, z, Direction.WEST, style, seeThrough);
            if (!RangeGeometry.diskCoversBlock(centerX, centerZ, radius, x, z + 1))
                drawFace(x, y, z, Direction.SOUTH, style, seeThrough);
            if (!RangeGeometry.diskCoversBlock(centerX, centerZ, radius, x, z - 1))
                drawFace(x, y, z, Direction.NORTH, style, seeThrough);
            if (!posts) continue;
            double px = x + 0.5, pz = z + 0.5;
            apply(Gizmos.line(new Vec3(px, y, pz), new Vec3(px, top, pz), stroke, 1.5f), seeThrough);
        }
    }

    /** One face of the block, pushed {@link #SKIN} outward so it clears the block face. */
    private static void drawFace(int x, int y, int z, Direction face,
                                 GizmoStyle style, boolean seeThrough) {
        Vec3 min = new Vec3(x - SKIN, y - SKIN, z - SKIN);
        Vec3 max = new Vec3(x + 1.0 + SKIN, y + 1.0 + SKIN, z + 1.0 + SKIN);
        apply(Gizmos.rect(min, max, face, style), seeThrough);
    }

    private static double snitchRadius(OIConfig cfg) {
        int r = cfg.vizSnitchRadius;
        if (r < 1) return RangeGeometry.SNITCH_RADIUS;
        return Math.min(32, r);
    }

    private static double bastionRadius(OIConfig cfg) {
        double r = cfg.vizBastionRadius;
        if (!Double.isFinite(r) || r < 1) return RangeGeometry.BASTION_RADIUS;
        return Math.min(64.0, r);
    }

    private static boolean nearShape(Vec3 eye, double x, double y, double z, double radius) {
        double reach = radius + DRAW_RANGE;
        double dx = eye.x - x, dy = eye.y - y, dz = eye.z - z;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static boolean near(Vec3 eye, double x, double y, double z) {
        double dx = eye.x - x, dy = eye.y - y, dz = eye.z - z;
        return dx * dx + dy * dy + dz * dz <= DRAW_RANGE_SQ;
    }

    private static void apply(GizmoProperties props, boolean seeThrough) {
        if (seeThrough) props.setAlwaysOnTop();
    }

    private static boolean dimEquals(String current, String stored) {
        if (current == null || stored == null) return false;
        return current.equalsIgnoreCase(stored);
    }

    private static boolean isOverworld(String dim) {
        return "minecraft:overworld".equalsIgnoreCase(dim);
    }
}
