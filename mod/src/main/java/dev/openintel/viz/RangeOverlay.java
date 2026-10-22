package dev.openintel.viz;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.wartable.Sanctuaries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import dev.openintel.render.WorldOverlayMesh;
import net.minecraft.world.phys.Vec3;

/** Collects exact block-aligned coverage surfaces for the dedicated world overlay pipelines. */
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
    private static final Direction[] FACES = Direction.values();

    /** Collect at tick rate; the immutable snapshot is attached during level extraction. */
    public static void collect(Minecraft client, WorldOverlayMesh.Builder mesh) {
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
                            drawSnitch(mesh, shape.x, shape.y, shape.z, snitchR,
                                    logging ? LOGGING_STROKE : SNITCH_STROKE,
                                    logging ? LOGGING_FILL : SNITCH_FILL,
                                    eye, seeThrough);
                        }
                    }
                    case BASTION -> {
                        if (cfg.vizBastion) {
                            drawBastion(mesh, shape.x, shape.y, shape.z, bastionR, eye, seeThrough);
                        }
                    }
                    case SANCTUARY -> {
                        if (cfg.vizSanctuary) {
                            drawDisk(mesh, shape.x + 0.5, shape.y, shape.z + 0.5,
                                    shape.sanctuaryRadius, SANCTUARY_STROKE, SANCTUARY_FILL,
                                    eye, seeThrough, false, false);
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
                drawDisk(mesh, s.x(), groundY, s.z(), s.radius(),
                        SANCTUARY_STROKE, SANCTUARY_FILL, eye, seeThrough, false, false);
            }
        }
    }

    static void drawSnitch(WorldOverlayMesh.Builder mesh, int bx, int by, int bz, double radius,
                           int stroke, int fill, Vec3 eye, boolean seeThrough) {
        if (!nearShape(eye, bx + 0.5, by + 0.5, bz + 0.5, radius)) return;
        int[] shell = RangeGeometry.snitchShellOffsets(radius);
        if (shell.length == 0) return;
        for (int i = 0; i + 2 < shell.length; i += 3) {
            int dx = shell[i], dy = shell[i + 1], dz = shell[i + 2];
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (!near(eye, x + 0.5, y + 0.5, z + 0.5)) continue;
            for (Direction face : FACES) {
                if (RangeGeometry.snitchCoversBlock(bx, by, bz,
                        x + face.getStepX(), y + face.getStepY(), z + face.getStepZ(), radius)) {
                    continue;
                }
                mesh.face(x, y, z, face, stroke, fill, 1.5f, seeThrough);
            }
        }
    }

    static void drawBastion(WorldOverlayMesh.Builder mesh, int bx, int by, int bz, double radius, Vec3 eye, boolean seeThrough) {
        drawDisk(mesh, bx + 0.5, by, bz + 0.5, radius, BASTION_STROKE, BASTION_FILL,
                eye, seeThrough, true, true);
    }

    private static void drawDisk(WorldOverlayMesh.Builder mesh, double centerX, int y, double centerZ, double radius,
                                 int stroke, int fill, Vec3 eye, boolean seeThrough, boolean posts, boolean bastion) {
        if (!nearShape(eye, centerX, y + 0.5, centerZ, radius)) return;
        int[] ring = bastion
                ? RangeGeometry.bastionRingOffsets(radius)
                : RangeGeometry.diskRingOffsets(centerX, centerZ, radius);
        if (ring.length == 0) return;
        int baseX = (int) Math.floor(centerX);
        int baseZ = (int) Math.floor(centerZ);
        int top = y + RangeGeometry.BASTION_COLUMN_HEIGHT;
        for (int i = 0; i + 1 < ring.length; i += 2) {
            int x = baseX + ring[i];
            int z = baseZ + ring[i + 1];
            if (!near(eye, x + 0.5, y + 0.5, z + 0.5)) continue;
            mesh.face(x, y, z, Direction.UP, stroke, fill, 2f, seeThrough);
            mesh.face(x, y, z, Direction.DOWN, stroke, fill, 2f, seeThrough);
            if (!horizontalCover(bastion, centerX, centerZ, radius, x + 1, z))
                mesh.face(x, y, z, Direction.EAST, stroke, fill, 2f, seeThrough);
            if (!horizontalCover(bastion, centerX, centerZ, radius, x - 1, z))
                mesh.face(x, y, z, Direction.WEST, stroke, fill, 2f, seeThrough);
            if (!horizontalCover(bastion, centerX, centerZ, radius, x, z + 1))
                mesh.face(x, y, z, Direction.SOUTH, stroke, fill, 2f, seeThrough);
            if (!horizontalCover(bastion, centerX, centerZ, radius, x, z - 1))
                mesh.face(x, y, z, Direction.NORTH, stroke, fill, 2f, seeThrough);
            if (!posts) continue;
            double px = x + 0.5, pz = z + 0.5;
            mesh.line(new Vec3(px, y, pz), new Vec3(px, top, pz), stroke, 1.5f, seeThrough);
        }
    }

    private static boolean horizontalCover(boolean bastion, double centerX, double centerZ, double radius,
                                           int blockX, int blockZ) {
        if (!bastion) return RangeGeometry.diskCoversBlock(centerX, centerZ, radius, blockX, blockZ);
        return RangeGeometry.bastionDiskCovers((int) Math.floor(centerX), (int) Math.floor(centerZ),
                blockX, blockZ, radius);
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

    private static boolean dimEquals(String current, String stored) {
        if (current == null || stored == null) return false;
        return current.equalsIgnoreCase(stored);
    }

    private static boolean isOverworld(String dim) {
        return "minecraft:overworld".equalsIgnoreCase(dim);
    }
}
