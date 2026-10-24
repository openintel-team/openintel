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
        bastionRadiusMigration();
        sanctuaryBlocks();
        shells();
        renderedShells();
        persistence();
        RangeGroupTest.run();
        dev.openintel.gui.VisualizationsInteractionTest.run();
        System.out.println("Range shape tests passed");
    }

    private static void renderedShells() {
        var mesh = new dev.openintel.render.WorldOverlayMesh.Builder();
        var eye = new net.minecraft.world.phys.Vec3(0.5, 64.5, 0.5);
        RangeOverlay.drawSnitch(mesh, 0, 64, 0, 8, -1, 0x3300FFFF, eye, true);
        var snitch = mesh.build();
        assertFalse(snitch.faces().isEmpty(), "Actual snitch renderer emits a shell");
        assertEquals(1350, snitch.faceCount());
        assertEquals(2700, snitch.lineCount());
        for (var f : snitch.faces()) {
            assertTrue(f.onTop(), "See-through survives geometry collection");
            for (var v : new net.minecraft.world.phys.Vec3[]{f.a(), f.b(), f.c(), f.d()}) {
                assertEquals(Math.rint(v.x), v.x);
                assertEquals(Math.rint(v.y), v.y);
                assertEquals(Math.rint(v.z), v.z);
            }
        }
        mesh = new dev.openintel.render.WorldOverlayMesh.Builder();
        RangeOverlay.drawBastion(mesh, 0, 64, 0, 10.5, eye, false);
        var bastion = mesh.build();
        assertFalse(bastion.faces().isEmpty(), "Actual bastion renderer emits a disk");
        for (var f : bastion.faces()) {
            assertFalse(f.onTop(), "Depth-tested coverage stays depth-tested");
            for (var v : new net.minecraft.world.phys.Vec3[]{f.a(), f.b(), f.c(), f.d()}) {
                assertTrue(v.y >= 64 && v.y <= 65, "No coverage below the bastion block");
                assertTrue(v.x >= -9 && v.x <= 10, "Bastion field is 19 blocks across");
                assertTrue(v.z >= -9 && v.z <= 10, "Bastion field is 19 blocks across");
            }
        }
        assertBastionCenterPlus(bastion, 0, 64, 0);
        mesh = new dev.openintel.render.WorldOverlayMesh.Builder();
        RangeOverlay.drawBastion(mesh, 20, 70, -4, 8, eye, true);
        assertBastionCenterPlus(mesh.build(), 20, 70, -4);
        mesh = new dev.openintel.render.WorldOverlayMesh.Builder();
        RangeOverlay.drawSnitch(mesh, 1000, 64, 0, 8, -1, -1, eye, true);
        assertTrue(mesh.build().faces().isEmpty(), "Distant shapes stay culled");
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
        // Civ+ field, 19 blocks across. Half-widths from the axis: 9,9,9,9,9,8,7,7,5,4.
        // Blocks at distance exactly 10 — (10,0), (8,6), (6,8) — are outside.
        int[] shoulder = {9, 9, 9, 9, 9, 8, 7, 7, 5, 4};
        for (int dz = 0; dz < shoulder.length; dz++) {
            int edge = shoulder[dz];
            assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, edge, 64, dz),
                    "bastion shoulder in " + edge + ",0," + dz);
            assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, edge + 1, 64, dz),
                    "bastion shoulder out " + (edge + 1) + ",0," + dz);
            assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, -edge, 64, -dz),
                    "bastion shoulder mirrors");
        }
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 10, 64, 0), "distance 10 axis is outside");
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 8, 64, 6), "distance 10 shoulder is outside");
        assertFalse(RangeGeometry.bastionCoversBlock(0, 64, 0, 6, 64, 8), "distance 10 shoulder is outside");
        assertTrue(RangeGeometry.bastionCoversBlock(0, 64, 0, 7, 64, 7), "7,7 is inside");
        assertEquals(19.0, RangeGeometry.BASTION_DIAMETER);
        assertEquals(10.5, RangeGeometry.BASTION_RADIUS);
        assertTrue(RangeGeometry.bastionCoversBlock(-10, 64, -10, -19, 64, -10), "negative bastion edge");
        assertFalse(RangeGeometry.bastionCoversBlock(-10, 64, -10, -20, 64, -10), "negative bastion just past");
        assertTrue(RangeGeometry.bastionRingBlock(0, 0, 9, 0, RangeGeometry.BASTION_RADIUS), "civ edge is ring");
        assertFalse(RangeGeometry.bastionRingBlock(0, 0, 0, 0, RangeGeometry.BASTION_RADIUS), "civ center is not ring");
        assertFalse(RangeGeometry.bastionRingBlock(0, 0, 10, 0, RangeGeometry.BASTION_RADIUS), "distance 10 is not ring");
        int[] ring = RangeGeometry.bastionRingOffsets(RangeGeometry.BASTION_RADIUS);
        assertTrue(hasPair(ring, 9, 0), "civ ring includes the axis edge");
        assertFalse(hasPair(ring, 10, 0), "civ ring omits distance 10");
        assertFalse(hasPair(ring, 0, 0), "civ ring omits the center");
        int ringBlocks = 0;
        int disk = 0;
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                boolean covered = RangeGeometry.bastionDiskCovers(0, 0, x, z, RangeGeometry.BASTION_RADIUS);
                if (!covered) {
                    assertFalse(hasPair(ring, x, z), "uncovered civ block in ring " + x + "," + z);
                    continue;
                }
                disk++;
                boolean onRing = RangeGeometry.bastionRingBlock(0, 0, x, z, RangeGeometry.BASTION_RADIUS);
                if (onRing) ringBlocks++;
                assertTrue(onRing == hasPair(ring, x, z), "civ ring list " + x + "," + z);
            }
        }
        assertEquals(305, disk);
        assertTrue(ringBlocks > 0 && ringBlocks < disk, "civ ring is the perimeter");
        assertEquals(ringBlocks, ring.length / 2);
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

    private static void bastionRadiusMigration() {
        var fresh = new dev.openintel.config.OIConfig();
        dev.openintel.config.OIConfig.applyBastionRadiusDefault(fresh, false, false);
        assertEquals(10.5, fresh.vizBastionRadius);
        assertFalse(fresh.vizBastionRadiusEdited, "A new config uses the Civ+ field");
        assertFalse(fresh.vizSeeThrough, "See-through wireframes start off");

        var legacy = new dev.openintel.config.OIConfig();
        legacy.vizBastionRadius = 10.5;
        dev.openintel.config.OIConfig.applyBastionRadiusDefault(legacy, true, false);
        assertEquals(10.5, legacy.vizBastionRadius);
        assertFalse(legacy.vizBastionRadiusEdited, "An unedited saved 10.5 stays the Civ+ field");

        var custom = new dev.openintel.config.OIConfig();
        custom.vizBastionRadius = 12;
        dev.openintel.config.OIConfig.applyBastionRadiusDefault(custom, true, false);
        assertEquals(12.0, custom.vizBastionRadius);
        assertTrue(custom.vizBastionRadiusEdited, "A radius the player changed is kept");

        var unedited = new dev.openintel.config.OIConfig();
        unedited.vizBastionRadius = 14;
        unedited.vizBastionRadiusEdited = false;
        dev.openintel.config.OIConfig.applyBastionRadiusDefault(unedited, true, true);
        assertEquals(10.5, unedited.vizBastionRadius);
        assertFalse(unedited.vizBastionRadiusEdited, "An unedited setting is put back on Civ+");

        var kept = new dev.openintel.config.OIConfig();
        kept.vizBastionRadius = 12;
        kept.vizBastionRadiusEdited = true;
        dev.openintel.config.OIConfig.applyBastionRadiusDefault(kept, true, true);
        assertEquals(12.0, kept.vizBastionRadius);
        assertTrue(kept.vizBastionRadiusEdited, "An edited radius survives the next launch");
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

        assertTrue(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, 10, 0), "disk edge is ring");
        assertFalse(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, 0, 0), "disk center is not ring");
        assertFalse(RangeGeometry.diskRingBlock(0.5, 0.5, 10.5, 11, 0), "disk outside is not ring");
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

            // The world overlay rebuilds only when this revision moves.
            RangeStore store = new RangeStore(dir.resolve("revision.json"));
            long revision = store.revision();
            store.add(new RangeShape(RangeType.SNITCH, 0, 64, 0, "minecraft:overworld"));
            assertTrue(store.revision() != revision, "adding a shape changes the revision");
            revision = store.revision();
            String id = store.shapes().get(0).id;
            store.mutate(id, s -> s.enabled = false);
            assertTrue(store.revision() != revision, "editing a shape changes the revision");
            revision = store.revision();
            store.toggleGroup(store.createGroup("Layer").id);
            assertTrue(store.revision() != revision, "group visibility changes the revision");
            revision = store.revision();
            store.shapes();
            store.visibleShapes();
            assertEquals(revision, store.revision());

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
            for (int i = 0; i < 4096; i++) {
                RangeShape s = new RangeShape(RangeType.SNITCH, i, 1, 2, "minecraft:overworld");
                s.id = "n" + i;
                many.add(s);
            }
            RangeStore.writeFile(file, many);
            assertEquals(4096, RangeStore.readFile(file).size());
            assertEquals("n0", RangeStore.readFile(file).get(0).id);
            Path export = dir.resolve("export.json");
            RangeStore.exportFile(export, List.of(logging, notifier, bastion, sanctuary));
            List<RangeShape> imported = RangeStore.readImport(export);
            assertEquals(4, imported.size());
            assertEquals(SnitchKind.LOGGING, imported.get(0).snitchKind);
            assertEquals(42.0, imported.get(3).sanctuaryRadius);
            assertFalse(imported.get(2).enabled, "Export preserves visibility");
            List<RangeShape> merged = new ArrayList<>(List.of(logging));
            assertEquals(3, RangeStore.merge(merged, imported));
            assertEquals(0, RangeStore.merge(merged, RangeStore.readImport(export)));
            assertEquals(4, merged.size());
            RangeShape collision = new RangeShape(RangeType.BASTION, 90, 80, 70, "minecraft:overworld");
            collision.id = logging.id;
            assertEquals(1, RangeStore.merge(merged, List.of(collision)));
            assertFalse(collision.id.equals(logging.id), "ID collisions get independent IDs");
            for (String invalid : List.of("{}", "{\"version\":3,\"shapes\":[]}",
                    "{\"shapes\":[{\"type\":\"BOGUS\",\"x\":0,\"y\":0,\"z\":0,\"dimension\":\"minecraft:overworld\"}]}",
                    "{\"shapes\":[{\"type\":\"SNITCH\",\"x\":0.5,\"y\":0,\"z\":0,\"dimension\":\"minecraft:overworld\"}]}")) {
                Files.writeString(file, invalid);
                boolean rejected = false;
                try { RangeStore.readImport(file); } catch (java.io.IOException expected) { rejected = true; }
                assertTrue(rejected, "Invalid import must fail: " + invalid);
            }
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

    /**
     * The four blocks beside the bastion are outlined on the disk layer.
     * Their inner walls leave the bastion block open. Diagonals and the layer above are unmarked.
     */
    private static void assertBastionCenterPlus(dev.openintel.render.WorldOverlayMesh mesh, int x, int y, int z) {
        for (int[] arm : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int ax = x + arm[0], az = z + arm[1];
            assertEquals(6, facesOnBlock(mesh, ax, y, az));
            assertFalse(hasFaceCenter(mesh, ax + 0.5, y + 1.5, az + 0.5), "plus stays on the first layer");
            assertFalse(hasFaceCenter(mesh, ax + 0.5, y + 2, az + 0.5), "plus stays on the first layer");
        }
        assertFalse(hasFaceCenter(mesh, x + 0.5, y, z + 0.5), "bastion block has no floor");
        assertFalse(hasFaceCenter(mesh, x + 0.5, y + 1, z + 0.5), "bastion block has no cap");
        assertEquals(4, facesOnBlock(mesh, x, y, z));
        assertFalse(hasFaceCenter(mesh, x + 1.5, y, z + 1.5), "diagonal stays open");
        assertFalse(hasFaceCenter(mesh, x + 1.5, y + 1, z + 1.5), "diagonal stays open");
    }

    private static boolean hasFaceCenter(dev.openintel.render.WorldOverlayMesh mesh, double x, double y, double z) {
        for (var f : mesh.faces()) {
            var c = f.center();
            if (c.x == x && c.y == y && c.z == z) return true;
        }
        return false;
    }

    /** Faces whose center sits on one side of this block. */
    private static int facesOnBlock(dev.openintel.render.WorldOverlayMesh mesh, int x, int y, int z) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        int n = 0;
        for (var f : mesh.faces()) {
            var c = f.center();
            double dx = Math.abs(c.x - cx), dy = Math.abs(c.y - cy), dz = Math.abs(c.z - cz);
            if ((dx == 0.5 && dy == 0 && dz == 0)
                    || (dy == 0.5 && dx == 0 && dz == 0)
                    || (dz == 0.5 && dx == 0 && dy == 0)) n++;
        }
        return n;
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
