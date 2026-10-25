package dev.openintel.viz;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.wartable.Sanctuaries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import dev.openintel.render.WorldOverlayMesh;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** A shape is drawn when its center is within this distance plus its radius; sanctuary ring blocks also stop here. */
    private static final double DRAW_RANGE = 256;
    private static final double DRAW_RANGE_SQ = DRAW_RANGE * DRAW_RANGE;
    private static final Direction[] FACES = Direction.values();

    /**
     * Sanctuary rings are culled per block against the center of the player's cell, so they are rebuilt
     * only when the player crosses a cell boundary. At {@link #DRAW_RANGE} the cull edge moves a few blocks.
     */
    private static final int CELL = 32;

    /**
     * Snitch and bastion geometry. Those shapes are drawn whole once their center is in range, so the
     * mesh only changes when a shape enters or leaves range. {@code inRange} indexes {@code visibleShapes}
     * for this store revision.
     */
    public record ShapesKey(String dim, long revision, boolean snitch, boolean bastion, boolean seeThrough,
                            double snitchRadius, double bastionRadius, BitSet inRange, int buildHeight) { }

    /**
     * Thick bastion rings drawn at Y 0 and lifted to the player's block Y.
     * {@code rings} indexes bastions already in range whose layer is not the player's.
     */
    public record BastionHeightKey(String dim, long revision, boolean seeThrough, double bastionRadius, BitSet rings) { }

    /** Manually placed sanctuaries, which can be far larger than the draw range. */
    public record DiskKey(String dim, long revision, boolean seeThrough, int cellX, int cellY, int cellZ) { }

    /** Live-map sanctuaries are built at Y 0 and lifted to the player's block Y when drawn. */
    public record LiveKey(int revision, boolean seeThrough, int cellX, int cellZ) { }

    private record TemplateKey(boolean bastion, double radius, int stroke, int fill, boolean onTop) { }
    private static final Map<TemplateKey, WorldOverlayMesh> TEMPLATES = new HashMap<>();

    /** Null when no snitch or bastion can be drawn. */
    public static ShapesKey shapesKey(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return null;
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.vizEnabled || !(cfg.vizSnitch || cfg.vizBastion)) return null;
        String dim = client.level.dimension().identifier().toString();
        double snitchR = snitchRadius(cfg), bastionR = bastionRadius(cfg);
        Vec3 eye = client.player.position();
        RangeStore store = RangeStore.get();
        long revision = store.revision();
        List<RangeShape> shapes = store.visibleShapes();
        BitSet inRange = new BitSet();
        for (int i = 0; i < shapes.size(); i++) {
            RangeShape shape = shapes.get(i);
            if (shape == null || !shape.enabled || shape.type == null || !dimEquals(dim, shape.dimension)) continue;
            boolean near = switch (shape.type) {
                case SNITCH -> cfg.vizSnitch && nearShape(eye, shape.x + 0.5, shape.y + 0.5, shape.z + 0.5, snitchR);
                case BASTION -> cfg.vizBastion && nearShape(eye, shape.x + 0.5, shape.y + 0.5, shape.z + 0.5, bastionR);
                case SANCTUARY -> false;
            };
            if (near) inRange.set(i);
        }
        if (inRange.isEmpty()) return null;
        return new ShapesKey(dim, revision, cfg.vizSnitch, cfg.vizBastion, cfg.vizSeeThrough, snitchR, bastionR,
                inRange, client.level.getMaxY());
    }

    /** Null when every in-range bastion already sits on the player's layer. */
    public static BastionHeightKey bastionHeightKey(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return null;
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.vizEnabled || !cfg.vizBastion) return null;
        String dim = client.level.dimension().identifier().toString();
        double bastionR = bastionRadius(cfg);
        Vec3 eye = client.player.position();
        int playerY = client.player.blockPosition().getY();
        RangeStore store = RangeStore.get();
        List<RangeShape> shapes = store.visibleShapes();
        BitSet rings = new BitSet();
        for (int i = 0; i < shapes.size(); i++) {
            RangeShape shape = shapes.get(i);
            if (shape == null || !shape.enabled || shape.type != RangeType.BASTION) continue;
            if (!dimEquals(dim, shape.dimension) || !bastionPlayerBorder(shape.y, playerY)) continue;
            if (!nearShape(eye, shape.x + 0.5, shape.y + 0.5, shape.z + 0.5, bastionR)) continue;
            rings.set(i);
        }
        if (rings.isEmpty()) return null;
        return new BastionHeightKey(dim, store.revision(), cfg.vizSeeThrough, bastionR, rings);
    }

    /** Null when no manual sanctuary can be drawn in this dimension. */
    public static DiskKey diskKey(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return null;
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.vizEnabled || !cfg.vizSanctuary) return null;
        String dim = client.level.dimension().identifier().toString();
        RangeStore store = RangeStore.get();
        long revision = store.revision();
        boolean any = false;
        for (RangeShape shape : store.visibleShapes()) {
            if (shape != null && shape.enabled && shape.type == RangeType.SANCTUARY && dimEquals(dim, shape.dimension)) {
                any = true;
                break;
            }
        }
        if (!any) return null;
        return new DiskKey(dim, revision, cfg.vizSeeThrough,
                cell(client.player.getX()), cell(client.player.getY()), cell(client.player.getZ()));
    }

    /** Null when live-map sanctuaries are hidden or the player is outside the Overworld. */
    public static LiveKey liveKey(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return null;
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.vizEnabled || !cfg.vizLiveSanctuaries) return null;
        if (!isOverworld(client.level.dimension().identifier().toString())) return null;
        Sanctuaries.ensureLoaded();
        return new LiveKey(Sanctuaries.revision(), cfg.vizSeeThrough,
                cell(client.player.getX()), cell(client.player.getZ()));
    }

    public static void collectShapes(ShapesKey key, WorldOverlayMesh.Builder mesh) {
        List<RangeShape> shapes = RangeStore.get().visibleShapes();
        boolean seeThrough = key.seeThrough();
        for (int i = key.inRange().nextSetBit(0); i >= 0 && i < shapes.size(); i = key.inRange().nextSetBit(i + 1)) {
            RangeShape shape = shapes.get(i);
            if (shape.type == RangeType.SNITCH && key.snitch()) {
                boolean logging = shape.snitchKind == SnitchKind.LOGGING;
                mesh.append(snitchTemplate(key.snitchRadius(), logging ? LOGGING_STROKE : SNITCH_STROKE,
                        logging ? LOGGING_FILL : SNITCH_FILL, seeThrough), shape.x, shape.y, shape.z);
            } else if (shape.type == RangeType.BASTION && key.bastion()) {
                placeBastion(mesh, shape.x, shape.y, shape.z, key.bastionRadius(), seeThrough, key.buildHeight());
            }
        }
    }

    public static void collectBastionHeights(BastionHeightKey key, WorldOverlayMesh.Builder mesh) {
        List<RangeShape> shapes = RangeStore.get().visibleShapes();
        for (int i = key.rings().nextSetBit(0); i >= 0 && i < shapes.size(); i = key.rings().nextSetBit(i + 1)) {
            RangeShape shape = shapes.get(i);
            drawBastionPlayerBorder(mesh, shape.x, 0, shape.z, key.bastionRadius(), key.seeThrough());
        }
    }

    public static void collectDisks(DiskKey key, WorldOverlayMesh.Builder mesh) {
        Vec3 eye = cellCenter(key.cellX(), key.cellY(), key.cellZ());
        for (RangeShape shape : RangeStore.get().visibleShapes()) {
            if (shape == null || !shape.enabled || shape.type != RangeType.SANCTUARY) continue;
            if (!dimEquals(key.dim(), shape.dimension)) continue;
            drawDisk(mesh, shape.x + 0.5, shape.y, shape.z + 0.5, shape.sanctuaryRadius,
                    SANCTUARY_STROKE, SANCTUARY_FILL, eye, key.seeThrough(), false);
        }
    }

    public static void collectLive(LiveKey key, WorldOverlayMesh.Builder mesh) {
        Vec3 center = cellCenter(key.cellX(), 0, key.cellZ());
        Vec3 eye = new Vec3(center.x, 0, center.z);
        for (Sanctuaries.Sanctuary s : Sanctuaries.all()) {
            if (s == null || !s.overworld()) continue;
            drawDisk(mesh, s.x(), 0, s.z(), s.radius(),
                    SANCTUARY_STROKE, SANCTUARY_FILL, eye, key.seeThrough(), false);
        }
    }

    /** Block Y the live-map sanctuary layer is drawn at. */
    public static int liveY(Minecraft client) {
        return client.player == null ? 0 : client.player.blockPosition().getY();
    }

    private static int cell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL);
    }

    private static Vec3 cellCenter(int cx, int cy, int cz) {
        double half = CELL / 2.0;
        return new Vec3(cx * (double) CELL + half, cy * (double) CELL + half, cz * (double) CELL + half);
    }

    static void drawSnitch(WorldOverlayMesh.Builder mesh, int bx, int by, int bz, double radius,
                           int stroke, int fill, Vec3 eye, boolean seeThrough) {
        if (!nearShape(eye, bx + 0.5, by + 0.5, bz + 0.5, radius)) return;
        mesh.append(snitchTemplate(radius, stroke, fill, seeThrough), bx, by, bz);
    }

    static void drawBastion(WorldOverlayMesh.Builder mesh, int bx, int by, int bz, double radius, Vec3 eye,
                            boolean seeThrough, int buildHeight) {
        if (eye != null && !nearShape(eye, bx + 0.5, by + 0.5, bz + 0.5, radius)) return;
        placeBastion(mesh, bx, by, bz, radius, seeThrough, buildHeight);
    }

    /** Thick outer ring at {@code y}, without the center plus or the vertical posts. */
    static void drawBastionPlayerBorder(WorldOverlayMesh.Builder mesh, int bx, int y, int bz, double radius,
                                        boolean seeThrough) {
        drawPerimeter(mesh, bx + 0.5, y, bz + 0.5, radius, BASTION_STROKE, BASTION_FILL, null, seeThrough, true);
    }

    /** The bastion layer already draws this ring, so it is omitted when the heights match. */
    static boolean bastionPlayerBorder(int bastionY, int playerY) {
        return bastionY != playerY;
    }

    private static void placeBastion(WorldOverlayMesh.Builder mesh, int bx, int by, int bz, double radius,
                                     boolean seeThrough, int buildHeight) {
        mesh.append(bastionTemplate(radius, seeThrough), bx, by, bz);
        if (buildHeight <= by) return;
        int[] ring = RangeGeometry.bastionRingOffsets(radius);
        for (int i = 0; i + 1 < ring.length; i += 2) {
            double px = bx + ring[i] + 0.5;
            double pz = bz + ring[i + 1] + 0.5;
            mesh.line(px, by, pz, px, buildHeight, pz, BASTION_STROKE, 1.5f, seeThrough);
        }
    }

    /** Shell around a snitch at the origin, built once per radius and style. */
    private static WorldOverlayMesh snitchTemplate(double radius, int stroke, int fill, boolean seeThrough) {
        TemplateKey key = new TemplateKey(false, radius, stroke, fill, seeThrough);
        WorldOverlayMesh cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        WorldOverlayMesh.Builder mesh = new WorldOverlayMesh.Builder();
        int[] shell = RangeGeometry.snitchShellOffsets(radius);
        for (int i = 0; i + 2 < shell.length; i += 3) {
            int x = shell[i], y = shell[i + 1], z = shell[i + 2];
            for (Direction face : FACES) {
                if (RangeGeometry.snitchCoversBlock(0, 0, 0,
                        x + face.getStepX(), y + face.getStepY(), z + face.getStepZ(), radius)) {
                    continue;
                }
                mesh.face(x, y, z, face, stroke, fill, 1.5f, seeThrough);
            }
        }
        return remember(key, mesh.build());
    }

    /** Bastion field around a bastion at the origin, built once per radius and style. */
    private static WorldOverlayMesh bastionTemplate(double radius, boolean seeThrough) {
        TemplateKey key = new TemplateKey(true, radius, BASTION_STROKE, BASTION_FILL, seeThrough);
        WorldOverlayMesh cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        WorldOverlayMesh.Builder mesh = new WorldOverlayMesh.Builder();
        drawDisk(mesh, 0.5, 0, 0.5, radius, BASTION_STROKE, BASTION_FILL, null, seeThrough, true);
        return remember(key, mesh.build());
    }

    private static WorldOverlayMesh remember(TemplateKey key, WorldOverlayMesh mesh) {
        if (TEMPLATES.size() > 32) TEMPLATES.clear();
        TEMPLATES.put(key, mesh);
        return mesh;
    }

    /** {@code eye} null draws the whole disk. Bastion disks include the hollow center plus. */
    private static void drawDisk(WorldOverlayMesh.Builder mesh, double centerX, int y, double centerZ, double radius,
                                 int stroke, int fill, Vec3 eye, boolean seeThrough, boolean bastion) {
        drawPerimeter(mesh, centerX, y, centerZ, radius, stroke, fill, eye, seeThrough, bastion);
        if (!bastion) return;
        if (eye != null && !nearShape(eye, centerX, y + 0.5, centerZ, radius)) return;
        drawBastionCenter(mesh, (int) Math.floor(centerX), y, (int) Math.floor(centerZ), radius, stroke, fill, eye, seeThrough);
    }

    /** Thick perimeter faces. {@code eye} null draws the whole ring. */
    private static void drawPerimeter(WorldOverlayMesh.Builder mesh, double centerX, int y, double centerZ, double radius,
                                      int stroke, int fill, Vec3 eye, boolean seeThrough, boolean bastion) {
        if (eye != null && !nearShape(eye, centerX, y + 0.5, centerZ, radius)) return;
        int[] ring = bastion
                ? RangeGeometry.bastionRingOffsets(radius)
                : RangeGeometry.diskRingOffsets(centerX, centerZ, radius);
        if (ring.length == 0) return;
        int baseX = (int) Math.floor(centerX);
        int baseZ = (int) Math.floor(centerZ);
        for (int i = 0; i + 1 < ring.length; i += 2) {
            int x = baseX + ring[i];
            int z = baseZ + ring[i + 1];
            if (eye != null && !near(eye, x + 0.5, y + 0.5, z + 0.5)) continue;
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
        }
    }

    /**
     * Hollow plus on the disk layer: the four blocks beside the bastion, which are usually obsidian.
     * The bastion block stays open. Blocks already on the perimeter are left to the ring.
     */
    private static void drawBastionCenter(WorldOverlayMesh.Builder mesh, int baseX, int y, int baseZ, double radius,
                                         int stroke, int fill, Vec3 eye, boolean seeThrough) {
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            int x = baseX + dx[i];
            int z = baseZ + dz[i];
            if (eye != null && !near(eye, x + 0.5, y + 0.5, z + 0.5)) continue;
            if (!RangeGeometry.bastionDiskCovers(baseX, baseZ, x, z, radius)) continue;
            if (RangeGeometry.bastionRingBlock(baseX, baseZ, x, z, radius)) continue;
            mesh.box(x, y, z, stroke, fill, 2f, seeThrough);
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
