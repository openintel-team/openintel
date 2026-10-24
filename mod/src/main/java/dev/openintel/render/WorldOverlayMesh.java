package dev.openintel.render;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable world-space geometry. Vertices stay on block boundaries; depth policy belongs to the pipeline.
 * Stored as flat arrays: a busy coverage scene has tens of thousands of faces and is rebuilt while moving.
 */
public final class WorldOverlayMesh {
    public record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean onTop) {
        public Vec3 center() { return a.add(c).scale(0.5); }
    }
    public record Line(Vec3 a, Vec3 b, int color, float width, boolean onTop) { }

    public static final WorldOverlayMesh EMPTY = new Builder().build();

    /** Four corners per face, x/y/z each. */
    final double[] faceVerts;
    final int[] faceColors;
    final boolean[] faceTop;
    final int faceCount;
    /** Two endpoints per line, x/y/z each. */
    final double[] lineVerts;
    final int[] lineColors;
    final float[] lineWidths;
    final boolean[] lineTop;
    final int lineCount;

    private WorldOverlayMesh(Builder b) {
        faceCount = b.faceCount;
        faceVerts = Arrays.copyOf(b.faceVerts, faceCount * 12);
        faceColors = Arrays.copyOf(b.faceColors, faceCount);
        faceTop = Arrays.copyOf(b.faceTop, faceCount);
        lineCount = b.lineCount;
        lineVerts = Arrays.copyOf(b.lineVerts, lineCount * 6);
        lineColors = Arrays.copyOf(b.lineColors, lineCount);
        lineWidths = Arrays.copyOf(b.lineWidths, lineCount);
        lineTop = Arrays.copyOf(b.lineTop, lineCount);
    }

    public boolean isEmpty() { return faceCount == 0 && lineCount == 0; }
    public int faceCount() { return faceCount; }
    public int lineCount() { return lineCount; }

    public List<Face> faces() {
        List<Face> out = new ArrayList<>(faceCount);
        for (int i = 0; i < faceCount; i++) {
            int o = i * 12;
            out.add(new Face(vec(faceVerts, o), vec(faceVerts, o + 3), vec(faceVerts, o + 6), vec(faceVerts, o + 9),
                    faceColors[i], faceTop[i]));
        }
        return List.copyOf(out);
    }

    public List<Line> lines() {
        List<Line> out = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            int o = i * 6;
            out.add(new Line(vec(lineVerts, o), vec(lineVerts, o + 3), lineColors[i], lineWidths[i], lineTop[i]));
        }
        return List.copyOf(out);
    }

    private static Vec3 vec(double[] v, int o) { return new Vec3(v[o], v[o + 1], v[o + 2]); }

    public static final class Builder {
        private double[] faceVerts = new double[12 * 64];
        private int[] faceColors = new int[64];
        private boolean[] faceTop = new boolean[64];
        private int faceCount;
        private double[] lineVerts = new double[6 * 128];
        private int[] lineColors = new int[128];
        private float[] lineWidths = new float[128];
        private boolean[] lineTop = new boolean[128];
        private int lineCount;
        /** Open-addressed set of line indices + 1; dedupes shared borders without allocating per edge. */
        private int[] table = new int[256];

        public void line(Vec3 a, Vec3 b, int color, float width, boolean onTop) {
            line(a.x, a.y, a.z, b.x, b.y, b.z, color, width, onTop);
        }

        public void line(double ax, double ay, double az, double bx, double by, double bz,
                         int color, float width, boolean onTop) {
            if (ax == bx && ay == by && az == bz) return;
            // Canonical endpoints prevent shared face borders getting darker or thicker.
            if (compare(ax, ay, az, bx, by, bz) > 0) {
                double t;
                t = ax; ax = bx; bx = t;
                t = ay; ay = by; by = t;
                t = az; az = bz; bz = t;
            }
            int hash = hash(ax, ay, az, bx, by, bz, color, width, onTop);
            int mask = table.length - 1;
            for (int slot = hash & mask; ; slot = (slot + 1) & mask) {
                int entry = table[slot];
                if (entry == 0) break;
                int i = entry - 1, o = i * 6;
                if (lineVerts[o] == ax && lineVerts[o + 1] == ay && lineVerts[o + 2] == az
                        && lineVerts[o + 3] == bx && lineVerts[o + 4] == by && lineVerts[o + 5] == bz
                        && lineColors[i] == color && lineWidths[i] == width && lineTop[i] == onTop) return;
            }
            if (lineCount == lineColors.length) growLines();
            int i = lineCount++, o = i * 6;
            lineVerts[o] = ax; lineVerts[o + 1] = ay; lineVerts[o + 2] = az;
            lineVerts[o + 3] = bx; lineVerts[o + 4] = by; lineVerts[o + 5] = bz;
            lineColors[i] = color; lineWidths[i] = width; lineTop[i] = onTop;
            if (lineCount * 2 > table.length) rehash(); else insert(hash, i);
        }

        public void face(int x, int y, int z, Direction side, int stroke, int fill, float width, boolean onTop) {
            if (faceCount == faceColors.length) growFaces();
            int o = faceCount * 12;
            double[] v = faceVerts;
            switch (side) {
                case DOWN, UP -> {
                    double py = side == Direction.UP ? y + 1.0 : y;
                    set(v, o, x, py, z); set(v, o + 3, x + 1.0, py, z);
                    set(v, o + 6, x + 1.0, py, z + 1.0); set(v, o + 9, x, py, z + 1.0);
                }
                case NORTH, SOUTH -> {
                    double pz = side == Direction.SOUTH ? z + 1.0 : z;
                    set(v, o, x, y, pz); set(v, o + 3, x + 1.0, y, pz);
                    set(v, o + 6, x + 1.0, y + 1.0, pz); set(v, o + 9, x, y + 1.0, pz);
                }
                default -> {
                    double px = side == Direction.EAST ? x + 1.0 : x;
                    set(v, o, px, y, z); set(v, o + 3, px, y, z + 1.0);
                    set(v, o + 6, px, y + 1.0, z + 1.0); set(v, o + 9, px, y + 1.0, z);
                }
            }
            faceColors[faceCount] = fill;
            faceTop[faceCount] = onTop;
            faceCount++;
            for (int e = 0; e < 4; e++) {
                int p = o + e * 3, q = o + ((e + 1) & 3) * 3;
                line(v[p], v[p + 1], v[p + 2], v[q], v[q + 1], v[q + 2], stroke, width, onTop);
            }
        }

        public void box(int x, int y, int z, int stroke, int fill, float width, boolean onTop) {
            for (Direction side : Direction.values()) face(x, y, z, side, stroke, fill, width, onTop);
        }

        /**
         * Copies a prebuilt part shifted by whole blocks. Its lines are not deduplicated against lines
         * already in this builder, which only matters for translucent strokes on coincident edges.
         */
        public void append(WorldOverlayMesh part, int dx, int dy, int dz) {
            while (faceColors.length < faceCount + part.faceCount) growFaces();
            for (int i = 0; i < part.faceCount; i++) {
                int src = i * 12, dst = (faceCount + i) * 12;
                for (int c = 0; c < 12; c += 3) {
                    faceVerts[dst + c] = part.faceVerts[src + c] + dx;
                    faceVerts[dst + c + 1] = part.faceVerts[src + c + 1] + dy;
                    faceVerts[dst + c + 2] = part.faceVerts[src + c + 2] + dz;
                }
            }
            System.arraycopy(part.faceColors, 0, faceColors, faceCount, part.faceCount);
            System.arraycopy(part.faceTop, 0, faceTop, faceCount, part.faceCount);
            faceCount += part.faceCount;
            while (lineColors.length < lineCount + part.lineCount) growLines();
            for (int i = 0; i < part.lineCount; i++) {
                int src = i * 6, dst = (lineCount + i) * 6;
                for (int c = 0; c < 6; c += 3) {
                    lineVerts[dst + c] = part.lineVerts[src + c] + dx;
                    lineVerts[dst + c + 1] = part.lineVerts[src + c + 1] + dy;
                    lineVerts[dst + c + 2] = part.lineVerts[src + c + 2] + dz;
                }
            }
            System.arraycopy(part.lineColors, 0, lineColors, lineCount, part.lineCount);
            System.arraycopy(part.lineWidths, 0, lineWidths, lineCount, part.lineCount);
            System.arraycopy(part.lineTop, 0, lineTop, lineCount, part.lineCount);
            lineCount += part.lineCount;
        }

        public WorldOverlayMesh build() { return new WorldOverlayMesh(this); }

        private static void set(double[] v, int o, double x, double y, double z) {
            v[o] = x; v[o + 1] = y; v[o + 2] = z;
        }

        private void growFaces() {
            int n = faceColors.length * 2;
            faceVerts = Arrays.copyOf(faceVerts, n * 12);
            faceColors = Arrays.copyOf(faceColors, n);
            faceTop = Arrays.copyOf(faceTop, n);
        }

        private void growLines() {
            int n = lineColors.length * 2;
            lineVerts = Arrays.copyOf(lineVerts, n * 6);
            lineColors = Arrays.copyOf(lineColors, n);
            lineWidths = Arrays.copyOf(lineWidths, n);
            lineTop = Arrays.copyOf(lineTop, n);
        }

        private void rehash() {
            table = new int[table.length * 2];
            for (int i = 0; i < lineCount; i++) {
                int o = i * 6;
                insert(hash(lineVerts[o], lineVerts[o + 1], lineVerts[o + 2], lineVerts[o + 3], lineVerts[o + 4],
                        lineVerts[o + 5], lineColors[i], lineWidths[i], lineTop[i]), i);
            }
        }

        private void insert(int hash, int index) {
            int mask = table.length - 1;
            int slot = hash & mask;
            while (table[slot] != 0) slot = (slot + 1) & mask;
            table[slot] = index + 1;
        }

        private static int hash(double ax, double ay, double az, double bx, double by, double bz,
                                int color, float width, boolean onTop) {
            // Whole-block doubles keep all their information in the high bits, so each part is mixed alone.
            long h = mix(Double.doubleToLongBits(ax));
            h = mix(h ^ Double.doubleToLongBits(ay));
            h = mix(h ^ Double.doubleToLongBits(az));
            h = mix(h ^ Double.doubleToLongBits(bx));
            h = mix(h ^ Double.doubleToLongBits(by));
            h = mix(h ^ Double.doubleToLongBits(bz));
            h = mix(h ^ (((long) color << 32) | (Float.floatToIntBits(width) & 0xFFFFFFFFL)) ^ (onTop ? 1 : 0));
            return (int) (h ^ (h >>> 32));
        }

        /** SplitMix64 finalizer. */
        private static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        private static int compare(double ax, double ay, double az, double bx, double by, double bz) {
            int c = Double.compare(ax, bx);
            if (c == 0) c = Double.compare(ay, by);
            if (c == 0) c = Double.compare(az, bz);
            return c;
        }
    }
}
