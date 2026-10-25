package dev.openintel.gui;

import com.mojang.blaze3d.platform.InputConstants;

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

import dev.openintel.investigate.InvestigateLogImport;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
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
    private String status = "Import a Discord .txt export or paste logs, then Parse.";
    private String parsedText;
    private String parsedDimension;
    private boolean importing;
    private int importRevision;

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
        String draft = pasteBox == null ? "" : pasteBox.getValue();
        int pasteH = Math.min(120, height / 3);
        pasteBox = MultiLineEditBox.builder()
                .setX(8)
                .setY(24)
                .setPlaceholder(Component.literal("Paste snitch logs here…"))
                .build(font, width - 16, pasteH, Component.literal("logs"));
        pasteBox.setCharacterLimit(InvestigateLogImport.MAX_BYTES);
        pasteBox.setLineLimit(Integer.MAX_VALUE);
        pasteBox.setValue(draft);
        addRenderableWidget(pasteBox);

        int by = 28 + pasteH;
        int slot = Math.min(110, (width - 28) / 4);
        addRenderableWidget(Button.builder(Component.literal("Import .txt…"),
                b -> importFile()).bounds(8, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Paste"),
                b -> pasteClipboard()).bounds(12 + slot, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Parse"),
                b -> parse()).bounds(16 + slot * 2, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"),
                b -> clear()).bounds(20 + slot * 3, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Watch playback"),
                b -> watch()).bounds(8, height - 30, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> onClose()).bounds(width - 88, height - 30, 80, 20).build());

        int dimIndex = 0;
        for (int i = 0; i < DIMS.length; i++) {
            if (DIMS[i].equalsIgnoreCase(defaultDim)) dimIndex = i;
        }
        defaultDim = DIMS[dimIndex];
        addRenderableWidget(CycleButton.<String>builder(s -> Component.literal(shortDim(s)), defaultDim)
                .withValues(DIMS)
                .create(8, by + 24, Math.min(260, width - 16), 20, Component.literal("World if missing"),
                        (btn, value) -> defaultDim = value));

        listX = 8;
        listY = by + 64;
        listW = width - 16;
        listH = Math.max(0, height - listY - 58);
    }

    private void importFile() {
        if (importing) return;
        minecraft.setScreen(new InvestigateFileScreen(this, this::readFile));
    }

    private void readFile(Path selected) {
        int revision = ++importRevision;
        importing = true;
        status = "Reading log file…";
        CompletableFuture.supplyAsync(() -> {
            try {
                return InvestigateLogImport.read(selected);
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }).whenComplete((text, error) -> Minecraft.getInstance().execute(() -> {
            if (revision != importRevision) return;
            importing = false;
            if (error != null) {
                status = "Could not read logs. Choose a UTF-8 text file up to 2 MiB.";
                return;
            }
            pasteBox.setValue(text);
            parse();
            status = "Imported " + selected.getFileName() + ": " + status;
        }));
    }

    private void pasteClipboard() {
        importRevision++;
        importing = false;
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
        parsedText = pasteBox.getValue();
        parsedDimension = defaultDim;
        SnitchLogParser.ParseResult result = SnitchLogParser.parsePaste(parsedText);
        InvestigateSession.get().load(result, defaultDim);
        status = result.events().size() + " events"
                + (result.skippedNoCoords() > 0
                ? ", skipped " + result.skippedNoCoords() + " non-event lines"
                : "")
                + ".";
        scroll = 0;
    }

    private void watch() {
        if (importing) {
            status = "Reading log file — please wait.";
            return;
        }
        InvestigateSession session = InvestigateSession.get();
        if ((parsedText != null || !pasteBox.getValue().isBlank() || session.size() == 0)
                && (!pasteBox.getValue().equals(parsedText) || !defaultDim.equals(parsedDimension))) {
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
        importRevision++;
        importing = false;
        InvestigateSession.get().clear();
        pasteBox.setValue("");
        status = "Cleared.";
        scroll = 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
        ctx.drawString(font, "Investigate — import and review logs", 8, 8, 0xFFE8EAF0);

        List<InvestigateSession.Event> events = InvestigateSession.get().events();
        ctx.drawString(font, "Events — " + events.size() + " loaded", listX, listY - 12, 0xFFE8EAF0);
        int selected = InvestigateSession.get().index();
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        if (events.isEmpty()) {
            ctx.drawString(font, "Import or paste logs, then Parse to review events.",
                    listX + 4, listY + 6, 0xFF8E93A6);
        }
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
        ctx.drawString(font, font.plainSubstrByWidth(status, width - 16), 8, height - 46, 0xFF8E93A6);
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
