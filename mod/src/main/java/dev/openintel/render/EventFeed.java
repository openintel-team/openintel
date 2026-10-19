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
 * keeps a busy fight from flooding the screen. Until the HUD editor resizes
 * it, each message wraps within about a third of the screen and, by default,
 * stays right-aligned to that corner. A dragged box uses that width and height
 * instead. The static hooks below are wired from OpenIntelClient; renderers
 * never call them directly.
 *
 * <p>Faded lines stay in a session log until the player leaves the server or
 * the client disconnects. Opening chat and scrolling over the feed reads that
 * log; normal play keeps the fading stack.
 */
public final class EventFeed {
    private EventFeed() { }

    /** On-screen stack. Older session lines stay scrollable past this cap. */
    private static final int MAX_VISIBLE = 8;
    /** Bounds one long session. Oldest lines drop first. */
    private static final int MAX_SESSION = 400;
    private static final long FADE_MS = 1500;

    /** Lines from the oldest session row. The tail is showing when {@code pinned}. */
    private static int logOffset;
    private static boolean logPinned = true;

    /** HUD-editor resize edges. A corner sets the two edges that meet there. */
    public static final int EDGE_LEFT = 1;
    public static final int EDGE_RIGHT = 2;
    public static final int EDGE_TOP = 4;
    public static final int EDGE_BOTTOM = 8;

    /** GUI-pixel rectangle produced by dragging one or more edges. */
    public record Resize(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }

    /** Element-pixel size plus the GUI rectangle the layout anchor should keep. */
    public record PlacedResize(int x, int y, int elementWidth, int elementHeight, int width, int height) { }

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
            while (entries.size() >= MAX_SESSION) entries.pollFirst();
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
            logOffset = 0;
            logPinned = true;
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
        if (cfg == null || !cfg.eventFeedEnabled) {
            logPinned = true;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;
        if (!chatOpen(client)) logPinned = true;

        long now = System.currentTimeMillis();
        long holdMs = cfg.eventFeedSeconds * 1000L;
        List<Entry> session = snapshot();
        if (session.isEmpty()) {
            logPinned = true;
            return;
        }
        int lineHeight = Math.max(1, client.font.lineHeight + 2);
        List<Entry> live = liveEntries(session, now, holdMs);
        Arrangement liveArrangement = liveArrangement(client, cfg, ctx.guiWidth(), ctx.guiHeight(),
                live, now, holdMs, true);
        boolean open = chatOpen(client);
        boolean showOnChat = cfg.eventFeedShowOnChat;
        if (open && (showOnChat || !logPinned)) {
            LogRegion region = historyRegion(client, cfg, ctx.guiWidth(), ctx.guiHeight(), session,
                    showOnChat ? null : liveArrangement);
            if (!logPinned) {
                FeedScroll clamped = scrollLog(region.wrapped(), region.visibleRows(), logOffset, false, 0);
                logOffset = clamped.offset();
                logPinned = clamped.pinned();
            }
            if (showOnChat || !logPinned) {
                paint(ctx, client, cfg, visibleWindow(region.lines(), region.visibleRows(), logOffset, logPinned),
                        region.elementWidth(), region.elementHeight(), lineHeight);
                return;
            }
        }
        if (open && live.isEmpty()) {
            LogRegion region = historyRegion(client, cfg, ctx.guiWidth(), ctx.guiHeight(), session, null);
            if (contains(region.frame(), mouseX(client), mouseY(client))) {
                paint(ctx, client, cfg, visibleWindow(region.lines(), region.visibleRows(), logOffset, true),
                        region.elementWidth(), region.elementHeight(), lineHeight);
                return;
            }
        }
        if (liveArrangement == null) return;
        paint(ctx, client, cfg, liveArrangement.lines(), liveArrangement.width(),
                Math.max(1, liveArrangement.height()), lineHeight);
    }

    /**
     * Scrolls the session log when chat is open and the pointer is over the feed.
     * Positive {@code vertical} moves toward older lines, matching chat.
     *
     * @return true when the wheel belongs to the log
     */
    public static boolean scroll(double mouseX, double mouseY, double vertical) {
        if (!(vertical > 0d || vertical < 0d)) return false;
        Minecraft client = Minecraft.getInstance();
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.eventFeedEnabled || client.player == null || client.getWindow() == null) return false;
        if (client.options.hideGui || !chatOpen(client)) return false;
        List<Entry> session = snapshot();
        if (session.isEmpty()) return false;
        int guiW = client.getWindow().getGuiScaledWidth();
        int guiH = client.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();
        Arrangement liveArrangement = liveArrangement(client, cfg, guiW, guiH,
                liveEntries(session, now, cfg.eventFeedSeconds * 1000L), now, cfg.eventFeedSeconds * 1000L, false);
        boolean showOnChat = cfg.eventFeedShowOnChat;
        LogRegion region = historyRegion(client, cfg, guiW, guiH, session, showOnChat ? null : liveArrangement);
        if (maxScroll(region.wrapped(), region.visibleRows()) <= 0) return false;
        HudLayout.Frame hit = region.frame();
        if (!showOnChat && logPinned && liveArrangement != null && liveArrangement.height() > 0) {
            hit = HudLayouts.place(HudLayouts.Element.EVENTS, cfg,
                    new HudSize(liveArrangement.width(), Math.max(1, liveArrangement.height())), guiW, guiH);
        }
        if (!contains(hit, mouseX, mouseY)) return false;
        FeedScroll next = scrollLog(region.wrapped(), region.visibleRows(), logOffset, logPinned, vertical);
        logOffset = next.offset();
        logPinned = next.pinned();
        return true;
    }

    /** Where a wheel notch lands. Positive {@code vertical} reveals older lines. */
    public static FeedScroll scrollLog(int lineCount, int visibleRows, int offset, boolean pinned, double vertical) {
        int max = maxScroll(lineCount, visibleRows);
        int base = pinned ? max : Math.clamp(offset, 0, max);
        if (!(vertical > 0d || vertical < 0d) || max == 0) return new FeedScroll(base, pinned || max == 0);
        int delta = (int) Math.round(vertical);
        if (delta == 0) delta = vertical > 0d ? 1 : -1;
        int next = Math.clamp(base - delta, 0, max);
        return new FeedScroll(next, next == max);
    }

    public static int maxScroll(int lineCount, int visibleRows) {
        return Math.max(0, lineCount - Math.max(1, visibleRows));
    }

    /** Rows that fit in the feed, oldest at the start of {@code lines}. */
    public static List<FeedLine> visibleWindow(List<FeedLine> lines, int visibleRows, int offset, boolean pinned) {
        if (lines == null || lines.isEmpty()) return List.of();
        int rows = Math.max(1, visibleRows);
        int start = Math.clamp(pinned ? maxScroll(lines.size(), rows) : offset, 0, maxScroll(lines.size(), rows));
        return List.copyOf(lines.subList(start, Math.min(lines.size(), start + rows)));
    }

    /** Index of the first entry still allowed on the live stack. */
    public static int firstVisibleIndex(int count) {
        return Math.max(0, count - MAX_VISIBLE);
    }

    /** True while a line is holding or fading. The next millisecond drops it from the HUD. */
    public static boolean onScreen(long ageMs, long holdMs) {
        return ageMs <= holdMs + FADE_MS;
    }

    /** Scroll position. {@code offset} counts wrapped lines from the oldest. */
    public record FeedScroll(int offset, boolean pinned) { }

    private record LogRegion(HudLayout.Frame frame, List<FeedLine> lines, int wrapped,
                             int visibleRows, int elementWidth, int elementHeight) { }

    public static HudSize size(Minecraft client) {
        int viewport = 0;
        int screenHeight = 0;
        if (client.getWindow() != null) {
            viewport = client.getWindow().getGuiScaledWidth();
            screenHeight = client.getWindow().getGuiScaledHeight();
        }
        OIConfig cfg = OpenIntelClient.config();
        float scale = cfg == null || viewport <= 0 ? 1f : HudLayouts.scale(cfg, viewport, screenHeight);
        int boxWidth = cfg == null ? 0 : explicitSpan(cfg.eventFeedWidth);
        int boxHeight = cfg == null ? 0 : explicitSpan(cfg.eventFeedHeight);
        long holdMs = cfg == null ? 8000L : cfg.eventFeedSeconds * 1000L;
        List<Entry> live = liveEntries(snapshot(), System.currentTimeMillis(), holdMs);
        if (live.isEmpty()) {
            int width = boxWidth > 0 ? boxWidth : Math.min(180, maxWidth(Math.max(viewport, 1), scale));
            int height = boxHeight > 0 ? boxHeight : 58;
            return new HudSize(width, height);
        }
        int lineHeight = client.font.lineHeight + 2;
        List<FeedLine> lines = colored(live, System.currentTimeMillis(), holdMs, false);
        Arrangement arrangement = arrange(lines, viewport, scale, lineHeight, measurer(client), boxWidth, boxHeight);
        int height = boxHeight > 0 ? arrangement.height() : Math.max(lineHeight, arrangement.height());
        return new HudSize(arrangement.width(), Math.max(1, height));
    }

    /** Zero means automatic. Any other stored span is clamped into {@link HudSize}'s range. */
    public static int explicitSpan(int value) {
        if (value <= 0) return 0;
        return Math.clamp(value, 1, 32768);
    }

    /** Hit thickness for corners and edges, leaving a move area in the middle when it fits. */
    public static int resizeGrip(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        int grip = Math.min(8, Math.max(4, Math.min(width, height) / 3));
        return Math.min(grip, Math.max(1, (Math.min(width, height) - 1) / 2));
    }

    /** Edges under a point inside the box. Interior clicks return 0 so the box still moves. */
    public static int resizeEdgesAt(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (width <= 0 || height <= 0 || mouseX < x || mouseY < y
                || mouseX >= x + width || mouseY >= y + height) return 0;
        int grip = resizeGrip(width, height);
        boolean left = mouseX - x < grip;
        boolean right = x + width - mouseX <= grip;
        boolean top = mouseY - y < grip;
        boolean bottom = y + height - mouseY <= grip;
        if (left && right) {
            left = mouseX - x <= x + width - mouseX;
            right = !left;
        }
        if (top && bottom) {
            top = mouseY - y <= y + height - mouseY;
            bottom = !top;
        }
        int edges = 0;
        if (left) edges |= EDGE_LEFT;
        if (right) edges |= EDGE_RIGHT;
        if (top) edges |= EDGE_TOP;
        if (bottom) edges |= EDGE_BOTTOM;
        return edges;
    }

    /**
     * Moves the dragged edges to the pointer and keeps the others fixed.
     * The box stays at least {@code minWidth} by {@code minHeight} and inside the viewport.
     */
    public static Resize resizeBox(int x, int y, int width, int height, int edges,
                                   double mouseX, double mouseY, int viewportWidth, int viewportHeight,
                                   int minWidth, int minHeight) {
        int left = x;
        int top = y;
        int right = x + Math.max(1, width);
        int bottom = y + Math.max(1, height);
        if ((edges & EDGE_LEFT) != 0) left = (int) Math.round(mouseX);
        if ((edges & EDGE_RIGHT) != 0) right = (int) Math.round(mouseX);
        if ((edges & EDGE_TOP) != 0) top = (int) Math.round(mouseY);
        if ((edges & EDGE_BOTTOM) != 0) bottom = (int) Math.round(mouseY);
        int[] horizontal = clampSpan(left, right, viewportWidth, minWidth,
                (edges & EDGE_LEFT) != 0 && (edges & EDGE_RIGHT) == 0);
        int[] vertical = clampSpan(top, bottom, viewportHeight, minHeight,
                (edges & EDGE_TOP) != 0 && (edges & EDGE_BOTTOM) == 0);
        return new Resize(horizontal[0], vertical[0],
                Math.max(1, horizontal[1] - horizontal[0]), Math.max(1, vertical[1] - vertical[0]));
    }

    private static int[] clampSpan(int start, int end, int viewport, int minimum, boolean startMoves) {
        int min = Math.max(1, minimum);
        if (viewport > 0) min = Math.min(min, viewport);
        if (end - start < min) {
            if (startMoves) start = end - min;
            else end = start + min;
        }
        if (viewport > 0) {
            if (start < 0) {
                start = 0;
                if (end < start + min) end = Math.min(viewport, start + min);
            }
            if (end > viewport) {
                end = viewport;
                if (start > end - min) start = Math.max(0, end - min);
            }
            if (end - start > viewport) {
                start = 0;
                end = viewport;
            }
        }
        if (end <= start) end = start + 1;
        return new int[]{start, end};
    }

    /** Converts a dragged GUI-pixel span back into element pixels at the current HUD scale. */
    public static int elementSpan(int guiPixels, float hudScale) {
        float scale = Float.isFinite(hudScale) && hudScale > 0f ? hudScale : 1f;
        return Math.clamp((int) Math.round(Math.max(1, guiPixels) / scale), 1, 32768);
    }

    /**
     * GUI resize followed by the element size {@link HudLayout} will actually place,
     * with the undragged edges kept put when rounding changes the box by a pixel.
     */
    public static PlacedResize placedResize(int x, int y, int width, int height, int edges,
                                            double mouseX, double mouseY, int viewportWidth, int viewportHeight,
                                            int minWidth, int minHeight, float hudScale,
                                            int referenceWidth, int referenceHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return new PlacedResize(0, 0, 1, 1, 1, 1);
        Resize next = resizeBox(x, y, width, height, edges, mouseX, mouseY,
                viewportWidth, viewportHeight, minWidth, minHeight);
        int elementWidth = elementSpan(next.width(), hudScale);
        int elementHeight = elementSpan(next.height(), hudScale);
        HudLayout.Frame placed = HudLayout.place(null, elementWidth, elementHeight,
                Math.max(1, referenceWidth), Math.max(1, referenceHeight), viewportWidth, viewportHeight);
        boolean moveLeft = (edges & EDGE_LEFT) != 0 && (edges & EDGE_RIGHT) == 0;
        boolean moveTop = (edges & EDGE_TOP) != 0 && (edges & EDGE_BOTTOM) == 0;
        int placedX = moveLeft ? next.right() - placed.width() : next.x();
        int placedY = moveTop ? next.bottom() - placed.height() : next.y();
        placedX = Math.clamp(placedX, 0, Math.max(0, viewportWidth - placed.width()));
        placedY = Math.clamp(placedY, 0, Math.max(0, viewportHeight - placed.height()));
        return new PlacedResize(placedX, placedY, elementWidth, elementHeight, placed.width(), placed.height());
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

    /** Wrapped rows plus the box that holds them. Automatic width never exceeds {@link #maxWidth}. */
    public record Arrangement(List<FeedLine> lines, int width, int height) { }

    public static Arrangement arrange(List<FeedLine> entries, int viewportWidth, float hudScale,
                                      int lineHeight, ToDoubleFunction<String> measure) {
        return arrange(entries, viewportWidth, hudScale, lineHeight, measure, 0, 0);
    }

    /**
     * @param boxWidth element-pixel width; zero keeps the automatic cap and hugs the text
     * @param boxHeight element-pixel height; zero grows with the wrapped lines, otherwise the newest rows that fit are kept
     */
    public static Arrangement arrange(List<FeedLine> entries, int viewportWidth, float hudScale,
                                      int lineHeight, ToDoubleFunction<String> measure,
                                      int boxWidth, int boxHeight) {
        int fixedWidth = explicitSpan(boxWidth);
        int fixedHeight = explicitSpan(boxHeight);
        int cap = fixedWidth > 0 ? fixedWidth : maxWidth(viewportWidth, hudScale);
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
        if (measure != null) {
            for (FeedLine line : lines) {
                width = Math.max(width, (int) Math.ceil(measure.applyAsDouble(line.text())));
            }
        }
        width = fixedWidth > 0 ? fixedWidth : Math.min(Math.max(width, 1), cap);
        if (fixedHeight > 0) {
            int fit = Math.max(1, fixedHeight / row);
            if (lines.size() > fit) lines = new ArrayList<>(lines.subList(lines.size() - fit, lines.size()));
            return new Arrangement(List.copyOf(lines), width, fixedHeight);
        }
        int height = lines.isEmpty() ? 0 : lines.size() * row;
        return new Arrangement(List.copyOf(lines), width, height);
    }

    private static List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    private static List<Entry> liveEntries(List<Entry> session, long now, long holdMs) {
        int start = firstVisibleIndex(session.size());
        List<Entry> live = new ArrayList<>();
        for (int i = start; i < session.size(); i++) {
            Entry entry = session.get(i);
            if (onScreen(now - entry.createdAt, holdMs)) live.add(entry);
        }
        return live;
    }

    private static List<FeedLine> colored(List<Entry> source, long now, long holdMs, boolean fade) {
        List<FeedLine> lines = new ArrayList<>(source.size());
        for (Entry entry : source) {
            float alpha = 1f;
            if (fade) {
                long age = now - entry.createdAt;
                alpha = age <= holdMs ? 1f : 1f - (age - holdMs) / (float) FADE_MS;
            }
            lines.add(new FeedLine(entry.text, scaleAlpha(entry.color, alpha)));
        }
        return lines;
    }

    private static Arrangement liveArrangement(Minecraft client, OIConfig cfg, int guiWidth, int guiHeight,
                                               List<Entry> live, long now, long holdMs, boolean fade) {
        if (live.isEmpty()) return null;
        int lineHeight = Math.max(1, client.font.lineHeight + 2);
        float hudScale = HudLayouts.scale(cfg, guiWidth, guiHeight);
        Arrangement arrangement = arrange(colored(live, now, holdMs, fade), guiWidth, hudScale, lineHeight,
                measurer(client), explicitSpan(cfg.eventFeedWidth), explicitSpan(cfg.eventFeedHeight));
        if (arrangement.lines().isEmpty() || arrangement.height() <= 0) return null;
        return arrangement;
    }

    /**
     * Scroll viewport. An explicit box wins. While lines are still on screen the height
     * stays that stack, so scrolling does not move it. After they fade, the viewport is
     * the newest page, up to eight rows.
     */
    private static LogRegion historyRegion(Minecraft client, OIConfig cfg, int guiWidth, int guiHeight,
                                           List<Entry> session, Arrangement live) {
        int lineHeight = Math.max(1, client.font.lineHeight + 2);
        float hudScale = HudLayouts.scale(cfg, guiWidth, guiHeight);
        var measure = measurer(client);
        int explicitW = explicitSpan(cfg.eventFeedWidth);
        int explicitH = explicitSpan(cfg.eventFeedHeight);
        int liveWidth = live == null ? 0 : live.width();
        int liveHeight = live == null ? 0 : live.height();
        Arrangement all = arrange(colored(session, 0L, 0L, false), guiWidth, hudScale, lineHeight, measure, explicitW, 0);
        int elementWidth = Math.max(1, explicitW > 0 ? explicitW : Math.max(liveWidth, all.width()));
        int elementHeight;
        if (explicitH > 0) elementHeight = explicitH;
        else if (liveHeight > 0) elementHeight = liveHeight;
        else {
            int from = firstVisibleIndex(session.size());
            Arrangement newest = arrange(colored(session.subList(from, session.size()), 0L, 0L, false),
                    guiWidth, hudScale, lineHeight, measure, explicitW > 0 ? explicitW : elementWidth, 0);
            int rows = Math.min(MAX_VISIBLE, Math.max(1, newest.lines().size()));
            elementHeight = Math.max(lineHeight, rows * lineHeight);
        }
        int visibleRows = Math.max(1, elementHeight / lineHeight);
        var frame = HudLayouts.place(HudLayouts.Element.EVENTS, cfg,
                new HudSize(elementWidth, elementHeight), guiWidth, guiHeight);
        return new LogRegion(frame, all.lines(), all.lines().size(), visibleRows, elementWidth, elementHeight);
    }

    private static void paint(GuiGraphics ctx, Minecraft client, OIConfig cfg, List<FeedLine> lines,
                              int elementWidth, int elementHeight, int lineHeight) {
        if (lines.isEmpty() || elementWidth <= 0 || elementHeight <= 0) return;
        var frame = HudLayouts.place(HudLayouts.Element.EVENTS, cfg,
                new HudSize(elementWidth, elementHeight), ctx.guiWidth(), ctx.guiHeight());
        if (frame.scale() <= 0) return;
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            int y = 0;
            boolean cf = CleanFont.active();
            var measure = measurer(client);
            for (FeedLine line : lines) {
                float tw = (float) Math.ceil(measure.applyAsDouble(line.text()));
                int x = lineX(cfg.eventFeedAlign, elementWidth, tw);
                if (cf) CleanFont.draw(ctx, line.text(), x, y, line.color(), true);
                else ctx.drawString(client.font, line.text(), x, y, line.color(), true);
                y += lineHeight;
            }
        }
    }

    private static boolean contains(HudLayout.Frame frame, double x, double y) {
        return frame != null && frame.scale() > 0 && frame.width() > 0 && frame.height() > 0
                && x >= frame.x() && y >= frame.y() && x < frame.right() && y < frame.bottom();
    }

    private static boolean chatOpen(Minecraft client) {
        return client.gui.getChat().isChatFocused();
    }

    private static double mouseX(Minecraft client) {
        return client.mouseHandler.getScaledXPos(client.getWindow());
    }

    private static double mouseY(Minecraft client) {
        return client.mouseHandler.getScaledYPos(client.getWindow());
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
