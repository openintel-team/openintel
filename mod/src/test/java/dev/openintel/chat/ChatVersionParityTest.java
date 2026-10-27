package dev.openintel.chat;

import net.minecraft.client.GuiMessage;
import net.minecraft.util.FormattedCharSequence;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * 1.21.11 has no parent message and no client-system source, so it joins
 * wrapped lines and treats a local insert as the Client mark. The channel
 * those lines land in, and the chip row around them, still has to match 26.3.
 *
 * <p>The 26.3 sources are bundled for CI. When the sibling {@code openintel}
 * checkout is present, those bundled files have to still be that checkout.
 */
public final class ChatVersionParityTest {
    private static final String[] MAIN_SOURCES = {
            "ChatChannel.java", "ChatChannelFilter.java", "ChatChannelBar.java", "ChatReception.java"
    };
    private static final Pattern SNITCH = Pattern.compile(
            "(?i)entered snitch|snitch at|you have entered|opened container|logged in at|logged out at|damaged sanctuary|broke block|placed block");
    private static final String[] LINES = {
            null,
            "",
            "   ",
            "<Steve> hello",
            "Steve fell from a high place",
            "* Alex entered snitch at vault [world 1 2 3]",
            "Alex logged in at spawn",
            "Alex logged out at spawn",
            "Alex opened container at vault",
            "Alex damaged sanctuary at spawn",
            "Alex broke block at vault",
            "Alex placed block at vault",
            "[OpenIntel] ice road on",
            "\u00A76[OpenIntel] \u00A77ice road on",
            "[OpenIntel] * Alex entered snitch at vault",
            "[14:02:11] [OpenIntel] ice road on",
            "[14:02:11 PM] [System] [OpenIntel] ice road on",
            "&6[OpenIntel] &7ice road on",
            "<Steve> [OpenIntel] is a mod",
            "You revealed a hidden [Coal Ore] !",
            "You revealed 2 hidden [Diamond Ore] !!!",
            "  You revealed a hidden [Coal Ore] !  ",
            "[14:02:11] You revealed a hidden [Coal Ore] !",
            "[14:02:11] You revealed 2 hidden [Diamond Ore] !!! (x3)",
            "\u00A76You revealed a hidden \u00A7b[Coal Ore]\u00A76 !",
            "You revealed a hidden [Coal Ore]!",
            "hello \uD83D\uDE00"
    };

    private static Class<?> mainFilter;
    private static Class<?> mainChannel;
    private static Class<?> mainBar;
    private static Class<?> mainReception;
    private static Method mainClassify;
    private static Method mainOrigin;
    private static Method mainShows;
    private static Method mainPack;
    private static Method mainParse;
    private static Method mainLabel;
    private static Method mainRowY;
    private static Method mainBell;
    private static Method mainLayout;
    private static Method mainHit;
    private static Method mainBegin;
    private static Method mainEnd;
    private static Method mainObserve;
    private static Method mainTake;

    public static void main(String[] args) throws Exception {
        try (URLClassLoader loader = loadMain()) {
            classificationMatches();
            visibilityMatches();
            packingMatches();
            clientInsertMatches();
            receptionMatches();
            chipFormattingMatches();
            wrappedLinesMatchTheParentText();
            filterCallUsesThisVersionsFlagOrder();
        }
        System.out.println("ChatVersionParityTest passed");
    }

    private static URLClassLoader loadMain() throws Exception {
        Path mod = modDir();
        String source = bundledOrLive(mod);
        Path out = Files.createTempDirectory("openintel-chat-26.3");
        out.toFile().deleteOnExit();
        Path src = out.resolve("src");
        Files.createDirectories(src);
        for (String name : MAIN_SOURCES) {
            Files.writeString(src.resolve(name), source(name, mod));
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "The parity test needs the JDK compiler");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = List.of("--release", "21", "-parameters", "-proc:none", "-encoding", "UTF-8", "-d", out.toString());
            Boolean ok = compiler.getTask(null, files, null, options, null,
                    files.getJavaFileObjects(
                            src.resolve("ChatChannel.java").toFile(),
                            src.resolve("ChatChannelFilter.java").toFile(),
                            src.resolve("ChatChannelBar.java").toFile(),
                            src.resolve("ChatReception.java").toFile())).call();
            check(Boolean.TRUE.equals(ok), "26.3 chat sources failed to compile");
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        mainFilter = loader.loadClass("dev.openintel.chat.ChatChannelFilter");
        mainChannel = loader.loadClass("dev.openintel.chat.ChatChannel");
        mainBar = loader.loadClass("dev.openintel.chat.ChatChannelBar");
        mainReception = loader.loadClass("dev.openintel.chat.ChatReception");
        check(mainFilter.getClassLoader() != ChatChannelFilter.class.getClassLoader(),
                "26.3 filter must not be the 1.21.11 class");
        mainClassify = mainFilter.getMethod("classify", String.class, Pattern.class, boolean.class);
        mainOrigin = mainFilter.getMethod("clientOrigin", boolean.class, boolean.class, String.class);
        mainShows = longest(mainFilter, "shows");
        check(mainShows.getParameterCount() == 12, "26.3 shows takes the full flag set");
        check(mainShows.getParameters()[8].isNamePresent(), "26.3 filter was compiled with parameter names");
        check("viewClient".equals(mainShows.getParameters()[8].getName())
                        && "viewEvents".equals(mainShows.getParameters()[10].getName()),
                "Loaded the 26.3 filter, which takes Client flags before OI Events");
        mainPack = mainFilter.getMethod("pack", List.class, Pattern.class,
                boolean.class, boolean.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class);
        mainParse = mainChannel.getMethod("parse", String.class);
        mainLabel = mainBar.getMethod("label", mainChannel);
        mainRowY = mainBar.getMethod("rowY", int.class, double.class, double.class, int.class, int.class);
        mainBell = mainBar.getMethod("bellPixel", boolean.class, int.class, int.class);
        mainLayout = mainBar.getMethod("layout", int.class, int.class, ToIntFunction.class);
        mainHit = mainBar.getMethod("hit", List.class, double.class, double.class);
        mainBegin = mainReception.getMethod("beginServer");
        mainEnd = mainReception.getMethod("endServer");
        mainObserve = mainReception.getMethod("observe", Object.class, String.class);
        mainTake = mainReception.getMethod("takeLocal");
        System.out.println("Chat parity against " + source);
        return loader;
    }

    private static void classificationMatches() throws Exception {
        Pattern[] patterns = {null, SNITCH};
        boolean[] flags = {false, true};
        String[] tags = {null, "Restored", "System"};
        for (String text : LINES) {
            for (Pattern pattern : patterns) {
                for (boolean system : flags) {
                    for (boolean local : flags) {
                        for (String tag : tags) {
                            boolean origin = ChatChannelFilter.clientOrigin(system, local, tag);
                            boolean other = mainOrigin(system, local, tag);
                            check(origin == other, "client origin " + system + " " + local + " " + tag);
                            String here = ChatChannelFilter.classify(text, pattern, origin).name();
                            check(here.equals(mainChannelName(text, pattern, other)),
                                    "classify [" + text + "] -> " + here);
                        }
                    }
                }
                check(ChatChannelFilter.classify(text, pattern).name().equals(mainChannelName(text, pattern, false)),
                        "classify without an origin [" + text + "]");
            }
        }
        for (String raw : new String[]{null, "", "   ", "system", "snitches", "mining", "client", "events", "OI Events", "oi"}) {
            Object parsed = mainParse.invoke(null, raw);
            check(ChatChannel.parse(raw).name().equals(nameOf(parsed)), "parse " + raw);
            check(ChatChannel.parse(raw).id().equals(parsed.getClass().getMethod("id").invoke(parsed)), "id " + raw);
        }
    }

    private static void visibilityMatches() throws Exception {
        String[] channels = {"CHAT", "SNITCHES", "MINING", "CLIENT", "EVENTS"};
        String[] flags = {
                "viewChat", "viewSnitches", "viewMining", "showChat", "showSnitches", "showMining",
                "viewClient", "showClient", "viewEvents", "showEvents"
        };
        List<Flags> cases = new ArrayList<>();
        cases.add(Flags.NONE);
        cases.add(Flags.ALL);
        for (String flag : flags) cases.add(Flags.NONE.with(flag));
        for (String channel : channels) {
            for (boolean open : new boolean[]{false, true}) {
                for (Flags flagsNow : cases) {
                    boolean here = localShows(channel, open, flagsNow);
                    boolean there = mainShows(channel, open, flagsNow);
                    check(here == there, channel + (open ? " open " : " closed ") + flagsNow + " -> " + here + " vs 26.3 " + there);
                }
            }
        }
        check(localShows(null, true, Flags.ALL) == mainShows(null, true, Flags.ALL), "A missing channel is hidden");
    }

    private static void packingMatches() throws Exception {
        List<String> lines = List.of(
                "<Steve> hello",
                "* Alex entered snitch at vault [world 1 2 3]",
                "You revealed a hidden [Coal Ore] !",
                "[OpenIntel] relay connected",
                "Alex logged in at spawn");
        samePack(lines, SNITCH, false, false, true, false, true, false, false);
        samePack(lines, SNITCH, true, false, true, false, true, false, false);
        samePack(lines, SNITCH, true, true, true, false, false, false, false);
        samePack(lines, null, false, true, false, false, true, false, false);
    }

    /**
     * 1.21.11 has no client-system source. A line inserted outside a server
     * packet is the Client channel there, which is the same channel 26.3 gives
     * a client-system line.
     */
    private static void clientInsertMatches() throws Exception {
        check(mainOrigin(true, false, null) == ChatChannelFilter.clientOrigin(false, true, null),
                "A local insert stands in for the client-system mark");
        String snitch = "* Alex entered snitch at vault";
        String here = ChatChannelFilter.classify(snitch, SNITCH, ChatChannelFilter.clientOrigin(false, true, null)).name();
        String there = mainChannelName(snitch, SNITCH, mainOrigin(true, false, null));
        check(here.equals(there) && "CLIENT".equals(here), "A client snitch is Client on both");
        check(!ChatChannelFilter.clientOrigin(false, true, "Restored") && !mainOrigin(true, true, "Restored"),
                "Restored history is classified from its text on both");
        String restored = ChatChannelFilter.classify(snitch, SNITCH, ChatChannelFilter.clientOrigin(false, true, "Restored")).name();
        check(restored.equals(mainChannelName(snitch, SNITCH, mainOrigin(true, true, "Restored")))
                        && "SNITCHES".equals(restored),
                "A restored snitch stays in Snitches on both");
    }

    private static void receptionMatches() throws Exception {
        ChatReception.observe(new Object(), null);
        mainObserve.invoke(null, new Object(), null);
        check(ChatReception.takeLocal() == (boolean) mainTake.invoke(null),
                "A line stored outside a server packet is local on both");

        ChatReception.beginServer();
        mainBegin.invoke(null);
        ChatReception.observe(new Object(), null);
        mainObserve.invoke(null, new Object(), null);
        check(!ChatReception.takeLocal() && !(boolean) mainTake.invoke(null),
                "A line stored inside a server packet is not local on either");
        ChatReception.endServer();
        mainEnd.invoke(null);

        ChatReception.observe(new Object(), "Restored");
        mainObserve.invoke(null, new Object(), "Restored");
        check(!ChatReception.takeLocal() && !(boolean) mainTake.invoke(null),
                "Restored history is not a local insert on either");

        Object here = new Object();
        Object there = new Object();
        ChatReception.observe(here, null);
        ChatReception.observe(here, null);
        mainObserve.invoke(null, there, null);
        mainObserve.invoke(null, there, null);
        check(ChatReception.takeLocal() && (boolean) mainTake.invoke(null),
                "Replaying a cancelled add keeps the original local decision");
        check(ChatReception.takeLocal() && (boolean) mainTake.invoke(null),
                "The original observation is local too");
    }

    private static void chipFormattingMatches() throws Exception {
        for (String channel : new String[]{"CHAT", "SNITCHES", "MINING", "CLIENT", "EVENTS"}) {
            String label = ChatChannelBar.label(ChatChannel.valueOf(channel));
            check(label.equals(mainLabel.invoke(null, channel(channel))), "Chip label " + channel);
        }
        int[][] rows = {{360, 20, 0}, {360, 2, 0}, {360, 20, 8}, {80, 40, 0}};
        for (int[] row : rows) {
            int here = ChatChannelBar.rowY(row[0], 1, 0, row[1], row[2]);
            int there = (int) mainRowY.invoke(null, row[0], 1d, 0d, row[1], row[2]);
            check(here == there, "Chip row y " + here + " vs 26.3 " + there);
        }
        check((boolean) mainBell.invoke(null, true, 4, 0) == ChatChannelBar.bellPixel(true, 4, 0), "Bell dome");
        check((boolean) mainBell.invoke(null, false, 0, 0) == ChatChannelBar.bellPixel(false, 0, 0), "Muted bell corner");

        ToIntFunction<String> width = text -> text.length() * 6;
        List<ChatChannelBar.Chip> here = ChatChannelBar.layout(400, 120, width);
        @SuppressWarnings("unchecked")
        List<Object> there = (List<Object>) mainLayout.invoke(null, 400, 120, width);
        check(here.size() == there.size(), "Both chip rows have one chip per channel");
        for (int i = 0; i < here.size(); i++) {
            ChatChannelBar.Chip chip = here.get(i);
            Object other = there.get(i);
            Object plate = other.getClass().getMethod("plate").invoke(other);
            check(chip.plate().x() == num(plate, "x") && chip.plate().y() == num(plate, "y")
                            && chip.plate().width() == num(plate, "width")
                            && chip.plate().height() == num(plate, "height"),
                    "Chip plate " + chip.channel());
            sameHit(there, chip.plate().x() + 1, chip.plate().y() + 1, "CHANNEL", chip.channel().name());
            sameHit(there, chip.bell().x() + 1, chip.bell().y() + 1, "BELL", chip.channel().name());
        }
        check(ChatChannelBar.hit(here, -1, -1) == null && mainHit.invoke(null, there, -1d, -1d) == null,
                "A click off the chips hits nothing on either");
    }

    /**
     * 26.3 classifies {@code parent.getString()}. 1.21.11 joins the wrapped
     * lines of that message, newest line first in the trimmed log, and
     * classifies the same characters. A phrase split across the wrap still
     * has to land in the channel of the whole message.
     */
    private static void wrappedLinesMatchTheParentText() throws Exception {
        String[][] history = {
                {"<Steve> hello"},
                {"* Alex entered ", "snitch at vault"},
                {"You revealed a hidden [Coal", " Ore] !"},
                {"[14:02:11] ", "[OpenIntel] ice road on"},
                {"hello ", "\uD83D\uDE00", " world"},
                {"\u00A76You revealed a hidden \u00A7b[Coal Ore]\u00A76 !"}
        };
        List<GuiMessage.Line> trimmed = new ArrayList<>();
        for (int message = history.length - 1; message >= 0; message--) {
            trimmed.addAll(newestFirst(history[message]));
        }
        List<GuiMessage.Line> gap = new ArrayList<>();
        gap.add(line("world", true));
        gap.add(new GuiMessage.Line(0, null, null, false));
        gap.add(line("hello ", false));
        trimmed.addAll(0, gap);

        List<String> expected = new ArrayList<>();
        expected.add("hello world");
        for (int message = history.length - 1; message >= 0; message--) {
            expected.add(String.join("", history[message]));
        }
        List<List<GuiMessage.Line>> groups = ChatTranscript.messages(trimmed);
        check(groups.size() == expected.size(), "Each wrapped message stays one group");
        for (int i = 0; i < groups.size(); i++) {
            String text = ChatTranscript.readingOrderLines(groups.get(i));
            check(text.equals(expected.get(i)), "Joined text [" + text + "] vs [" + expected.get(i) + "]");
            String here = ChatChannelFilter.classify(text, SNITCH).name();
            check(here.equals(mainChannelName(text, SNITCH, false)),
                    "Wrapped message [" + text + "] -> " + here);
        }
        check("SNITCHES".equals(ChatChannelFilter.classify("* Alex entered snitch at vault", SNITCH).name()),
                "The joined snitch phrase is Snitches");
        check("MINING".equals(ChatChannelFilter.classify("You revealed a hidden [Coal Ore] !", SNITCH).name()),
                "The joined ore reveal is Mining");
        check("EVENTS".equals(ChatChannelFilter.classify("[14:02:11] [OpenIntel] ice road on", SNITCH).name()),
                "The joined OpenIntel line is OI Events");
        check("CHAT".equals(ChatChannelFilter.classify("* Alex entered ", SNITCH).name())
                        && "CHAT".equals(ChatChannelFilter.classify("You revealed a hidden [Coal", SNITCH).name())
                        && "CHAT".equals(ChatChannelFilter.classify("[14:02:11] ", SNITCH).name()),
                "A wrapped fragment alone is not the whole message");
    }

    private static void filterCallUsesThisVersionsFlagOrder() throws Exception {
        Path mod = modDir();
        String filter = Files.readString(mod.resolve("src/main/java/dev/openintel/chat/ChatChannelFilter.java"));
        String mixin = Files.readString(mod.resolve("src/main/java/dev/openintel/mixin/ChatChannelMixin.java"));
        List<String> params = longestShowsParameters(filter);
        List<String> args = topLevelArgs(mixin, "ChatChannelFilter.shows(");
        check(args.size() == params.size(), "The mixin passes one argument per shows parameter");
        check("open".equals(args.get(1).trim()), "The second shows argument is whether chat is open");
        for (int i = 2; i < params.size(); i++) {
            String name = params.get(i);
            String expected = "config.chat" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            check(expected.equals(args.get(i).replaceAll("\\s+", "")),
                    "shows argument " + name + " is passed from " + expected);
        }
        check(mixin.contains("ChatChannelFilter.clientOrigin(false, locallyAdded, logTag)"),
                "1.21.11 passes a local insert where 26.3 passes the client-system mark");
        check(mixin.contains("ChatTranscript.readingOrderLines(group)"),
                "Wrapped lines are classified from the same joined text");
    }

    private static boolean localShows(String channelName, boolean chatOpen, Flags flags) {
        ChatChannel channel = channelName == null ? null : ChatChannel.valueOf(channelName);
        return ChatChannelFilter.shows(channel, chatOpen,
                flags.viewChat, flags.viewSnitches, flags.viewMining,
                flags.showChat, flags.showSnitches, flags.showMining,
                flags.viewEvents, flags.showEvents,
                flags.viewClient, flags.showClient);
    }

    private static boolean mainShows(String channelName, boolean chatOpen, Flags flags) throws Exception {
        Object[] args = new Object[mainShows.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            String name = mainShows.getParameters()[i].getName();
            args[i] = switch (name) {
                case "line" -> channelName == null ? null : channel(channelName);
                case "chatOpen" -> chatOpen;
                case "viewChat" -> flags.viewChat;
                case "viewSnitches" -> flags.viewSnitches;
                case "viewMining" -> flags.viewMining;
                case "showChat" -> flags.showChat;
                case "showSnitches" -> flags.showSnitches;
                case "showMining" -> flags.showMining;
                case "viewClient" -> flags.viewClient;
                case "showClient" -> flags.showClient;
                case "viewEvents" -> flags.viewEvents;
                case "showEvents" -> flags.showEvents;
                default -> throw new AssertionError("Unexpected 26.3 shows parameter " + name);
            };
        }
        return (boolean) mainShows.invoke(null, args);
    }

    private static void samePack(List<String> lines, Pattern pattern, boolean chatOpen,
                                 boolean viewChat, boolean viewSnitches, boolean viewMining,
                                 boolean showChat, boolean showSnitches, boolean showMining) throws Exception {
        List<String> here = ChatChannelFilter.pack(lines, pattern, chatOpen,
                viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining);
        Object there = mainPack.invoke(null, lines, pattern, chatOpen,
                viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining);
        check(here.equals(there), "Packed lines differ: " + here + " vs 26.3 " + there);
    }

    private static void sameHit(List<Object> chips, double x, double y, String hit, String channel) throws Exception {
        ChatChannelBar.Target here = ChatChannelBar.hit(ChatChannelBar.layout(400, 120, text -> text.length() * 6), x, y);
        Object there = mainHit.invoke(null, chips, x, y);
        check(here != null && there != null, "Click " + x + "," + y + " hits a chip");
        check(hit.equals(here.hit().name()) && hit.equals(nameOf(there.getClass().getMethod("hit").invoke(there))),
                "Click target " + hit);
        check(channel.equals(here.chatChannel().name())
                        && channel.equals(nameOf(there.getClass().getMethod("chatChannel").invoke(there))),
                "Click channel " + channel);
    }

    private static List<GuiMessage.Line> newestFirst(String... readingOrder) {
        List<GuiMessage.Line> lines = new ArrayList<>();
        for (int i = readingOrder.length - 1; i >= 0; i--) {
            lines.add(line(readingOrder[i], i == readingOrder.length - 1));
        }
        return lines;
    }

    private static GuiMessage.Line line(String text, boolean end) {
        return new GuiMessage.Line(0, sequence(text), null, end);
    }

    private static FormattedCharSequence sequence(String text) {
        if (text == null) return null;
        int[] points = text.codePoints().toArray();
        return sink -> {
            for (int i = 0; i < points.length; i++) {
                if (!sink.accept(i, null, points[i])) return false;
            }
            return true;
        };
    }

    private static String mainChannelName(String text, Pattern pattern, boolean origin) throws Exception {
        return nameOf(mainClassify.invoke(null, text, pattern, origin));
    }

    private static boolean mainOrigin(boolean system, boolean local, String tag) throws Exception {
        return (boolean) mainOrigin.invoke(null, system, local, tag);
    }

    private static Object channel(String name) throws Exception {
        Method valueOf = mainChannel.getMethod("valueOf", String.class);
        return valueOf.invoke(null, name);
    }

    private static String nameOf(Object enumValue) throws Exception {
        return (String) enumValue.getClass().getMethod("name").invoke(enumValue);
    }

    private static int num(Object record, String accessor) throws Exception {
        return (int) record.getClass().getMethod(accessor).invoke(record);
    }

    private static Method longest(Class<?> type, String name) {
        Method best = null;
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && (best == null || method.getParameterCount() > best.getParameterCount())) {
                best = method;
            }
        }
        check(best != null, "Missing " + name);
        return best;
    }

    private static String bundledOrLive(Path mod) throws Exception {
        Path sibling = mod.getParent().resolveSibling("openintel").resolve("mod");
        Path properties = sibling.resolve("gradle.properties");
        if (!Files.exists(properties) || !Files.readString(properties).contains("minecraft_version=26.3")) {
            return "the bundled 26.3 chat sources";
        }
        for (String name : MAIN_SOURCES) {
            String live = Files.readString(sibling.resolve("src/main/java/dev/openintel/chat").resolve(name));
            check(normalize(live).equals(normalize(bundled(name))),
                    "26.3 " + name + " changed. Update src/test/resources/chat-26.3/" + name);
        }
        return sibling.toString();
    }

    private static String source(String name, Path mod) throws Exception {
        Path properties = mod.getParent().resolveSibling("openintel").resolve("mod/gradle.properties");
        Path file = mod.getParent().resolveSibling("openintel")
                .resolve("mod/src/main/java/dev/openintel/chat").resolve(name);
        if (Files.exists(properties) && Files.readString(properties).contains("minecraft_version=26.3")) {
            return Files.readString(file);
        }
        return bundled(name);
    }

    private static String bundled(String name) throws Exception {
        try (var input = ChatVersionParityTest.class.getResourceAsStream("/chat-26.3/" + name)) {
            check(input != null, "Missing bundled 26.3 source " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path modDir() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path properties = dir.resolve("gradle.properties");
            if (Files.exists(properties)
                    && Files.readString(properties).contains("minecraft_version=1.21.11")
                    && Files.exists(dir.resolve("src/main/java/dev/openintel/chat/ChatChannelFilter.java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not find the 1.21.11 mod directory from " + Path.of("").toAbsolutePath());
    }

    private static List<String> longestShowsParameters(String source) {
        String needle = "static boolean shows(";
        List<String> best = List.of();
        for (int from = 0; from < source.length(); ) {
            int at = source.indexOf(needle, from);
            if (at < 0) break;
            int open = source.indexOf('(', at);
            int close = source.indexOf(')', open);
            List<String> names = new ArrayList<>();
            for (String part : source.substring(open + 1, close).split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                String[] tokens = trimmed.split("\\s+");
                names.add(tokens[tokens.length - 1]);
            }
            if (names.size() > best.size()) best = names;
            from = close + 1;
        }
        check(best.size() == 12, "The full shows method has 12 parameters");
        return best;
    }

    private static List<String> topLevelArgs(String source, String call) {
        int start = source.indexOf(call);
        check(start >= 0, "Missing " + call);
        int depth = 1;
        StringBuilder current = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (int i = start + call.length(); i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0) break;
            if (c == ',' && depth == 1) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) args.add(current.toString().trim());
        return args;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private record Flags(boolean viewChat, boolean viewSnitches, boolean viewMining,
                         boolean showChat, boolean showSnitches, boolean showMining,
                         boolean viewClient, boolean showClient,
                         boolean viewEvents, boolean showEvents) {
        private static final Flags NONE = new Flags(false, false, false, false, false, false, false, false, false, false);
        private static final Flags ALL = new Flags(true, true, true, true, true, true, true, true, true, true);

        private Flags with(String flag) {
            return switch (flag) {
                case "viewChat" -> new Flags(true, viewSnitches, viewMining, showChat, showSnitches, showMining, viewClient, showClient, viewEvents, showEvents);
                case "viewSnitches" -> new Flags(viewChat, true, viewMining, showChat, showSnitches, showMining, viewClient, showClient, viewEvents, showEvents);
                case "viewMining" -> new Flags(viewChat, viewSnitches, true, showChat, showSnitches, showMining, viewClient, showClient, viewEvents, showEvents);
                case "showChat" -> new Flags(viewChat, viewSnitches, viewMining, true, showSnitches, showMining, viewClient, showClient, viewEvents, showEvents);
                case "showSnitches" -> new Flags(viewChat, viewSnitches, viewMining, showChat, true, showMining, viewClient, showClient, viewEvents, showEvents);
                case "showMining" -> new Flags(viewChat, viewSnitches, viewMining, showChat, showSnitches, true, viewClient, showClient, viewEvents, showEvents);
                case "viewClient" -> new Flags(viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining, true, showClient, viewEvents, showEvents);
                case "showClient" -> new Flags(viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining, viewClient, true, viewEvents, showEvents);
                case "viewEvents" -> new Flags(viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining, viewClient, showClient, true, showEvents);
                case "showEvents" -> new Flags(viewChat, viewSnitches, viewMining, showChat, showSnitches, showMining, viewClient, showClient, viewEvents, true);
                default -> throw new AssertionError(flag);
            };
        }
    }
}
