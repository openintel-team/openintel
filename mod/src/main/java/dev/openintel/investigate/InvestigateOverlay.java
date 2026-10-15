package dev.openintel.investigate;

import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/**
 * Draws investigate playback block outlines via the per-tick gizmo collector
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
        if (!session.isWatching() || client.level == null) return;

        String dim = client.level.dimension().identifier().toString();
        int selectedIndex = session.index();
        java.util.List<InvestigateSession.Event> events = session.events();
        for (int i = 0; i < events.size(); i++) {
            InvestigateSession.Event ev = events.get(i);
            if (!dim.equalsIgnoreCase(ev.dimension())) continue;
            drawEvent(ev, i == selectedIndex);
        }
    }

    private static void drawEvent(InvestigateSession.Event ev, boolean selected) {
        BlockPos pos = new BlockPos(ev.x(), ev.y(), ev.z());
        AABB box = new AABB(pos).inflate(0.02);
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
        GizmoStyle style = GizmoStyle.strokeAndFill(stroke, width, fill);
        GizmoProperties props = Gizmos.cuboid(box, style);
        props.setAlwaysOnTop();
        if (selected) {
            Gizmos.billboardTextOverBlock(ev.detail(), pos, 0, stroke, 0.35f).setAlwaysOnTop();
        }
    }

    public static void renderHud(GuiGraphicsExtractor ctx) {
        InvestigateSession session = InvestigateSession.get();
        if (!session.isWatching()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
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
