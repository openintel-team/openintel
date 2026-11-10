package dev.openintel.gui;

import com.mojang.blaze3d.platform.InputConstants;

import dev.openintel.OpenIntelClient;
import dev.openintel.investigate.InvestigateSession;
import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    /** Eases the drawn list offset toward {@code scroll}, which stays the target. */
    private final SmoothScroll smooth = new SmoothScroll();
    private int shownScroll() { return smooth.shownPx(scroll); }
    private static final int ROW_H = 14;
    private int logsY0, logsY1;
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
        int pasteH = Math.clamp(height / 4, 40, 120);
        int inner = width - 32;
        logsY0 = OiStyle.TITLE_H + 2;
        pasteBox = MultiLineEditBox.builder()
                .setX(16)
                .setY(logsY0 + 18)
                .setPlaceholder(Component.literal("Paste snitch logs here…"))
                .build(font, inner, pasteH, Component.literal("logs"));
        pasteBox.setCharacterLimit(InvestigateLogImport.MAX_BYTES);
        pasteBox.setLineLimit(Integer.MAX_VALUE);
        pasteBox.setValue(draft);
        addRenderableWidget(pasteBox);

        int by = logsY0 + 22 + pasteH;
        int cycleW = Math.min(200, inner);
        boolean oneRow = inner >= 4 * 70 + 12 + 8 + 140;
        int buttonsW = oneRow ? inner - Math.min(cycleW, inner - 4 * 70 - 20) - 8 : inner;
        if (oneRow) cycleW = inner - buttonsW - 8;
        int slot = Math.min(110, (buttonsW - 12) / 4);
        addRenderableWidget(Button.builder(Component.literal("Import .txt…"),
                b -> importFile()).bounds(16, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Paste"),
                b -> pasteClipboard()).bounds(20 + slot, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Parse"),
                b -> parse()).bounds(24 + slot * 2, by, slot, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"),
                b -> clear()).bounds(28 + slot * 3, by, slot, 20).build());
        int buttonY = OiStyle.barButtonY(height);
        addRenderableWidget(Button.builder(Component.literal("Watch playback"),
                b -> watch()).bounds(8, buttonY, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> onClose()).bounds(width - 88, buttonY, 80, 20).build());

        int dimIndex = 0;
        for (int i = 0; i < DIMS.length; i++) {
            if (DIMS[i].equalsIgnoreCase(defaultDim)) dimIndex = i;
        }
        defaultDim = DIMS[dimIndex];
        int cycleX = oneRow ? width - 16 - cycleW : 16;
        int cycleY = oneRow ? by : by + 24;
        addRenderableWidget(CycleButton.<String>builder(s -> Component.literal(shortDim(s)), defaultDim)
                .withValues(DIMS)
                .create(cycleX, cycleY, cycleW, 20, Component.literal("World if missing"),
                        (btn, value) -> defaultDim = value));
        logsY1 = cycleY + 28;

        listX = 16;
        listY = logsY1 + 4 + 20;
        listW = width - 32;
        listH = Math.max(0, height - OiStyle.BAR_H - 8 - 16 - listY);
    }

    private void importFile() {
        if (importing) return;
        minecraft.gui.setScreen(new InvestigateFileScreen(this, this::readFile));
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
        smooth.snap(0);
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
        minecraft.gui.setScreen(null);
    }

    private void clear() {
        importRevision++;
        importing = false;
        InvestigateSession.get().clear();
        pasteBox.setValue("");
        status = "Cleared.";
        scroll = 0;
        smooth.snap(0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            double mx = click.x(), my = click.y();
            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
                List<InvestigateSession.Event> events = InvestigateSession.get().events();
                int row = (int) ((my - listY + shownScroll()) / ROW_H);
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
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        smooth.update(scroll);
        // In-world screens must not call extractBackground: its blur pass runs once per frame.
        extractTransparentBackground(ctx);
        OiStyle.bottomBar(ctx, width, height);
        OiStyle.sectionTitle(ctx, font, Component.literal("Investigate — import and review logs"), width / 2, 0);
        OiStyle.tile(ctx, 8, logsY0, width - 8, logsY1, false, false);
        int eventsY0 = logsY1 + 4;
        int eventsY1 = height - OiStyle.BAR_H - 6;
        OiStyle.tile(ctx, 8, eventsY0, width - 8, eventsY1, false, true);
        ctx.text(font, "Logs", 16, logsY0 + 6, OiStyle.TEXT_DIM);
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        List<InvestigateSession.Event> events = InvestigateSession.get().events();
        ctx.text(font, "Events — " + events.size() + " loaded", listX, eventsY0 + 7, OiStyle.TEXT_DIM);
        ctx.fill(listX, listY - 3, listX + listW, listY - 2, OiStyle.DIVIDER);
        int selected = InvestigateSession.get().index();
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        if (events.isEmpty()) {
            ctx.text(font, "Import or paste logs, then Parse to review events.",
                    listX + 4, listY + 6, OiStyle.TEXT_DIM);
        }
        int y = listY - shownScroll();
        for (int i = 0; i < events.size(); i++) {
            InvestigateSession.Event ev = events.get(i);
            if (y + ROW_H >= listY && y <= listY + listH) {
                boolean hov = mouseX >= listX && mouseX < listX + listW && mouseY >= Math.max(y, listY)
                        && mouseY < Math.min(y + ROW_H, listY + listH);
                int bg = i == selected ? OiStyle.SELECTED_BG : (hov ? OiStyle.ROW_HOVER : (i % 2 == 0 ? 0x18000000 : 0));
                ctx.fill(listX, y, listX + listW, y + ROW_H, bg);
                if (i == selected) ctx.fill(listX, y + 2, listX + 2, y + ROW_H - 2, OiStyle.TEXT);
                int color = switch (ev.kind()) {
                    case BREAK -> 0xFFFF8A80;
                    case PLACE -> 0xFF80FFB0;
                    default -> 0xFFE8EAF0;
                };
                String line = (i + 1) + ". " + ev.detail();
                if (line.length() > 120) line = line.substring(0, 117) + "...";
                ctx.text(font, font.plainSubstrByWidth(line, listW - 14), listX + 6, y + 3, color);
            }
            y += ROW_H;
        }
        ctx.disableScissor();
        int max = Math.max(0, events.size() * ROW_H - listH);
        OiStyle.scrollbar(ctx, listX + listW, listY, listY + listH, events.size() * ROW_H, smooth.shown(scroll), max);
        ctx.fill(listX, listY + listH + 2, listX + listW, listY + listH + 3, OiStyle.DIVIDER);
        ctx.text(font, font.plainSubstrByWidth(status, listW), listX, listY + listH + 6, statusColor());
    }

    /** Red for failures, green once events are loaded, grey otherwise. */
    private int statusColor() {
        String s = status.toLowerCase(Locale.ROOT);
        if (s.startsWith("could not") || s.startsWith("no events")) return OiStyle.BAD;
        if (!importing && InvestigateSession.get().size() > 0 && !s.startsWith("cleared")) return OiStyle.GOOD;
        return OiStyle.TEXT_DIM;
    }

    private static String shortDim(String dim) {
        if (dim == null) return "?";
        int i = dim.indexOf(':');
        String s = i >= 0 ? dim.substring(i + 1) : dim;
        return s.toLowerCase(Locale.ROOT);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
