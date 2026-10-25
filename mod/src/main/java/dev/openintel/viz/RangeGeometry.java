package dev.openintel.viz;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Civ+ coverage as whole blocks, measured block-center to block-center.
 *
 * <ul>
 *   <li>Snitch: block sphere. "8 blocks out" covers every block whose center
 *       is within 8.5 of the snitch center, so the cardinal arm is 8 blocks
 *       and the shoulders step inward (8,8,8,7,7,6,6,4,2). The drawn shape
 *       is the outer shell, not the filled ball.</li>
 *   <li>Bastion: Civ+ field when the setting is still the default. That field
 *       is 19 blocks across. From the bastion block outward the row reaches
 *       are 9,9,9,9,9,8,7,7,5,4. A block is inside when its horizontal
 *       distance squared is under 100, so the blocks at distance exactly 10
 *       stay outside. Covered blocks have Y at or above the bastion block,
 *       with no upper limit. Outline posts are drawn up to the dimension
 *       build limit, and blocks below the bastion are uncovered. Any other
 *       radius uses a block-center disk.</li>
 *   <li>Sanctuary: the same horizontal disk with a caller-supplied radius.</li>
 * </ul>
 *
 * {@link #SNITCH_RADIUS} and {@link #BASTION_RADIUS} are defaults. Overlays
 * use the values from client settings.
 */
public final class RangeGeometry {
    private RangeGeometry() { }

    public static final double SNITCH_RADIUS = 8.0;
    /**
     * Setting value that selects the Civ+ bastion field. The field itself is
     * not a radius-10.5 disk; {@link #BASTION_DIAMETER} is the cardinal width.
     */
    public static final double BASTION_RADIUS = 10.5;
    /** Cardinal width of the Civ+ bastion field, bastion block included. */
    public static final double BASTION_DIAMETER = 19.0;
    /** dx² + dz² must be strictly under this for the Civ+ field. */
    private static final int CIV_BASTION_LIMIT_SQ = 100;

    private static final Map<Long, int[]> SNITCH_SHELLS = new HashMap<>();
    private static final Map<DiskKey, int[]> DISK_RINGS = new HashMap<>();
    private static volatile int[] CIV_BASTION_RING;

    public static boolean snitchCoversBlock(int originX, int originY, int originZ,
                                            int blockX, int blockY, int blockZ) {
        return snitchCoversBlock(originX, originY, originZ, blockX, blockY, blockZ, SNITCH_RADIUS);
    }

    /**
     * Covered when the block center is within {@code blocksOut + 0.5} of the
     * snitch center. {@code blocksOut} 8 matches the Civ+ checkerboard circle.
     */
    public static boolean snitchCoversBlock(int originX, int originY, int originZ,
                                            int blockX, int blockY, int blockZ, double blocksOut) {
        if (!validRadius(blocksOut)) return false;
        return offsetInside(blockX - originX, blockY - originY, blockZ - originZ, blocksOut);
    }

    /** Outer shell: covered, and at least one face-adjacent neighbor is not. */
    public static boolean snitchShellBlock(int originX, int originY, int originZ,
                                           int blockX, int blockY, int blockZ, double radius) {
        if (!snitchCoversBlock(originX, originY, originZ, blockX, blockY, blockZ, radius)) return false;
        return !snitchCoversBlock(originX, originY, originZ, blockX + 1, blockY, blockZ, radius)
                || !snitchCoversBlock(originX, originY, originZ, blockX - 1, blockY, blockZ, radius)
                || !snitchCoversBlock(originX, originY, originZ, blockX, blockY + 1, blockZ, radius)
                || !snitchCoversBlock(originX, originY, originZ, blockX, blockY - 1, blockZ, radius)
                || !snitchCoversBlock(originX, originY, originZ, blockX, blockY, blockZ + 1, radius)
                || !snitchCoversBlock(originX, originY, originZ, blockX, blockY, blockZ - 1, radius);
    }

    /**
     * Shell offsets relative to the snitch block, packed as x,y,z triples.
     * Empty when the radius is not drawable.
     */
    public static int[] snitchShellOffsets(double radius) {
        if (!validRadius(radius) || radius > 64) return new int[0];
        long key = Double.doubleToLongBits(radius);
        int[] cached = SNITCH_SHELLS.get(key);
        if (cached != null) return cached;
        int[] built = buildSnitchShell(radius);
        if (SNITCH_SHELLS.size() > 32) SNITCH_SHELLS.clear();
        SNITCH_SHELLS.put(key, built);
        return built;
    }

    public static boolean bastionCoversBlock(int originX, int originY, int originZ,
                                             int blockX, int blockY, int blockZ) {
        return bastionCoversBlock(originX, originY, originZ, blockX, blockY, blockZ, BASTION_RADIUS);
    }

    public static boolean bastionCoversBlock(int originX, int originY, int originZ,
                                             int blockX, int blockY, int blockZ, double radius) {
        if (blockY < originY) return false;
        return bastionDiskCovers(originX, originZ, blockX, blockZ, radius);
    }

    /**
     * Horizontal bastion footprint. The default setting uses the Civ+ field.
     * Other radii use the same block-center disk as sanctuaries.
     */
    public static boolean bastionDiskCovers(int originX, int originZ,
                                            int blockX, int blockZ, double radius) {
        if (!validRadius(radius)) return false;
        if (civBastion(radius)) {
            int dx = blockX - originX;
            int dz = blockZ - originZ;
            return (long) dx * dx + (long) dz * dz < CIV_BASTION_LIMIT_SQ;
        }
        return diskCoversBlock(originX + 0.5, originZ + 0.5, radius, blockX, blockZ);
    }

    /** Perimeter of {@link #bastionDiskCovers}. */
    public static boolean bastionRingBlock(int originX, int originZ,
                                           int blockX, int blockZ, double radius) {
        if (!bastionDiskCovers(originX, originZ, blockX, blockZ, radius)) return false;
        return !bastionDiskCovers(originX, originZ, blockX + 1, blockZ, radius)
                || !bastionDiskCovers(originX, originZ, blockX - 1, blockZ, radius)
                || !bastionDiskCovers(originX, originZ, blockX, blockZ + 1, radius)
                || !bastionDiskCovers(originX, originZ, blockX, blockZ - 1, radius);
    }

    /**
     * Ring offsets packed as dx,dz relative to the bastion block.
     * Empty when the radius is not drawable.
     */
    public static int[] bastionRingOffsets(double radius) {
        if (!validRadius(radius) || radius > 64) return new int[0];
        if (!civBastion(radius)) return diskRingOffsets(0.5, 0.5, radius);
        int[] cached = CIV_BASTION_RING;
        if (cached != null) return cached;
        int[] built = buildBastionRing(radius);
        CIV_BASTION_RING = built;
        return built;
    }

    private static boolean civBastion(double radius) {
        return Double.doubleToLongBits(radius) == Double.doubleToLongBits(BASTION_RADIUS);
    }

    public static boolean sanctuaryCoversBlock(int originX, int originZ, double radius,
                                               int blockX, int blockZ) {
        return diskCoversBlock(originX + 0.5, originZ + 0.5, radius, blockX, blockZ);
    }

    /** Horizontal coverage against an arbitrary world-space center (live-map feed). */
    public static boolean diskCoversBlock(double centerX, double centerZ, double radius,
                                          int blockX, int blockZ) {
        if (!validRadius(radius)) return false;
        double dx = blockX + 0.5 - centerX;
        double dz = blockZ + 0.5 - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Perimeter of {@link #diskCoversBlock}: covered, with a horizontal neighbor outside. */
    public static boolean diskRingBlock(double centerX, double centerZ, double radius,
                                        int blockX, int blockZ) {
        if (!diskCoversBlock(centerX, centerZ, radius, blockX, blockZ)) return false;
        return !diskCoversBlock(centerX, centerZ, radius, blockX + 1, blockZ)
                || !diskCoversBlock(centerX, centerZ, radius, blockX - 1, blockZ)
                || !diskCoversBlock(centerX, centerZ, radius, blockX, blockZ + 1)
                || !diskCoversBlock(centerX, centerZ, radius, blockX, blockZ - 1);
    }

    /**
     * Ring offsets packed as dx,dz relative to {@code floor(center)}.
     * Empty when the radius is not drawable.
     */
    public static int[] diskRingOffsets(double centerX, double centerZ, double radius) {
        if (!validRadius(radius) || radius > 2048) return new int[0];
        DiskKey key = diskKey(centerX, centerZ, radius);
        int[] cached = DISK_RINGS.get(key);
        if (cached != null) return cached;
        int[] built = buildDiskRing(centerX, centerZ, radius);
        if (DISK_RINGS.size() > 128) DISK_RINGS.clear();
        DISK_RINGS.put(key, built);
        return built;
    }

    private static int[] buildSnitchShell(double radius) {
        int r = (int) Math.ceil(radius);
        IntBuf buf = new IntBuf();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!shellOffset(dx, dy, dz, radius)) continue;
                    buf.add(dx);
                    buf.add(dy);
                    buf.add(dz);
                }
            }
        }
        return buf.toArray();
    }

    private static boolean shellOffset(int dx, int dy, int dz, double radius) {
        if (!offsetInside(dx, dy, dz, radius)) return false;
        return !offsetInside(dx + 1, dy, dz, radius)
                || !offsetInside(dx - 1, dy, dz, radius)
                || !offsetInside(dx, dy + 1, dz, radius)
                || !offsetInside(dx, dy - 1, dz, radius)
                || !offsetInside(dx, dy, dz + 1, radius)
                || !offsetInside(dx, dy, dz - 1, radius);
    }

    /**
     * Integer block offsets are distances between block centers. A field that
     * reaches N blocks out includes the outer face of that block, so the
     * limit is N + 0.5.
     */
    private static boolean offsetInside(int dx, int dy, int dz, double blocksOut) {
        double reach = blocksOut + 0.5;
        double x = dx, y = dy, z = dz;
        return x * x + y * y + z * z <= reach * reach;
    }

    private static int[] buildBastionRing(double radius) {
        int r = (int) Math.ceil(radius) + 1;
        IntBuf buf = new IntBuf();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!bastionRingBlock(0, 0, dx, dz, radius)) continue;
                buf.add(dx);
                buf.add(dz);
            }
        }
        return buf.toArray();
    }

    private static int[] buildDiskRing(double centerX, double centerZ, double radius) {
        int minX = (int) Math.floor(centerX - radius) - 1;
        int maxX = (int) Math.ceil(centerX + radius) + 1;
        int minZ = (int) Math.floor(centerZ - radius) - 1;
        int maxZ = (int) Math.ceil(centerZ + radius) + 1;
        int baseX = (int) Math.floor(centerX);
        int baseZ = (int) Math.floor(centerZ);
        IntBuf buf = new IntBuf();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!diskRingBlock(centerX, centerZ, radius, x, z)) continue;
                buf.add(x - baseX);
                buf.add(z - baseZ);
            }
        }
        return buf.toArray();
    }

    private static DiskKey diskKey(double centerX, double centerZ, double radius) {
        double fracX = centerX - Math.floor(centerX);
        double fracZ = centerZ - Math.floor(centerZ);
        return new DiskKey(Double.doubleToLongBits(fracX), Double.doubleToLongBits(fracZ),
                Double.doubleToLongBits(radius));
    }

    private static boolean validRadius(double radius) {
        return radius > 0 && Double.isFinite(radius);
    }

    private record DiskKey(long fracX, long fracZ, long radiusBits) { }

    private static final class IntBuf {
        private int[] data = new int[256];
        private int n;

        void add(int v) {
            if (n == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[n++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(data, n);
        }
    }
}
