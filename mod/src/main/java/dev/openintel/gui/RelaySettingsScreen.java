package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.config.RelayProfile;
import net.minecraft.client.gui.GuiGraphics;
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
    private int listWidth;
    private int listHeight;
    private boolean dirty;
    private boolean revealToken;
    private static final int LIST_TOP = 28;
    private static final int ROW_HEIGHT = 28;

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
        listWidth = Math.max(80, width / 3);
        listHeight = Math.max(28, height - 90);
        scroll = Math.clamp(scroll, 0, Math.max(0, profiles.size() * ROW_HEIGHT - listHeight));
        int editorX = listWidth + 16;
        int editorWidth = Math.max(40, width - editorX - 8);
        RelayProfile profile = profiles.isEmpty() ? null : profiles.get(selected);
        if (profile != null) {
            field("name", profile.name, editorX, 38, editorWidth, 128, value -> profile.name = value);
            field("url", profile.url, editorX, 70, editorWidth, 512, value -> profile.url = value);
            int tokenWidth = Math.max(20, editorWidth - 48);
            EditBox token = field("token", profile.token, editorX, 102, tokenWidth, 256,
                    value -> profile.token = value);
            token.addFormatter((value, offset) -> FormattedCharSequence.forward(
                    revealToken ? value : "*".repeat(value.length()), Style.EMPTY));
            addRenderableWidget(Button.builder(text(revealToken ? "hide" : "show"), button -> {
                revealToken = !revealToken;
                rebuildWidgets();
            }).bounds(editorX + tokenWidth + 4, 102, 44, 18).build());
            field("server", profile.minecraftServer, editorX, 134, editorWidth, 255,
                    value -> profile.minecraftServer = value);
            int half = (editorWidth - 4) / 2;
            addRenderableWidget(Button.builder(enabledLabel(profile), button -> {
                profile.enabled = !profile.enabled;
                dirty = true;
                button.setMessage(enabledLabel(profile));
            }).bounds(editorX, 158, half, 20).build());
            Button sharing = addRenderableWidget(Button.builder(sharingLabel(profile), button -> {
                profile.share = !profile.share;
                dirty = true;
                button.setMessage(sharingLabel(profile));
            }).bounds(editorX + half + 4, 158, editorWidth - half - 4, 20).build());
            sharing.setTooltip(Tooltip.create(text("sharing_tooltip")));
        }
        int actionWidth = Math.max(40, (listWidth - 4) / 2);
        addRenderableWidget(Button.builder(text("add"), button -> {
            RelayProfile added = new RelayProfile();
            added.name = text("new_name").getString();
            profiles().add(added);
            selected = profiles().size() - 1;
            scroll = Math.max(0, profiles().size() * ROW_HEIGHT - listHeight);
            dirty = true;
            revealToken = false;
            rebuildWidgets();
        }).bounds(8, height - 54, actionWidth, 20).build());
        Button remove = addRenderableWidget(Button.builder(text("remove"), button -> {
            if (!profiles().isEmpty()) {
                profiles().remove(selected);
                dirty = true;
                revealToken = false;
                rebuildWidgets();
            }
        }).bounds(12 + actionWidth, height - 54, actionWidth, 20).build());
        remove.active = profile != null;
        int footerWidth = Math.min(200, (width - 24) / 2);
        addRenderableWidget(Button.builder(text("reconnect"), button -> apply())
                .bounds(width / 2 - footerWidth - 4, height - 26, footerWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 4, height - 26, footerWidth, 20).build());
    }

    private EditBox field(String key, String value, int x, int y, int width, int maxLength,
                          Consumer<String> setter) {
        EditBox field = new EditBox(font, x, y, width, 18, text(key));
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
        if (click.button() == 0 && click.x() >= 8 && click.x() < 8 + listWidth
                && click.y() >= LIST_TOP && click.y() < LIST_TOP + listHeight) {
            int row = (int) ((click.y() - LIST_TOP + scroll) / ROW_HEIGHT);
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
        if (mouseX >= 8 && mouseX < 8 + listWidth && mouseY >= LIST_TOP
                && mouseY < LIST_TOP + listHeight) {
            scroll = (int) Math.clamp(scroll - vertical * ROW_HEIGHT, 0,
                    Math.max(0, profiles().size() * ROW_HEIGHT - listHeight));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xE0101015);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawString(font, title, 8, 10, 0xFFE8EAF0);
        ctx.enableScissor(8, LIST_TOP, 8 + listWidth, LIST_TOP + listHeight);
        List<RelayProfile> profiles = profiles();
        for (int i = 0; i < profiles.size(); i++) {
            int y = LIST_TOP + i * ROW_HEIGHT - scroll;
            if (y + ROW_HEIGHT <= LIST_TOP || y >= LIST_TOP + listHeight) continue;
            RelayProfile profile = profiles.get(i);
            ctx.fill(8, y, 8 + listWidth, y + ROW_HEIGHT, i == selected ? 0x557C8CF8 : 0x22000000);
            String name = profile.name.isBlank() ? text("new_name").getString() : profile.name;
            ctx.drawString(font, font.plainSubstrByWidth(name, listWidth - 8), 12, y + 3, 0xFFE8EAF0);
            String state = enabledLabel(profile).getString() + " / " + sharingLabel(profile).getString();
            ctx.drawString(font, font.plainSubstrByWidth(state, listWidth - 8), 12, y + 15, 0xFFAAAAAA);
        }
        ctx.disableScissor();
        if (profiles.isEmpty()) {
            ctx.drawString(font, text("empty"), listWidth + 16, 38, 0xFFAAAAAA);
        } else {
            int x = listWidth + 16;
            ctx.drawString(font, text("name"), x, 28, 0xFFAAAAAA);
            ctx.drawString(font, text("url"), x, 60, 0xFFAAAAAA);
            ctx.drawString(font, text("token"), x, 92, 0xFFAAAAAA);
            ctx.drawString(font, text("server"), x, 124, 0xFFAAAAAA);
            if (OpenIntelClient.relay() instanceof dev.openintel.net.RelayCoordinator coordinator) {
                String status = coordinator.status(profiles.get(selected).id);
                ctx.drawString(font, font.plainSubstrByWidth(status, width - x - 8), x, 184, 0xFFAAAAAA);
            }
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
