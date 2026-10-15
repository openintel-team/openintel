package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.investigate.InvestigateSession;
import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Paste snitch / Discord logs and watch block break/place highlights in-world. */
public final class InvestigateScreen extends Screen {
    private final Screen parent;
    private MultiLineEditBox pasteBox;
    private String defaultDim = "minecraft:overworld";
    private int listX, listY, listW, listH;
    private int scroll;
    private static final int ROW_H = 14;
    private String status = "Paste snitch or Discord log lines, then Parse.";

    private static final String[] DIMS = {
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
    };

    public InvestigateScreen(Screen parent) {
        super(Component.literal("Investigate"));
        this.parent = parent;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            defaultDim = mc.level.dimension().identifier().toString();
        }
    }

    @Override
    protected void init() {
        int pasteH = Math.min(120, height / 3);
        pasteBox = MultiLineEditBox.builder()
                .setX(8)
                .setY(24)
                .setPlaceholder(Component.literal("Paste snitch logs here…"))
                .build(font, width - 16, pasteH, Component.literal("logs"));
        pasteBox.setCharacterLimit(200_000);
        pasteBox.setLineLimit(2000);
        addRenderableWidget(pasteBox);

        int by = 28 + pasteH;
        addRenderableWidget(Button.builder(Component.literal("Paste clipboard"),
                b -> pasteClipboard()).bounds(8, by, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Parse"),
                b -> parse()).bounds(122, by, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Watch"),
                b -> watch()).bounds(186, by, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"),
                b -> clear()).bounds(250, by, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> onClose()).bounds(width - 88, by, 80, 20).build());

        int dimIndex = 0;
        for (int i = 0; i < DIMS.length; i++) {
            if (DIMS[i].equalsIgnoreCase(defaultDim)) dimIndex = i;
        }
        defaultDim = DIMS[dimIndex];
        addRenderableWidget(CycleButton.<String>builder(s -> Component.literal(shortDim(s)), defaultDim)
                .withValues(DIMS)
                .create(314, by, 120, 20, Component.literal("Dim"),
                        (btn, value) -> defaultDim = value));

        listX = 8;
        listY = by + 28;
        listW = width - 16;
        listH = height - listY - 28;
    }

    private void pasteClipboard() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null || clip.isBlank()) {
            status = "Clipboard is empty.";
            return;
        }
        String existing = pasteBox.getValue();
        if (existing == null || existing.isBlank()) pasteBox.setValue(clip);
        else pasteBox.setValue(existing + "\n" + clip);
        status = "Clipboard pasted — click Parse.";
    }

    private void parse() {
        SnitchLogParser.ParseResult result = SnitchLogParser.parsePaste(pasteBox.getValue());
        InvestigateSession.get().load(result, defaultDim);
        status = result.events().size() + " events"
                + (result.skippedNoCoords() > 0
                ? ", skipped " + result.skippedNoCoords() + " without coords"
                : "")
                + ".";
        scroll = 0;
    }

    private void watch() {
        InvestigateSession session = InvestigateSession.get();
        if (session.size() == 0) {
            parse();
        }
        if (session.size() == 0) {
            status = "No events with coordinates to watch.";
            return;
        }
        session.watch();
        OpenIntelClient.status("investigate watching " + session.size() + " events");
        minecraft.setScreen(null);
    }

    private void clear() {
        InvestigateSession.get().clear();
        pasteBox.setValue("");
        status = "Cleared.";
        scroll = 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
                List<InvestigateSession.Event> events = InvestigateSession.get().events();
                int row = (int) ((my - listY + scroll) / ROW_H);
                if (row >= 0 && row < events.size()) {
                    InvestigateSession.get().select(row);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        if (mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH) {
            int max = Math.max(0, InvestigateSession.get().size() * ROW_H - listH);
            scroll = (int) Math.max(0, Math.min(max, scroll - vertical * ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101015);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawString(font, "Investigate — paste snitch / Discord logs", 8, 8, 0xFFE8EAF0);

        List<InvestigateSession.Event> events = InvestigateSession.get().events();
        int selected = InvestigateSession.get().index();
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int y = listY - scroll;
        for (int i = 0; i < events.size(); i++) {
            InvestigateSession.Event ev = events.get(i);
            if (y + ROW_H >= listY && y <= listY + listH) {
                int bg = i == selected ? 0x2E7C8CF8 : (i % 2 == 0 ? 0x18000000 : 0x10000000);
                ctx.fill(listX, y, listX + listW, y + ROW_H, bg);
                int color = switch (ev.kind()) {
                    case BREAK -> 0xFFFF8A80;
                    case PLACE -> 0xFF80FFB0;
                    default -> 0xFFE8EAF0;
                };
                String line = (i + 1) + ". " + ev.detail();
                if (line.length() > 120) line = line.substring(0, 117) + "...";
                ctx.drawString(font, line, listX + 4, y + 3, color);
            }
            y += ROW_H;
        }
        ctx.disableScissor();
        ctx.drawString(font, status, 8, height - 18, 0xFF8E93A6);
    }

    private static String shortDim(String dim) {
        if (dim == null) return "?";
        int i = dim.indexOf(':');
        String s = i >= 0 ? dim.substring(i + 1) : dim;
        return s.toLowerCase(Locale.ROOT);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
