package dev.openintel.investigate;

import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.openintel.render.WorldOverlayMesh;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws investigate playback block outlines via dedicated world overlay pipelines
 * and a small HUD caption for the selected event.
 */
public final class InvestigateOverlay {
    private InvestigateOverlay() { }

    private static final int BREAK = 0xFFFF3B30;
    private static final int PLACE = 0xFF34C759;
    private static final int OTHER = 0xFFFFD60A;
    private static final int SELECTED_FILL_BREAK = 0x66FF3B30;
    private static final int SELECTED_FILL_PLACE = 0x6634C759;
    private static final int SELECTED_FILL_OTHER = 0x66FFD60A;

    public static void tick(Minecraft client) {
        InvestigateSession session = InvestigateSession.get();
        session.tick();
    }

    public static void collect(Minecraft client, WorldOverlayMesh.Builder mesh) {
        InvestigateSession session = InvestigateSession.get();
        if (!session.isWatching() || client.level == null) return;

        String dim = client.level.dimension().identifier().toString();
        int selectedIndex = session.index();
        java.util.List<InvestigateSession.Event> events = session.events();
        for (int i = 0; i < events.size(); i++) {
            InvestigateSession.Event ev = events.get(i);
            if (!dim.equalsIgnoreCase(ev.dimension())) continue;
            drawEvent(mesh, ev, i == selectedIndex);
        }
    }

    private static void drawEvent(WorldOverlayMesh.Builder mesh, InvestigateSession.Event ev, boolean selected) {
        int stroke;
        int fill;
        SnitchLogParser.Kind kind = ev.kind();
        if (kind == SnitchLogParser.Kind.BREAK) {
            stroke = BREAK;
            fill = selected ? SELECTED_FILL_BREAK : 0x22FF3B30;
        } else if (kind == SnitchLogParser.Kind.PLACE) {
            stroke = PLACE;
            fill = selected ? SELECTED_FILL_PLACE : 0x2234C759;
        } else {
            stroke = OTHER;
            fill = selected ? SELECTED_FILL_OTHER : 0x22FFD60A;
        }
        float width = selected ? 3.5f : 2f;
        mesh.box(ev.x(), ev.y(), ev.z(), stroke, fill, width, true);
    }

    private static void renderSelectedLabel(GuiGraphicsExtractor ctx, Minecraft mc, InvestigateSession.Event event) {
        if (event == null || mc.level == null || !mc.level.dimension().identifier().toString().equalsIgnoreCase(event.dimension())) return;
        Vec3 anchor = new Vec3(event.x() + 0.5, event.y() + 1.35, event.z() + 0.5);
        Vec3 relative = anchor.subtract(mc.gameRenderer.getMainCamera().position());
        Vector3f local = new Vector3f((float) relative.x, (float) relative.y, (float) relative.z);
        new Quaternionf(mc.gameRenderer.getMainCamera().rotation()).conjugate().transform(local);
        if (local.z >= -0.05f) return;
        Vec3 ndc = mc.gameRenderer.projectPointToScreen(anchor);
        if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y) || Math.abs(ndc.x) > 1 || Math.abs(ndc.y) > 1) return;
        int x = (int) ((ndc.x * 0.5 + 0.5) * ctx.guiWidth()) - mc.font.width(event.detail()) / 2;
        int y = (int) ((0.5 - ndc.y * 0.5) * ctx.guiHeight());
        int color = event.kind() == SnitchLogParser.Kind.BREAK ? BREAK
                : event.kind() == SnitchLogParser.Kind.PLACE ? PLACE : OTHER;
        ctx.text(mc.font, event.detail(), x, y, color);
    }

    public static void renderHud(GuiGraphicsExtractor ctx) {
        InvestigateSession session = InvestigateSession.get();
        if (!session.isWatching()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;
        renderSelectedLabel(ctx, mc, session.current());
        InvestigateSession.Event cur = session.current();
        String line = cur == null
                ? "Investigate: empty"
                : "Investigate " + (session.index() + 1) + "/" + session.size()
                + (session.isPlaying() ? " [play]" : " [paused]")
                + "  " + cur.detail();
        var font = mc.font;
        int w = font.width(line);
        int x = Math.max(4, (ctx.guiWidth() - w) / 2);
        int y = 8;
        ctx.fill(x - 4, y - 2, x + w + 4, y + 12, 0xC0101015);
        ctx.text(font, line, x, y, 0xFFFFFFFF);
    }
}
