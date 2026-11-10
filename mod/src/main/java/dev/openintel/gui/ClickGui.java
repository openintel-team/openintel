package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.feature.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Option model and content for {@link ClickGuiScreen}.
 *
 * The screen only knows the shapes below: a {@link Category} is a sidebar
 * entry, a {@link Group} is a section divider inside a category, and an
 * {@link Option} is one settings row (label + tooltip + a main widget with
 * an optional narrow aux widget on its right). All widgets are built here
 * once and repositioned by the screen during layout.
 *
 * On/off switches live on the Features screen; this model holds only the
 * values to adjust. Categories, groups and rows can name the {@link Feature}
 * they configure, and {@link #visible} drops them while that feature is off.
 * Every label and tooltip comes from the language file: a row's label is
 * its key and its tooltip is {@code key + ".tooltip"}.
 *
 * Keybind rows set {@link Option#keybind()}; clicking their widget calls
 * back through {@link RebindHandler} so the screen can own capture state.
 */
public final class ClickGui {
    private ClickGui() { }

    public sealed interface Item permits Group, Option { }

    /** Section divider inside a category; a non-null feature hides the whole section while it is off. */
    public record Group(String title, Feature feature) implements Item {
        public Group(String title) {
            this(title, null);
        }
    }

    /**
     * One settings row.
     * @param label   row text, drawn left
     * @param tooltip hover description, may be null
     * @param widget  main control, right-aligned
     * @param aux     optional narrow control to the right of widget (e.g. reset)
     * @param keybind non-null when the row rebinds a KeyMapping
     * @param feature hides the row while this feature is off; null always shows it
     */
    public record Option(String label, String tooltip, AbstractWidget widget,
                         AbstractWidget aux, KeyMapping keybind, Feature feature) implements Item {
        public Option(String label, String tooltip, AbstractWidget widget) {
            this(label, tooltip, widget, null, null, null);
        }
    }

    /** A sidebar category; a non-null feature hides it while that feature is off. */
    public record Category(String id, String title, Feature feature, List<Item> items) { }

    /** Rebind capture is owned by the screen; rows only report clicks. */
    public interface RebindHandler {
        void begin(KeyMapping kb);
        void reset(KeyMapping kb);
    }

    /**
     * Drops categories, sections and rows whose feature is off, then any
     * category left without a row. Search runs over the result, so hidden
     * settings never match.
     */
    public static List<Category> visible(List<Category> all, Predicate<Feature> on) {
        List<Category> out = new ArrayList<>();
        for (Category cat : all) {
            if (cat.feature() != null && !on.test(cat.feature())) continue;
            List<Item> items = new ArrayList<>();
            Group pending = null;
            boolean sectionOn = true;
            for (Item item : cat.items()) {
                if (item instanceof Group g) {
                    sectionOn = g.feature() == null || on.test(g.feature());
                    pending = g;
                } else if (item instanceof Option o) {
                    if (!sectionOn || (o.feature() != null && !on.test(o.feature()))) continue;
                    if (pending != null) {
                        items.add(pending);
                        pending = null;
                    }
                    items.add(o);
                }
            }
            if (!items.isEmpty()) out.add(new Category(cat.id(), cat.title(), cat.feature(), List.copyOf(items)));
        }
        return out;
    }

    // ------------------------------------------------------------ content ----

    public static List<Category> build(Screen self, RebindHandler rebind) {
        OIConfig cfg = OpenIntelClient.config();
        List<Category> cats = new ArrayList<>();

        // ---------------------------------------------------------- general
        cats.add(category("general", null,
                group("relay"),
                row(Feature.RELAY, "options.openintel.relays.title",
                        button("options.openintel.relays.manage", () -> Minecraft.getInstance()
                                .setScreen(new RelaySettingsScreen(self)))),
                row("options.openintel.general.live_map",
                        textField(cfg.liveMapBase, "https://host/livemap-api", 512, s -> cfg.liveMapBase = s)),
                row(Feature.RELAY, "options.openintel.general.reconnect",
                        button("options.openintel.general.reconnect.button", () -> {
                            OpenIntelClient.config().save();
                            OpenIntelClient.reconnectRelay();
                        }))));

        // ===================================================== Intel & Relay
        cats.add(category("markers", Feature.PLAYER_MARKERS,
                group("markers"),
                row("options.openintel.relay.visible_markers",
                        bool("options.openintel.relay.visible_markers", cfg.markVisiblePlayers, v -> cfg.markVisiblePlayers = v)),
                row("options.openintel.relay.stale_decay",
                        bool("options.openintel.relay.stale_decay", cfg.staleDecay, v -> cfg.staleDecay = v)),
                row("options.openintel.relay.opacity",
                        slider("options.openintel.relay.opacity", 0, 100,
                                OIConfig.markerOpacityPercent(cfg.relayOpacity),
                                "%", v -> cfg.relayOpacity = v)),
                row("options.openintel.relay.marker_scale",
                        slider("options.openintel.relay.marker_scale", 50, 200, (int) (cfg.markerScale * 100),
                                "%", v -> cfg.markerScale = v / 100f)),
                row("options.openintel.relay.max_dist",
                        rangeSlider("options.openintel.relay.max_dist",
                                (int) Math.min(20000, Math.max(0, cfg.maxMarkerDistance)),
                                v -> cfg.maxMarkerDistance = v))));

        cats.add(category("snitch", Feature.SNITCH_ALERTS,
                group("snitch"),
                row("options.openintel.snitch.seconds",
                        slider("options.openintel.snitch.seconds", 30, 300, cfg.snitchMarkerSeconds,
                                "s", v -> cfg.snitchMarkerSeconds = v)),
                row("options.openintel.snitch.range",
                        rangeSlider("options.openintel.snitch.range", cfg.snitchMarkerRange,
                                v -> cfg.snitchMarkerRange = v)),
                row("options.openintel.snitch.opacity",
                        slider("options.openintel.snitch.opacity", 30, 150,
                                cfg.snitchMarkerOpacity, "", v -> cfg.snitchMarkerOpacity = v)),
                row("options.openintel.snitch.color",
                        colorButton(self, tr("options.openintel.snitch.color"),
                                () -> cfg.snitchMarkerColorAuto ? -1 : cfg.snitchMarkerColor,
                                v -> {
                                    cfg.snitchMarkerColor = v;
                                    cfg.snitchMarkerColorAuto = false;
                                },
                                tr("options.openintel.snitch.color.auto"), () -> {
                                    cfg.snitchMarkerColorAuto = true;
                                    cfg.snitchMarkerColor = 0xFFAAAAAA;
                                }))));

        cats.add(category("pings", Feature.PINGS,
                group("pings"),
                row("options.openintel.ping.seconds",
                        slider("options.openintel.ping.seconds", 10, 120, cfg.pingSeconds,
                                "s", v -> cfg.pingSeconds = v))));

        cats.add(category("presence", Feature.PRESENCE,
                group("presence"),
                row("options.openintel.presence.all_dims",
                        bool("options.openintel.presence.all_dims", cfg.presenceShowAllDims, v -> cfg.presenceShowAllDims = v)),
                row("options.openintel.presence.tab_only",
                        bool("options.openintel.presence.tab_only", cfg.presenceOnlyOnTab, v -> cfg.presenceOnlyOnTab = v)),
                row("options.openintel.presence.rows",
                        slider("options.openintel.presence.rows", 4, 24, cfg.presenceMaxRows,
                                "", v -> cfg.presenceMaxRows = v))));

        cats.add(category("feed", Feature.EVENT_FEED,
                group("feed"),
                row("options.openintel.feed.show_on_chat",
                        bool("options.openintel.feed.show_on_chat", cfg.eventFeedShowOnChat, v -> cfg.eventFeedShowOnChat = v)),
                row("options.openintel.feed.align",
                        cycle("options.openintel.feed.align", OIConfig.FeedAlign.values(),
                                a -> a.translationKey, OIConfig.FeedAlign::byName,
                                cfg.eventFeedAlign, v -> cfg.eventFeedAlign = v)),
                row("options.openintel.feed.seconds",
                        slider("options.openintel.feed.seconds", 3, 30, cfg.eventFeedSeconds,
                                "s", v -> cfg.eventFeedSeconds = v))));

        // =================================================== Visuals & World
        cats.add(category("crosshair", Feature.CROSSHAIR,
                group("crosshair"),
                row("options.openintel.crosshair.color",
                        colorButton(self, tr("options.openintel.crosshair.color"), () -> cfg.crosshair.color,
                                v -> cfg.crosshair.color = v, null, null)),
                row("options.openintel.crosshair.length",
                        slider("options.openintel.crosshair.length", 0, 20, cfg.crosshair.length,
                                " px", v -> cfg.crosshair.length = v)),
                row("options.openintel.crosshair.width",
                        slider("options.openintel.crosshair.width", 1, 8, cfg.crosshair.width,
                                " px", v -> cfg.crosshair.width = v)),
                row("options.openintel.crosshair.gap",
                        slider("options.openintel.crosshair.gap", 0, 20, cfg.crosshair.gap,
                                " px", v -> cfg.crosshair.gap = v)),
                row("options.openintel.crosshair.outline",
                        bool("options.openintel.crosshair.outline", cfg.crosshair.outline,
                                v -> cfg.crosshair.outline = v)),
                row("options.openintel.crosshair.dot",
                        bool("options.openintel.crosshair.dot", cfg.crosshair.dot,
                                v -> cfg.crosshair.dot = v))));

        cats.add(category("radar", Feature.RADAR,
                group("radar"),
                row("options.openintel.radar.north_up",
                        bool("options.openintel.radar.north_up", cfg.radarNorthUp, v -> cfg.radarNorthUp = v)),
                row("options.openintel.radar.compress",
                        slider("options.openintel.radar.compress", 0, 100, cfg.radarCompression,
                                "%", v -> cfg.radarCompression = v)),
                row("options.openintel.radar.items",
                        bool("options.openintel.radar.items", cfg.radarShowItems, v -> cfg.radarShowItems = v)),
                row("options.openintel.radar.vehicles",
                        bool("options.openintel.radar.vehicles", cfg.radarShowVehicles, v -> cfg.radarShowVehicles = v)),
                group("contacts"),
                row("options.openintel.radar.players",
                        bool("options.openintel.radar.players", cfg.radarShowPlayers, v -> cfg.radarShowPlayers = v)),
                row("options.openintel.radar.ping_filter",
                        cycle("options.openintel.radar.ping_filter", cfg.radarPlayerFilter,
                                v -> cfg.radarPlayerFilter = v)),
                row(Feature.PINGS, "options.openintel.radar.pings",
                        bool("options.openintel.radar.pings", cfg.radarShowPings, v -> cfg.radarShowPings = v)),
                group("dial"),
                row("options.openintel.radar.size",
                        slider("options.openintel.radar.size", 16, 160, cfg.radarSize,
                                " px", v -> cfg.radarSize = v)),
                row("options.openintel.radar.range",
                        slider("options.openintel.radar.range", 8, 512, (int) cfg.radarRange,
                                " blocks", v -> cfg.radarRange = v)),
                row("options.openintel.radar.circles",
                        slider("options.openintel.radar.circles", 0, 8, cfg.radarCircles,
                                "", v -> cfg.radarCircles = v)),
                row("options.openintel.radar.icon_size",
                        slider("options.openintel.radar.icon_size", 50, 200, (int) (cfg.radarIconSize * 100),
                                "%", v -> cfg.radarIconSize = v / 100f)),
                row("options.openintel.radar.text_size",
                        slider("options.openintel.radar.text_size", 50, 200, (int) (cfg.radarTextSize * 100),
                                "%", v -> cfg.radarTextSize = v / 100f)),
                group("colors"),
                row("options.openintel.radar.bg_alpha",
                        alphaSlider("options.openintel.radar.bg_alpha", cfg.radarBgColor,
                                v -> cfg.radarBgColor = v)),
                row("options.openintel.radar.fg_alpha",
                        alphaSlider("options.openintel.radar.fg_alpha", cfg.radarFgColor,
                                v -> cfg.radarFgColor = v)),
                row("options.openintel.radar.fg_color",
                        colorButton(self, tr("options.openintel.radar.fg_color"), () -> cfg.radarFgColor,
                                v -> cfg.radarFgColor = v, null, null)),
                row("options.openintel.radar.bg_color",
                        colorButton(self, tr("options.openintel.radar.bg_color"), () -> cfg.radarBgColor,
                                v -> cfg.radarBgColor = v, null, null))));

        cats.add(category("viz", Feature.VISUALIZATIONS,
                group("viz"),
                row(Feature.VIZ_SNITCH, "options.openintel.viz.snitch_radius",
                        slider("options.openintel.viz.snitch_radius", 1, 32,
                                Math.clamp(cfg.vizSnitchRadius, 1, 32),
                                "", v -> cfg.vizSnitchRadius = v)),
                row(Feature.VIZ_BASTION, "options.openintel.viz.bastion_radius",
                        halfSlider("options.openintel.viz.bastion_radius", 2, 128,
                                bastionHalf(cfg.vizBastionRadius),
                                v -> {
                                    cfg.vizBastionRadius = v / 2.0;
                                    cfg.vizBastionRadiusEdited = v != 21;
                                })),
                row("options.openintel.viz.edit_shapes",
                        button("options.openintel.viz.edit_shapes.button", () -> Minecraft.getInstance()
                                .setScreen(new VisualizationsScreen(self))))));

        cats.add(category("investigate", Feature.INVESTIGATE,
                group("investigate_logs"),
                row("options.openintel.investigate.open",
                        button("options.openintel.investigate.open.button", () -> Minecraft.getInstance()
                                .setScreen(new InvestigateScreen(self)))),
                row("options.openintel.investigate.clear",
                        button("options.openintel.investigate.clear.button", () -> {
                            dev.openintel.investigate.InvestigateSession.get().clear();
                            OpenIntelClient.status("investigate cleared");
                        })),
                group("camera"),
                row("options.openintel.investigate.camera",
                        cycle("options.openintel.investigate.camera", OIConfig.InvestigateCameraMode.values(),
                                mode -> mode.translationKey, OIConfig.InvestigateCameraMode::byName,
                                cfg.investigateCameraMode, value -> cfg.investigateCameraMode = value)),
                row("options.openintel.investigate.pan_duration",
                        slider("options.openintel.investigate.pan_duration", 100, 2000,
                                cfg.investigatePanDurationMs, "ms", value -> cfg.investigatePanDurationMs = value))));

        cats.add(category("wartable", Feature.WAR_TABLE,
                group("wartable_pins", Feature.WAR_TABLE_HUD_PINS),
                row("options.openintel.wartable.pin_opacity",
                        slider("options.openintel.wartable.pin_opacity", 30, 150,
                                cfg.warTablePinOpacity, "", v -> cfg.warTablePinOpacity = v))));

        // =============================================================== HUD
        cats.add(category("hud", null,
                group("layout"),
                row("options.openintel.hud.editor",
                        button("options.openintel.hud.editor.button", () ->
                                Minecraft.getInstance().setScreen(new HudEditorScreen(self)))),
                group("armor", Feature.ARMOR_HUD),
                row("options.openintel.armor.layout",
                        cycle("options.openintel.armor.layout", OIConfig.ArmorHudLayout.values(),
                                l -> l.translationKey, OIConfig.ArmorHudLayout::byName,
                                cfg.armorHudLayout, v -> cfg.armorHudLayout = v)),
                row("options.openintel.armor.mode",
                        cycle("options.openintel.armor.mode", OIConfig.ArmorHudMode.values(),
                                m -> m.translationKey, OIConfig.ArmorHudMode::byName,
                                cfg.armorHudMode, v -> cfg.armorHudMode = v))));

        // ===================================================== Maps & Relics
        cats.add(category("relics", Feature.RELIC_MAPS,
                group("relic_pins", Feature.RELIC_HUD_PINS),
                row("options.openintel.relic.marker_range",
                        slider("options.openintel.relic.marker_range", 50, 2000, cfg.relicMarkerRange,
                                "m", v -> cfg.relicMarkerRange = v))));

        // =========================================================== Utility
        cats.add(category("macros", Feature.MACROS,
                group("attack_macro"),
                row("options.openintel.macro.attack_interval",
                        slider("options.openintel.macro.attack_interval", 50, 1000, cfg.attackMacroIntervalMs,
                                " ms", v -> cfg.attackMacroIntervalMs = v)),
                group("use_macro"),
                row("options.openintel.macro.use_interval",
                        slider("options.openintel.macro.use_interval", 50, 1000, cfg.useMacroIntervalMs,
                                " ms", v -> cfg.useMacroIntervalMs = v)),
                group("ice_road"),
                row("options.openintel.macro.snap_yaw",
                        bool("options.openintel.macro.snap_yaw", cfg.iceRoadSnapYaw, v -> cfg.iceRoadSnapYaw = v)),
                row("options.openintel.macro.snap_pitch",
                        bool("options.openintel.macro.snap_pitch", cfg.iceRoadSnapPitch, v -> cfg.iceRoadSnapPitch = v)),
                row("options.openintel.macro.auto_eat",
                        bool("options.openintel.macro.auto_eat", cfg.iceRoadAutoEat, v -> cfg.iceRoadAutoEat = v)),
                row("options.openintel.macro.stop_hunger",
                        bool("options.openintel.macro.stop_hunger", cfg.iceRoadStopAtHunger, v -> cfg.iceRoadStopAtHunger = v))));

        cats.add(category("inventory", Feature.JUNK_REJECT,
                group("junk"),
                row("options.openintel.junk.items",
                        button("options.openintel.junk.items.button", () -> Minecraft.getInstance()
                                .setScreen(new JunkItemPickerScreen(self)))),
                row("options.openintel.junk.reset",
                        button("options.openintel.junk.reset.button", () ->
                                cfg.junkItems = new ArrayList<>(OIConfig.DEFAULT_JUNK_ITEMS)))));

        // --------------------------------------------------------- keybinds
        List<Item> keyItems = new ArrayList<>();
        keyItems.add(group("controls"));
        for (KeyMapping kb : OpenIntelClient.allKeys()) {
            AbstractWidget bind = new OiWidgets.Flat(kb.getTranslatedKeyMessage(), () -> rebind.begin(kb));
            AbstractWidget reset = new OiWidgets.Flat(Component.translatable("options.openintel.keybind.reset"),
                    () -> rebind.reset(kb));
            keyItems.add(new Option(tr(kb.getName()), tr("options.openintel.keybind.tooltip"),
                    bind, reset, kb, KeybindFeatures.featureFor(kb.getName())));
        }
        cats.add(new Category("keybinds", tr("options.openintel.category.keybinds"), null, keyItems));

        return cats;
    }

    // ------------------------------------------------------------ helpers ----

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static Category category(String id, Feature feature, Item... items) {
        return new Category(id, tr("options.openintel.category." + id), feature, List.of(items));
    }

    private static Group group(String id) {
        return group(id, null);
    }

    private static Group group(String id, Feature feature) {
        return new Group(tr("options.openintel.group." + id), feature);
    }

    /** Row whose label is {@code key} and tooltip {@code key + ".tooltip"}. */
    private static Option row(String key, AbstractWidget widget) {
        return row(null, key, widget);
    }

    private static Option row(Feature feature, String key, AbstractWidget widget) {
        return new Option(tr(key), tr(key + ".tooltip"), widget, null, null, feature);
    }

    private static AbstractWidget bool(String key, boolean current,
                                        Consumer<Boolean> apply) {
        return new OiWidgets.Toggle(current, apply);
    }

    private static AbstractWidget slider(String key, int min, int max, int current,
                                          String suffix, Consumer<Integer> apply) {
        return new OiWidgets.Slider(min, max, current, v -> v + suffix, apply);
    }

    /** Slider stored in half-block steps. 21 keeps the Civ+ field (19 blocks across). */
    private static AbstractWidget halfSlider(String key, int minHalf, int maxHalf, int current,
                                              Consumer<Integer> apply) {
        return new OiWidgets.Slider(minHalf, maxHalf, current,
                v -> v == 21 ? "Civ+" : halfText(v) + " (Ø " + v + ")", apply);
    }

    private static String halfText(int half) {
        int whole = half / 2;
        if ((half & 1) == 0) return Integer.toString(whole);
        return whole + ".5";
    }

    private static int bastionHalf(double radius) {
        if (!Double.isFinite(radius)) return 21;
        return (int) Math.round(Math.clamp(radius, 1.0, 64.0) * 2.0);
    }

    /** 0–20000 slider that reads "unlimited" at 0. */
    private static AbstractWidget rangeSlider(String key, int current,
                                               Consumer<Integer> apply) {
        return new OiWidgets.Slider(0, 20000, current,
                v -> v <= 0 ? Component.translatable("options.openintel.unlimited").getString() : v + "m", apply);
    }

    /** 0–255 slider that writes only the alpha byte of an ARGB color. */
    private static AbstractWidget alphaSlider(String key, int color,
                                               Consumer<Integer> apply) {
        int alpha = (color >>> 24) & 0xFF;
        return new OiWidgets.Slider(0, 255, alpha, Integer::toString,
                v -> apply.accept((color & 0x00FFFFFF) | (v << 24)));
    }

    /** Cycles a RadarBlipFilter; the label resolves through the enum's lang key. */
    private static AbstractWidget cycle(String key, OIConfig.RadarBlipFilter current,
                                         Consumer<OIConfig.RadarBlipFilter> apply) {
        return cycle(key, OIConfig.RadarBlipFilter.values(), f -> f.translationKey,
                OIConfig.RadarBlipFilter::byName, current, apply);
    }

    /** Cycles an enum; each value's label resolves through its lang key. */
    private static <E extends Enum<E>> AbstractWidget cycle(String key, E[] values,
            Function<E, String> lang, Function<String, E> byName,
            E current, Consumer<E> apply) {
        return new OiWidgets.Picker<>(List.of(values), current,
                v -> Component.translatable(lang.apply(v)), apply);
    }

    private static AbstractWidget textField(String current, String placeholder,
                                             int maxLen, Consumer<String> apply) {
        EditBox field = new EditBox(
                Minecraft.getInstance().font, 0, 0, 150, 20,
                Component.literal("field"));
        field.setMaxLength(maxLen);
        field.setValue(current);
        field.setHint(Component.literal(placeholder).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        // The settings tile draws the field box; the text sits inset inside it.
        field.setBordered(false);
        field.setResponder(s -> apply.accept(s.trim()));
        return field;
    }


    /** Button whose caption is the translation of {@code key}. */
    private static AbstractWidget button(String key, Runnable action) {
        return new OiWidgets.Flat(Component.translatable(key), action);
    }

    /**
     * Button row that opens the HSV color picker. `get` returns -1 when the
     * option is in its "auto" state; `autoLabel`/`autoAction` offer an escape
     * hatch (e.g. "Allegiance color") back to it.
     */
    private static AbstractWidget colorButton(Screen self, String label,
                                               java.util.function.IntSupplier get,
                                               java.util.function.IntConsumer set,
                                               String autoLabel, Runnable autoAction) {
        OiWidgets.Flat[] ref = new OiWidgets.Flat[1];
        java.util.function.IntConsumer apply = v -> {
            set.accept(v);
            ref[0].setMessage(v == -1
                    ? Component.translatable("options.openintel.color.auto")
                    : Component.literal(String.format("#%08X", v)));
        };
        ref[0] = new OiWidgets.Flat(Component.literal(""), () ->
                Minecraft.getInstance().setScreen(new ColorPickerScreen(self, label,
                        get.getAsInt() == -1 ? 0xFFFF5555 : get.getAsInt(),
                        apply, autoLabel, autoAction)));
        ref[0].swatch(get::getAsInt);
        apply.accept(get.getAsInt());
        return ref[0];
    }
}
