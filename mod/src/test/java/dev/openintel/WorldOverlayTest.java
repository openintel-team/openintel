package dev.openintel;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.openintel.render.WorldOverlayMesh;
import dev.openintel.render.WorldOverlayRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Checks actual geometry and vanilla buffer formats without a running client/GPU. */
public final class WorldOverlayTest {
    public static void main(String[] args) {
        for (int x : new int[]{0, -12, 29_999_998}) {
            var builder = new WorldOverlayMesh.Builder();
            builder.box(x, 64, -32, 0xFFFF0000, 0x66FF0000, 3.5f, true);
            var mesh = builder.build();
            check(mesh.faces().size() == 6, "Six box faces");
            check(mesh.lines().size() == 12, "Twelve unique edges");
            for (var face : mesh.faces()) {
                check(face.onTop(), "Investigate remains see-through");
                for (Vec3 v : new Vec3[]{face.a(), face.b(), face.c(), face.d()}) {
                    check(v.x == x || v.x == x + 1.0, "Exact X boundaries, including distant coordinates");
                    check(v.y == 64 || v.y == 65, "Exact Y boundaries");
                    check(v.z == -32 || v.z == -31, "Exact Z boundaries");
                }
            }
            // Snapshot survives further builder mutations and cannot be changed by the drawing thread.
            builder.line(Vec3.ZERO, new Vec3(1, 1, 1), -1, 2, false);
            check(mesh.lines().size() == 12, "Immutable frame snapshot");
            try { mesh.faces().clear(); throw new AssertionError("Mutable snapshot"); }
            catch (UnsupportedOperationException expected) { }
        }
        var adjacent = new WorldOverlayMesh.Builder();
        adjacent.face(0, 64, 0, Direction.UP, -1, 0x330000FF, 2, false);
        adjacent.face(1, 64, 0, Direction.UP, -1, 0x330000FF, 2, false);
        check(adjacent.build().lines().size() == 7, "Shared border is drawn once");
        adjacent.line(Vec3.ZERO, Vec3.ZERO, -1, 2, false);
        check(adjacent.build().lines().size() == 7, "Degenerate lines are ignored");

        for (var p : new com.mojang.blaze3d.pipeline.RenderPipeline[]{
                WorldOverlayRenderer.FILL_DEPTH, WorldOverlayRenderer.FILL_TOP,
                WorldOverlayRenderer.LINE_DEPTH, WorldOverlayRenderer.LINE_TOP,
                WorldOverlayRenderer.FILL_SHADER_DEPTH, WorldOverlayRenderer.LINE_SHADER_DEPTH}) {
            var depth = p.getDepthStencilState();
            boolean top = p == WorldOverlayRenderer.FILL_TOP || p == WorldOverlayRenderer.LINE_TOP;
            check(!depth.writeDepth(), "Overlays preserve shaderpack/world depth");
            check(depth.depthTest() == (top ? CompareOp.ALWAYS_PASS : CompareOp.LESS_THAN_OR_EQUAL), "Reverse-Z depth policy");
            boolean shader = p == WorldOverlayRenderer.FILL_SHADER_DEPTH || p == WorldOverlayRenderer.LINE_SHADER_DEPTH;
            check(top ? depth.depthBiasScaleFactor() == 0 : shader ? depth.depthBiasScaleFactor() < 0 : depth.depthBiasScaleFactor() < 0,
                    "Only surface overlays use depth bias");
            check(!p.isCull(), "Shells visible from inside and outside");
            boolean lines = p == WorldOverlayRenderer.LINE_DEPTH || p == WorldOverlayRenderer.LINE_TOP || p == WorldOverlayRenderer.LINE_SHADER_DEPTH;
            check(p.getVertexFormatMode() == (lines ? VertexFormat.Mode.LINES : VertexFormat.Mode.QUADS), "Batch topology");
            try (var memory = new ByteBufferBuilder(1024)) {
                var vertices = new BufferBuilder(memory, p.getVertexFormatMode(), p.getVertexFormat());
                int n = lines ? 2 : 4;
                for (int i = 0; i < n; i++) {
                    var v = vertices.addVertex(i & 1, i >> 1, 0).setColor(-1);
                    if (lines) v.setNormal(1, 0, 0).setLineWidth(3.5f);
                }
                try (var mesh = vertices.buildOrThrow()) {
                    check(mesh.drawState().indexCount() == 6, "A line/quad expands to two GPU triangles");
                    check(mesh.vertexBuffer().remaining() > 0, "Real packed vertices emitted");
                }
            }
        }
        System.out.println("World overlay geometry, depth policies, and vertex batches passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
