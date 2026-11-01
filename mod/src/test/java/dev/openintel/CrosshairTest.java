package dev.openintel;

import com.google.gson.Gson;
import dev.openintel.render.CrosshairStyle;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CrosshairTest {
    private record Rect(int x1, int y1, int x2, int y2, int color) { }

    public static void main(String[] args) throws Exception {
        var style = new CrosshairStyle();
        check(!style.enabled, "Existing users retain vanilla by default");
        style.outline = false;
        var bars = paint(style, 100, 100);
        check(bars.size() == 4, "Four arms without a center dot");
        check(bars.get(0).equals(new Rect(91, 100, 97, 101, style.color)), "Exact length, width and gap");
        check(bars.get(1).equals(new Rect(104, 100, 110, 101, style.color)), "Mirrored right arm");
        style.dot = true;
        check(paint(style, 100, 100).get(4).equals(new Rect(100, 100, 101, 101, style.color)), "Centered dot");
        style.outline = true;
        var outlined = paint(style, 100, 100);
        check(outlined.size() == 10, "Outline and fill for each part");
        for (int i = 0; i < 5; i++) {
            Rect border = outlined.get(i), fill = outlined.get(i + 5);
            check(border.color == 0xFF000000 && border.x1 == fill.x1 - 1 && border.y1 == fill.y1 - 1
                    && border.x2 == fill.x2 + 1 && border.y2 == fill.y2 + 1, "Black one-pixel outlines precede all fills");
        }
        style.length = 0;
        check(paint(style, 0, 0).size() == 2, "Dot-only crosshair");
        style.dot = false;
        check(paint(style, 0, 0).isEmpty(), "Zero length and no dot produces no geometry");
        for (int width = 1; width <= 8; width++) {
            style.width = width;
            style.length = 6;
            style.gap = 3;
            style.outline = false;
            bars = paint(style, 100, 100);
            check(bars.get(0).y2 - bars.get(0).y1 == width, "Odd and even thickness remains exact");
            int center2 = 200 + width % 2;
            check(bars.get(0).x1 + bars.get(1).x2 == center2, "Horizontal symmetry");
            check(bars.get(2).y1 + bars.get(3).y2 == center2, "Vertical symmetry");
            var preview = paint(style, 200, 150);
            for (int i = 0; i < bars.size(); i++) {
                Rect a = bars.get(i), b = preview.get(i);
                check(b.x1 - a.x1 == 100 && b.x2 - a.x2 == 100
                        && b.y1 - a.y1 == 50 && b.y2 - a.y2 == 50, "Preview is translated, never scaled");
            }
        }
        style.length = Integer.MAX_VALUE;
        style.width = -100;
        style.gap = Integer.MIN_VALUE;
        style.sanitize();
        check(style.length == 20 && style.width == 1 && style.gap == 0, "Malformed configuration is bounded");
        style.enabled = true;
        style.color = 0xFF12ABCD;
        style.outline = true;
        style.dot = true;
        Gson gson = new Gson();
        var restored = gson.fromJson(gson.toJson(style), CrosshairStyle.class);
        check(restored.enabled && restored.color == style.color && restored.dot && restored.outline
                && restored.length == style.length && restored.width == style.width && restored.gap == style.gap,
                "All controls survive JSON round-trip");
        check(!gson.fromJson("{}", CrosshairStyle.class).enabled, "Missing settings retain defaults");
        verifyHook();
        String gui = Files.readString(Path.of("src/main/java/dev/openintel/gui/ClickGui.java"));
        check(gui.contains("new Category(\"crosshair\"") && gui.contains("Crosshair color")
                && gui.contains("cfg.crosshair.gap") && gui.contains("cfg.crosshair.width"), "Settings wired into the category");
        String screen = Files.readString(Path.of("src/main/java/dev/openintel/gui/ClickGuiScreen.java"));
        check(screen.contains("CrosshairRenderer.preview(") && screen.contains("crosshairPreviewHeight()"), "Pinned shared-renderer preview");
        System.out.println("Crosshair geometry, persistence and hook tests passed");
    }

    private static List<Rect> paint(CrosshairStyle style, int x, int y) {
        var out = new ArrayList<Rect>();
        style.draw((x1, y1, x2, y2, color) -> out.add(new Rect(x1, y1, x2, y2, color)), x, y);
        return out;
    }

    private static void verifyHook() throws Exception {
        ClassNode mixin = readClass("dev/openintel/mixin/CrosshairMixin");
        AnnotationNode target = mixin.invisibleAnnotations.stream()
                .filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        String owner = ((Type) ((List<?>) value(target, "value")).get(0)).getInternalName();
        var hook = mixin.methods.stream().filter(m -> m.name.equals("openintel$crosshair")).findFirst().orElseThrow();
        AnnotationNode wrap = hook.visibleAnnotations.stream()
                .filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        String method = (String) ((List<?>) value(wrap, "method")).get(0);
        AnnotationNode at = (AnnotationNode) ((List<?>) value(wrap, "at")).get(0);
        String invocation = (String) value(at, "target");
        var targetMethod = readClass(owner).methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        long calls = 0;
        for (var instruction : targetMethod.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && invocation.equals("L" + call.owner + ";" + call.name + call.desc)) calls++;
        }
        check(calls == 3, "Crosshair and attack-indicator sprite call sites still match the hook");
        String registration = Files.readString(Path.of("src/main/resources/openintel.mixins.json"));
        check(registration.contains("\"CrosshairMixin\""), "Mixin registered");
    }

    private static Object value(AnnotationNode node, String key) {
        for (int i = 0; i < node.values.size(); i += 2)
            if (key.equals(node.values.get(i))) return node.values.get(i + 1);
        throw new AssertionError("Missing annotation field: " + key);
    }

    private static ClassNode readClass(String name) throws Exception {
        try (var stream = CrosshairTest.class.getResourceAsStream("/" + name + ".class")) {
            check(stream != null, "Class exists: " + name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
