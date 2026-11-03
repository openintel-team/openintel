package dev.openintel.jm;

import com.mojang.blaze3d.platform.NativeImage;
import dev.openintel.map.MapIconPixels;
import dev.openintel.map.MapMarkerStyle;
import journeymap.api.v2.client.model.MapImage;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * One baked {@link NativeImage} per marker look (shape, relic texture,
 * selection), built from {@link MapIconPixels}. Images live for the session
 * and are shared by every overlay of that look; JourneyMap tints the white
 * parts with the marker color.
 */
final class JmIcons {
    private final Map<String, NativeImage> images = new HashMap<>();
    private final Map<String, int[]> textures = new HashMap<>();

    MapImage image(MapMarkerStyle.Style style) {
        String key = style.shape() + "|" + style.relicTexture() + "|" + style.selected();
        NativeImage image = images.computeIfAbsent(key, ignored -> bake(style));
        MapImage map = new MapImage(image).centerAnchors();
        // Relic art keeps its own colors; every other shape is tinted.
        map.setColor(style.shape() == MapMarkerStyle.Shape.RELIC ? 0xFFFFFF : style.argb() & 0xFFFFFF);
        return map;
    }

    /** Pixels from the label's top edge down to the text, per shape. */
    static int textOffset(MapMarkerStyle.Shape shape) {
        return switch (shape) {
            case NAME_DIAMOND -> 14;
            case RELIC -> 26;
            default -> 24;
        };
    }

    private NativeImage bake(MapMarkerStyle.Style style) {
        MapIconPixels.Icon icon = switch (style.shape()) {
            case RELIC -> {
                int[] texture = texture(style.relicTexture(), style.relicSize());
                yield texture == null ? MapIconPixels.cross() : MapIconPixels.relic(texture, style.relicSize(), style.selected());
            }
            default -> MapIconPixels.of(style.shape());
        };
        NativeImage image = new NativeImage(icon.width(), icon.height(), false);
        // Write every pixel. Unwritten memory shows through as speckled noise.
        for (int y = 0; y < icon.height(); y++) {
            for (int x = 0; x < icon.width(); x++) {
                image.setPixel(x, y, icon.at(x, y));
            }
        }
        return image;
    }

    /** Square ARGB grid for a bundled relic texture, read once. */
    private int[] texture(String file, int size) {
        if (file == null || size <= 0) return null;
        return textures.computeIfAbsent(file, name -> {
            String path = "/assets/openintel/textures/relic/" + name;
            try (InputStream in = JmIcons.class.getResourceAsStream(path)) {
                if (in == null) return null;
                try (NativeImage png = NativeImage.read(in)) {
                    if (png.getWidth() < size || png.getHeight() < size) return null;
                    int[] px = new int[size * size];
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) px[y * size + x] = png.getPixel(x, y);
                    }
                    return px;
                }
            } catch (Exception e) {
                LoggerFactory.getLogger("openintel").warn("JourneyMap relic icon {} unreadable", name, e);
                return null;
            }
        });
    }
}
