package dev.openintel.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Compares this install with the public OpenIntel team release list.
 * The chat link stays on the releases page itself; release JSON never chooses the URL.
 */
public final class ReleaseUpdate {
    public static final String RELEASES_PAGE = "https://github.com/openintel-team/openintel/releases";
    static final String RELEASES_API = "https://api.github.com/repos/openintel-team/openintel/releases?per_page=20";

    private ReleaseUpdate() { }

    /**
     * Newest published release that is newer than {@code installedVersion} and
     * ships a jar for {@code minecraftVersion}. Null when nothing qualifies,
     * including a failed or unreadable response.
     */
    public static String newerVersion(String installedVersion, String minecraftVersion, String releasesJson) {
        int[] installed = numbers(installedVersion);
        if (installed == null || minecraftVersion == null || minecraftVersion.isBlank() || releasesJson == null) return null;
        String suffix = "-mc" + minecraftVersion + ".jar";
        JsonArray releases;
        try {
            JsonElement parsed = JsonParser.parseString(releasesJson);
            if (!parsed.isJsonArray()) return null;
            releases = parsed.getAsJsonArray();
        } catch (RuntimeException ignored) {
            return null;
        }
        int[] best = null;
        String bestText = null;
        for (JsonElement element : releases) {
            try {
                if (!element.isJsonObject()) continue;
                JsonObject release = element.getAsJsonObject();
                if (flag(release, "draft") || flag(release, "prerelease")) continue;
                if (!ships(release, suffix)) continue;
                int[] version = numbers(text(release, "tag_name"));
                if (version == null || compare(version, installed) <= 0) continue;
                if (best == null || compare(version, best) > 0) {
                    best = version;
                    bestText = text(version);
                }
            } catch (RuntimeException ignored) {
                // One bad release does not hide a later valid one.
            }
        }
        return bestText;
    }

    private static boolean ships(JsonObject release, String suffix) {
        if (!release.has("assets") || !release.get("assets").isJsonArray()) return false;
        for (JsonElement element : release.getAsJsonArray("assets")) {
            if (!element.isJsonObject()) continue;
            String name = text(element.getAsJsonObject(), "name");
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    private static boolean flag(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) return false;
        var value = object.get(name).getAsJsonPrimitive();
        return value.isBoolean() && value.getAsBoolean();
    }

    private static String text(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) return "";
        return object.get(name).getAsString();
    }

    /** Leading {@code v} is optional. Build metadata and pre-release tails are ignored. */
    static int[] numbers(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        int start = 0;
        if (value.length() > 1 && (value.charAt(0) == 'v' || value.charAt(0) == 'V') && Character.isDigit(value.charAt(1))) {
            start = 1;
        }
        int end = start;
        while (end < value.length()) {
            char c = value.charAt(end);
            if (c == '.' || (c >= '0' && c <= '9')) end++;
            else break;
        }
        if (end == start) return null;
        String[] parts = value.substring(start, end).split("\\.", -1);
        if (parts.length == 0 || parts.length > 8) return null;
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 6) return null;
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return numbers;
    }

    private static String text(int[] version) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < version.length; i++) {
            if (i > 0) out.append('.');
            out.append(version[i]);
        }
        return out.toString();
    }

    private static int compare(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }
}
