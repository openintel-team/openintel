package dev.openintel.xaero;

import dev.openintel.config.OIConfig;
import dev.openintel.render.EventFeed;
import dev.openintel.render.MarkerHud;

/** Standalone assertions, requiring neither a Minecraft client nor Xaero. */
public final class RelicMapMathTest {
    public static void main(String[] args) {
        assert RelicMapMath.worldFromIcon(100, 1, 4) == 104;
        assert RelicMapMath.centerFromPlayer(104, 4, 1) == 100;
        double center = RelicMapMath.centerFromPlayer(-250.5, -20, 2);
        assert center == -210.5;
        assert RelicMapMath.worldFromIcon(center, 2, 30) == -150.5;
        assert RelicMapMath.iconClampedToEdge(-128);
        assert RelicMapMath.iconClampedToEdge(127);
        assert !RelicMapMath.iconClampedToEdge(0);
        assert !RelicMapMath.iconClampedToEdge(126);
        assert !RelicMapMath.validScale(-1);
        assert RelicMapMath.validScale(0);
        assert RelicMapMath.validScale(4);
        assert !RelicMapMath.validScale(5);
        assert RelicMapMath.isInteriorPlayerIcon("player", 10, -10);
        assert !RelicMapMath.isInteriorPlayerIcon("player", 127, 0);
        assert !RelicMapMath.isInteriorPlayerIcon("player_off_map", 0, 0);

        assert RelicMapMath.isRelicIcon("minecraft", "red_x", false);
        assert RelicMapMath.isRelicIcon("minecraft", "target_x", false);
        assert RelicMapMath.isRelicIcon("minecraft", "target_point", false);
        assert RelicMapMath.isRelicIcon("minecraft", "mansion", true);
        assert !RelicMapMath.isRelicIcon("minecraft", "player", false);
        assert !RelicMapMath.isRelicIcon("minecraft", "player_off_map", false);
        assert !RelicMapMath.isRelicIcon("minecraft", "frame", false);
        assert !RelicMapMath.isRelicIcon("minecraft", "banner_red", false);
        assert !RelicMapMath.isRelicIcon("minecraft", "red_marker", false);
        assert RelicMapMath.isRelicIcon("civ", "relic", false);
        assert !RelicMapMath.isRelicIcon("minecraft", "", false);
        assert !RelicMapMath.isRelicIcon(null, null, true);

        assert RelicCatalog.find("Lunar Wand").enchantment().equals("Fire Protection");
        assert RelicCatalog.find("  lunar   wand ").name().equals("Lunar Wand");
        assert RelicCatalog.find("White Rabbit’s Timepiece").enchantment().equals("Lure");
        assert RelicCatalog.find("Paddlin' Stick").enchantment().equals("Knockback");
        assert RelicCatalog.find("Pixie Dust").enchantment().equals("Efficiency");
        assert RelicCatalog.find("Pixie Dust").texture().equals("angel_dust.png");
        assert RelicCatalog.find("Angel Dust").name().equals("Pixie Dust");
        assert RelicCatalog.find("Pixel Dust").name().equals("Pixie Dust");
        assert RelicCatalog.find("Treasure Map") == null;
        assert RelicCatalog.find("Original") == null;
        assert RelicCatalog.isGeneric("Copy of a Copy");
        assert RelicCatalog.isWorldMapName("World Map");
        assert RelicCatalog.isWorldMapName("  world   map ");
        assert !RelicCatalog.isWorldMapName("Treasure Map");
        assert !RelicCatalog.isWorldMapName("Displays where you are in the world");
        assert RelicCatalog.all().size() == 25;
        for (var relic : RelicCatalog.all()) {
            var texture = RelicCatalog.class.getClassLoader()
                    .getResource("assets/openintel/textures/relic/" + relic.texture());
            assert texture != null : relic.texture();
        }
        var lunar = RelicNames.resolve(java.util.List.of("Treasure Map", "Lunar Wand", "Original"));
        assert lunar.title().equals("Lunar Wand") && lunar.enchantment().equals("Fire Protection");
        var heart = RelicNames.resolve(java.util.List.of("§dTelltale Heart", "Protection III", "Relic"));
        assert heart.title().equals("Telltale Heart") && heart.enchantment().equals("Protection");
        var unknown = RelicNames.resolve(java.util.List.of("Treasure Map", "Original"));
        assert unknown.title().equals("Relic") && unknown.enchantment().isEmpty();

        var swaps = RelicMapOrder.swaps(new int[]{2, 5, 9}, new double[]{100, 10, 50});
        assert swaps.size() == 2;
        assert swaps.get(0)[0] == 2 && swaps.get(0)[1] == 5;
        assert swaps.get(1)[0] == 5 && swaps.get(1)[1] == 9;
        assert RelicMapOrder.swaps(new int[]{1, 2}, new double[]{1, 5}).isEmpty();
        var ties = RelicMapOrder.swaps(new int[]{1, 2, 3}, new double[]{5, 5, 1});
        assert ties.size() == 1 && ties.get(0)[0] == 1 && ties.get(0)[1] == 3;
        assert RelicMapOrder.swaps(new int[0], new double[0]).isEmpty();

        // Default pin range (300): inclusive at the edge, exclusive just past it.
        assert RelicProximity.near(0, 0, 0, 0, 300);
        assert RelicProximity.near(0, 0, 300, 0, 300);
        assert !RelicProximity.near(0, 0, 300.1, 0, 300);
        // Legacy 100-block pin checks keep an explicit range argument.
        assert RelicProximity.near(0, 0, 100, 0, 100);
        assert RelicProximity.near(10, -20, -90, -20, 100);
        assert !RelicProximity.near(0, 0, 100.1, 0, 100);
        assert RelicProximity.near(0, 0, 70, 70, 100);
        assert !RelicProximity.near(0, 0, 71, 71, 100);
        assert RelicProximity.label("Lunar Wand", "Fire Protection").equals("Lunar Wand | Fire Protection");
        assert RelicProximity.label("Relic", "120, -40").equals("Relic | 120, -40");
        assert RelicProximity.label("Lunar Wand", "").equals("Lunar Wand");
        assert RelicProximity.label("Lunar Wand", null).equals("Lunar Wand");
        // Nearby relic pins already include one distance. The shared caption must not add another.
        String silk = RelicProximity.label("Silk from Akkabah", "Silk Touch") + " | 107m";
        assert MarkerHud.withDistance(silk, 1, false, 107.4).equals("Silk from Akkabah | Silk Touch | 107m");
        assert MarkerHud.withDistance("snitch | player | now", 2, true, 42.2).equals("snitch | player | now | 42m");
        assert MarkerHud.withDistance("Alex", 0, true, 15.9).equals("Alex 15m");

        // A learned map center stays put while the player icon byte is unchanged,
        // and still settles on the real center after the icon steps.
        assertStableCenter(1000.0, -250.5, 2, 30);
        assertStableCenter(-4000.0, 800.0, 0, -12);
        assertStableCenter(2048.0, 2048.0, 4, -90);

        // Opposite icon edges at scale 4 are ~1920 world blocks apart; center sorts closer.
        double mapCenter = 2048;
        int scale = 4;
        double west = RelicMapMath.worldFromIcon(mapCenter, scale, -120);
        double east = RelicMapMath.worldFromIcon(mapCenter, scale, 120);
        double mid = RelicMapMath.worldFromIcon(mapCenter, scale, 0);
        assert east - west == 1920;
        double playerX = mapCenter;
        double distWest = Math.abs(playerX - west);
        double distEast = Math.abs(playerX - east);
        double distMid = Math.abs(playerX - mid);
        assert distMid < distWest && distMid < distEast;
        // Component-space ±120 must not be used as world distance when center+scale known.
        double componentSpan = 120 - (-120);
        assert east - west != componentSpan;
        assert !RelicMapMath.looksLikeWorldCoords(120, -120);
        assert !RelicMapMath.useComponentAsWorld(120, -120, true);
        assert RelicMapMath.useComponentAsWorld(120, -120, false);
        assert RelicMapMath.looksLikeWorldCoords(1000, 50);
        assert RelicMapMath.useComponentAsWorld(1000, 50, true);

        assert EventFeed.lineX(OIConfig.FeedAlign.RIGHT, 200f, 50f) == 150;
        assert EventFeed.lineX(OIConfig.FeedAlign.LEFT, 200f, 50f) == 0;
        assert EventFeed.lineX(OIConfig.FeedAlign.RIGHT, 80f, 100f) == 0;

        System.out.println("RelicMapMathTest passed");
    }

    /**
     * Vanilla map icons quantize {@code (player - center) / (1 << scale)} with
     * {@code (int)(local * 2 + 0.5)}. Re-deriving the center from the live player
     * position makes the relic swim inside one icon pixel.
     */
    private static void assertStableCenter(double trueX, double trueZ, int scale, int relicByte) {
        var lock = new RelicMapMath.CenterLock();
        double previousX = Double.NaN;
        double previousZ = Double.NaN;
        int previousByteX = Integer.MIN_VALUE;
        int previousByteZ = Integer.MIN_VALUE;
        int previousBlockX = Integer.MIN_VALUE;
        int previousBlockZ = Integer.MIN_VALUE;
        boolean saw = false;
        for (double t = -50; t <= 50; t += 0.25) {
            double playerX = trueX + t;
            double playerZ = trueZ - t * 0.5;
            int byteX = vanillaIconByte(playerX, trueX, scale);
            int byteZ = vanillaIconByte(playerZ, trueZ, scale);
            if (byteX <= -128 || byteX >= 127 || byteZ <= -128 || byteZ >= 127) continue;
            lock.observe(playerX, playerZ, byteX, byteZ, scale);
            saw = true;
            boolean samePixel = byteX == previousByteX && byteZ == previousByteZ;
            if (samePixel) {
                assert lock.x() == previousX : "center x moved inside one map pixel";
                assert lock.z() == previousZ : "center z moved inside one map pixel";
            }
            int blockX = (int) Math.round(RelicMapMath.worldFromIcon(lock.x(), scale, relicByte));
            int blockZ = (int) Math.round(RelicMapMath.worldFromIcon(lock.z(), scale, relicByte));
            if (samePixel && previousBlockX != Integer.MIN_VALUE) {
                assert blockX == previousBlockX && blockZ == previousBlockZ
                        : "relic block hopped while the player icon pixel stayed put";
            }
            previousX = lock.x();
            previousZ = lock.z();
            previousByteX = byteX;
            previousByteZ = byteZ;
            previousBlockX = blockX;
            previousBlockZ = blockZ;
        }
        assert saw;
        assert Math.abs(lock.x() - trueX) < 1.0 : lock.x() + " vs " + trueX;
        assert Math.abs(lock.z() - trueZ) < 1.0 : lock.z() + " vs " + trueZ;
    }

    /** Same truncation vanilla uses in {@code MapItemSavedData.clampMapCoordinate}. */
    private static int vanillaIconByte(double player, double center, int scale) {
        float local = (float) (player - center) / (1 << scale);
        if (local <= -63f) return -128;
        if (local >= 63f) return 127;
        return (int) (local * 2.0 + 0.5);
    }
}
