package dev.openintel.render;

import dev.openintel.config.OIConfig;

import java.util.List;

public final class EventFeedLayoutTest {
    public static void main(String[] args) {
        maxWidthTracksAThirdOfTheScreen();
        wrapsOnWords();
        hardBreaksLongWords();
        preservesNewlinesAndCodePoints();
        rightAlignsInsideTheCap();
        shortMessageHugsItsText();
        watermarkHiddenByDefault();
        System.out.println("EventFeedLayoutTest passed");
    }

    private static void maxWidthTracksAThirdOfTheScreen() {
        check(EventFeed.maxWidth(300, 1f) == 100, "300px screen caps the feed at 100px");
        check(EventFeed.maxWidth(900, 3f) == 100, "HUD scale is removed so the drawn box stays a third of the screen");
        check(EventFeed.maxWidth(0, 1f) == 1, "Empty viewport still has a positive cap");
        check(EventFeed.maxWidth(10, 100f) == 1, "A huge HUD scale cannot produce a zero cap");
    }

    private static void wrapsOnWords() {
        check(EventFeed.wrap("hello world", 5, String::length).equals(List.of("hello", "world")),
                "A space breaks before the line overflows");
        check(EventFeed.wrap("ab cd ef", 5, String::length).equals(List.of("ab cd", "ef")),
                "A prefix that already ends on a word is kept whole");
        check(EventFeed.wrap("abc defghij", 6, String::length).equals(List.of("abc", "defghi", "j")),
                "A word that does not fit moves to the next line, then hard-breaks");
    }

    private static void hardBreaksLongWords() {
        check(EventFeed.wrap("abcdefghij", 4, String::length).equals(List.of("abcd", "efgh", "ij")),
                "A token wider than the cap splits without overflowing");
    }

    private static void preservesNewlinesAndCodePoints() {
        check(EventFeed.wrap("hi\nthere friend", 5, String::length)
                        .equals(List.of("hi", "there", "frien", "d")),
                "Existing line breaks are kept and the next paragraph still wraps");
        String supplementary = "\uD801\uDC00\uD801\uDC01\uD801\uDC02";
        List<String> lines = EventFeed.wrap(supplementary, 2, s -> s.codePointCount(0, s.length()));
        check(lines.size() == 2, "Supplementary characters wrap by code point");
        check(lines.get(0).codePointCount(0, lines.get(0).length()) == 2, "First line keeps two whole code points");
        check(!lines.get(0).contains("\uD801") || lines.get(0).codePoints().count() == 2,
                "A surrogate pair is not split");
        for (String line : lines) {
            check(line.codePointCount(0, line.length()) == line.codePoints().count(), "Every line is valid Unicode");
        }
    }

    private static void rightAlignsInsideTheCap() {
        String message = "1234567890".repeat(8);
        var laid = EventFeed.arrange(List.of(
                        new EventFeed.FeedLine("ok", 0xFF111111),
                        new EventFeed.FeedLine(message, 0xFF222222)),
                90, 1f, 10, String::length);
        int cap = EventFeed.maxWidth(90, 1f);
        check(laid.width() <= cap && laid.width() > 0, "Wrapped feed stays within a third of the screen");
        check(laid.lines().get(0).text().equals("ok"), "Messages stay in order");
        check(laid.lines().get(0).color() == 0xFF111111, "Wrapped lines keep their message color");
        check(laid.lines().stream().anyMatch(line -> line.color() == 0xFF222222), "The long message is still present");
        for (EventFeed.FeedLine line : laid.lines()) {
            int width = line.text().length();
            check(width <= cap, "No visual line is wider than the cap: " + line.text());
            int x = EventFeed.lineX(OIConfig.FeedAlign.RIGHT, laid.width(), width);
            check(x >= 0 && x + width == laid.width(), "Right alignment shares the feed's right edge");
        }
        check(laid.height() == laid.lines().size() * 10, "Each visual line takes one row");
    }

    private static void shortMessageHugsItsText() {
        var laid = EventFeed.arrange(List.of(new EventFeed.FeedLine("ok", 1)),
                300, 1f, 10, String::length);
        check(laid.width() == 2 && laid.height() == 10 && laid.lines().size() == 1,
                "A short message stays one line and does not stretch to the cap");
    }

    private static void watermarkHiddenByDefault() {
        check(!new OIConfig().logoHudEnabled, "The watermark is hidden until it is turned on");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
