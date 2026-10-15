package dev.openintel.render;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Immutable world-space geometry. Vertices stay on block boundaries; depth policy belongs to the pipeline. */
public record WorldOverlayMesh(List<Face> faces, List<Line> lines) {
    public record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean onTop) {
        public Vec3 center() { return a.add(c).scale(0.5); }
    }
    public record Line(Vec3 a, Vec3 b, int color, float width, boolean onTop) { }

    public static final class Builder {
        private final List<Face> faces = new ArrayList<>();
        private final LinkedHashSet<Line> lines = new LinkedHashSet<>();

        public void line(Vec3 a, Vec3 b, int color, float width, boolean onTop) {
            if (a.equals(b)) return;
            // Canonical endpoints prevent shared face borders getting darker or thicker.
            if (compare(a, b) > 0) { Vec3 swap = a; a = b; b = swap; }
            lines.add(new Line(a, b, color, width, onTop));
        }

        public void face(int x, int y, int z, Direction side, int stroke, int fill, float width, boolean onTop) {
            Vec3 a, b, c, d;
            switch (side) {
                case DOWN, UP -> {
                    double py = side == Direction.UP ? y + 1.0 : y;
                    a = new Vec3(x, py, z); b = new Vec3(x + 1.0, py, z);
                    c = new Vec3(x + 1.0, py, z + 1.0); d = new Vec3(x, py, z + 1.0);
                }
                case NORTH, SOUTH -> {
                    double pz = side == Direction.SOUTH ? z + 1.0 : z;
                    a = new Vec3(x, y, pz); b = new Vec3(x + 1.0, y, pz);
                    c = new Vec3(x + 1.0, y + 1.0, pz); d = new Vec3(x, y + 1.0, pz);
                }
                default -> {
                    double px = side == Direction.EAST ? x + 1.0 : x;
                    a = new Vec3(px, y, z); b = new Vec3(px, y, z + 1.0);
                    c = new Vec3(px, y + 1.0, z + 1.0); d = new Vec3(px, y + 1.0, z);
                }
            }
            faces.add(new Face(a, b, c, d, fill, onTop));
            line(a, b, stroke, width, onTop); line(b, c, stroke, width, onTop);
            line(c, d, stroke, width, onTop); line(d, a, stroke, width, onTop);
        }

        public void box(int x, int y, int z, int stroke, int fill, float width, boolean onTop) {
            for (Direction side : Direction.values()) face(x, y, z, side, stroke, fill, width, onTop);
        }

        public WorldOverlayMesh build() { return new WorldOverlayMesh(List.copyOf(faces), List.copyOf(lines)); }

        private static int compare(Vec3 a, Vec3 b) {
            int c = Double.compare(a.x, b.x);
            if (c == 0) c = Double.compare(a.y, b.y);
            if (c == 0) c = Double.compare(a.z, b.z);
            return c;
        }
    }
}
