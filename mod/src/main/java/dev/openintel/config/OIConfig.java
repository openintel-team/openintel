package dev.openintel.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.openintel.chat.ChatChannel;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side configuration, persisted to config/openintel.json.
 *
 * Note: webhook URLs intentionally do NOT live here. All webhook traffic is
 * fired by the relay server so alerts are deduplicated and the URLs are never
 * distributed inside a client jar (anyone with a webhook URL can post to it,
 * or delete it).
 */
public class OIConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("openintel.json");
    }
    private static final String OLD_SNITCH_PATTERN = "(?i)entered snitch|snitch at|you have entered";
    private static final String DEFAULT_SNITCH_PATTERN = OLD_SNITCH_PATTERN
            + "|opened container|logged in at|logged out at|damaged sanctuary|broke block|placed block";

    /** WebSocket URL of the relay server, e.g. ws://relay.example.com:8765 */
    public String relayUrl = "";

    /** Minecraft multiplayer server allowed to use this relay. */
    public String minecraftServer = "";

    /** Personal auth token issued by the relay admin (matches users.json on the relay).
     *  Default comes from a bundled resource so token-specific builds can be
     *  baked by patching one text file inside the jar — no recompile. */
    public String token = bundledToken();

    private static String bundledToken() {
        try (var in = OIConfig.class.getResourceAsStream("/openintel_token.txt")) {
            if (in != null) {
                String t = new String(in.readAllBytes()).trim();
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ignored) { }
        return "CHANGE_ME";
    }

    /** How often (ms) the client uploads what it sees. 250–500 is plenty. */
    public int reportIntervalMs = 250;

    /** Drop a shared marker if it hasn't been refreshed in this many ms. */
    public int staleAfterMs = 10_000;

    /** Max distance (blocks) at which in-world markers are drawn; <= 0 = unlimited. */
    public double maxMarkerDistance = 0;

    /** Draw markers for players that are already visibly rendered next to you. */
    public boolean markVisiblePlayers = true;

    /** Edge-of-screen chevrons for targets outside your field of view. */
    public boolean edgeChevrons = true;

    /** Top/bottom edge list: horizontal anchor, % of screen width. */
    public int edgeRowXPct = 50;
    public int edgeTopXPct = 50;
    public int edgeBottomXPct = 50;

    /** Top/bottom edge list: arrow row inset from the screen edge, px. */
    public int edgeRowInset = 5;

    /** Left/right edge stack: vertical anchor, % of screen height. */
    public int edgeColumnYPct = 50;
    public int edgeLeftYPct = 50;
    public int edgeRightYPct = 50;

    /** Left/right edge stack: arrow column inset from the screen edge, px. */
    public int edgeColumnInset = 5;

    /** Play a sound + chat line when an enemy first enters someone's render. */
    public boolean localEnemyAlert = true;

    /** Master switch for every relay-driven overlay (markers, chevrons, nameplates). */
    public boolean relayRendering = true;

    /** Nearest percent to the previous 151/255 marker alpha. */
    public static final int DEFAULT_RELAY_OPACITY_PERCENT = 59;

    /** Default over-head marker scale, as a fraction (60%). */
    public static final float DEFAULT_MARKER_SCALE = 0.6f;

    /** Opacity of active relay markers (players, pings, chevrons), 0–100 percent. */
    public int relayOpacity = DEFAULT_RELAY_OPACITY_PERCENT;

    /**
     * Stored marker opacity is a percent from 0 to 100. Values above 100 are
     * the old 151–255 alpha and convert to the nearest percent.
     */
    public static int markerOpacityPercent(int stored) {
        if (stored > 100) stored = Math.round(stored * 100f / 255f);
        return Math.clamp(stored, 0, 100);
    }

    /** Alpha byte for a stored marker-opacity percent. Legacy alpha is accepted. */
    public static int markerAlpha(int stored) {
        return Math.round(markerOpacityPercent(stored) * 255f / 100f);
    }

    /**
     * Fills a missing marker opacity or scale, and converts a legacy alpha into
     * a percent. An explicit saved scale stays as it was.
     */
    public static void applyMarkerDefaults(OIConfig config, boolean hasRelayOpacity, boolean hasMarkerScale) {
        if (!hasRelayOpacity) config.relayOpacity = DEFAULT_RELAY_OPACITY_PERCENT;
        else config.relayOpacity = markerOpacityPercent(config.relayOpacity);
        if (!hasMarkerScale || !Float.isFinite(config.markerScale) || config.markerScale <= 0f) {
            config.markerScale = DEFAULT_MARKER_SCALE;
        } else {
            config.markerScale = Math.clamp(config.markerScale, 0.5f, 2.0f);
        }
    }

    /** Fade relay blips/markers out as their intel approaches staleAfterMs. */
    public boolean staleDecay = true;

    /** Scale of over-head markers — chevron/diamond and the name label together. */
    public float markerScale = DEFAULT_MARKER_SCALE;

    /** Clean TTF text on OpenIntel surfaces. Off by default, so Minecraft's font is used until this is turned on. */
    public boolean cleanFont = false;

    /** Bottom-right watermark. Off until turned on under HUD → Elements. */
    public boolean logoHudEnabled = false;

    // ---------------------------------------------------------- presence ----

    /** Side-panel list of all relay-tracked players. */
    public boolean presenceEnabled = true;

    /** Presence panel anchor x (GUI-scaled px). */
    public int presenceX = -1;      // -1 = hug the right edge

    /** Presence panel anchor y (GUI-scaled px). */
    public int presenceY = 120;

    /** Max rows in the presence panel. */
    public int presenceMaxRows = 12;

    /** List players in other bounds (with a dim tag, no distance). */
    public boolean presenceShowAllDims = true;

    // ---------------------------------------------------------- ping wheel ---

    /** Radial ping wheel (hold key to open, flick to a slot, release to send). */
    public boolean pingWheelEnabled = true;

    /** Seconds a shared ping stays on the map. */
    public int pingSeconds = 45;

    // ------------------------------------------------------------ events ----

    /** Toast feed for deaths/logouts/enemy sightings. */
    public boolean eventFeedEnabled = true;

    /** Show the session log as soon as chat opens, without waiting for the pointer. */
    public boolean eventFeedShowOnChat = true;

    /** Seconds an event feed line stays fully visible before fading. */
    public int eventFeedSeconds = 8;
    public int eventFeedX = -1;
    public int eventFeedY = 4;

    /**
     * Event-feed box in element pixels. Zero keeps the automatic size:
     * width wraps within a third of the screen and hugs the text, and height
     * grows with the visible lines. The HUD editor writes both when a corner
     * or edge is dragged.
     */
    public int eventFeedWidth = 0;
    public int eventFeedHeight = 0;

    /** Horizontal alignment of event-feed text within its HUD box. */
    public FeedAlign eventFeedAlign = FeedAlign.RIGHT;

    /** HUD keeps the corner feed. Chat sends the same lines into the OI Events tab. */
    public EventLogPlace eventLogPlace = EventLogPlace.HUD;

    /** Where event-log lines are shown. */
    public enum EventLogPlace {
        HUD("options.openintel.feed.place.hud"),
        CHAT("options.openintel.feed.place.chat");

        public final String translationKey;
        EventLogPlace(String key) { this.translationKey = key; }

        public static EventLogPlace byName(String name) {
            for (EventLogPlace place : values()) if (place.name().equalsIgnoreCase(name)) return place;
            return HUD;
        }
    }

    /** Event feed text alignment inside the HUD box. */
    public enum FeedAlign {
        LEFT("options.openintel.feed.align.left"),
        RIGHT("options.openintel.feed.align.right");

        public final String translationKey;
        FeedAlign(String key) { this.translationKey = key; }

        public static FeedAlign byName(String name) {
            for (FeedAlign f : values()) if (f.name().equalsIgnoreCase(name)) return f;
            return RIGHT;
        }
    }

    public boolean armorHudEnabled = true;
    public int armorHudX = 8;
    public int armorHudY = 116;

    /** Row or column arrangement for the armor pieces. */
    public ArmorHudLayout armorHudLayout = ArmorHudLayout.HORIZONTAL;

    /** How each piece's remaining durability is shown. */
    public ArmorHudMode armorHudMode = ArmorHudMode.PERCENT;

    /** Armor HUD arrangement. */
    public enum ArmorHudLayout {
        HORIZONTAL("options.openintel.armor.layout.horizontal"),
        VERTICAL("options.openintel.armor.layout.vertical");

        public final String translationKey;
        ArmorHudLayout(String key) { this.translationKey = key; }

        public static ArmorHudLayout byName(String name) {
            for (ArmorHudLayout f : values()) if (f.name().equalsIgnoreCase(name)) return f;
            return HORIZONTAL;
        }
    }

    /** Armor HUD durability readout. */
    public enum ArmorHudMode {
        PERCENT("options.openintel.armor.mode.percent"),
        BAR("options.openintel.armor.mode.bar"),
        POINTS("options.openintel.armor.mode.points");

        public final String translationKey;
        ArmorHudMode(String key) { this.translationKey = key; }

        public static ArmorHudMode byName(String name) {
            for (ArmorHudMode f : values()) if (f.name().equalsIgnoreCase(name)) return f;
            return PERCENT;
        }
    }

    public boolean potionHudEnabled = true;
    public int potionHudX = -1;
    public int potionHudY = 40;

    /** Forward snitch-alert chat messages to the relay for the whole team. */
    public boolean snitchRelay = true;

    /** Regex matched against incoming chat text to detect a snitch hit. */
    public String snitchPattern = DEFAULT_SNITCH_PATTERN;

    /**
     * Legacy single selection from the first channel bar. Kept so an older
     * config can be split into the view toggles below.
     */
    public String chatChannel = "chat";

    /** Show regular chat in the open chat box. */
    public boolean chatViewChat = true;

    /** Show snitch alerts in the open chat box. */
    public boolean chatViewSnitches = false;

    /** Show hidden-ore reveals in the open chat box. */
    public boolean chatViewMining = false;

    /**
     * Show lines Minecraft marked as client-system messages. This is not a
     * text guess, so server chat and player chat stay out of it.
     */
    public boolean chatViewClient = false;

    /** Fade new regular chat lines in while chat is closed. */
    public boolean chatShowChat = true;

    /** Fade new snitch lines in while chat is closed. */
    public boolean chatShowSnitches = false;

    /** Fade new hidden-ore reveals in while chat is closed. */
    public boolean chatShowMining = false;

    /** Fade new client-system lines in while chat is closed. */
    public boolean chatShowClient = false;

    /** Show OpenIntel event lines in the open chat box. */
    public boolean chatViewEvents = true;

    /** Fade new OpenIntel event lines in while chat is closed. */
    public boolean chatShowEvents = true;

    public ChatChannel chatChannel() {
        return ChatChannel.parse(chatChannel);
    }

    /** Whether this category is included in the open chat box. */
    public boolean views(ChatChannel channel) {
        if (channel == null) return false;
        return switch (channel) {
            case CHAT -> chatViewChat;
            case SNITCHES -> chatViewSnitches;
            case MINING -> chatViewMining;
            case CLIENT -> chatViewClient;
            case EVENTS -> chatViewEvents;
        };
    }

    public boolean showsNew(ChatChannel channel) {
        if (channel == null) return false;
        return switch (channel) {
            case CHAT -> chatShowChat;
            case SNITCHES -> chatShowSnitches;
            case MINING -> chatShowMining;
            case CLIENT -> chatShowClient;
            case EVENTS -> chatShowEvents;
        };
    }

    /**
     * An older config stored one open channel in {@link #chatChannel}. Gson
     * reads a missing view toggle as false, so those files get the old
     * selection back. A file that already has the toggles is left alone.
     */
    public void repairChatView(JsonObject raw) {
        if (raw != null && (raw.has("chatViewChat") || raw.has("chatViewSnitches") || raw.has("chatViewMining"))) {
            return;
        }
        ChatChannel selected = chatChannel();
        chatViewChat = selected == ChatChannel.CHAT;
        chatViewSnitches = selected == ChatChannel.SNITCHES;
        chatViewMining = selected == ChatChannel.MINING;
        chatViewClient = selected == ChatChannel.CLIENT;
    }

    /** Seconds a snitch-hit marker stays on screen, fading linearly to zero. */
    public int snitchMarkerSeconds = 120;

    /** Max distance (blocks) for snitch-hit markers; <= 0 = unlimited. */
    public int snitchMarkerRange = 0;

    /** Static snitch marker opacity, 30–150, independent of active relay markers. */
    public int snitchMarkerOpacity = 150;

    /** Snitch marker icon + label color (ARGB). */
    public int snitchMarkerColor = 0xFFAAAAAA;

    /** Use the tripper's allegiance color instead of the configured snitch color. */
    public boolean snitchMarkerColorAuto = true;

    // ------------------------------------------------------- journeymap ----

    /** Draw snitch hits + shared pings on JourneyMap's fullscreen map (soft dep). */
    public boolean jmMarkers = true;

    /** Draw snitch hits + shared pings on Xaero's World Map screen (soft dep). */
    public boolean xaeroMarkers = true;

    /** Draw Civ+ relic maps from the local inventory on Xaero's World Map. */
    public boolean xaeroRelicMaps = true;

    /** Horizontal range (blocks) for nearby relic HUD pins; clamped 50–2000. */
    public int relicMarkerRange = 300;

    /** Arrange relic maps in an opened chest from closest to furthest. */
    public boolean sortRelicMaps = true;

    // -------------------------------------------------- visualizations ----

    /** Master switch for in-world snitch / bastion / sanctuary range overlays. */
    public boolean vizEnabled = true;

    public boolean vizSnitch = true;
    public boolean vizBastion = true;
    public boolean vizSanctuary = true;

    /** Draw sanctuary circles from the live-map API feed. */
    public boolean vizLiveSanctuaries = true;

    /** Draw range wireframes on top of terrain (see through walls). */
    public boolean vizSeeThrough = true;

    /** Snitch Euclidean radius in blocks (block-center distance). Default 8. */
    public int vizSnitchRadius = 8;

    /** Bastion horizontal radius in blocks. Default 10.5 (diameter 21). */
    public double vizBastionRadius = 10.5;

    // ------------------------------------------------------- investigate ----

    public enum InvestigateCameraMode {
        PAN("options.openintel.investigate.camera.pan"),
        SNAP("options.openintel.investigate.camera.snap"),
        OFF("options.openintel.investigate.camera.off");

        public final String translationKey;
        InvestigateCameraMode(String key) { translationKey = key; }
        public static InvestigateCameraMode byName(String name) {
            for (var mode : values()) if (mode.name().equalsIgnoreCase(name)) return mode;
            return PAN;
        }
    }

    /** Turn toward a newly focused investigation event, then release camera control. */
    public InvestigateCameraMode investigateCameraMode = InvestigateCameraMode.PAN;
    public int investigatePanDurationMs = 350;

    public static void applyInvestigateDefaults(OIConfig config) {
        if (config.investigateCameraMode == null) config.investigateCameraMode = InvestigateCameraMode.PAN;
        config.investigatePanDurationMs = Math.clamp(config.investigatePanDurationMs, 100, 2000);
    }

    // -------------------------------------------------------- war table ----

    /** War Table screen: shared admin-drawn annotations over the live map. */
    public boolean warTableEnabled = true;

    /** Live-map API base (no trailing slash); serves /maps/map-{r}.{json,png}. */
    public String liveMapBase = "";

    /** Which live-map renderer image the War Table uses as its backdrop. */
    public String warTableRenderer = "hillshade";

    /** Draw sanctuary boundaries over the War Table map. */
    public boolean warTableSanctuaries = true;

    /** Render synced War Table pins as in-world HUD waypoint markers. */
    public boolean warTablePinMarkers = true;

    /** Draw War Table pins on Xaero's World Map (independent of the HUD toggle). */
    public boolean warTablePinWorldMap = true;

    /** Draw War Table place labels on Xaero's World Map. */
    public boolean warTableLabelWorldMap = true;

    /** Draw live-map sanctuary names on Xaero's World Map. */
    public boolean warTableSanctuaryWorldMap = true;

    /** Static waypoint opacity, 30–150, independent of active relay markers. */
    public int warTablePinOpacity = 150;

    // -------------------------------------------------------------- radar ----

    /** Circular HUD radar. */
    public boolean radarEnabled = true;

    /** Dial center sits at (radarX, radarY) + radarSize from the top-left corner. */
    public int radarX = 8;
    public int radarY = 8;

    /** Dial radius in GUI-scaled pixels. */
    public int radarSize = 48;

    /** Radar sweep range in blocks. */
    public double radarRange = 64;

    /** Concentric range rings drawn inside the dial. */
    public int radarCircles = 4;

    /** true = north always up; false = the dial rotates with your view. */
    public boolean radarNorthUp = false;

    /** Distance compression 0–100: blends linear scale toward a log curve.
     *  0 = true linear (motion matches world movement exactly);
     *  100 = full log (close contacts expand, far contacts compress). */
    public int radarCompression = 50;

    /** Player contacts blip on the radar at all. */
    public boolean radarShowPlayers = true;

    /** Which players blip on the radar. */
    public RadarBlipFilter radarPlayerFilter = RadarBlipFilter.EVERYONE;

    /** Shared ping-wheel pings on the radar. */
    public boolean radarShowPings = true;

    /** Dropped items on the radar (noisy on busy servers). */
    public boolean radarShowItems = false;

    /** Boats and minecarts (incl. TNT/chest/hopper carts) on the radar. */
    public boolean radarShowVehicles = true;

    /** Relay-reported players beyond render distance shown as rim dots. */
    public boolean radarShowRelay = false;

    /** Who counts as a radar contact. */
    public enum RadarBlipFilter {
        EVERYONE("options.openintel.radar.filter.everyone"),
        NON_RELAY("options.openintel.radar.filter.non_relay"),
        ENEMIES("options.openintel.radar.filter.enemies");

        public final String translationKey;
        RadarBlipFilter(String key) { this.translationKey = key; }

        public static RadarBlipFilter byName(String name) {
            for (RadarBlipFilter f : values()) if (f.name().equalsIgnoreCase(name)) return f;
            return EVERYONE;
        }
    }

    /** Blip icon scale (player heads, item icons). */
    public float radarIconSize = 1.0f;

    /** Name label scale under blips. */
    public float radarTextSize = 1.0f;

    /** Dial background color (ARGB). */
    public int radarBgColor = 0x73090D14;

    /** Ring + spoke color (ARGB). */
    public int radarFgColor = 0x80FFFFFF;

    // ------------------------------------------------------------- macros ----

    /** Milliseconds between simulated attack presses for the attack macro. */
    public int attackMacroIntervalMs = 200;

    /** Milliseconds between simulated use presses for the use macro. */
    public int useMacroIntervalMs = 200;

    /** Snap yaw to the nearest 45° when the ice road macro engages. */
    public boolean iceRoadSnapYaw = true;

    /** Snap pitch to the nearest 45° when the ice road macro engages. */
    public boolean iceRoadSnapPitch = false;

    /** Hold use on food in the main hand while ice-roading. */
    public boolean iceRoadAutoEat = true;

    /** Park the ice road macro at <=6 hunger until you can eat again. */
    public boolean iceRoadStopAtHunger = false;

    // --------------------------------------------------------------- junk ----

    /** Throw listed items back out the moment they enter the inventory. */
    public boolean junkReject = false;

    public static final List<String> DEFAULT_JUNK_ITEMS = List.of(
            "cobblestone", "cobbled_deepslate", "dirt", "gravel", "sand",
            "andesite", "diorite", "granite", "deepslate", "tuff", "netherrack");
    public List<String> junkItems = new ArrayList<>(DEFAULT_JUNK_ITEMS);

    public int hudReferenceWidth;
    public int hudReferenceHeight;
    public Map<String, HudAnchorState> hudAnchors = new LinkedHashMap<>();

    public record HudAnchorState(int sourceX, int sourceY, dev.openintel.render.HudLayout.Anchor anchor) { }

    public Map<String, ExternalHudState> externalHudElements = new LinkedHashMap<>();

    public static final class ExternalHudState {
        public Integer x;
        public Integer y;
        public Boolean enabled = true;

        public ExternalHudState() {
        }

        public ExternalHudState(int x, int y, boolean enabled) {
            this.x = x;
            this.y = y;
            this.enabled = enabled;
        }
    }

    public void repairExternalHudElements() {
        if (externalHudElements == null) externalHudElements = new LinkedHashMap<>();
        externalHudElements.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (ExternalHudState state : externalHudElements.values()) {
            if (state.x != null && state.x < 0) state.x = null;
            if (state.y != null && state.y < 0) state.y = null;
            if (state.enabled == null) state.enabled = true;
        }
    }

    public static OIConfig load() {
        try {
            Path path = path();
            if (Files.exists(path)) {
                String json = Files.readString(path);
                OIConfig c = GSON.fromJson(json, OIConfig.class);
                JsonObject raw = GSON.fromJson(json, JsonObject.class);
                if (c == null || raw == null) return new OIConfig();
                c.repairExternalHudElements();
                if (c.hudAnchors == null) c.hudAnchors = new LinkedHashMap<>();
                c.hudAnchors.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null
                        || e.getValue().anchor() == null || !e.getValue().anchor().valid());
                if (c.radarPlayerFilter == null) c.radarPlayerFilter = RadarBlipFilter.EVERYONE;
                if (c.armorHudLayout == null) c.armorHudLayout = ArmorHudLayout.HORIZONTAL;
                if (c.armorHudMode == null) c.armorHudMode = ArmorHudMode.PERCENT;
                if (c.eventFeedAlign == null) c.eventFeedAlign = FeedAlign.RIGHT;
                if (c.eventLogPlace == null) c.eventLogPlace = EventLogPlace.HUD;
                boolean repairChatChannel = c.chatChannel == null
                        || (!c.chatChannel.equals("chat")
                        && !c.chatChannel.equals("snitches")
                        && !c.chatChannel.equals("mining"));
                if (repairChatChannel) c.chatChannel = "chat";
                c.repairChatView(raw);
                // The old default (4096) silently hid teammates across the map.
                // Migrate it to unlimited; explicit non-default caps survive.
                if (c.maxMarkerDistance == 4096) c.maxMarkerDistance = 0;
                if (OLD_SNITCH_PATTERN.equals(c.snitchPattern)) c.snitchPattern = DEFAULT_SNITCH_PATTERN;
                c.snitchMarkerOpacity = Math.clamp(c.snitchMarkerOpacity, 30, 150);
                c.warTablePinOpacity = Math.clamp(c.warTablePinOpacity, 30, 150);
                // Gson leaves a missing boolean false. Relic maps / world-map
                // pins stay on for configs saved before those toggles existed.
                boolean defaults = repairChatChannel;
                // Gson leaves a missing boolean false. The Chat eye starts open.
                if (!raw.has("chatShowChat")) {
                    c.chatShowChat = true;
                    defaults = true;
                }
                if (!raw.has("chatViewEvents")) {
                    c.chatViewEvents = true;
                    defaults = true;
                }
                if (!raw.has("chatShowEvents")) {
                    c.chatShowEvents = true;
                    defaults = true;
                }
                // Gson leaves a missing boolean false. Opening chat shows the session log.
                if (!raw.has("eventFeedShowOnChat")) {
                    c.eventFeedShowOnChat = true;
                    defaults = true;
                }
                int opacityBefore = c.relayOpacity;
                float scaleBefore = c.markerScale;
                applyMarkerDefaults(c, raw.has("relayOpacity"), raw.has("markerScale"));
                if (c.relayOpacity != opacityBefore || Float.compare(c.markerScale, scaleBefore) != 0) {
                    defaults = true;
                }
                if (!raw.has("xaeroRelicMaps")) {
                    c.xaeroRelicMaps = true;
                    defaults = true;
                }
                // Gson leaves a missing int at 0; migrate to the 300-block default.
                if (!raw.has("relicMarkerRange")) {
                    c.relicMarkerRange = 300;
                    defaults = true;
                } else {
                    c.relicMarkerRange = Math.clamp(c.relicMarkerRange, 50, 2000);
                }
                if (!raw.has("sortRelicMaps")) {
                    c.sortRelicMaps = true;
                    defaults = true;
                }
                if (!raw.has("warTablePinWorldMap")) {
                    c.warTablePinWorldMap = true;
                    defaults = true;
                }
                if (!raw.has("warTableLabelWorldMap")) {
                    c.warTableLabelWorldMap = true;
                    defaults = true;
                }
                if (!raw.has("warTableSanctuaryWorldMap")) {
                    c.warTableSanctuaryWorldMap = true;
                    defaults = true;
                }
                // Gson leaves a missing boolean false and a missing number at 0.
                // Visualization toggles and radii stay at their defaults for
                // configs saved before those keys existed.
                if (!raw.has("vizEnabled")) { c.vizEnabled = true; defaults = true; }
                if (!raw.has("vizSnitch")) { c.vizSnitch = true; defaults = true; }
                if (!raw.has("vizBastion")) { c.vizBastion = true; defaults = true; }
                if (!raw.has("vizSanctuary")) { c.vizSanctuary = true; defaults = true; }
                if (!raw.has("vizLiveSanctuaries")) { c.vizLiveSanctuaries = true; defaults = true; }
                if (!raw.has("vizSeeThrough")) { c.vizSeeThrough = true; defaults = true; }
                if (!raw.has("vizSnitchRadius")) {
                    c.vizSnitchRadius = 8;
                    defaults = true;
                } else {
                    c.vizSnitchRadius = Math.clamp(c.vizSnitchRadius, 1, 32);
                }
                if (!raw.has("vizBastionRadius") || !Double.isFinite(c.vizBastionRadius)) {
                    c.vizBastionRadius = 10.5;
                    defaults = true;
                } else {
                    c.vizBastionRadius = Math.clamp(c.vizBastionRadius, 1.0, 64.0);
                }
                if (!raw.has("investigateCameraMode") || c.investigateCameraMode == null) {
                    c.investigateCameraMode = InvestigateCameraMode.PAN;
                    defaults = true;
                }
                if (!raw.has("investigatePanDurationMs")) {
                    c.investigatePanDurationMs = 350;
                    defaults = true;
                }
                applyInvestigateDefaults(c);
                // Before the explicit auto-color flag existed, -1 was the
                // allegiance auto-color sentinel and the default. Preserve it
                // under the flag; explicit colors keep their static value.
                // Configs missing both keys keep the field default (auto on).
                if (!raw.has("snitchMarkerColorAuto") && raw.has("snitchMarkerColor")) {
                    c.snitchMarkerColorAuto = c.snitchMarkerColor == -1;
                    if (c.snitchMarkerColorAuto) c.snitchMarkerColor = 0xFFAAAAAA;
                    defaults = true;
                }
                if (defaults) c.save();
                return c;
            }
        } catch (Exception ignored) {
        }
        OIConfig fresh = new OIConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
