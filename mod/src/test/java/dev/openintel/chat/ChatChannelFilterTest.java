package dev.openintel.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.config.OIConfig;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Display-only chat channels. Snitch lines follow the caller's pattern, hidden-ore
 * reveals are Mining, and everything else stays Chat. Packing drops hidden lines
 * without leaving placeholders.
 */
public final class ChatChannelFilterTest {
    private static final Pattern SNITCH = Pattern.compile(
            "(?i)entered snitch|snitch at|you have entered|opened container|logged in at|logged out at|damaged sanctuary|broke block|placed block");

    public static void main(String[] args) throws Exception {
        classifiesDefaultSnitchPhrases();
        classifiesHiddenOreReveals();
        leavesOrdinaryLinesInChat();
        packsClosedChatToOpenEyes();
        packsOpenChatToTheSelectedChannels();
        nullSnitchPatternStaysInChat();
        defaultsAndSelectionFallback();
        pinsTheRowToTheTopOfTheChatBox();
        verifyMixinTargets();
        System.out.println("ChatChannelFilterTest passed");
    }

    private static void classifiesDefaultSnitchPhrases() {
        String[] lines = {
                "* Alex entered snitch at vault [world 1 2 3]",
                "Alex logged in at spawn",
                "Alex logged out at spawn",
                "Alex opened container at vault",
                "Alex damaged sanctuary at spawn",
                "Alex broke block at vault",
                "Alex placed block at vault"
        };
        for (String line : lines) {
            check(ChatChannelFilter.classify(line, SNITCH) == ChatChannel.SNITCHES, "Snitch phrase stays in Snitches: " + line);
        }
    }

    private static void classifiesHiddenOreReveals() {
        check(ChatChannelFilter.classify("You revealed a hidden [Coal Ore] !", SNITCH) == ChatChannel.MINING,
                "A singular hidden-ore reveal is Mining");
        check(ChatChannelFilter.classify("You revealed 2 hidden [Diamond Ore] !!!", SNITCH) == ChatChannel.MINING,
                "A counted hidden-ore reveal is Mining");
        check(ChatChannelFilter.classify("  You revealed a hidden [Coal Ore] !  ", SNITCH) == ChatChannel.MINING,
                "Classification trims the line first");
        check(ChatChannelFilter.classify("[14:02:11] You revealed a hidden [Coal Ore] !", SNITCH) == ChatChannel.MINING,
                "A timestamp prefix still counts as Mining");
        check(ChatChannelFilter.classify("[14:02:11] You revealed 2 hidden [Diamond Ore] !!! (x3)", SNITCH) == ChatChannel.MINING,
                "A timestamp and a duplicate counter still count as Mining");
        check(ChatChannelFilter.classify("\u00A76You revealed a hidden \u00A7b[Coal Ore]\u00A76 !", SNITCH) == ChatChannel.MINING,
                "Legacy color codes still count as Mining");
        check(ChatChannelFilter.classify("You revealed a hidden [Coal Ore]!", SNITCH) == ChatChannel.MINING,
                "A reveal with no space before the mark is Mining");
    }

    private static void leavesOrdinaryLinesInChat() {
        check(ChatChannelFilter.classify("<Steve> hello", SNITCH) == ChatChannel.CHAT, "A player line stays in Chat");
        check(ChatChannelFilter.classify("Steve fell from a high place", SNITCH) == ChatChannel.CHAT, "A death line stays in Chat");
        check(ChatChannelFilter.classify("[OpenIntel] relay connected", SNITCH) == ChatChannel.CHAT,
                "An OpenIntel status line stays in Chat");
        check(ChatChannelFilter.classify("<Steve> hello", SNITCH, false) == ChatChannel.CHAT,
                "A player line without the client mark stays in Chat");
        check(ChatChannelFilter.classify("Steve fell from a high place", SNITCH, false) == ChatChannel.CHAT,
                "A server system line without the client mark stays in Chat");
        check(ChatChannelFilter.classify("You revealed a hidden [Coal Ore] !", SNITCH, false) == ChatChannel.MINING,
                "An ore reveal without the client mark stays in Mining");
        check(ChatChannelFilter.classify("* Alex entered snitch at vault", SNITCH, false) == ChatChannel.SNITCHES,
                "A snitch line without the client mark stays in Snitches");
        check(ChatChannelFilter.clientOrigin(true, null),
                "A live client-system line is Client");
        check(!ChatChannelFilter.clientOrigin(true, "Restored"),
                "ChatPatches restored history is classified from its text");
        check(!ChatChannelFilter.clientOrigin(false, null),
                "A server or player line is never Client");
        check(ChatChannelFilter.classify("<Steve> hello", SNITCH, ChatChannelFilter.clientOrigin(true, "Restored")) == ChatChannel.CHAT,
                "A restored player line stays in Chat");
        check(ChatChannelFilter.classify("* Alex entered snitch at vault", SNITCH, ChatChannelFilter.clientOrigin(true, "Restored")) == ChatChannel.SNITCHES,
                "A restored snitch line stays in Snitches");
        check(ChatChannelFilter.classify("You revealed a hidden [Coal Ore] !", SNITCH, ChatChannelFilter.clientOrigin(true, "Restored")) == ChatChannel.MINING,
                "A restored ore reveal stays in Mining");
        check(ChatChannelFilter.classify("Waypoint saved", SNITCH, true) == ChatChannel.CLIENT,
                "A client-marked line is Client even when the words are ordinary");
        check(ChatChannelFilter.classify("You revealed a hidden [Coal Ore] !", SNITCH, true) == ChatChannel.CLIENT,
                "A client-marked line stays Client even when it looks like an ore reveal");
        check(ChatChannelFilter.classify("* Alex entered snitch at vault", SNITCH, true) == ChatChannel.CLIENT,
                "A client-marked line stays Client even when it looks like a snitch");
        check(!ChatChannelFilter.shows(ChatChannel.CLIENT, true, true, false, false, true, false, false, false, false),
                "Client lines stay out of the open Chat view until Client is selected");
        check(ChatChannelFilter.shows(ChatChannel.CLIENT, true, false, false, false, false, false, false, true, false),
                "Selecting Client shows a client-marked line");
    }

    private static void packsClosedChatToOpenEyes() {
        List<String> lines = sample();
        List<String> packed = ChatChannelFilter.pack(lines, SNITCH, false, false, true, false, true, false, false);
        check(packed.equals(List.of("<Steve> hello", "[OpenIntel] relay connected")),
                "Closed chat with only the Chat eye open hides snitches and ore reveals");
    }

    private static void packsOpenChatToTheSelectedChannels() {
        List<String> lines = sample();
        List<String> snitches = ChatChannelFilter.pack(lines, SNITCH, true, false, true, false, true, false, false);
        check(snitches.equals(List.of(
                "* Alex entered snitch at vault [world 1 2 3]",
                "Alex logged in at spawn")),
                "Open Snitches shows those lines even when the Snitches eye is closed, and hides ore");
        List<String> both = ChatChannelFilter.pack(lines, SNITCH, true, true, true, false, false, false, false);
        check(both.equals(List.of(
                "<Steve> hello",
                "* Alex entered snitch at vault [world 1 2 3]",
                "[OpenIntel] relay connected",
                "Alex logged in at spawn")),
                "Open Chat and Snitches together keep both and still hide ore");
        List<String> later = ChatChannelFilter.pack(lines, SNITCH, true, true, false, false, false, true, false);
        check(later.contains("<Steve> hello"),
                "A player line sent while Chat was off is still there when Chat is turned back on");
    }

    private static void nullSnitchPatternStaysInChat() {
        String snitch = "* Alex entered snitch at vault [world 1 2 3]";
        check(ChatChannelFilter.classify(snitch, null) == ChatChannel.CHAT,
                "A null snitch pattern classifies snitch text as Chat");
        List<String> packed = ChatChannelFilter.pack(sample(), null, false, true, false, false, true, false, false);
        check(packed.contains(snitch), "A null snitch pattern leaves snitch text in the Chat view");
        check(!packed.contains("You revealed a hidden [Coal Ore] !"),
                "Mining still leaves Chat when the snitch pattern is null");
    }

    private static void defaultsAndSelectionFallback() {
        OIConfig config = new OIConfig();
        check("chat".equals(config.chatChannel), "Chat is the saved channel");
        check(config.chatViewChat && !config.chatViewSnitches && !config.chatViewMining && !config.chatViewClient,
                "The open box starts on Chat only");
        check(config.chatShowChat, "The Chat eye starts open");
        check(!config.chatShowSnitches, "The Snitches eye starts closed");
        check(!config.chatShowMining, "The Mining eye starts closed");
        check(!config.chatShowClient, "The Client bell starts muted");
        check(config.chatChannel() == ChatChannel.CHAT, "The helper reads Chat");
        check(config.views(ChatChannel.CHAT) && !config.views(ChatChannel.SNITCHES)
                        && !config.views(ChatChannel.MINING) && !config.views(ChatChannel.CLIENT),
                "Only Chat is in the open view");
        check(config.showsNew(ChatChannel.CHAT) && !config.showsNew(ChatChannel.SNITCHES)
                        && !config.showsNew(ChatChannel.MINING) && !config.showsNew(ChatChannel.CLIENT),
                "Only new Chat lines show while chat is closed");

        config.chatChannel = "mining";
        config.chatShowMining = true;
        check(config.chatChannel() == ChatChannel.MINING, "Mining can be selected");
        check(config.showsNew(ChatChannel.MINING), "The Mining eye can be opened");

        OIConfig migrated = new OIConfig();
        migrated.chatViewChat = false;
        migrated.chatChannel = "snitches";
        JsonObject old = new JsonObject();
        old.addProperty("chatChannel", "snitches");
        migrated.repairChatView(old);
        check(migrated.chatViewSnitches && !migrated.chatViewChat && !migrated.chatViewMining && !migrated.chatViewClient,
                "An older single Snitches selection becomes the Snitches view");
        migrated.chatViewChat = true;
        JsonObject saved = new JsonObject();
        saved.addProperty("chatViewChat", true);
        saved.addProperty("chatViewSnitches", true);
        saved.addProperty("chatChannel", "mining");
        migrated.repairChatView(saved);
        check(migrated.chatViewChat && migrated.chatViewSnitches,
                "A config that already stores view toggles keeps Chat and Snitches together");

        check(ChatChannel.parse(null) == ChatChannel.CHAT, "A missing channel name is Chat");
        check(ChatChannel.parse("") == ChatChannel.CHAT, "A blank channel name is Chat");
        check(ChatChannel.parse("   ") == ChatChannel.CHAT, "A whitespace channel name is Chat");
        check(ChatChannel.parse("system") == ChatChannel.CHAT, "An unknown channel name is Chat");
        check(ChatChannel.parse("snitches") == ChatChannel.SNITCHES, "snitches parses");
        check(ChatChannel.parse("mining") == ChatChannel.MINING, "mining parses");
        check(ChatChannel.parse("client") == ChatChannel.CLIENT, "client parses");
        config.chatChannel = "nope";
        check(config.chatChannel() == ChatChannel.CHAT, "The helper treats an unknown saved channel as Chat");
    }

    private static List<String> sample() {
        return List.of(
                "<Steve> hello",
                "* Alex entered snitch at vault [world 1 2 3]",
                "You revealed a hidden [Coal Ore] !",
                "[OpenIntel] relay connected",
                "Alex logged in at spawn");
    }

    private static void pinsTheRowToTheTopOfTheChatBox() {
        // Focused height 1.0 is 180 chat pixels, 20 lines of 9. Screen 360, scale 1.
        int full = ChatChannelBar.rowY(360, 1, 0, 20, 0);
        int shortBox = ChatChannelBar.rowY(360, 1, 0, 2, 0);
        int shifted = ChatChannelBar.rowY(360, 1, 0, 20, 8);
        check(full == 123, "A full 180px chat box puts the chips just above y = 140");
        check(shortBox == 285, "A shorter chat box keeps the chips on its own top");
        check(shortBox > full, "A shorter box sits lower on the screen than a taller one");
        check(shifted == 115, "ChatPatches' upward shift moves the chips with the box");
        check(ChatChannelBar.rowY(80, 1, 0, 40, 0) == 2, "A box taller than the screen keeps the chips on screen");

        var chips = ChatChannelBar.layout(400, full, text -> text.length() * 6);
        check(chips.size() == 4, "Chat, Snitches, Mining, and Client each get a chip");
        check(chipY(chips) == full, "The chips use the row pinned to the chat box");
        check(chips.get(0).plate().x() == 2, "The first chip starts at x = 2");
        check(ChatChannelBar.bellPixel(true, 4, 0) && ChatChannelBar.bellPixel(true, 0, 6),
                "A ringing bell has a nub and a rim");
        check(ChatChannelBar.bellPixel(false, 8, 0) && !ChatChannelBar.bellPixel(true, 8, 0),
                "A muted bell keeps the slash a ringing bell does not");
        check(ChatChannelBar.bellPixel(false, 4, 0), "A muted bell still has the nub");
        for (int i = 0; i < chips.size(); i++) {
            var chip = chips.get(i);
            check(chip.name().x() > chip.plate().x()
                            && chip.bell().x() >= chip.name().x() + chip.name().width()
                            && chip.bell().width() == ChatChannelBar.BELL_W
                            && chip.bell().height() == ChatChannelBar.BELL_H,
                    "Each label is padded and its bell sits to the right");
            if (i + 1 < chips.size()) {
                var next = chips.get(i + 1);
                check(chip.plate().x() + chip.plate().width() < next.plate().x(),
                        "Chips are separate, with a gap between them");
            }
        }
        var chat = chips.get(0);
        var nameHit = ChatChannelBar.hit(chips, chat.name().x(), chat.name().y());
        check(nameHit != null && nameHit.hit() == ChatChannelBar.Hit.CHANNEL
                        && nameHit.chatChannel() == ChatChannel.CHAT,
                "A name click toggles that channel");
        var snitchBell = chips.get(1).bell();
        var bellHit = ChatChannelBar.hit(chips, snitchBell.x(), snitchBell.y());
        check(bellHit != null && bellHit.hit() == ChatChannelBar.Hit.BELL
                        && bellHit.chatChannel() == ChatChannel.SNITCHES,
                "A bell click toggles that channel");
        check(ChatChannelBar.hit(chips, 0, 0) == null, "Clicks outside the chips are left alone");
    }

    private static int chipY(List<ChatChannelBar.Chip> chips) {
        int y = chips.get(0).plate().y();
        for (var chip : chips) {
            check(chip.plate().y() == y, "Every chip sits on the same row");
            check(chip.bell().y() >= chip.plate().y()
                            && chip.bell().y() + chip.bell().height() <= chip.plate().y() + chip.plate().height(),
                    "The bell stays inside its chip");
        }
        return y;
    }

    private static void verifyMixinTargets() throws Exception {
        Class<?> chat = Class.forName("net.minecraft.client.gui.components.ChatComponent");
        Class<?> alpha = Class.forName("net.minecraft.client.gui.components.ChatComponent$AlphaCalculator");
        Class<?> consumer = Class.forName("net.minecraft.client.gui.components.ChatComponent$LineConsumer");
        var forEachLine = chat.getDeclaredMethod("forEachLine", alpha, consumer);
        check(forEachLine.getReturnType() == int.class, "forEachLine returns how many lines it accepted");
        chat.getDeclaredMethod("scrollChat", int.class);
        Class<?> screen = Class.forName("net.minecraft.client.gui.screens.ChatScreen");
        screen.getDeclaredMethod("extractRenderState",
                Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor"), int.class, int.class, float.class);
        screen.getDeclaredMethod("mouseClicked",
                Class.forName("net.minecraft.client.input.MouseButtonEvent"), boolean.class);
        Class.forName("dev.openintel.mixin.ChatChannelMixin");
        Class.forName("dev.openintel.mixin.ChatChannelBarMixin");
        try (var input = ChatChannelFilterTest.class.getResourceAsStream("/openintel.mixins.json")) {
            check(input != null, "Mixin config is on the test classpath");
            var mixins = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client");
            for (String name : new String[]{"ChatChannelMixin", "ChatChannelBarMixin"}) {
                check(mixins.asList().stream().anyMatch(value -> value.getAsString().equals(name)),
                        "Chat channel mixin is registered: " + name);
            }
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
