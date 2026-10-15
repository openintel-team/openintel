package dev.openintel;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import dev.openintel.render.WorldOverlayRenderer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Optional graphical check: actual vanilla fill shaders and overlay reverse-Z depth states. Does not emulate Iris. */
public final class WorldOverlayShaderTest {
    public static void main(String[] args) throws Exception {
        long window = 0, context = 0;
        try {
            check(GLFW.glfwInit(), "GLFW initialization");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(64, 64, "OpenIntel overlay depth tests", 0, 0);
            check(window != 0, "Hidden GL window");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            verifyDepth();
            System.out.println("World overlay shader/depth readback passed on " + GL33C.glGetString(GL33C.GL_RENDERER));
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GL.destroy();
            GLFW.glfwTerminate();
        }
    }

    private static void verifyDepth() throws Exception {
        int vertex = compile(GL33C.GL_VERTEX_SHADER, "/assets/minecraft/shaders/core/position_color.vsh");
        int fragment = compile(GL33C.GL_FRAGMENT_SHADER, "/assets/minecraft/shaders/core/position_color.fsh");
        int program = GL33C.glCreateProgram();
        GL33C.glAttachShader(program, vertex); GL33C.glAttachShader(program, fragment);
        GL33C.glBindAttribLocation(program, 0, "Position"); GL33C.glBindAttribLocation(program, 1, "Color"); GL33C.glLinkProgram(program);
        check(GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) != 0, GL33C.glGetProgramInfoLog(program));
        int fbo = GL33C.glGenFramebuffers(), color = GL33C.glGenRenderbuffers(), depth = GL33C.glGenRenderbuffers();
        int vao = GL33C.glGenVertexArrays(), vbo = GL33C.glGenBuffers(), projection = GL33C.glGenBuffers(), dynamic = GL33C.glGenBuffers();
        try (var stack = MemoryStack.stackPush()) {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, color);
            GL33C.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_RGBA8, 64, 64);
            GL33C.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_RENDERBUFFER, color);
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, depth);
            GL33C.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH_COMPONENT24, 64, 64);
            GL33C.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT, GL33C.GL_RENDERBUFFER, depth);
            check(GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER) == GL33C.GL_FRAMEBUFFER_COMPLETE, "Depth test FBO");
            GL33C.glUseProgram(program); GL33C.glBindVertexArray(vao);
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
            // A unit block face under identity projection: its exact boundary is at pixel 32.
            GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, new float[]{0,0,0, 1,0,0, 1,1,0, 0,0,0, 1,1,0, 0,1,0}, GL33C.GL_STATIC_DRAW);
            GL33C.glEnableVertexAttribArray(0); GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, 12, 0);
            GL33C.glVertexAttrib4f(1, 1, 0, 0, 1);
            float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
            float[] transforms = new float[40];
            System.arraycopy(identity, 0, transforms, 0, 16); System.arraycopy(identity, 0, transforms, 24, 16);
            java.util.Arrays.fill(transforms, 16, 20, 1);
            uniform(program, "Projection", projection, 0, identity);
            uniform(program, "DynamicTransforms", dynamic, 1, transforms);
            GL33C.glViewport(0, 0, 64, 64); GL33C.glDisable(GL33C.GL_CULL_FACE); GL33C.glDisable(GL33C.GL_BLEND);
            var pixel = stack.malloc(4); var depthPixel = stack.mallocFloat(1);
            for (boolean shaderDepth : new boolean[]{false, true}) {
            for (boolean top : new boolean[]{false, true}) {
                DepthStencilState policy = (top ? WorldOverlayRenderer.FILL_TOP : shaderDepth ? WorldOverlayRenderer.FILL_SHADER_DEPTH : WorldOverlayRenderer.FILL_DEPTH).getDepthStencilState();
                for (double terrainDepth : new double[]{0.2, 0.5, 0.8}) {
                    GL33C.glDepthMask(true); GL33C.glClearDepth(terrainDepth);
                    GL33C.glClearColor(0, 0, 0, 0); GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT | GL33C.GL_DEPTH_BUFFER_BIT);
                    GL33C.glEnable(GL33C.GL_DEPTH_TEST); GL33C.glDepthFunc(top ? GL33C.GL_ALWAYS : shaderDepth ? GL33C.GL_LEQUAL : GL33C.GL_GEQUAL);
                    GL33C.glDepthMask(policy.writeDepth());
                    GL33C.glEnable(GL33C.GL_POLYGON_OFFSET_FILL);
                    GL33C.glPolygonOffset(policy.depthBiasScaleFactor(), policy.depthBiasConstant());
                    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 6);
                    GL33C.glReadPixels(40, 40, 1, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixel);
                    check((pixel.get(0) & 255) == (top || (shaderDepth ? terrainDepth >= 0.5 : terrainDepth <= 0.5) ? 255 : 0), "Coplanar visibility / terrain occlusion");
                    GL33C.glReadPixels(31, 40, 1, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixel);
                    check((pixel.get(0) & 255) == 0, "Depth bias does not move the block boundary");
                    GL33C.glReadPixels(40, 40, 1, 1, GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, depthPixel);
                    check(Math.abs(depthPixel.get(0) - terrainDepth) < 1e-6, "World depth preserved");
                }
            }
            }
            check(GL33C.glGetError() == GL33C.GL_NO_ERROR, "No GL errors");
        } finally {
            GL33C.glDeleteBuffers(vbo); GL33C.glDeleteBuffers(projection); GL33C.glDeleteBuffers(dynamic);
            GL33C.glDeleteVertexArrays(vao); GL33C.glDeleteRenderbuffers(color); GL33C.glDeleteRenderbuffers(depth);
            GL33C.glDeleteFramebuffers(fbo); GL33C.glDeleteProgram(program); GL33C.glDeleteShader(vertex); GL33C.glDeleteShader(fragment);
        }
    }

    private static void uniform(int program, String name, int buffer, int binding, float[] data) {
        GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, buffer); GL33C.glBufferData(GL33C.GL_UNIFORM_BUFFER, data, GL33C.GL_STATIC_DRAW);
        GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, binding, buffer);
        GL33C.glUniformBlockBinding(program, GL33C.glGetUniformBlockIndex(program, name), binding);
    }
    private static int compile(int type, String resource) throws Exception {
        int shader = GL33C.glCreateShader(type); GL33C.glShaderSource(shader, source(resource, new HashSet<>())); GL33C.glCompileShader(shader);
        check(GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) != 0, resource + ": " + GL33C.glGetShaderInfoLog(shader));
        return shader;
    }
    private static String source(String resource, Set<String> seen) throws Exception {
        if (!seen.add(resource)) return "";
        String text;
        try (var stream = WorldOverlayShaderTest.class.getResourceAsStream(resource)) {
            check(stream != null, "Shader present: " + resource);
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (resource.endsWith(".glsl")) text = text.replaceAll("(?m)^#version[^\\n]*", "");
        var matches = Pattern.compile("#(?:include|moj_import) <(?:minecraft:)?([^>]+)>").matcher(text);
        StringBuilder out = new StringBuilder();
        while (matches.find()) matches.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(source("/assets/minecraft/shaders/include/" + matches.group(1), seen)));
        matches.appendTail(out);
        return out.toString();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
