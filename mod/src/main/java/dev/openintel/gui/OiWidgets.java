package dev.openintel.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Flat controls for settings tiles, in the Features screen's look: the
 * two-part switch, a thin slider with its value on the right, a value
 * picker with arrows, and dark bordered buttons. The row label lives on the
 * tile, so controls only show their value.
 */
public final class OiWidgets {
    private OiWidgets() { }

    static final int FIELD_BG = 0xFF0C0C10;
    static final int FIELD_BG_HOVER = 0xFF1C1C24;
    static final int FIELD_BORDER = 0xFF4A4A54;
    static final int FIELD_BORDER_HOVER = 0xFFC8C8D0;
    static final int TRACK = 0xFF3A3A44;
    static final int TRACK_FILL = 0xFF5A78B8;

    private static Font font() { return Minecraft.getInstance().font; }

    /** Dark field box used by buttons, pickers and text fields. */
    public static void box(GuiGraphicsExtractor ctx, int x, int y, int w, int h, boolean hovered, boolean active) {
        ctx.fill(x, y, x + w, y + h, hovered && active ? FIELD_BG_HOVER : FIELD_BG);
        ctx.outline(x, y, w, h, hovered && active ? FIELD_BORDER_HOVER : FIELD_BORDER);
    }

    // ------------------------------------------------------------ switch ----

    /** On/off switch drawn at the right edge of its bounds. */
    public static final class Toggle extends AbstractButton {
        private boolean value;
        private final Consumer<Boolean> apply;

        public Toggle(boolean value, Consumer<Boolean> apply) {
            super(0, 0, OiStyle.SW_W, 20, Component.empty());
            this.value = value;
            this.apply = apply;
        }

        public boolean value() { return value; }

        @Override public void onPress(InputWithModifiers input) {
            value = !value;
            apply.accept(value);
        }

        @Override protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
            int x = getX() + getWidth() - OiStyle.SW_W;
            int y = getY() + (getHeight() - OiStyle.SW_H) / 2;
            if (isHoveredOrFocused() && active) ctx.outline(x - 1, y - 1, OiStyle.SW_W + 2, OiStyle.SW_H + 2, FIELD_BORDER_HOVER);
            OiStyle.drawSwitch(ctx, x, y, value, active);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    // ------------------------------------------------------------ slider ----

    /** Integer slider: thin track with a fill and knob, value text right-aligned. */
    public static final class Slider extends AbstractSliderButton {
        private static final int VALUE_W = 54;
        private final int min, max;
        private final IntFunction<String> format;
        private final Consumer<Integer> apply;

        public Slider(int min, int max, int current, IntFunction<String> format, Consumer<Integer> apply) {
            super(0, 0, 150, 20, Component.empty(), max == min ? 0 : (Math.clamp(current, min, max) - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.format = format;
            this.apply = apply;
            updateMessage();
        }

        public int intValue() { return min + (int) Math.round(value * (max - min)); }

        @Override protected void updateMessage() { setMessage(Component.literal(format.apply(intValue()))); }

        @Override protected void applyValue() { apply.accept(intValue()); }

        @Override public void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
            Font font = font();
            boolean hov = isHoveredOrFocused() && active;
            int x0 = getX(), w = getWidth(), mid = getY() + getHeight() / 2;
            int valueW = Math.min(VALUE_W, w / 3);
            int t0 = x0 + 4, t1 = x0 + w - valueW - 6;
            int span = Math.max(1, t1 - t0);
            // Snap to whole steps so the knob sits on the value it shows.
            double shown = max == min ? 0 : (intValue() - min) / (double) (max - min);
            int kx = t0 + (int) Math.round(shown * span);
            ctx.fill(t0, mid - 1, t1, mid + 1, TRACK);
            ctx.fill(t0, mid - 1, kx, mid + 1, TRACK_FILL);
            ctx.fill(kx - 2, mid - 5, kx + 3, mid + 5, hov ? 0xFFFFFFFF : 0xFFD8D8D8);
            ctx.fill(kx - 2, mid + 4, kx + 3, mid + 5, 0xFF909090);
            String text = font.plainSubstrByWidth(getMessage().getString(), valueW);
            ctx.text(font, text, x0 + w - font.width(text), mid - 4, active ? OiStyle.TEXT : OiStyle.TEXT_DIM);
        }
    }

    // ------------------------------------------------------------ picker ----

    /** Value picker: "‹ value ›" in a box. Left click goes forward, right click back. */
    public static final class Picker<T> extends AbstractButton {
        private final List<T> values;
        private final Function<T, Component> label;
        private final Consumer<T> apply;
        private int index;

        public Picker(List<T> values, T current, Function<T, Component> label, Consumer<T> apply) {
            super(0, 0, 150, 20, Component.empty());
            this.values = List.copyOf(values);
            this.label = label;
            this.apply = apply;
            this.index = Math.max(0, this.values.indexOf(current));
            setMessage(label.apply(this.values.get(index)));
        }

        @Override public void onPress(InputWithModifiers input) { step(1); }

        @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
            if (active && visible && click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT
                    && isMouseOver(click.x(), click.y())) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                step(-1);
                return true;
            }
            return super.mouseClicked(click, doubled);
        }

        private void step(int dir) {
            index = Math.floorMod(index + dir, values.size());
            T v = values.get(index);
            setMessage(label.apply(v));
            apply.accept(v);
        }

        @Override protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
            Font font = font();
            boolean hov = isHoveredOrFocused();
            box(ctx, getX(), getY(), getWidth(), getHeight(), hov, active);
            int mid = getY() + getHeight() / 2;
            int arrow = hov ? OiStyle.TEXT : OiStyle.ARROW;
            ctx.text(font, "‹", getX() + 5, mid - 4, arrow);
            ctx.text(font, "›", getX() + getWidth() - 9, mid - 4, arrow);
            String text = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 24);
            ctx.centeredText(font, text, getX() + getWidth() / 2, mid - 4, active ? OiStyle.TEXT : OiStyle.TEXT_DIM);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    // ------------------------------------------------------------ button ----

    /** Flat button; an optional swatch color draws a chip on the left (color settings). */
    public static final class Flat extends AbstractButton {
        private final Runnable action;
        private Supplier<Integer> swatch;
        private boolean chevron;

        public Flat(Component message, Runnable action) {
            super(0, 0, 150, 20, message);
            this.action = action;
        }

        /** Shows a color chip; the supplier returns ARGB, or -1 for none. */
        public Flat swatch(Supplier<Integer> color) { this.swatch = color; return this; }

        /** Marks a button that opens another screen. */
        public Flat opens() { this.chevron = true; return this; }

        @Override public void onPress(InputWithModifiers input) { action.run(); }

        @Override protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
            Font font = font();
            boolean hov = isHoveredOrFocused();
            int x = getX(), y = getY(), w = getWidth(), h = getHeight(), mid = y + h / 2;
            box(ctx, x, y, w, h, hov, active);
            int left = x + 6, right = x + w - 6;
            Integer c = swatch == null ? null : swatch.get();
            if (c != null && c != -1) {
                // Checker under the chip so alpha reads.
                int sx = x + 4, sy = y + 4, ss = h - 8;
                ctx.fill(sx, sy, sx + ss, sy + ss, 0xFFFFFFFF);
                ctx.fill(sx, sy, sx + ss / 2, sy + ss / 2, 0xFFBBBBBB);
                ctx.fill(sx + ss / 2, sy + ss / 2, sx + ss, sy + ss, 0xFFBBBBBB);
                ctx.fill(sx, sy, sx + ss, sy + ss, c);
                ctx.outline(sx, sy, ss, ss, 0xFF000000);
                left = sx + ss + 6;
            }
            if (chevron) {
                OiStyle.chevron(ctx, right - 5, mid, hov ? OiStyle.TEXT : OiStyle.ARROW);
                right -= 10;
            }
            String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(10, right - left));
            int color = active ? OiStyle.TEXT : OiStyle.TEXT_DIM;
            if (c != null && c != -1) ctx.text(font, text, left, mid - 4, color);
            else ctx.centeredText(font, text, (left + right) / 2, mid - 4, color);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
