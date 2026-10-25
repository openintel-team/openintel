package dev.openintel.gui;

import dev.openintel.investigate.InvestigateLogImport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** A compact, read-only log picker. Selecting a row never imports it. */
final class InvestigateFileScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int TEXT = 0xFFE3E7ED, MUTED = 0xFF9CA6B5, ACCENT = 0xFF9DBCF4;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private record Entry(Path path, boolean folder, long bytes, String modified) {
        String name() { return path.getFileName().toString(); }
    }

    private final Screen parent;
    private final Consumer<Path> onSelect;
    private Path directory;
    private List<Entry> entries = List.of(), visible = List.of();
    private Entry selected;
    private EditBox pathBox, searchBox;
    private Button importButton;
    private String query = "", status = "";
    private int left, top, panelW, panelH, listX, listY, listW, listH, detailX, detailW;
    private int scroll;
    private boolean draggingScroll;
    private double grabOffset;

    InvestigateFileScreen(Screen parent, Consumer<Path> onSelect) {
        super(Component.literal("Import snitch logs"));
        this.parent = parent;
        this.onSelect = onSelect;
        Path home = Path.of(System.getProperty("user.home", "."));
        directory = Files.isDirectory(home.resolve("Downloads")) ? home.resolve("Downloads") : home;
        readDirectory();
    }

    private void readDirectory() {
        var found = new ArrayList<Entry>();
        status = "";
        try (var paths = Files.list(directory)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                try {
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    if (!attributes.isDirectory() && !(attributes.isRegularFile()
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))) continue;
                    if (found.size() == 5000) {
                        status = "Folder has more than 5000 entries. Enter a more specific path.";
                        break;
                    }
                    found.add(new Entry(path, attributes.isDirectory(), attributes.size(),
                            DATE.format(attributes.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()))));
                } catch (IOException | SecurityException ignored) { }
            }
            found.sort(Comparator.<Entry, Boolean>comparing(e -> !e.folder())
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        } catch (IOException | java.io.UncheckedIOException | SecurityException ex) {
            status = "Cannot read this folder. Use Up or enter another path.";
        }
        entries = List.copyOf(found);
        selected = null;
        query = "";
        filter();
    }

    private void filter() {
        String needle = query.toLowerCase(Locale.ROOT);
        visible = entries.stream().filter(e -> e.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
        if (!visible.contains(selected)) selected = null;
        scroll = 0;
        updateAction();
    }

    private void updateAction() {
        if (importButton == null) return;
        importButton.setMessage(Component.literal(selected != null && selected.folder() ? "Open folder" : "Import log"));
        importButton.active = selected != null && (selected.folder() || selected.bytes() <= InvestigateLogImport.MAX_BYTES);
    }

    private void navigate(Path path) {
        path = path.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            directory = path;
            readDirectory();
            rebuildWidgets();
        } else if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")) {
            directory = path.getParent();
            readDirectory();
            Path target = path;
            selected = visible.stream().filter(e -> e.path().equals(target)).findFirst().orElse(null);
            rebuildWidgets();
            revealSelection();
        } else {
            status = "Choose an existing folder or .txt file.";
        }
    }

    private void goToPath() {
        try {
            navigate(directory.resolve(Path.of(pathBox.getValue())));
        } catch (RuntimeException ex) {
            status = "Invalid path. Enter a folder or .txt file.";
        }
    }

    private void openSelected() {
        if (selected == null) return;
        if (selected.folder()) navigate(selected.path());
        else if (selected.bytes() <= InvestigateLogImport.MAX_BYTES) {
            minecraft.setScreen(parent);
            onSelect.accept(selected.path());
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(880, width - 16);
        panelH = Math.min(520, height - 16);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        detailW = panelW >= 520 ? Math.max(170, panelW / 4) : 0;
        listX = left + 10;
        listY = top + 84;
        listW = panelW - 20 - (detailW > 0 ? detailW + 8 : 0);
        listH = Math.max(18, panelH - 144 - (detailW == 0 ? 22 : 0));
        detailX = listX + listW + 8;
        int navY = top + 30;
        Button up = addRenderableWidget(Button.builder(Component.literal("Up"), b -> navigate(directory.getParent()))
                .bounds(left + 10, navY, 30, 20).build());
        up.active = directory.getParent() != null;
        addRenderableWidget(Button.builder(Component.literal("Downloads"), b ->
                navigate(Path.of(System.getProperty("user.home", "."), "Downloads")))
                .bounds(left + 44, navY, 68, 20).build());
        pathBox = new EditBox(font, left + 118, navY, panelW - 170, 20, Component.literal("Folder or file path"));
        pathBox.setMaxLength(8192);
        pathBox.setValue(directory.toString());
        addRenderableWidget(pathBox);
        addRenderableWidget(Button.builder(Component.literal("Go"), b -> goToPath())
                .bounds(left + panelW - 46, navY, 36, 20).build());
        searchBox = new EditBox(font, listX + 44, top + 51, listW - 44, 18, Component.literal("Filter filenames"));
        searchBox.setHint(Component.literal("Filter filenames…"));
        searchBox.setValue(query);
        searchBox.setResponder(value -> { query = value; filter(); });
        addRenderableWidget(searchBox);
        importButton = addRenderableWidget(Button.builder(Component.literal("Import log"), b -> openSelected())
                .bounds(left + panelW - 188, top + panelH - 30, 92, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + panelW - 90, top + panelH - 30, 80, 20).build());
        updateAction();
        draggingScroll = false;
        clampScroll();
        revealSelection();
    }

    private boolean inList(double x, double y) {
        return x >= listX && x < listX + listW && y >= listY && y < listY + listH;
    }

    private int maxScroll() { return Math.max(0, visible.size() * ROW_H - listH); }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, maxScroll())); }
    private int thumbH() { return Math.min(listH, Math.max(20, listH * listH / Math.max(listH, visible.size() * ROW_H))); }
    private int thumbY() { return listY + (maxScroll() == 0 ? 0 : scroll * (listH - thumbH()) / maxScroll()); }

    private void revealSelection() {
        int row = visible.indexOf(selected);
        if (row < 0) return;
        if (row * ROW_H < scroll) scroll = row * ROW_H;
        if ((row + 1) * ROW_H > scroll + listH) scroll = (row + 1) * ROW_H - listH;
        clampScroll();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && inList(click.x(), click.y())) {
            setFocused(null);
            if (click.x() >= listX + listW - 9 && maxScroll() > 0) {
                draggingScroll = true;
                grabOffset = click.y() >= thumbY() && click.y() < thumbY() + thumbH()
                        ? click.y() - thumbY() : thumbH() / 2.0;
                dragThumb(click.y());
            } else {
                int row = (int) ((click.y() - listY + scroll) / ROW_H);
                if (row < visible.size()) {
                    Entry previous = selected;
                    selected = visible.get(row);
                    updateAction();
                    if (doubled && selected.equals(previous)) openSelected();
                }
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void dragThumb(double y) {
        int travel = listH - thumbH();
        if (travel > 0) scroll = (int) Math.round((y - listY - grabOffset) * maxScroll() / travel);
        clampScroll();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScroll) { dragThumb(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScroll = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (inList(x, y)) {
            scroll -= (int) (vertical * ROW_H * 3);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (pathBox.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
            goToPath();
            return true;
        }
        if (!(getFocused() instanceof EditBox)) {
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                if (getFocused() == null) { openSelected(); return true; }
            }
            if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
                if (!visible.isEmpty()) {
                    int index = visible.indexOf(selected);
                    int next = index < 0 ? 0 : Math.max(0, Math.min(visible.size() - 1,
                            index + (event.key() == InputConstants.KEY_UP ? -1 : 1)));
                    selected = visible.get(next);
                    setFocused(null);
                    revealSelection();
                    updateAction();
                }
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xA0101218);
        ctx.fill(left, top, left + panelW, top + panelH, 0xF0181C24);
        ctx.fill(left, top, left + panelW, top + 24, 0xFF232A35);
        ctx.text(font, title, left + 10, top + 8, TEXT);
        ctx.text(font, "Search", listX, top + 56, MUTED);
        ctx.fill(listX, listY - 12, listX + listW, listY, 0xFF29313D);
        ctx.text(font, "Name", listX + 22, listY - 10, MUTED);
        if (listW >= 280) ctx.text(font, "Size", listX + listW - 65, listY - 10, MUTED);
        ctx.fill(listX, listY, listX + listW, listY + listH, 0xFF10141A);
        ctx.enableScissor(listX, listY, listX + listW - 10, listY + listH);
        int first = scroll / ROW_H;
        for (int i = first; i < visible.size() && i * ROW_H - scroll < listH; i++) {
            Entry entry = visible.get(i);
            int y = listY + i * ROW_H - scroll;
            boolean hovered = inList(mouseX, mouseY) && mouseY >= y && mouseY < y + ROW_H;
            int background = entry.equals(selected) ? 0xFF344D70 : hovered ? 0xFF252F3D
                    : i % 2 == 0 ? 0xFF161C24 : 0xFF10141A;
            ctx.fill(listX, y, listX + listW - 10, y + ROW_H, background);
            if (entry.equals(selected)) ctx.fill(listX, y, listX + 2, y + ROW_H, ACCENT);
            drawIcon(ctx, listX + 7, y + 5, entry.folder());
            int nameWidth = listW - (listW >= 280 ? 100 : 34);
            ctx.text(font, ellipsize(entry.name(), nameWidth), listX + 22, y + 5,
                    entry.folder() ? 0xFFE6C984 : TEXT);
            if (listW >= 280 && !entry.folder())
                ctx.text(font, size(entry.bytes()), listX + listW - 72, y + 5, MUTED);
        }
        if (visible.isEmpty()) ctx.text(font, entries.isEmpty() ? "No .txt files or folders here." : "No matching filenames.",
                listX + 10, listY + 12, MUTED);
        ctx.disableScissor();
        if (maxScroll() > 0) {
            ctx.fill(listX + listW - 7, listY, listX + listW - 2, listY + listH, 0xFF252D38);
            ctx.fill(listX + listW - 7, thumbY(), listX + listW - 2, thumbY() + thumbH(), 0xFF7A8CA6);
        }
        if (detailW > 0) {
            ctx.enableScissor(detailX, top + 51, detailX + detailW, listY + listH);
            drawDetails(ctx);
            ctx.disableScissor();
        }
        else ctx.text(font, ellipsize(selected == null ? "Select a file to import." : selected.name(), listW),
                listX, listY + listH + 8, selected == null ? MUTED : TEXT);
        int footerY = top + panelH - 50;
        ctx.text(font, ellipsize(status.isEmpty() ? visible.size() + " items • .txt logs only • Up to 2 MiB" : status,
                panelW - 20), left + 10, footerY, MUTED);
        if (panelW >= 520) ctx.text(font, "Double-click to open • Enter to confirm", left + 10, top + panelH - 24, MUTED);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void drawDetails(GuiGraphicsExtractor ctx) {
        int y = top + 51;
        ctx.fill(detailX, y, detailX + detailW, listY + listH, 0xFF10141A);
        ctx.text(font, "Selected file", detailX + 10, y + 8, MUTED);
        y += 28;
        if (selected == null) {
            wrapped(ctx, "Select a text export to see its details, then choose Import log.", y, MUTED, 5);
            return;
        }
        y = wrapped(ctx, selected.name(), y, TEXT, 4) + 12;
        ctx.text(font, selected.folder() ? "Folder" : "Discord / snitch text log", detailX + 10, y, ACCENT);
        y += 24;
        if (!selected.folder()) {
            ctx.text(font, "Size: " + size(selected.bytes()), detailX + 10, y, TEXT);
            y += 18;
        }
        ctx.text(font, "Modified", detailX + 10, y, MUTED);
        ctx.text(font, selected.modified(), detailX + 10, y + 14, TEXT);
        y += 44;
        wrapped(ctx, selected.folder() ? "Double-click or choose Open folder to browse."
                : selected.bytes() > InvestigateLogImport.MAX_BYTES ? "This file exceeds the 2 MiB limit. Choose a smaller export."
                : "Import reads this file locally. Your existing input is replaced after the file is read successfully.",
                y, selected.bytes() > InvestigateLogImport.MAX_BYTES && !selected.folder() ? 0xFFFFB0A3 : MUTED, 6);
    }

    private int wrapped(GuiGraphicsExtractor ctx, String text, int y, int color, int maxLines) {
        for (var line : font.split(Component.literal(text), detailW - 20)) {
            if (maxLines-- <= 0 || y + 9 >= listY + listH - 6) break;
            ctx.text(font, line, detailX + 10, y, color);
            y += 12;
        }
        return y;
    }

    private String ellipsize(String text, int pixels) {
        return font.width(text) <= pixels ? text : font.plainSubstrByWidth(text, Math.max(0, pixels - font.width("…"))) + "…";
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
    }

    private static void drawIcon(GuiGraphicsExtractor ctx, int x, int y, boolean folder) {
        if (folder) {
            ctx.fill(x, y, x + 5, y + 2, 0xFFE6C984);
            ctx.fill(x, y + 2, x + 10, y + 9, 0xFFBA9652);
            ctx.fill(x + 1, y + 4, x + 9, y + 8, 0xFFE6C984);
        } else {
            ctx.fill(x + 1, y, x + 8, y + 10, 0xFF99ADC8);
            ctx.fill(x + 3, y + 3, x + 7, y + 4, 0xFF344256);
            ctx.fill(x + 3, y + 6, x + 7, y + 7, 0xFF344256);
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
