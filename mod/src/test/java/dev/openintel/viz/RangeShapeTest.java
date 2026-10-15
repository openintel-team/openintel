package dev.openintel.viz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RangeShapeTest {
    public static void main(String[] args) throws Exception {
        snitchBlocks();
        bastionBlocks();
        sanctuaryBlocks();
        shells();
        persistence();
        System.out.println("Range shape tests passed");
    }

    private static void snitchBlocks() {
        // 8 blocks out: the 8th block is inside; the 9th is not. Shoulders of that
        // arm stay inside (centers within 8.5), matching the Civ+ checkerboard.
        assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, 8, 64, 0), "snitch +x edge");
        assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, 0, 72, 0), "snitch +y edge");
        assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, 0, 64, 8), "snitch +z edge");
        assertTrue(RangeGeometry.snitchCoversBlock(-10, 64, -10, -18, 64, -10), "snitch negative coords");
        assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, 9, 64, 0), "snitch just past +x");
        assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, 0, 64, 9), "snitch just past +z");
        // Horizontal slice half-widths for |dz| = 0..8: 8,8,8,7,7,6,6,4,2.
        int[] shoulder = {8, 8, 8, 7, 7, 6, 6, 4, 2};
        for (int dz = 0; dz < shoulder.length; dz++) {
            int edge = shoulder[dz];
            assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, edge, 64, dz, 8),
                    "snitch shoulder in " + edge + ",0," + dz);
            assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, edge + 1, 64, dz, 8),
                    "snitch shoulder out " + (edge + 1) + ",0," + dz);
        }
        // 6²+5²+2² = 65 is inside 8.5²; 6²+6²+2² = 76 is just past.
        assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, 6, 69, 2), "snitch diagonal inside");
        assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, 6, 70, 2), "snitch diagonal just past");
        // A smaller configured radius uses the same block-center rule.
        assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, 4, 64, 0, 4), "radius 4 edge");
        assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, 5, 64, 0, 4), "radius 4 just past");
        assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, 0, 64, 0, 0), "radius 0");
        assertFalse(RangeGeometry.snitchCoversBlock(0, 64, 0, 0, 64, 0, Double.NaN), "radius NaN");
        assertEquals(8.0, RangeGeometry.SNITCH_RADIUS);
    }

    private static void bastionBlocks() {
        // Diameter 21 → radius 10.5. Offset 10 is inside; offset 11 is the next block out.
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 10, 64, 0), "bastion edge");
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 11, 64, 0), "bastion just past");
        // 7²+7² = 98 <= 110.25; 8²+7² = 113 just past.
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 7, 64, 7), "bastion diagonal inside");
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 8, 64, 7), "bastion diagonal just past");
        assertEquals(21.0, RangeGeometry.BASTION_DIAMETER);
        assertEquals(10.5, RangeGeometry.BASTION_RADIUS);
        // Below the bastion block is uncovered. At and far above it is covered.
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 0, 63, 0), "below bastion");
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 0, 64, 0), "at bastion Y");
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 0, 64 + RangeGeometry.BASTION_COLUMN_HEIGHT, 0),
                "column height still protected");
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 0, 64 + 1000, 0), "above the drawn column");
        // Configured radius, not only the default.
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 4, 64, 0, 4), "bastion radius 4 edge");
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 5, 64, 0, 4), "bastion radius 4 just past");
    }

    private static void sanctuaryBlocks() {
        assertTrue(RangeGeometry.sanctuaryCoversBlock(100, 200, 50, 150, 200), "sanctuary edge");
        assertFalse(RangeGeometry.sanctuaryCoversBlock(100, 200, 50, 151, 200), "sanctuary just past");
        assertFalse(RangeGeometry.sanctuaryCoversBlock(100, 200, 0, 100, 200), "sanctuary radius 0");
        assertFalse(RangeGeometry.sanctuaryCoversBlock(100, 200, Double.NaN, 100, 200), "sanctuary NaN");
        // Live-map centers are world coordinates, not necessarily a block center.
        assertTrue(RangeGeometry.diskCoversBlock(100.5, 200.5, 50, 150, 200), "disk block center edge");
        assertFalse(RangeGeometry.diskCoversBlock(100.5, 200.5, 50, 151, 200), "disk just past");
    }

    private static void shells() {
        assertTrue(RangeGeometry.snitchShellBlock(0, 64, 0, 8, 64, 0, 8), "edge block is shell");
        assertFalse(RangeGeometry.snitchShellBlock(0, 64, 0, 0, 64, 0, 8), "center is not shell");
        assertFalse(RangeGeometry.snitchShellBlock(0, 64, 0, 9, 64, 0, 8), "outside is not shell");
        // (4,4,4) is covered and fully enclosed by covered neighbors.
        assertTrue(RangeGeometry.snitchCoversBlock(0, 64, 0, 4, 68, 4), "interior covered");
        assertFalse(RangeGeometry.snitchShellBlock(0, 64, 0, 4, 68, 4, 8), "interior not shell");

        int[] shell = RangeGeometry.snitchShellOffsets(8);
        assertTrue(hasTriple(shell, 8, 0, 0), "shell offsets include edge");
        assertFalse(hasTriple(shell, 0, 0, 0), "shell offsets omit center");
        assertFalse(hasTriple(shell, 9, 0, 0), "shell offsets omit outside");
        int shellBlocks = 0;
        int volume = 0;
        for (int x = -8; x <= 8; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -8; z <= 8; z++) {
                    boolean covered = RangeGeometry.snitchCoversBlock(0, 0, 0, x, y, z, 8);
                    if (!covered) {
                        assertFalse(hasTriple(shell, x, y, z), "uncovered in shell " + x + "," + y + "," + z);
                        continue;
                    }
                    volume++;
                    boolean onShell = RangeGeometry.snitchShellBlock(0, 0, 0, x, y, z, 8);
                    if (onShell) shellBlocks++;
                    assertTrue(onShell == hasTriple(shell, x, y, z),
                            "offset list matches shell predicate " + x + "," + y + "," + z);
                }
            }
        }
        assertTrue(volume > 1000, "radius 8 covers a volume");
        assertTrue(shellBlocks > 0 && shellBlocks * 2 < volume, "shell is much smaller than the filled ball");
        assertEquals(shellBlocks, shell.length / 3);

        assertTrue(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, 10, 0), "bastion edge is ring");
        assertFalse(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, 0, 0), "bastion center is not ring");
        assertFalse(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, 11, 0), "bastion outside is not ring");
        int[] ring = RangeGeometry.diskRingOffsets(0.5, 0.5, 10.5);
        assertTrue(hasPair(ring, 10, 0), "ring offsets include edge");
        assertFalse(hasPair(ring, 0, 0), "ring offsets omit center");
        int ringBlocks = 0;
        int disk = 0;
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                boolean covered = RangeGeometry.diskCoversBlock(0.5, 0.5, 10.5, x, z);
                if (!covered) continue;
                disk++;
                if (RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, x, z)) ringBlocks++;
                assertTrue(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, x, z) == hasPair(ring, x, z),
                        "ring list " + x + "," + z);
            }
        }
        assertTrue(ringBlocks > 0 && ringBlocks < disk, "ring is the perimeter, not the disk");
        assertEquals(ringBlocks, ring.length / 2);

        // Negative block center: floor(-0.5) is -1, so block 9 is offset 10 from the base.
        int[] neg = RangeGeometry.diskRingOffsets(-0.5, 0.5, 10.5);
        assertTrue(hasPair(neg, 10, 0), "negative center ring offset");
        assertTrue(RangeGeometry.diskRingBlock(-0.5, 0.5, 10.5, 9, 0), "negative center edge block");
    }

    private static void persistence() throws Exception {
        Path dir = Files.createTempDirectory("openintel-ranges-test");
        try {
            Path file = dir.resolve("openintel-ranges.json");
            RangeShape logging = new RangeShape(RangeType.SNITCH, 10, 64, -20, "minecraft:overworld");
            logging.id = "log-1";
            logging.snitchKind = SnitchKind.LOGGING;
            RangeShape notifier = new RangeShape(RangeType.SNITCH, 1, 2, 3, "minecraft:the_nether");
            notifier.id = "note-1";
            RangeShape bastion = new RangeShape(RangeType.BASTION, 4, 5, 6, "minecraft:overworld");
            bastion.id = "bast-1";
            bastion.enabled = false;
            RangeShape sanctuary = new RangeShape(RangeType.SANCTUARY, 7, 8, 9, "minecraft:overworld");
            sanctuary.id = "sanc-1";
            sanctuary.sanctuaryRadius = 42;

            RangeStore.writeFile(file, List.of(logging, notifier, bastion, sanctuary));
            String json = Files.readString(file);
            assertTrue(json.contains("LOGGING"), "logging kind is written");
            assertTrue(json.contains("log-1"), "id is written");

            List<RangeShape> loaded = RangeStore.readFile(file);
            assertEquals(4, loaded.size());
            assertEquals("log-1", loaded.get(0).id);
            assertEquals(SnitchKind.LOGGING, loaded.get(0).snitchKind);
            assertEquals(RangeType.SNITCH, loaded.get(0).type);
            assertEquals(10, loaded.get(0).x);
            assertEquals(64, loaded.get(0).y);
            assertEquals(-20, loaded.get(0).z);
            assertTrue(loaded.get(0).enabled, "logging snitch stays enabled");
            assertEquals(SnitchKind.NOTIFIER, loaded.get(1).snitchKind);
            assertEquals("minecraft:the_nether", loaded.get(1).dimension);
            assertEquals(RangeType.BASTION, loaded.get(2).type);
            assertFalse(loaded.get(2).enabled, "disabled bastion stays disabled");
            assertEquals(42.0, loaded.get(3).sanctuaryRadius);
            // A second read is a fresh parse, same as a client restart.
            List<RangeShape> again = RangeStore.readFile(file);
            assertEquals(SnitchKind.LOGGING, again.get(0).snitchKind);
            assertEquals(4, again.size());

            Files.writeString(file, """
                    {
                      "shapes": [
                        {
                          "id": "old",
                          "type": "SNITCH",
                          "x": 3,
                          "y": 4,
                          "z": 5,
                          "dimension": "minecraft:overworld",
                          "enabled": true,
                          "sanctuaryRadius": 50
                        }
                      ]
                    }
                    """);
            List<RangeShape> legacy = RangeStore.readFile(file);
            assertEquals(1, legacy.size());
            assertEquals("old", legacy.get(0).id);
            assertEquals(SnitchKind.NOTIFIER, legacy.get(0).snitchKind);
            assertEquals(3, legacy.get(0).x);

            RangeShape bare = new RangeShape(RangeType.SNITCH, 9, 9, 9, "minecraft:overworld");
            bare.id = "bare";
            bare.snitchKind = null;
            RangeStore.writeFile(file, List.of(bare));
            assertEquals(SnitchKind.NOTIFIER, RangeStore.readFile(file).get(0).snitchKind);

            List<RangeShape> many = new ArrayList<>();
            for (int i = 0; i < RangeStore.MAX_SHAPES + 4; i++) {
                RangeShape s = new RangeShape(RangeType.SNITCH, i, 1, 2, "minecraft:overworld");
                s.id = "n" + i;
                many.add(s);
            }
            RangeStore.writeFile(file, many);
            assertEquals(RangeStore.MAX_SHAPES, RangeStore.readFile(file).size());
            assertEquals("n0", RangeStore.readFile(file).get(0).id);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static boolean hasTriple(int[] data, int x, int y, int z) {
        for (int i = 0; i + 2 < data.length; i += 3) {
            if (data[i] == x && data[i + 1] == y && data[i + 2] == z) return true;
        }
        return false;
    }

    private static boolean hasPair(int[] data, int x, int z) {
        for (int i = 0; i + 1 < data.length; i += 2) {
            if (data[i] == x && data[i + 1] == z) return true;
        }
        return false;
    }

    private static void assertTrue(boolean v, String msg) {
        if (!v) throw new AssertionError(msg);
    }

    private static void assertFalse(boolean v, String msg) {
        if (v) throw new AssertionError(msg);
    }

    private static void assertEquals(double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            throw new AssertionError(expected + " != " + actual);
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(expected + " != " + actual);
        }
    }
}
