package dev.openintel.investigate;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.openintel.config.OIConfig;
import dev.openintel.config.OIConfig.InvestigateCameraMode;
import dev.openintel.snitch.SnitchLogParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Camera transition, settings persistence, selection lifecycle, and caption semantics without a running client. */
public final class InvestigatePresentationTest {
    public static void main(String[] args) throws Exception {
        aim();
        transitions();
        cancellationsAndRetargeting();
        sessionFocus();
        settings();
        captions();
        hooks();
        System.out.println("Investigate camera motion, caption emphasis, settings, and focus lifecycle passed");
    }

    private static void aim() {
        angles(0, 0, InvestigateCameraMotion.aim(0, 0, 10, 37));
        angles(-90, 0, InvestigateCameraMotion.aim(10, 0, 0, 37));
        angles(90, 0, InvestigateCameraMotion.aim(-10, 0, 0, 37));
        angles(-180, 0, InvestigateCameraMotion.aim(0, 0, -10, 37));
        angles(37, -90, InvestigateCameraMotion.aim(0, 10, 0, 37));
        angles(37, 90, InvestigateCameraMotion.aim(0, -10, 0, 37));
        check(InvestigateCameraMotion.aim(0, 0, 0, 37) == null, "No undefined turn when the eye is at the focus");
        check(InvestigateCameraMotion.aim(Double.NaN, 0, 1, 37) == null, "Invalid coordinates cannot rotate the player");
        var near = InvestigateCameraMotion.aim(1, 1, 1, 0);
        var far = InvestigateCameraMotion.aim(30_000_000, 30_000_000, 30_000_000, 0);
        angles(near.yaw(), near.pitch(), far);
    }

    private static void transitions() {
        var motion = new InvestigateCameraMotion();
        angles(0, 0, update(motion, 1, InvestigateCameraMode.PAN, 0, 0, 0, 1, 0, 0));
        var middle = update(motion, 1, InvestigateCameraMode.PAN, 175, 0, 0, 1, 0, 0);
        angles(-45, 0, middle);
        angles(-90, 0, update(motion, 1, InvestigateCameraMode.PAN, 350, middle.yaw(), 0, 1, 0, 0));
        check(update(motion, 1, InvestigateCameraMode.PAN, 351, -90, 0, 1, 0, 0) == null, "Pan releases control when finished");
        angles(-90, 0, update(motion, 2, InvestigateCameraMode.SNAP, 352, 0, 0, 1, 0, 0));
        check(update(motion, 2, InvestigateCameraMode.SNAP, 353, 0, 0, 1, 0, 0) == null, "Snap occurs once per focus request");
        check(update(motion, 3, InvestigateCameraMode.OFF, 400, 0, 0, 1, 0, 0) == null, "Off never changes look direction");
        angles(-90, 0, update(motion, 3, InvestigateCameraMode.SNAP, 401, 0, 0, 1, 0, 0));
        // The same elapsed time produces the same pan at different rendering rates.
        angles(sampleRate(30).yaw(), sampleRate(30).pitch(), sampleRate(144));
        motion = new InvestigateCameraMotion();
        double dx = -Math.sin(Math.toRadians(-179)), dz = Math.cos(Math.toRadians(-179));
        update(motion, 5, InvestigateCameraMode.PAN, 0, 179, 0, dx, 0, dz);
        middle = update(motion, 5, InvestigateCameraMode.PAN, 175, 179, 0, dx, 0, dz);
        near(180, middle.yaw());
        near(181, update(motion, 5, InvestigateCameraMode.PAN, 350, middle.yaw(), 0, dx, 0, dz).yaw());
        motion = new InvestigateCameraMotion();
        motion.update(1, InvestigateCameraMode.PAN, 1000, 0, 0, 0, 1, 0, 0);
        near(-45, motion.update(1, InvestigateCameraMode.PAN, 1000, 500_000_000L, 0, 0, 1, 0, 0).yaw());
    }

    private static InvestigateCameraMotion.Angles sampleRate(int fps) {
        var motion = new InvestigateCameraMotion();
        var current = new InvestigateCameraMotion.Angles(0, 0);
        for (int frame = 0; frame < fps; frame++) {
            long time = Math.round(frame * 1000.0 / fps);
            if (time >= 350) break;
            current = update(motion, 1, InvestigateCameraMode.PAN, time, current.yaw(), current.pitch(), 1, 1, 0);
        }
        return update(motion, 1, InvestigateCameraMode.PAN, 350, current.yaw(), current.pitch(), 1, 1, 0);
    }

    private static void cancellationsAndRetargeting() {
        var motion = new InvestigateCameraMotion();
        update(motion, 1, InvestigateCameraMode.PAN, 0, 0, 0, 1, 0, 0);
        var last = update(motion, 1, InvestigateCameraMode.PAN, 100, 0, 0, 1, 0, 0);
        check(update(motion, 1, InvestigateCameraMode.PAN, 101, last.yaw() + 1, 0, 1, 0, 0) == null, "Mouse look wins over the pan");
        check(update(motion, 1, InvestigateCameraMode.PAN, 102, 0, 0, 1, 0, 0) == null, "Cancelled focus is not retried every frame");
        angles(20, 10, update(motion, 2, InvestigateCameraMode.PAN, 103, 20, 10, -1, 0, 0));
        last = update(motion, 2, InvestigateCameraMode.PAN, 203, 20, 10, -1, 0, 0);
        angles(last.yaw(), last.pitch(), update(motion, 3, InvestigateCameraMode.PAN, 204, last.yaw(), last.pitch(), 0, 1, 0));
        motion.cancel();
        check(update(motion, 3, InvestigateCameraMode.PAN, 205, last.yaw(), last.pitch(), 0, 1, 0) == null, "Screens and other camera owners can cancel");
        motion.reset();
        check(update(motion, 3, InvestigateCameraMode.PAN, 206, 0, 0, 0, 1, 0) != null, "A new world/player can reinitialize the same selection");
        // Moving past the yaw seam must not reverse an active pan.
        motion = new InvestigateCameraMotion();
        update(motion, 4, InvestigateCameraMode.PAN, 0, 0, 0, -0.01, 0, -1);
        last = update(motion, 4, InvestigateCameraMode.PAN, 175, 0, 0, -0.01, 0, -1);
        var next = update(motion, 4, InvestigateCameraMode.PAN, 176, last.yaw(), 0, 0.01, 0, -1);
        check(Math.abs(next.yaw() - last.yaw()) < 2, "Pan crosses the moving target's 180-degree seam continuously");
    }

    private static void sessionFocus() throws Exception {
        var session = InvestigateSession.get();
        session.load(SnitchLogParser.parsePaste("Vault: Steve broke block at (1, 2, 3)\nVault: Alex placed block at (4, 5, 6)"), "minecraft:overworld");
        check(!session.focus().watching(), "Parsing alone cannot start camera control");
        long revision = session.focus().revision();
        session.watch(); check(session.focus().watching() && session.focus().revision() > revision, "Watch requests focus");
        revision = session.focus().revision();
        session.setPlaying(true); check(session.focus().revision() == revision, "Play/pause alone does not refocus");
        var clock = InvestigateSession.class.getDeclaredField("nextAdvanceAt"); clock.setAccessible(true); clock.setLong(session, 0);
        session.tick(); check(session.focus().revision() > revision && session.current().x() == 4, "Autoplay advances the focus request");
        revision = session.focus().revision();
        session.next(); check(session.focus().revision() == revision, "End-of-list key repeats do not restart a pan");
        session.previous(); check(session.focus().revision() > revision && session.current().x() == 1, "Previous focuses its event");
        revision = session.focus().revision();
        session.select(0); check(session.focus().revision() > revision, "Explicit reselection can refocus the same event");
        session.stopWatching(); check(!session.focus().watching(), "Stopping releases camera ownership");
        session.clear(); check(session.focus().event() == null, "Clear releases the focused event");
    }

    private static void settings() {
        var gson = new Gson();
        var defaults = gson.fromJson("{}", OIConfig.class);
        check(defaults.investigateCameraMode == InvestigateCameraMode.PAN && defaults.investigatePanDurationMs == 350, "Fresh and older configs default to a 350ms pan");
        for (var mode : InvestigateCameraMode.values()) {
            var config = new OIConfig(); config.investigateCameraMode = mode; config.investigatePanDurationMs = 750;
            var restored = gson.fromJson(gson.toJson(config), OIConfig.class);
            OIConfig.applyInvestigateDefaults(restored);
            check(restored.investigateCameraMode == mode && restored.investigatePanDurationMs == 750, "Camera preferences survive persistence");
        }
        var invalid = gson.fromJson("{\"investigateCameraMode\":\"invalid\",\"investigatePanDurationMs\":-50}", OIConfig.class);
        OIConfig.applyInvestigateDefaults(invalid);
        check(invalid.investigateCameraMode == InvestigateCameraMode.PAN && invalid.investigatePanDurationMs == 100, "Malformed camera config is repaired");
        invalid.investigatePanDurationMs = Integer.MAX_VALUE; OIConfig.applyInvestigateDefaults(invalid);
        check(invalid.investigatePanDurationMs == 2000, "Duration is bounded");
    }

    private record Span(String text, Style style) { }
    private static List<Span> spans(Component component) {
        List<Span> spans = new ArrayList<>();
        component.visit((style, text) -> { spans.add(new Span(text, style)); return Optional.empty(); }, Style.EMPTY);
        return spans;
    }
    private static void captions() {
        var event = new InvestigateSession.Event(SnitchLogParser.parseLine("Vault: Steve broke block at (-10, 64, 30)"), "minecraft:overworld");
        var spans = spans(InvestigateCaption.detail(event));
        var actor = spans.stream().filter(s -> s.text().contains("Steve")).findFirst().orElseThrow();
        var action = spans.stream().filter(s -> s.text().equals("broke block")).findFirst().orElseThrow();
        var coordinate = spans.stream().filter(s -> s.text().equals("-10, 64, 30")).findFirst().orElseThrow();
        check(actor.style().isBold() && actor.style().getColor().getValue() == InvestigateCaption.PLAYER, "Player receives distinct emphasis");
        check(action.style().isBold() && action.style().getColor().getValue() == InvestigateCaption.actionColor(SnitchLogParser.Kind.BREAK), "Break action is visibly emphasized");
        check(coordinate.style().isBold() && coordinate.style().getColor().getValue() == InvestigateCaption.COORDINATES, "Focused coordinates receive distinct emphasis");
        String detail = InvestigateCaption.detail(event).getString();
        check(detail.contains("Vault") && detail.contains("overworld") && detail.contains("-10, 64, 30"), "Caption retains event context");
        check(InvestigateCaption.actionColor(SnitchLogParser.Kind.BREAK) != InvestigateCaption.actionColor(SnitchLogParser.Kind.PLACE), "Break and place remain distinct");
        check(InvestigateCaption.actionColor(SnitchLogParser.Kind.SANCTUARY) != InvestigateCaption.actionColor(SnitchLogParser.Kind.ENTER), "Sanctuary events stand apart from routine entry");
        check(InvestigateCaption.dimensionNotice(event, "minecraft:overworld") == null, "No false dimension warning");
        check(InvestigateCaption.dimensionNotice(event, "minecraft:the_nether").getString().contains("overworld"), "Cross-dimension focus is explained");
        check(InvestigateCaption.header(0, 5, false).getString().contains("1 / 5"), "Human-readable progress");
        check(InvestigateCaption.header(1, 5, true).getString().contains("PLAYING"), "Playback state remains visible");
    }

    private static void hooks() throws Exception {
        check(net.minecraft.client.Camera.class.getDeclaredMethod("update", net.minecraft.client.DeltaTracker.class) != null, "Mixin target signature exists");
        try (var input = InvestigatePresentationTest.class.getResourceAsStream("/openintel.mixins.json")) {
            var mixins = JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject().getAsJsonArray("client");
            check(mixins.asList().stream().anyMatch(v -> v.getAsString().equals("InvestigateCameraMixin")), "Camera hook is registered");
        }
    }

    private static InvestigateCameraMotion.Angles update(InvestigateCameraMotion motion, long revision, InvestigateCameraMode mode,
                                                          long millis, float yaw, float pitch, double x, double y, double z) {
        return motion.update(revision, mode, 350, millis * 1_000_000L, yaw, pitch, x, y, z);
    }
    private static void angles(float yaw, float pitch, InvestigateCameraMotion.Angles actual) {
        check(actual != null, "Rotation exists"); near(yaw, actual.yaw()); near(pitch, actual.pitch());
    }
    private static void near(double expected, double actual) { check(Math.abs(expected - actual) < 0.001, expected + " != " + actual); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
