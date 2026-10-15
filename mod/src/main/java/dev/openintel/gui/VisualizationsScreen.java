package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.viz.RangeShape;
import dev.openintel.viz.RangeStore;
import dev.openintel.viz.RangeType;
import dev.openintel.viz.SnitchKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Editor for manually placed snitch / bastion / sanctuary coverage shapes. */
public final class VisualizationsScreen extends Screen {
    private final Screen parent;
    private EditBox radiusField;
    /** Kind applied by the next Add snitch. */
    private SnitchKind nextKind = SnitchKind.NOTIFIER;
    private final List<RangeShape> view = new ArrayList<>();
    private int selected = -1;
    private int listX, listY, listW, listH;
    private int scroll;
    private static final int ROW_H = 18;

    public VisualizationsScreen(Screen parent) {
        super(Component.literal("Visualizations"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refresh();
        int btnY = height - 52;
        addRenderableWidget(Button.builder(Component.literal("Add snitch"),
                b -> addAtLook(RangeType.SNITCH)).bounds(8, btnY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Add bastion"),
                b -> addAtLook(RangeType.BASTION)).bounds(102, btnY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Add sanctuary"),
                b -> addAtLook(RangeType.SANCTUARY)).bounds(196, btnY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Toggle"),
                b -> toggleSelected()).bounds(300, btnY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"),
                b -> deleteSelected()).bounds(364, btnY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> onClose()).bounds(width - 88, btnY, 80, 20).build());

        radiusField = new EditBox(font, 8, height - 28, 80, 18, Component.literal("radius"));
        radiusField.setHint(Component.literal("sanctuary r"));
        radiusField.setValue("50");
        radiusField.setMaxLength(4);
        addRenderableWidget(radiusField);
        addRenderableWidget(Button.builder(Component.literal("Set radius"),
                b -> applyRadius()).bounds(92, height - 28, 80, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Radius −"),
                b -> nudgeRadius(-5)).bounds(176, height - 28, 60, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Radius +"),
                b -> nudgeRadius(5)).bounds(240, height - 28, 60, 18).build());
        int addW = 88;
        int cycleW = 72;
        int cycleX = width - 8 - cycleW;
        int addKindX = cycleX - 4 - addW;
        if (addKindX < 304) {
            addKindX = 304;
            cycleX = addKindX + addW + 4;
        }
        addRenderableWidget(Button.builder(addKindLabel(), b -> {
            nextKind = nextKind == null ? SnitchKind.NOTIFIER : nextKind.next();
            b.setMessage(addKindLabel());
        }).bounds(addKindX, height - 28, addW, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Cycle kind"),
                b -> cycleSelectedKind()).bounds(cycleX, height - 28, cycleW, 18).build());

        listX = 8;
        listY = 28;
        listW = width - 16;
        listH = height - 90;
    }

    private void refresh() {
        view.clear();
        view.addAll(RangeStore.get().shapes());
        if (selected >= view.size()) selected = view.size() - 1;
    }

    private BlockPos targetPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return BlockPos.ZERO;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return mc.player.blockPosition();
    }

    private String currentDim() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "minecraft:overworld";
        return mc.level.dimension().identifier().toString();
    }

    private void addAtLook(RangeType type) {
        BlockPos pos = targetPos();
        RangeShape shape = new RangeShape(type, pos.getX(), pos.getY(), pos.getZ(), currentDim());
        if (type == RangeType.SANCTUARY) {
            shape.sanctuaryRadius = parseRadius(radiusField.getValue(), 50);
        }
        if (type == RangeType.SNITCH) {
            shape.snitchKind = nextKind == null ? SnitchKind.NOTIFIER : nextKind;
        }
        if (!RangeStore.get().add(shape)) {
            OpenIntelClient.status("shape limit reached (" + RangeStore.MAX_SHAPES + ")");
        }
        refresh();
        selected = view.size() - 1;
    }

    private void toggleSelected() {
        if (selected < 0 || selected >= view.size()) return;
        RangeShape s = view.get(selected);
        RangeStore.get().mutate(s.id, sh -> sh.enabled = !sh.enabled);
        refresh();
    }

    private void cycleSelectedKind() {
        if (selected < 0 || selected >= view.size()) {
            OpenIntelClient.status("select a snitch to change its kind");
            return;
        }
        RangeShape s = view.get(selected);
        if (s.type != RangeType.SNITCH) {
            OpenIntelClient.status("kind applies to snitches");
            return;
        }
        RangeStore.get().mutate(s.id, sh -> {
            SnitchKind kind = sh.snitchKind == null ? SnitchKind.NOTIFIER : sh.snitchKind;
            sh.snitchKind = kind.next();
        });
        refresh();
    }

    private Component addKindLabel() {
        SnitchKind kind = nextKind == null ? SnitchKind.NOTIFIER : nextKind;
        return Component.literal(kind == SnitchKind.LOGGING ? "Add: logging" : "Add: notifier");
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= view.size()) return;
        RangeStore.get().remove(view.get(selected).id);
        refresh();
    }

    private void applyRadius() {
        if (selected < 0 || selected >= view.size()) return;
        RangeShape s = view.get(selected);
        if (s.type != RangeType.SANCTUARY) return;
        double r = parseRadius(radiusField.getValue(), s.sanctuaryRadius);
        RangeStore.get().mutate(s.id, sh -> sh.sanctuaryRadius = r);
        refresh();
    }

    private void nudgeRadius(double delta) {
        if (selected < 0 || selected >= view.size()) return;
        RangeShape s = view.get(selected);
        if (s.type != RangeType.SANCTUARY) return;
        RangeStore.get().mutate(s.id, sh ->
                sh.sanctuaryRadius = Math.max(1, Math.min(512, sh.sanctuaryRadius + delta)));
        refresh();
        if (selected >= 0 && selected < view.size()) {
            radiusField.setValue(Integer.toString((int) Math.round(view.get(selected).sanctuaryRadius)));
        }
    }

    private static double parseRadius(String raw, double fallback) {
        try {
            double v = Double.parseDouble(raw.trim());
            if (!Double.isFinite(v) || v <= 0) return fallback;
            return Math.min(512, v);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
                int row = (int) ((my - listY + scroll) / ROW_H);
                if (row >= 0 && row < view.size()) {
                    selected = row;
                    RangeShape s = view.get(selected);
                    if (s.type == RangeType.SANCTUARY) {
                        radiusField.setValue(Integer.toString((int) Math.round(s.sanctuaryRadius)));
                    }
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
            int max = Math.max(0, view.size() * ROW_H - listH);
            scroll = (int) Math.max(0, Math.min(max, scroll - vertical * ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101015);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.text(font, "Coverage shapes — look at a block, then add", 8, 10, 0xFFE8EAF0);
        String hint = lookHint();
        if (!hint.isEmpty()) {
            ctx.text(font, hint, width - 8 - font.width(hint), 10, 0xFF7C8CF8);
        }
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int y = listY - scroll;
        for (int i = 0; i < view.size(); i++) {
            RangeShape s = view.get(i);
            if (y + ROW_H >= listY && y <= listY + listH) {
                int bg = i == selected ? 0x2E7C8CF8 : (i % 2 == 0 ? 0x18000000 : 0x10000000);
                ctx.fill(listX, y, listX + listW, y + ROW_H, bg);
                String mark = s.enabled ? "+" : "-";
                String line = mark + " " + s.type.name().toLowerCase(Locale.ROOT)
                        + "  " + s.x + ", " + s.y + ", " + s.z
                        + "  [" + shortDim(s.dimension) + "]";
                if (s.type == RangeType.SANCTUARY) {
                    line += "  r=" + (int) Math.round(s.sanctuaryRadius);
                } else if (s.type == RangeType.SNITCH) {
                    SnitchKind kind = s.snitchKind == null ? SnitchKind.NOTIFIER : s.snitchKind;
                    line += kind == SnitchKind.LOGGING ? "  logging" : "  notifier";
                }
                int color = s.enabled ? 0xFFE8EAF0 : 0xFF8E93A6;
                ctx.text(font, line, listX + 4, y + 5, color);
            }
            y += ROW_H;
        }
        ctx.disableScissor();
        ctx.text(font,
                view.size() + " / " + RangeStore.MAX_SHAPES
                        + "  — bastion column shows 64 blocks up; protection continues above",
                8, height - 64, 0xFF8E93A6);
    }

    /** Hint only. Looking at sponge/beacon/sculk does not place a shape. */
    private String lookHint() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "";
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return "";
        BlockState state = mc.level.getBlockState(bhr.getBlockPos());
        if (state.is(Blocks.SPONGE) || state.is(Blocks.WET_SPONGE)) return "looking at sponge → bastion";
        if (state.is(Blocks.BEACON)) return "looking at beacon → sanctuary";
        if (state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.CALIBRATED_SCULK_SENSOR)) {
            return "looking at sculk sensor → snitch";
        }
        return "";
    }

    private static String shortDim(String dim) {
        if (dim == null) return "?";
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }

    @Override
    public void onClose() {
        RangeStore.get().save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
