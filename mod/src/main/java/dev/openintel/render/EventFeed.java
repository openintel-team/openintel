package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.api.hud.HudSize;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.config.OIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.ToDoubleFunction;

/**
 * Compact intel feed in the top-right corner: deaths, disconnects,
 * enemies walking into render, relay joins/leaves, pings, snitch hits.
 *
 * Entries hold for `eventFeedSeconds`, then fade over 1.5s. A hard cap
 * keeps a busy fight from flooding the screen. Each message wraps within
 * about a third of the screen and, by default, stays right-aligned to that
 * corner. The static hooks below are wired from OpenIntelClient; renderers
 * never call them directly.
 */
public final class EventFeed {
    private EventFeed() { }

    private static final int MAX_ENTRIES = 8;
    private static final long FADE_MS = 1500;

    private record Entry(String text, int color, long createdAt, boolean relay) { }

    private static final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

    /** Players currently in render distance, so ENTITY_LOAD doesn't re-fire on chunk refresh. */
    private static final Set<String> inRender = new HashSet<>();
    /** Friendly corpses we've already announced — cleared when they respawn. */
    private static final Set<String> deadNotified = new HashSet<>();

    public static void add(String text, int argb) {
        add(text, argb, false);
    }

    public static void addRelay(String text, int argb) {
        add(text, argb, true);
    }

    private static void add(String text, int argb, boolean relay) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            var world = client.level;
            client.execute(() -> { if (client.level == world) add(text, argb, relay); });
            return;
        }
        OIConfig cfg = OpenIntelClient.config();
        if (cfg != null && cfg.eventFeedEnabled) {
            while (entries.size() >= MAX_ENTRIES) entries.pollFirst();
            entries.addLast(new Entry(text, argb, System.currentTimeMillis(), relay));
        }
        ApiBridge.notification(text, argb, "feed");
    }

    public static void clear() {
        clear(true);
    }

    public static void clearRelay() {
        clear(false);
    }

    private static void clear(boolean includeLocal) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> clear(includeLocal));
            return;
        }
        clearData(includeLocal);
        if (entries.isEmpty()) ApiBridge.notificationsCleared();
    }

    private static void clearData(boolean includeLocal) {
        if (includeLocal) {
            entries.clear();
            inRender.clear();
            deadNotified.clear();
        } else {
            entries.removeIf(Entry::relay);
        }
    }

    // ------------------------------------------------------------ hooks ----

    /** Fabric ENTITY_LOAD — announces enemy/focus players entering render. */
    public static void onEntityLoad(Entity entity, net.minecraft.client.multiplayer.ClientLevel world) {
        if (!(entity instanceof AbstractClientPlayer p)) return;
        String name = p.getGameProfile().name();
        if (!inRender.add(name)) return;

        Allegiance a = OpenIntelClient.allegiances().of(name);
        if (a == Allegiance.ENEMY || a == Allegiance.FOCUS) {
            add("⚠ " + name + " entered render distance", a.argb);
        }
    }

    public static void onEntityUnload(Entity entity, net.minecraft.client.multiplayer.ClientLevel world) {
        if (entity instanceof AbstractClientPlayer p) {
            inRender.remove(p.getGameProfile().name());
        }
    }

    /** Client tick — watches rendered teammates for deaths. */
    public static void tick(Minecraft client) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.eventFeedEnabled || client.level == null) return;

        for (AbstractClientPlayer p : client.level.players()) {
            if (p == client.player) continue;
            String name = p.getGameProfile().name();
            Allegiance a = OpenIntelClient.allegiances().of(name);
            boolean friendly = a == Allegiance.FRIEND || a == Allegiance.ALLY
                    || a == Allegiance.FOCUS;
            if (!friendly) {
                deadNotified.remove(name);
                continue;
            }
            if (!p.isAlive()) {
                if (deadNotified.add(name)) {
                    add("✝ " + name + " died", 0xFFFF5555);
                }
            } else {
                deadNotified.remove(name);
            }
        }
    }

    // ------------------------------------------------------------ render ---

    public static void render(GuiGraphics ctx) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.eventFeedEnabled || entries.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;

        long now = System.currentTimeMillis();
        long holdMs = cfg.eventFeedSeconds * 1000L;
        entries.removeIf(e -> now - e.createdAt > holdMs + FADE_MS);
        if (entries.isEmpty()) return;

        List<Entry> visible = List.copyOf(entries);
        int lineHeight = client.font.lineHeight + 2;
        float hudScale = HudLayouts.scale(cfg, ctx.guiWidth(), ctx.guiHeight());
        List<FeedLine> colored = new ArrayList<>(visible.size());
        for (Entry e : visible) {
            long age = now - e.createdAt;
            float fade = age <= holdMs ? 1f : 1f - (age - holdMs) / (float) FADE_MS;
            colored.add(new FeedLine(e.text, scaleAlpha(e.color, fade)));
        }
        Arrangement arrangement = arrange(colored, ctx.guiWidth(), hudScale, lineHeight, measurer(client));
        if (arrangement.lines().isEmpty()) return;
        HudSize size = new HudSize(arrangement.width(), Math.max(1, arrangement.height()));
        var frame = HudLayouts.place(HudLayouts.Element.EVENTS, cfg, size,
                ctx.guiWidth(), ctx.guiHeight());
        if (frame.scale() <= 0) return;
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            int y = 0;
            boolean cf = CleanFont.active();
            var measure = measurer(client);
            for (FeedLine line : arrangement.lines()) {
                float tw = (float) Math.ceil(measure.applyAsDouble(line.text()));
                int x = lineX(cfg.eventFeedAlign, size.width(), tw);
                if (cf) CleanFont.draw(ctx, line.text(), x, y, line.color(), true);
                else ctx.drawString(client.font, line.text(), x, y, line.color(), true);
                y += lineHeight;
            }
        }
    }

    public static HudSize size(Minecraft client) {
        int viewport = 0;
        int screenHeight = 0;
        if (client.getWindow() != null) {
            viewport = client.getWindow().getGuiScaledWidth();
            screenHeight = client.getWindow().getGuiScaledHeight();
        }
        OIConfig cfg = OpenIntelClient.config();
        float scale = cfg == null || viewport <= 0 ? 1f : HudLayouts.scale(cfg, viewport, screenHeight);
        if (entries.isEmpty()) {
            return new HudSize(Math.min(180, maxWidth(Math.max(viewport, 1), scale)), 58);
        }
        int lineHeight = client.font.lineHeight + 2;
        List<FeedLine> lines = new ArrayList<>();
        for (Entry entry : entries) lines.add(new FeedLine(entry.text, entry.color));
        Arrangement arrangement = arrange(lines, viewport, scale, lineHeight, measurer(client));
        return new HudSize(arrangement.width(), Math.max(lineHeight, arrangement.height()));
    }

    /** Visible so tests can check left/right line offsets without a client. */
    public static int lineX(OIConfig.FeedAlign align, float boxWidth, float textWidth) {
        if (align == OIConfig.FeedAlign.RIGHT) {
            return Math.max(0, Math.round(boxWidth - textWidth));
        }
        return 0;
    }

    /** Element-pixel cap whose scaled width stays within a third of the viewport. */
    public static int maxWidth(int viewportWidth, float hudScale) {
        if (viewportWidth <= 0) return 1;
        float scale = Float.isFinite(hudScale) && hudScale > 0f ? hudScale : 1f;
        return Math.max(1, (int) Math.floor(viewportWidth / 3.0 / scale));
    }

    /** Wrap on spaces, then split an over-long token by code point. */
    public static List<String> wrap(String text, double maxWidth, ToDoubleFunction<String> measure) {
        if (text == null || text.isEmpty() || !(maxWidth > 0) || measure == null) return List.of();
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            wrapParagraph(text.substring(start, end), maxWidth, measure, lines);
            if (newline < 0) break;
            start = newline + 1;
            if (start >= text.length()) {
                lines.add("");
                break;
            }
        }
        return lines;
    }

    private static void wrapParagraph(String text, double maxWidth, ToDoubleFunction<String> measure, List<String> lines) {
        int length = text.length();
        int start = 0;
        if (length == 0) {
            lines.add("");
            return;
        }
        while (start < length) {
            while (start < length && text.charAt(start) == ' ') start++;
            if (start >= length) break;
            int cursor = start;
            int breakAt = -1;
            while (cursor < length) {
                int next = text.offsetByCodePoints(cursor, 1);
                if (measure.applyAsDouble(text.substring(start, next)) > maxWidth) break;
                if (Character.isWhitespace(text.codePointAt(cursor))) breakAt = next;
                cursor = next;
            }
            if (cursor == start) cursor = text.offsetByCodePoints(start, 1);
            boolean more = cursor < length;
            boolean midWord = more && !Character.isWhitespace(text.codePointAt(cursor));
            int end = midWord && breakAt > start ? breakAt : cursor;
            String line = text.substring(start, end).stripTrailing();
            if (!line.isEmpty() && measure.applyAsDouble(line) > maxWidth) {
                line = HudLayout.ellipsize(line, maxWidth, measure);
            }
            if (!line.isEmpty()) lines.add(line);
            int nextStart = Math.max(end, text.offsetByCodePoints(start, 1));
            start = nextStart;
            while (start < length && text.charAt(start) == ' ') start++;
        }
    }

    /** One visual row after wrapping. */
    public record FeedLine(String text, int color) { }

    /** Wrapped rows plus the box that holds them. Width never exceeds {@link #maxWidth}. */
    public record Arrangement(List<FeedLine> lines, int width, int height) { }

    public static Arrangement arrange(List<FeedLine> entries, int viewportWidth, float hudScale,
                                      int lineHeight, ToDoubleFunction<String> measure) {
        int cap = maxWidth(viewportWidth, hudScale);
        int row = Math.max(1, lineHeight);
        List<FeedLine> lines = new ArrayList<>();
        if (entries != null && measure != null) {
            for (FeedLine entry : entries) {
                if (entry == null || entry.text() == null) continue;
                for (String line : wrap(entry.text(), cap, measure)) {
                    lines.add(new FeedLine(line, entry.color()));
                }
            }
        }
        int width = 1;
        for (FeedLine line : lines) {
            width = Math.max(width, (int) Math.ceil(measure.applyAsDouble(line.text())));
        }
        width = Math.min(Math.max(width, 1), cap);
        int height = lines.isEmpty() ? 0 : lines.size() * row;
        return new Arrangement(List.copyOf(lines), width, height);
    }

    private static ToDoubleFunction<String> measurer(Minecraft client) {
        boolean clean = CleanFont.active();
        var font = client.font;
        return text -> clean ? CleanFont.width(text) : font.width(text);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
