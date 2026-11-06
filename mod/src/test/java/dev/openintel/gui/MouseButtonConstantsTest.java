package dev.openintel.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mouse button IDs differ between Minecraft versions: 26.3 numbers left/right/middle as 1/3/2, while
 * GLFW and 26.2 and earlier use 0/1/2. A raw literal written against one version silently means a different
 * button (or none) on another, so every comparison must go through {@code InputConstants.MOUSE_BUTTON_*}.
 */
public final class MouseButtonConstantsTest {
    private static final Pattern RAW_BUTTON = Pattern.compile(
            "\\b(?:button|getButton)\\(\\)\\s*[!=]=\\s*-?\\d"
                    + "|-?\\d\\s*[!=]=\\s*[\\w.]*\\b(?:button|getButton)\\(\\)"
                    + "|\\bswitch\\s*\\([^)]*\\b(?:button|getButton)\\(\\)\\s*\\)");

    public static void main(String[] args) throws IOException {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) throw new AssertionError("Run from mod/: missing " + root.toAbsolutePath());
        List<String> failures = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanned++;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = RAW_BUTTON.matcher(lines.get(i));
                    if (m.find()) failures.add(root.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        if (scanned == 0) throw new AssertionError("No sources scanned under " + root.toAbsolutePath());
        if (!failures.isEmpty()) {
            throw new AssertionError("Compare mouse buttons with InputConstants.MOUSE_BUTTON_*, not raw numbers:\n  "
                    + String.join("\n  ", failures));
        }
        System.out.println("Mouse button constant checks passed (" + scanned + " sources)");
    }
}
