package dev.openintel.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import dev.openintel.investigate.InvestigateOverlay;
import dev.openintel.viz.RangeOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * Batched overlays using Fabric's level pass and Iris's public custom-pipeline mapping.
 * Geometry is rebuilt only when its inputs change and stays on the GPU between frames.
 */
public final class WorldOverlayRenderer {
    /** One cached layer for a frame, lifted by {@code offsetY} blocks when drawn. */
    public record LayerFrame(WorldOverlayMesh mesh, double offsetY) { }

    private static final RenderStateDataKey<List<LayerFrame>> FRAME = RenderStateDataKey.create();
    private static final WorldOverlayMesh EMPTY = WorldOverlayMesh.EMPTY;
    private static final int RANGES = 0, SANCTUARIES = 1, LIVE_SANCTUARIES = 2, INVESTIGATE = 3, BASTION_HEIGHTS = 4, LAYERS = 5;
    /** Translucent faces are re-sorted after the camera moves this far from the last sort. */
    private static final double SORT_STEP_SQ = 4.0 * 4.0;

    private static net.minecraft.client.multiplayer.ClientLevel collectedLevel;
    private static final Object[] keys = new Object[LAYERS];
    private static final WorldOverlayMesh[] meshes = new WorldOverlayMesh[LAYERS];
    private static List<LayerFrame> collected = List.of();
    private static final GpuLayer[] gpu = new GpuLayer[LAYERS];

    static {
        Arrays.fill(meshes, EMPTY);
        for (int i = 0; i < LAYERS; i++) gpu[i] = new GpuLayer();
    }

    public static final RenderPipeline FILL_DEPTH = pipeline("overlay_fill_depth", false, false);
    public static final RenderPipeline FILL_TOP = pipeline("overlay_fill_top", false, true);
    public static final RenderPipeline LINE_DEPTH = pipeline("overlay_line_depth", true, false);
    public static final RenderPipeline LINE_TOP = pipeline("overlay_line_top", true, true);
    public static final RenderPipeline FILL_SHADER_DEPTH = pipeline("overlay_fill_shader_depth", false, false, -1f);
    public static final RenderPipeline LINE_SHADER_DEPTH = pipeline("overlay_line_shader_depth", true, false, -1f);
    private static Object irisApi;
    private static java.lang.reflect.Method shaderPackInUse;

    private static RenderPipeline[] pipelines() {
        return new RenderPipeline[]{FILL_DEPTH, FILL_TOP, LINE_DEPTH, LINE_TOP, FILL_SHADER_DEPTH, LINE_SHADER_DEPTH};
    }

    private WorldOverlayRenderer() { }

    private static RenderPipeline pipeline(String name, boolean lines, boolean onTop) {
        return pipeline(name, lines, onTop, -1f);
    }

    private static RenderPipeline pipeline(String name, boolean lines, boolean onTop, float bias) {
        RenderPipeline base = lines ? RenderPipelines.LINES : RenderPipelines.DEBUG_FILLED_BOX;
        return RenderPipeline.builder(lines ? RenderPipelines.LINES_SNIPPET : RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("openintel", "pipeline/" + name))
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                // This version uses conventional depth. Bias affects depth only, never world coordinates.
                // Neither pass writes/clears world depth, so later shader composites keep it intact.
                .withDepthStencilState(onTop ? new DepthStencilState(CompareOp.ALWAYS_PASS, false)
                        : new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, bias, bias))
                .build();
    }

    public static void register() {
        for (RenderPipeline p : pipelines()) RenderPipelines.register(p);
        registerIris();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            for (GpuLayer g : gpu) g.reset(false);
        });
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            context.levelState().setData(FRAME, mc.level == context.level() && collectedLevel == context.level()
                    && !mc.options.hideGui ? collected : List.of());
        });
        LevelRenderEvents.END_MAIN.register(WorldOverlayRenderer::render);
    }

    /** Checked at tick rate; a layer is rebuilt only when its key changes. */
    public static void collect(Minecraft client) {
        if (client.level != collectedLevel || client.level == null || client.player == null) {
            Arrays.fill(keys, null);
            Arrays.fill(meshes, EMPTY);
            collectedLevel = client.level;
        }
        if (client.level == null || client.player == null) {
            collected = List.of();
            return;
        }
        RangeOverlay.ShapesKey shapes = RangeOverlay.shapesKey(client);
        update(RANGES, shapes, b -> RangeOverlay.collectShapes(shapes, b));
        RangeOverlay.DiskKey disks = RangeOverlay.diskKey(client);
        update(SANCTUARIES, disks, b -> RangeOverlay.collectDisks(disks, b));
        RangeOverlay.LiveKey live = RangeOverlay.liveKey(client);
        update(LIVE_SANCTUARIES, live, b -> RangeOverlay.collectLive(live, b));
        InvestigateOverlay.Key investigate = InvestigateOverlay.key(client);
        update(INVESTIGATE, investigate, b -> InvestigateOverlay.collect(investigate, b));
        RangeOverlay.BastionHeightKey bastionHeights = RangeOverlay.bastionHeightKey(client);
        update(BASTION_HEIGHTS, bastionHeights, b -> RangeOverlay.collectBastionHeights(bastionHeights, b));
        int playerY = RangeOverlay.liveY(client);
        collected = List.of(new LayerFrame(meshes[RANGES], 0),
                new LayerFrame(meshes[SANCTUARIES], 0),
                new LayerFrame(meshes[LIVE_SANCTUARIES], playerY),
                new LayerFrame(meshes[INVESTIGATE], 0),
                new LayerFrame(meshes[BASTION_HEIGHTS], playerY));
    }

    private static void update(int layer, Object key, Consumer<WorldOverlayMesh.Builder> build) {
        if (Objects.equals(keys[layer], key) && (key != null || meshes[layer] == EMPTY)) return;
        keys[layer] = key;
        if (key == null) {
            meshes[layer] = EMPTY;
            return;
        }
        WorldOverlayMesh.Builder builder = new WorldOverlayMesh.Builder();
        build.accept(builder);
        meshes[layer] = builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerIris() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Class<? extends Enum> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram").asSubclass(Enum.class);
            Object instance = api.getMethod("getInstance").invoke(null);
            irisApi = instance;
            shaderPackInUse = api.getMethod("isShaderPackInUse");
            var assign = api.getMethod("assignPipeline", RenderPipeline.class, program);
            for (RenderPipeline p : new RenderPipeline[]{FILL_DEPTH, FILL_TOP, FILL_SHADER_DEPTH})
                assign.invoke(instance, p, Enum.valueOf(program, "BASIC"));
            for (RenderPipeline p : new RenderPipeline[]{LINE_DEPTH, LINE_TOP, LINE_SHADER_DEPTH})
                assign.invoke(instance, p, Enum.valueOf(program, "LINES"));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            LoggerFactory.getLogger("openintel").error("Unable to register overlay pipelines with Iris", e);
        }
    }

    private static boolean shadersActive() {
        if (shaderPackInUse == null) return false;
        try {
            return (boolean) shaderPackInUse.invoke(irisApi);
        } catch (ReflectiveOperationException e) {
            LoggerFactory.getLogger("openintel").error("Unable to query Iris shader state", e);
            shaderPackInUse = null;
            return false;
        }
    }

    private static void render(LevelRenderContext context) {
        List<LayerFrame> frame = context.levelState().getData(FRAME);
        if (frame == null || frame.isEmpty()) return;
        Vec3 eye = context.levelState().cameraRenderState.pos;
        boolean any = false;
        for (int i = 0; i < LAYERS; i++) {
            LayerFrame layer = i < frame.size() ? frame.get(i) : null;
            WorldOverlayMesh mesh = layer == null ? EMPTY : layer.mesh();
            double offsetY = layer == null ? 0 : layer.offsetY();
            any |= gpu[i].sync(mesh, eye.x, eye.y - offsetY, eye.z);
        }
        if (!any) return;
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f[] transforms = new Matrix4f[LAYERS];
        for (int i = 0; i < LAYERS; i++) {
            GpuLayer g = gpu[i];
            if (g.mesh.isEmpty()) continue;
            double offsetY = i < frame.size() ? frame.get(i).offsetY() : 0;
            // Vertices are relative to the layer origin; only this small translation changes per frame.
            transforms[i] = new Matrix4f(view).translate((float) (g.originX - eye.x),
                    (float) (g.originY + offsetY - eye.y), (float) (g.originZ - eye.z));
        }
        // This version uses conventional depth with and without shader packs.
        boolean shaders = shadersActive();
        for (int t = 0; t < 2; t++) {
            boolean top = t == 1;
            RenderPipeline fill = top ? FILL_TOP : shaders ? FILL_SHADER_DEPTH : FILL_DEPTH;
            RenderPipeline line = top ? LINE_TOP : shaders ? LINE_SHADER_DEPTH : LINE_DEPTH;
            for (int i = 0; i < LAYERS; i++)
                if (transforms[i] != null) draw(gpu[i].fill[t], gpu[i].fillCount[t], fill, transforms[i]);
            // Lines draw last in each depth mode.
            for (int i = 0; i < LAYERS; i++)
                if (transforms[i] != null) draw(gpu[i].line[t], gpu[i].lineCount[t], line, transforms[i]);
        }
    }

    private static void draw(GpuBuffer vertices, int count, RenderPipeline pipeline, Matrix4f modelView) {
        if (vertices == null || count == 0) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var indices = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
        var indexBuffer = indices.getBuffer(count);
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView,
                new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        try (var pass = encoder.createRenderPass(() -> "OpenIntel world overlays", target.getColorTextureView(),
                OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, 0, count, 1);
        }
    }

    /** GPU copy of one layer. Index 0 holds depth-tested geometry and index 1 the see-through pass. */
    private static final class GpuLayer {
        WorldOverlayMesh mesh = EMPTY;
        double originX, originY, originZ;
        final GpuBuffer[] fill = new GpuBuffer[2];
        final GpuBuffer[] line = new GpuBuffer[2];
        final int[] fillCount = new int[2];
        final int[] lineCount = new int[2];
        /** Same-colored faces blend identically in any order, so only mixed colors need sorting. */
        final boolean[] needsSort = new boolean[2];
        double sortX, sortY, sortZ;

        /** Uploads or re-sorts as needed; false when there is nothing to draw. */
        boolean sync(WorldOverlayMesh next, double ex, double ey, double ez) {
            if (next != mesh) {
                reset(true);
                mesh = next;
                if (mesh.isEmpty()) return false;
                originX = Math.floor(ex);
                originY = Math.floor(ey);
                originZ = Math.floor(ez);
                for (int t = 0; t < 2; t++) {
                    needsSort[t] = mixedFill(t == 1);
                    uploadLines(t);
                    uploadFills(t, ex, ey, ez);
                }
                sortX = ex; sortY = ey; sortZ = ez;
                return true;
            }
            if (mesh.isEmpty()) return false;
            double dx = ex - sortX, dy = ey - sortY, dz = ez - sortZ;
            if ((needsSort[0] || needsSort[1]) && dx * dx + dy * dy + dz * dz > SORT_STEP_SQ) {
                for (int t = 0; t < 2; t++) if (needsSort[t]) uploadFills(t, ex, ey, ez);
                sortX = ex; sortY = ey; sortZ = ez;
            }
            return true;
        }

        private boolean mixedFill(boolean top) {
            boolean seen = false;
            int color = 0;
            for (int i = 0; i < mesh.faceCount; i++) {
                if (mesh.faceTop[i] != top) continue;
                if (!seen) { seen = true; color = mesh.faceColors[i]; }
                else if (mesh.faceColors[i] != color) return true;
            }
            return false;
        }

        private void uploadFills(int t, double ex, double ey, double ez) {
            retire(fill[t]);
            fill[t] = null;
            fillCount[t] = 0;
            boolean top = t == 1;
            WorldOverlayMesh m = mesh;
            int n = 0;
            for (int i = 0; i < m.faceCount; i++) if (m.faceTop[i] == top) n++;
            if (n == 0) return;
            int[] order = new int[n];
            if (needsSort[t]) {
                // Far faces first; ties keep the later face first, like the reversed stable sort it replaces.
                long[] keyed = new long[n];
                int k = 0;
                for (int i = 0; i < m.faceCount; i++) {
                    if (m.faceTop[i] != top) continue;
                    int o = i * 12;
                    double cx = (m.faceVerts[o] + m.faceVerts[o + 6]) * 0.5 - ex;
                    double cy = (m.faceVerts[o + 1] + m.faceVerts[o + 7]) * 0.5 - ey;
                    double cz = (m.faceVerts[o + 2] + m.faceVerts[o + 8]) * 0.5 - ez;
                    keyed[k++] = ((long) Float.floatToRawIntBits((float) (cx * cx + cy * cy + cz * cz)) << 32) | i;
                }
                Arrays.sort(keyed);
                for (int j = 0; j < n; j++) order[j] = (int) keyed[n - 1 - j];
            } else {
                int k = 0;
                for (int i = 0; i < m.faceCount; i++) if (m.faceTop[i] == top) order[k++] = i;
            }
            RenderPipeline format = FILL_DEPTH;
            try (ByteBufferBuilder memory = new ByteBufferBuilder(Math.max(1024, n * 64))) {
                BufferBuilder vertices = new BufferBuilder(memory, format.getVertexFormatMode(), format.getVertexFormat());
                for (int i : order) {
                    int o = i * 12, color = m.faceColors[i];
                    for (int c = 0; c < 12; c += 3) {
                        vertices.addVertex((float) (m.faceVerts[o + c] - originX), (float) (m.faceVerts[o + c + 1] - originY),
                                (float) (m.faceVerts[o + c + 2] - originZ)).setColor(color);
                    }
                }
                upload(vertices.build(), fill, fillCount, t, "OpenIntel overlay faces");
            }
        }

        private void uploadLines(int t) {
            boolean top = t == 1;
            WorldOverlayMesh m = mesh;
            int n = 0;
            for (int i = 0; i < m.lineCount; i++) if (m.lineTop[i] == top) n++;
            if (n == 0) return;
            RenderPipeline format = LINE_DEPTH;
            try (ByteBufferBuilder memory = new ByteBufferBuilder(Math.max(1024, n * 96))) {
                BufferBuilder vertices = new BufferBuilder(memory, format.getVertexFormatMode(), format.getVertexFormat());
                for (int i = 0; i < m.lineCount; i++) {
                    if (m.lineTop[i] != top) continue;
                    int o = i * 6;
                    double dx = m.lineVerts[o + 3] - m.lineVerts[o];
                    double dy = m.lineVerts[o + 4] - m.lineVerts[o + 1];
                    double dz = m.lineVerts[o + 5] - m.lineVerts[o + 2];
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float nx = (float) (dx / len), ny = (float) (dy / len), nz = (float) (dz / len);
                    for (int p = 0; p < 6; p += 3) {
                        vertices.addVertex((float) (m.lineVerts[o + p] - originX), (float) (m.lineVerts[o + p + 1] - originY),
                                        (float) (m.lineVerts[o + p + 2] - originZ))
                                .setColor(m.lineColors[i]).setNormal(nx, ny, nz).setLineWidth(m.lineWidths[i]);
                    }
                }
                upload(vertices.build(), line, lineCount, t, "OpenIntel overlay lines");
            }
        }

        private static void upload(MeshData data, GpuBuffer[] buffers, int[] counts, int t, String label) {
            if (data == null) return;
            try (data) {
                buffers[t] = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
                counts[t] = data.drawState().indexCount();
            }
        }

        /** Buffers replaced mid-session may still be read by queued frames, so they close after the GPU fence. */
        private static void retire(GpuBuffer buffer) {
            if (buffer != null) RenderSystem.queueFencedTask(buffer::close);
        }

        void reset(boolean fenced) {
            for (int t = 0; t < 2; t++) {
                if (fenced) {
                    retire(fill[t]);
                    retire(line[t]);
                } else {
                    if (fill[t] != null) fill[t].close();
                    if (line[t] != null) line[t].close();
                }
                fill[t] = null;
                line[t] = null;
                fillCount[t] = 0;
                lineCount[t] = 0;
            }
            if (!fenced) mesh = EMPTY;
        }
    }
}
