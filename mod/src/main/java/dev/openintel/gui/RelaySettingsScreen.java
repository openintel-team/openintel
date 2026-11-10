package dev.openintel.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.config.RelayProfile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Consumer;

/** Vanilla-widget editor. Credentials are never included in list rows or status text. */
public final class RelaySettingsScreen extends Screen {
    private final Screen parent;
    private final OIConfig config;
    private int selected;
    private int scroll;
    /** Eases the drawn list offset toward {@code scroll}, which stays the target. */
    private final SmoothScroll smooth = new SmoothScroll();
    private int shownScroll() { return smooth.shownPx(scroll); }
    private int listWidth;
    private int listHeight;
    private boolean dirty;
    private boolean revealToken;
    private int cardX0;
    private int cardX1;
    private int statusY;
    private static final int LIST_TOP = 6;
    private static final int ROW_HEIGHT = 28;
    private static final int CARD_TOP = OiStyle.TITLE_H + 4;
    private static final int PAD = 8;
    private static final int FIELD_STEP = 32;

    public RelaySettingsScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
        this.config = OpenIntelClient.config();
    }

    private static Component text(String key) {
        return Component.translatable("options.openintel.relays." + key);
    }

    private List<RelayProfile> profiles() {
        return config.relayProfiles();
    }

    @Override
    protected void init() {
        List<RelayProfile> profiles = profiles();
        selected = Math.max(0, Math.min(selected, profiles.size() - 1));
        listWidth = Math.clamp(width / 4, 110, 200);
        int barTop = height - OiStyle.BAR_H;
        listHeight = Math.max(ROW_HEIGHT, barTop - LIST_TOP - 4);
        scroll = Math.clamp(scroll, 0, Math.max(0, profiles.size() * ROW_HEIGHT - listHeight));
        // A readable form width, centered in the space beside the list, instead of stretching edge to edge.
        int room = width - listWidth - 24;
        int cardW = Math.max(60, Math.min(420, room));
        cardX0 = listWidth + 12 + (room - cardW) / 2;
        cardX1 = cardX0 + cardW;
        int editorX = cardX0 + PAD;
        int editorWidth = Math.max(40, cardX1 - cardX0 - PAD * 2);
        RelayProfile profile = profiles.isEmpty() ? null : profiles.get(selected);
        if (profile != null) {
            int y = CARD_TOP + PAD + 10;
            field("name", profile.name, editorX, y, editorWidth, 128, value -> profile.name = value);
            y += FIELD_STEP;
            field("url", profile.url, editorX, y, editorWidth, 512, value -> profile.url = value);
            y += FIELD_STEP;
            int tokenWidth = Math.max(20, editorWidth - 48);
            EditBox token = field("token", profile.token, editorX, y, tokenWidth, 256,
                    value -> profile.token = value);
            token.addFormatter((value, offset) -> FormattedCharSequence.forward(
                    revealToken ? value : "*".repeat(value.length()), Style.EMPTY));
            OiWidgets.Flat reveal = new OiWidgets.Flat(text(revealToken ? "hide" : "show"), () -> {
                revealToken = !revealToken;
                rebuildWidgets();
            });
            reveal.setRectangle(44, 18, editorX + tokenWidth + 4, y);
            addRenderableWidget(reveal);
            y += FIELD_STEP;
            field("server", profile.minecraftServer, editorX, y, editorWidth, 255,
                    value -> profile.minecraftServer = value);
            y += 26;
            int half = (editorWidth - 4) / 2;
            OiWidgets.Flat[] enabled = new OiWidgets.Flat[1];
            enabled[0] = new OiWidgets.Flat(enabledLabel(profile), () -> {
                profile.enabled = !profile.enabled;
                dirty = true;
                enabled[0].setMessage(enabledLabel(profile));
            });
            enabled[0].setRectangle(half, 18, editorX, y);
            addRenderableWidget(enabled[0]);
            OiWidgets.Flat[] share = new OiWidgets.Flat[1];
            share[0] = new OiWidgets.Flat(sharingLabel(profile), () -> {
                profile.share = !profile.share;
                dirty = true;
                share[0].setMessage(sharingLabel(profile));
            });
            share[0].setRectangle(editorWidth - half - 4, 18, editorX + half + 4, y);
            OiWidgets.Flat sharing = addRenderableWidget(share[0]);
            sharing.setTooltip(Tooltip.create(text("sharing_tooltip")));
            statusY = y + 28;
        }
        int buttonY = OiStyle.barButtonY(height);
        // Bottom bar: list actions on the left, save/close on the right (Jade's Cancel / Save & Quit).
        int actionWidth = Math.clamp((width - 32) / 4, 40, 90);
        addRenderableWidget(Button.builder(text("add"), button -> {
            RelayProfile added = new RelayProfile();
            added.name = text("new_name").getString();
            profiles().add(added);
            selected = profiles().size() - 1;
            scroll = Math.max(0, profiles().size() * ROW_HEIGHT - listHeight);
            dirty = true;
            revealToken = false;
            rebuildWidgets();
        }).bounds(8, buttonY, actionWidth, 20).build());
        Button remove = addRenderableWidget(Button.builder(text("remove"), button -> {
            if (!profiles().isEmpty()) {
                profiles().remove(selected);
                dirty = true;
                revealToken = false;
                rebuildWidgets();
            }
        }).bounds(12 + actionWidth, buttonY, actionWidth, 20).build());
        remove.active = profile != null;
        int footerWidth = Math.max(40, Math.min(Math.clamp((width - 32) / 4 + 20, 60, 130),
                (width - actionWidth * 2 - 36) / 2));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width - 8 - footerWidth, buttonY, footerWidth, 20).build());
        addRenderableWidget(Button.builder(text("reconnect"), button -> apply())
                .bounds(width - 12 - footerWidth * 2, buttonY, footerWidth, 20).build());
    }

    private EditBox field(String key, String value, int x, int y, int width, int maxLength,
                          Consumer<String> setter) {
        // Borderless text inside the box drawn in extractRenderState, like the settings tiles.
        EditBox field = new EditBox(font, x + 5, y + 5, width - 10, 10, text(key));
        field.setBordered(false);
        field.setMaxLength(maxLength);
        field.setValue(value);
        field.setResponder(updated -> {
            setter.accept(updated);
            dirty = true;
        });
        return addRenderableWidget(field);
    }

    private static Component enabledLabel(RelayProfile profile) {
        return text(profile.enabled ? "enabled" : "disabled");
    }

    private static Component sharingLabel(RelayProfile profile) {
        return text(profile.share ? "share" : "receive_only");
    }

    private void apply() {
        config.save();
        OpenIntelClient.reconnectRelay();
        dirty = false;
    }

    @Override
    public void onClose() {
        if (dirty) apply();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.x() >= 0 && click.x() < listWidth
                && click.y() >= LIST_TOP && click.y() < LIST_TOP + listHeight) {
            int row = (int) ((click.y() - LIST_TOP + shownScroll()) / ROW_HEIGHT);
            if (row < profiles().size()) {
                selected = row;
                revealToken = false;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= 0 && mouseX < listWidth && mouseY >= LIST_TOP
                && mouseY < LIST_TOP + listHeight) {
            scroll = (int) Math.clamp(scroll - vertical * ROW_HEIGHT, 0,
                    Math.max(0, profiles().size() * ROW_HEIGHT - listHeight));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private void drawChrome(GuiGraphicsExtractor graphics) {
        // In-world screens must not call extractBackground: its blur pass runs once per frame.
        extractTransparentBackground(graphics);
        int barTop = height - OiStyle.BAR_H;
        OiStyle.sidebar(graphics, listWidth, barTop);
        OiStyle.bottomBar(graphics, width, height);
        List<RelayProfile> profiles = profiles();
        int cx = (cardX0 + cardX1) / 2;
        OiStyle.sectionTitle(graphics, font, title, cx, 0);
        if (profiles.isEmpty()) {
            OiStyle.tile(graphics, cardX0, CARD_TOP, cardX1, CARD_TOP + 34, false, false);
            graphics.centeredText(font, text("empty"), cx, CARD_TOP + 13, OiStyle.TEXT_DIM);
        } else {
            OiStyle.tile(graphics, cardX0, CARD_TOP, cardX1, statusY + 8 + PAD, false, true);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        smooth.update(scroll);
        drawChrome(graphics);
        for (var child : children()) {
            if (child instanceof EditBox field && field.visible) {
                int bx = field.getX() - 5, by = field.getY() - 5, bw = field.getWidth() + 10;
                boolean hot = field.isFocused() || (mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 18);
                OiWidgets.box(graphics, bx, by, bw, 18, hot, true);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.enableScissor(0, LIST_TOP, listWidth, LIST_TOP + listHeight);
        List<RelayProfile> profiles = profiles();
        for (int i = 0; i < profiles.size(); i++) {
            int y = LIST_TOP + i * ROW_HEIGHT - shownScroll();
            if (y + ROW_HEIGHT <= LIST_TOP || y >= LIST_TOP + listHeight) continue;
            RelayProfile profile = profiles.get(i);
            boolean current = i == selected;
            boolean hovered = mouseX >= 0 && mouseX < listWidth && mouseY >= Math.max(y, LIST_TOP)
                    && mouseY < Math.min(y + ROW_HEIGHT, LIST_TOP + listHeight);
            if (current) {
                graphics.fill(0, y + 1, listWidth, y + ROW_HEIGHT - 1, OiStyle.SELECTED_BG);
                graphics.fill(2, y + 4, 4, y + ROW_HEIGHT - 4, OiStyle.TEXT);
            } else if (hovered) {
                graphics.fill(0, y + 1, listWidth, y + ROW_HEIGHT - 1, OiStyle.ROW_HOVER);
            }
            String name = profile.name.isBlank() ? text("new_name").getString() : profile.name;
            graphics.text(font, font.plainSubstrByWidth(name, listWidth - 16), 10, y + 5,
                    hovered && !current ? OiStyle.TEXT_HOVER : OiStyle.TEXT);
            String state = enabledLabel(profile).getString() + " / " + sharingLabel(profile).getString();
            graphics.text(font, font.plainSubstrByWidth(state, listWidth - 16), 10, y + 16,
                    profile.enabled ? OiStyle.TEXT_DIM : OiStyle.TEXT_FAINT);
        }
        graphics.disableScissor();
        OiStyle.scrollbar(graphics, listWidth, LIST_TOP, LIST_TOP + listHeight,
                profiles.size() * ROW_HEIGHT, smooth.shown(scroll), Math.max(0, profiles.size() * ROW_HEIGHT - listHeight));
        if (!profiles.isEmpty()) {
            int x = cardX0 + PAD;
            int y = CARD_TOP + PAD;
            graphics.text(font, text("name"), x, y, OiStyle.TEXT_DIM);
            graphics.text(font, text("url"), x, y + FIELD_STEP, OiStyle.TEXT_DIM);
            graphics.text(font, text("token"), x, y + FIELD_STEP * 2, OiStyle.TEXT_DIM);
            graphics.text(font, text("server"), x, y + FIELD_STEP * 3, OiStyle.TEXT_DIM);
            if (OpenIntelClient.relay() instanceof dev.openintel.net.RelayCoordinator coordinator) {
                String status = coordinator.status(profiles.get(selected).id);
                graphics.fill(x, statusY - 4, cardX1 - PAD, statusY - 3, OiStyle.DIVIDER);
                graphics.text(font, font.plainSubstrByWidth(status, cardX1 - x - PAD), x, statusY + 1,
                        statusColor(status));
            }
        }
    }

    /** Green for a live session, red for a failure, grey otherwise. */
    private static int statusColor(String status) {
        String s = status == null ? "" : status.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("authenticated") || s.startsWith("connected")) return OiStyle.GOOD;
        if (s.contains("fail") || s.contains("reject") || s.contains("error") || s.contains("invalid")
                || s.contains("denied")) return OiStyle.BAD;
        return OiStyle.TEXT_DIM;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
