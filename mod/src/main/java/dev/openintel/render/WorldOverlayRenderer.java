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

import java.util.Comparator;
import java.util.OptionalInt;
import java.util.OptionalDouble;

/** Batched overlays using Fabric's level pass and Iris's public custom-pipeline mapping. */
public final class WorldOverlayRenderer {
    private static final RenderStateDataKey<WorldOverlayMesh> MESH = RenderStateDataKey.create();
    private static final WorldOverlayMesh EMPTY = new WorldOverlayMesh(java.util.List.of(), java.util.List.of());
    private static net.minecraft.client.multiplayer.ClientLevel collectedLevel;
    private static WorldOverlayMesh collected = EMPTY;
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

    private static final java.util.Map<RenderPipeline, net.minecraft.client.renderer.MappableRingBuffer> BUFFERS = new java.util.HashMap<>();

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
            BUFFERS.values().forEach(net.minecraft.client.renderer.MappableRingBuffer::close);
            BUFFERS.clear();
        });
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            context.levelState().setData(MESH, mc.level == context.level() && collectedLevel == context.level()
                    && !mc.options.hideGui ? collected : EMPTY);
        });
        LevelRenderEvents.END_MAIN.register(WorldOverlayRenderer::render);
    }

    /** Rebuild at tick rate; frame extraction only attaches immutable geometry. Camera-relative vertices are rebuilt each frame. */
    public static void collect(Minecraft client) {
        collectedLevel = client.level;
        if (client.level == null || client.player == null) {
            collected = EMPTY;
            return;
        }
        WorldOverlayMesh.Builder geometry = new WorldOverlayMesh.Builder();
        RangeOverlay.collect(client, geometry);
        InvestigateOverlay.collect(client, geometry);
        collected = geometry.build();
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
        WorldOverlayMesh mesh = context.levelState().getData(MESH);
        if (mesh == null || (mesh.faces().isEmpty() && mesh.lines().isEmpty())) return;
        Vec3 eye = context.levelState().cameraRenderState.pos;
        // Stable sorting blends far translucent faces before near ones. Lines draw last in each depth mode.
        var faces = mesh.faces().stream().sorted(Comparator.comparingDouble(
                (WorldOverlayMesh.Face f) -> f.center().distanceToSqr(eye)).reversed()).toList();
        // This version uses conventional depth with and without shader packs.
        boolean shaders = shadersActive();
        for (boolean top : new boolean[]{false, true}) {
            RenderPipeline fill = top ? FILL_TOP : shaders ? FILL_SHADER_DEPTH : FILL_DEPTH;
            RenderPipeline line = top ? LINE_TOP : shaders ? LINE_SHADER_DEPTH : LINE_DEPTH;
            try (ByteBufferBuilder memory = new ByteBufferBuilder(256 * 1024)) {
                BufferBuilder vertices = new BufferBuilder(memory, fill.getVertexFormatMode(), fill.getVertexFormat());
                for (var face : faces) if (face.onTop() == top) {
                    for (Vec3 p : new Vec3[]{face.a(), face.b(), face.c(), face.d()}) {
                        Vec3 v = p.subtract(eye);
                        vertices.addVertex((float) v.x, (float) v.y, (float) v.z).setColor(face.color());
                    }
                }
                draw(context, vertices.build(), fill);
                vertices = new BufferBuilder(memory, line.getVertexFormatMode(), line.getVertexFormat());
                for (var edge : mesh.lines()) if (edge.onTop() == top) {
                    Vec3 direction = edge.b().subtract(edge.a()).normalize();
                    for (Vec3 p : new Vec3[]{edge.a(), edge.b()}) {
                        Vec3 v = p.subtract(eye);
                        vertices.addVertex((float) v.x, (float) v.y, (float) v.z).setColor(edge.color())
                                .setNormal((float) direction.x, (float) direction.y, (float) direction.z).setLineWidth(edge.width());
                    }
                }
                draw(context, vertices.build(), line);
            }
        }
    }

    private static void draw(LevelRenderContext context, MeshData mesh, RenderPipeline pipeline) {
        if (mesh == null) return;
        try (mesh) {
            var target = Minecraft.getInstance().getMainRenderTarget();
            if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            int bytes = mesh.vertexBuffer().remaining();
            var ring = BUFFERS.get(pipeline);
            if (ring == null || ring.size() < bytes) {
                if (ring != null) {
                    var retired = ring;
                    RenderSystem.queueFencedTask(retired::close);
                }
                ring = new net.minecraft.client.renderer.MappableRingBuffer(() -> "OpenIntel overlay vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes);
                BUFFERS.put(pipeline, ring);
            }
            var vertices = ring.currentBuffer().slice(0, bytes);
            try (var mapped = encoder.mapBuffer(vertices, false, true)) {
                mapped.data().put(mesh.vertexBuffer());
            }
            var indices = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            int count = mesh.drawState().indexCount();
            var indexBuffer = indices.getBuffer(count);
            var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(RenderSystem.getModelViewMatrix()),
                    new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            try (var pass = encoder.createRenderPass(() -> "OpenIntel world overlays", target.getColorTextureView(),
                    OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, ring.currentBuffer());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, count, 1);
            }
            ring.rotate();
        }
    }
}
