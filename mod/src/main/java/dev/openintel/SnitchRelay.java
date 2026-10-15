package dev.openintel;

import com.google.gson.JsonObject;
import dev.openintel.render.EventFeed;
import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forwards snitch-alert chat lines (JukeAlert-style "* X entered snitch at
 * name [world x y z]") to the relay so the whole team sees them, not just
 * whoever is standing on the alert group.
 *
 * Detection is a two-stage regex: `snitchPattern` decides whether a line is
 * a snitch hit at all (configurable), then {@link SnitchLogParser} pulls
 * coords / player / action. When extraction fails we still forward the raw
 * text — teammates get the heads up, just without a map marker.
 *
 * Dedupe: identical text is only forwarded once per 10s — some setups echo
 * the same alert through multiple chat channels.
 */
public final class SnitchRelay {
    private SnitchRelay() { }

    private static final Pattern HOVER_DIMENSION = Pattern.compile(
            "(?<![a-z0-9_:])minecraft:(?:overworld|the_nether|the_end)(?![a-z0-9_/])",
            Pattern.CASE_INSENSITIVE);
    private static final long DEDUPE_MS = 10_000;
    private static final Map<String, Long> recent = new HashMap<>();

    /** Wired to ClientReceiveMessageEvents.GAME — game/system chat only. */
    public static void onGameMessage(Component message, boolean overlay) {
        if (overlay) return;
        var cfg = OpenIntelClient.config();
        if (cfg == null) return;

        String text = message.getString();
        if (text == null || text.isEmpty()) return;
        // Never forward our own status lines — they can quote the original
        // alert text and would loop the detection.
        if (text.startsWith("[OpenIntel]")) return;

        Pattern detection;
        try {
            detection = Pattern.compile(cfg.snitchPattern);
        } catch (Exception e) {
            return; // bad user regex — fail closed, don't forward everything
        }
        if (!detection.matcher(text).find()) return;
        String hoverWorld = hoverWorld(message);
        if (!dedupe(text + "|" + hoverWorld)) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "snitch");
        msg.addProperty("message", text);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            msg.addProperty("reporter", client.player.getGameProfile().name());
        }

        SnitchLogParser.ParsedLine parsed = SnitchLogParser.parseLine(text);
        String player = parsed.player();
        String snitchName = parsed.snitch();
        String world = parsed.world();
        boolean hasCoords = parsed.hasCoords();
        int x = hasCoords ? parsed.x() : 0;
        int y = hasCoords ? parsed.y() : 0;
        int z = hasCoords ? parsed.z() : 0;

        if (player != null) msg.addProperty("player", player);
        if (snitchName != null) msg.addProperty("snitch", snitchName);
        if (parsed.action() != null) {
            msg.addProperty("action", parsed.action());
            if (parsed.eventKind() != null) msg.addProperty("eventKind", parsed.eventKind());
        }
        if (hasCoords) {
            msg.addProperty("x", x);
            msg.addProperty("y", y);
            msg.addProperty("z", z);
        }

        if (hoverWorld != null) world = hoverWorld;
        if (world == null) world = "openintel:unknown";
        msg.addProperty("world", world);

        // Local marker now — the relay echo refreshes the same hit later, and
        // this still works if the relay is unreachable.
        if (hasCoords && snitchName != null && player != null) {
            String me = client.player != null ? client.player.getGameProfile().name() : "?";
            OpenIntelClient.tracker().addSnitchHit(
                    snitchName, player, me, x, y, z, world, System.currentTimeMillis());
        }

        if (cfg.snitchRelay) OpenIntelClient.relay().send(msg);
        else dev.openintel.api.internal.ApiBridge.relaySnitch(msg);
        EventFeed.add("Snitch: " + compact(text), 0xFFFFAA00);
    }

    /**
     * Wired to ClientReceiveMessageEvents.MODIFY_GAME — rewrites a snitch
     * alert line so the reported player's name draws in their allegiance
     * color (focus/friend/ally/enemy/neutral). Everything else passes
     * through untouched; click/hover styling on every segment is kept.
     */
    public static Component restyleForChat(Component message, boolean overlay) {
        if (overlay) return message;
        var cfg = OpenIntelClient.config();
        if (cfg == null) return message;
        String text = message.getString();
        if (text == null || text.isEmpty() || text.startsWith("[OpenIntel]")) return message;
        Pattern detection;
        try {
            detection = Pattern.compile(cfg.snitchPattern);
        } catch (Exception e) {
            return message;
        }
        if (!detection.matcher(text).find()) return message;

        int[] span = SnitchLogParser.playerSpan(text);
        if (span == null) return message;
        int color = OpenIntelClient.allegiances()
                .of(text.substring(span[0], span[1])).argb & 0xFFFFFF;

        // Rebuild as flat styled segments; visit() yields leaves in order so
        // the concatenation reproduces the line exactly, minus the recolor.
        MutableComponent out = Component.empty();
        int[] pos = {0};
        message.visit((style, content) -> {
            int start = pos[0], end = start + content.length();
            pos[0] = end;
            int a = Math.max(0, span[0] - start);
            int b = Math.min(content.length(), span[1] - start);
            if (a >= b) {
                out.append(Component.literal(content).setStyle(style));
            } else {
                if (a > 0) out.append(Component.literal(content.substring(0, a)).setStyle(style));
                out.append(Component.literal(content.substring(a, b))
                        .setStyle(style.withColor(TextColor.fromRgb(color))));
                if (b < content.length()) out.append(Component.literal(content.substring(b)).setStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    static String hoverWorld(Component message) {
        Set<String> worlds = new HashSet<>();
        message.visit((style, content) -> {
            if (style.getHoverEvent() instanceof HoverEvent.ShowText hover) {
                Matcher match = HOVER_DIMENSION.matcher(hover.value().getString());
                while (match.find()) worlds.add(match.group().toLowerCase(Locale.ROOT));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return worlds.isEmpty() ? null : worlds.size() == 1 ? worlds.iterator().next() : "openintel:unknown";
    }

    public static void reset() {
        recent.clear();
    }

    private static boolean dedupe(String text) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUPE_MS) it.remove();
        }
        Long last = recent.get(text);
        if (last != null && now - last < DEDUPE_MS) return false;
        recent.put(text, now);
        return true;
    }

    /** Trim a long alert line down for the feed. */
    private static String compact(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 64 ? t.substring(0, 61) + "..." : t;
    }
}
