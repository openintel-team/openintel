package dev.openintel.render;

import dev.openintel.config.OIConfig;

import java.util.ArrayList;
import java.util.List;

public final class EventFeedLayoutTest {
    public static void main(String[] args) {
        maxWidthTracksAThirdOfTheScreen();
        wrapsOnWords();
        hardBreaksLongWords();
        preservesNewlinesAndCodePoints();
        rightAlignsInsideTheCap();
        shortMessageHugsItsText();
        explicitBoxWrapsAndClips();
        resizeKeepsTheOppositeEdge();
        watermarkHiddenByDefault();
        keepsEightLinesOnScreenAndTheSessionBehindThem();
        scrollsOlderLinesWhilePinnedToTheNewest();
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

    private static void explicitBoxWrapsAndClips() {
        var wide = EventFeed.arrange(List.of(new EventFeed.FeedLine("ok", 1)),
                300, 1f, 10, String::length, 140, 0);
        check(wide.width() == 140 && wide.lines().size() == 1,
                "A chosen width stays wider than the text and can pass a third of the screen");
        check(EventFeed.lineX(OIConfig.FeedAlign.RIGHT, wide.width(), 2) == 138,
                "Right alignment uses the resized box");
        String message = "1234567890".repeat(8);
        var narrow = EventFeed.arrange(List.of(new EventFeed.FeedLine(message, 2)),
                300, 1f, 10, String::length, 4, 0);
        check(narrow.width() == 4, "A chosen width can be narrower than a third of the screen");
        for (EventFeed.FeedLine line : narrow.lines()) {
            check(line.text().length() <= 4, "Lines wrap inside the chosen width");
        }
        var lines = new ArrayList<EventFeed.FeedLine>();
        for (int i = 1; i <= 5; i++) lines.add(new EventFeed.FeedLine(Integer.toString(i), i));
        var clipped = EventFeed.arrange(lines, 300, 1f, 10, String::length, 40, 25);
        check(clipped.height() == 25 && clipped.width() == 40, "The box keeps the chosen size");
        check(clipped.lines().size() == 2 && clipped.lines().get(0).text().equals("4")
                        && clipped.lines().get(1).text().equals("5") && clipped.lines().get(1).color() == 5,
                "The newest lines stay and keep their color");
    }

    private static void resizeKeepsTheOppositeEdge() {
        var anchor = HudLayout.capture(-1, 4, 100, 40, 640, 360);
        var frame = HudLayout.place(anchor, 100, 40, 640, 360, 640, 360);
        var left = EventFeed.resizeBox(frame.x(), frame.y(), frame.width(), frame.height(),
                EventFeed.EDGE_LEFT, frame.x() - 30, frame.y() + 4, 640, 360, 48, 12);
        check(left.right() == frame.right() && left.width() == frame.width() + 30 && left.y() == frame.y(),
                "Dragging the left edge grows the box without moving the right edge");
        var corner = EventFeed.resizeBox(100, 40, 120, 50,
                EventFeed.EDGE_RIGHT | EventFeed.EDGE_BOTTOM, 240, 120, 640, 360, 48, 12);
        check(corner.x() == 100 && corner.y() == 40 && corner.right() == 240 && corner.bottom() == 120,
                "A corner drag keeps the opposite corner fixed");
        var stopped = EventFeed.resizeBox(100, 20, 80, 40, EventFeed.EDGE_LEFT, 170, 20, 640, 360, 32, 12);
        check(stopped.right() == 180 && stopped.width() == 32,
                "The box cannot invert past its minimum width");
        var pinned = EventFeed.resizeBox(frame.x(), frame.y(), frame.width(), frame.height(),
                EventFeed.EDGE_LEFT, -10, frame.y(), 640, 360, 32, 12);
        check(pinned.x() == 0 && pinned.right() == frame.right(),
                "A drag past the screen pins the moving edge");
        check(EventFeed.resizeEdgesAt(10, 10, 200, 80, 10, 40) == EventFeed.EDGE_LEFT,
                "The left edge resizes");
        check(EventFeed.resizeEdgesAt(10, 10, 200, 80, 100, 40) == 0,
                "The middle of the box still moves");
        check(EventFeed.resizeEdgesAt(10, 10, 200, 80, 12, 12)
                        == (EventFeed.EDGE_LEFT | EventFeed.EDGE_TOP),
                "A corner sets both edges");
        check(EventFeed.resizeEdgesAt(10, 10, 200, 80, 9, 40) == 0, "Points outside the box do not resize it");
        check(EventFeed.elementSpan(100, 2f) == 50, "GUI pixels convert back to element pixels");

        var placed = EventFeed.placedResize(frame.x(), frame.y(), frame.width(), frame.height(),
                EventFeed.EDGE_LEFT, frame.x() - 30, frame.y() + 4, 640, 360, 48, 12, 1f, 640, 360);
        OIConfig config = new OIConfig();
        config.hudReferenceWidth = 640;
        config.hudReferenceHeight = 360;
        HudLayouts.resizeEvents(config, placed.x(), placed.y(), placed.elementWidth(), placed.elementHeight(),
                placed.width(), placed.height(), 640, 360);
        var again = HudLayout.place(config.hudAnchors.get("EVENTS").anchor(),
                config.eventFeedWidth, config.eventFeedHeight, 640, 360, 640, 360);
        check(again.right() == frame.right() && again.y() == frame.y(),
                "The saved anchor keeps the undragged edges");
        check(config.eventFeedWidth == placed.elementWidth() && config.eventFeedHeight == placed.elementHeight(),
                "The editor stores the resized box");
    }

    private static void keepsEightLinesOnScreenAndTheSessionBehindThem() {
        check(EventFeed.firstVisibleIndex(8) == 0, "Eight fresh lines all stay on the HUD");
        check(EventFeed.firstVisibleIndex(9) == 1, "The ninth line leaves the HUD and stays in the session");
        check(EventFeed.onScreen(8_000L, 8_000L), "A line is solid through the hold");
        check(EventFeed.onScreen(9_500L, 8_000L), "The fade still counts as on screen");
        check(!EventFeed.onScreen(9_501L, 8_000L), "The next millisecond leaves the HUD");
    }

    private static void scrollsOlderLinesWhilePinnedToTheNewest() {
        var lines = new ArrayList<EventFeed.FeedLine>();
        for (int i = 1; i <= 10; i++) lines.add(new EventFeed.FeedLine(Integer.toString(i), i));
        check(EventFeed.visibleWindow(lines, 4, 0, true).stream().map(EventFeed.FeedLine::text).toList()
                        .equals(List.of("7", "8", "9", "10")),
                "A pinned log shows the newest rows");
        var older = EventFeed.scrollLog(10, 4, 0, true, 1);
        check(!older.pinned() && older.offset() == 5, "Scrolling up leaves the newest row");
        check(EventFeed.visibleWindow(lines, 4, older.offset(), older.pinned()).stream()
                        .map(EventFeed.FeedLine::text).toList().equals(List.of("6", "7", "8", "9")),
                "The window moves one row toward older lines");
        var top = EventFeed.scrollLog(10, 4, older.offset(), false, 20);
        check(!top.pinned() && top.offset() == 0, "Scrolling up stops on the oldest line");
        check(EventFeed.visibleWindow(lines, 4, top.offset(), top.pinned()).get(0).text().equals("1"),
                "The oldest line is reachable");
        var back = EventFeed.scrollLog(10, 4, 5, false, -1);
        check(back.pinned() && back.offset() == 6, "Scrolling down to the end follows new lines again");
        check(EventFeed.maxScroll(4, 4) == 0, "A page that already fits does not scroll");
        check(EventFeed.scrollLog(4, 4, 3, false, 1).pinned(), "Nothing to scroll stays on the newest line");
        var held = EventFeed.scrollLog(12, 4, 2, false, 0);
        check(!held.pinned() && held.offset() == 2, "New lines keep a scrolled window where it was");
    }

    private static void watermarkHiddenByDefault() {
        check(!new OIConfig().logoHudEnabled, "The watermark is hidden until it is turned on");
        check(new OIConfig().eventFeedShowOnChat, "Opening chat shows the session log");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
