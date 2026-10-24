package dev.openintel.gui;

import dev.openintel.gui.VisualizationsScreen.EntryKind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.Arrays;

/**
 * Nearest already-loaded block for a coverage entry. Callers pass only chunks
 * the client has; this search never asks the server for more.
 */
public final class NearbyBlockSearch {
    public enum Material {
        OBSIDIAN("obsidian"), SPONGE("sponge"), BEACON("beacon");
        public final String noun;
        Material(String noun) { this.noun = noun; }
    }

    public record Hit(int x, int y, int z) {}

    /** Loaded chunk data. {@link #matches} is only called inside sections that may match. */
    public interface World {
        int chunkRadius();
        int minSectionY();
        int sectionCount();
        boolean loaded(int chunkX, int chunkZ);
        boolean sectionMayMatch(int chunkX, int chunkZ, int sectionIndex, Material material);
        boolean matches(int x, int y, int z, Material material);
    }

    private NearbyBlockSearch() {}

    public static Material material(EntryKind kind) {
        return switch (kind) {
            case BASTION -> Material.SPONGE;
            case SANCTUARY -> Material.BEACON;
            case SNITCH, LOGGING -> Material.OBSIDIAN;
        };
    }

    /** Block ids shared by the client scan. Wet sponge counts as a bastion block. */
    public static boolean matchesId(Material material, String blockId) {
        return switch (material) {
            case OBSIDIAN -> "minecraft:obsidian".equals(blockId);
            case SPONGE -> "minecraft:sponge".equals(blockId) || "minecraft:wet_sponge".equals(blockId);
            case BEACON -> "minecraft:beacon".equals(blockId);
        };
    }

    static boolean matchesBlock(Material material, Block block) {
        return switch (material) {
            case OBSIDIAN -> block == Blocks.OBSIDIAN;
            case SPONGE -> block == Blocks.SPONGE || block == Blocks.WET_SPONGE;
            case BEACON -> block == Blocks.BEACON;
        };
    }

    /** Nearest matching block to the origin, or null when none are loaded in range. */
    public static Hit nearest(int ox, int oy, int oz, Material material, World world) {
        int radius = Math.min(64, Math.max(0, world.chunkRadius()));
        int originChunkX = ox >> 4, originChunkZ = oz >> 4;
        int span = radius * 2 + 1;
        int[] chunkX = new int[span * span];
        int[] chunkZ = new int[span * span];
        int count = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int cx = originChunkX + dx, cz = originChunkZ + dz;
                if (!world.loaded(cx, cz)) continue;
                chunkX[count] = cx;
                chunkZ[count] = cz;
                count++;
            }
        }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> compareChunks(ox, oz, chunkX[a], chunkZ[a], chunkX[b], chunkZ[b]));
        int minSection = world.minSectionY();
        int sections = world.sectionCount();
        long best = Long.MAX_VALUE;
        int bx = 0, by = 0, bz = 0;
        boolean found = false;
        for (int index : order) {
            int cx = chunkX[index], cz = chunkZ[index];
            long column = horizontal(ox, oz, cx, cz);
            if (found && column > best) continue;
            int baseX = cx << 4, baseZ = cz << 4;
            for (int section = 0; section < sections; section++) {
                int baseY = (minSection + section) << 4;
                long box = boxDistance(ox, oy, oz, baseX, baseY, baseZ);
                if (found && box > best) continue;
                if (!world.sectionMayMatch(cx, cz, section, material)) continue;
                for (int y = 0; y < 16; y++) {
                    int wy = baseY + y;
                    for (int z = 0; z < 16; z++) {
                        int wz = baseZ + z;
                        for (int x = 0; x < 16; x++) {
                            int wx = baseX + x;
                            if (!world.matches(wx, wy, wz, material)) continue;
                            long distance = distance(ox, oy, oz, wx, wy, wz);
                            if (!found || better(distance, wx, wy, wz, best, bx, by, bz)) {
                                best = distance;
                                bx = wx;
                                by = wy;
                                bz = wz;
                                found = true;
                            }
                        }
                    }
                }
            }
        }
        return found ? new Hit(bx, by, bz) : null;
    }

    private static int compareChunks(int ox, int oz, int ax, int az, int bx, int bz) {
        int cmp = Long.compare(horizontal(ox, oz, ax, az), horizontal(ox, oz, bx, bz));
        if (cmp != 0) return cmp;
        cmp = Integer.compare(ax, bx);
        return cmp != 0 ? cmp : Integer.compare(az, bz);
    }

    private static boolean better(long distance, int x, int y, int z, long best, int bx, int by, int bz) {
        if (distance < best) return true;
        if (distance > best) return false;
        if (y != by) return y < by;
        if (x != bx) return x < bx;
        return z < bz;
    }

    private static long horizontal(int ox, int oz, int chunkX, int chunkZ) {
        return boxDistance(ox, 0, oz, chunkX << 4, 0, chunkZ << 4);
    }

    /** Minimum squared distance from the point to the 16×16×16 block box, ignoring Y when the box Y is 0. */
    private static long boxDistance(int px, int py, int pz, int x0, int y0, int z0) {
        long dx = axis(px, x0, x0 + 15);
        long dy = axis(py, y0, y0 + 15);
        long dz = axis(pz, z0, z0 + 15);
        return dx * dx + dy * dy + dz * dz;
    }

    private static long axis(int value, int min, int max) {
        if (value < min) return (long) min - value;
        if (value > max) return (long) value - max;
        return 0;
    }

    private static long distance(int ox, int oy, int oz, int x, int y, int z) {
        long dx = (long) x - ox, dy = (long) y - oy, dz = (long) z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Reads blocks the server has already sent. Missing chunks are skipped. */
    public static final class ClientWorld implements World {
        private final Minecraft minecraft;
        public ClientWorld(Minecraft minecraft) { this.minecraft = minecraft; }
        @Override public int chunkRadius() { return Math.max(0, minecraft.options.getEffectiveRenderDistance()); }
        @Override public int minSectionY() { return minecraft.level.getMinSectionY(); }
        @Override public int sectionCount() { return minecraft.level.getSectionsCount(); }
        @Override public boolean loaded(int chunkX, int chunkZ) { return minecraft.level.hasChunk(chunkX, chunkZ); }
        @Override public boolean sectionMayMatch(int chunkX, int chunkZ, int sectionIndex, Material material) {
            LevelChunkSection section = section(chunkX, chunkZ, sectionIndex);
            return section != null && !section.hasOnlyAir() && section.maybeHas(state -> matchesBlock(material, state.getBlock()));
        }
        @Override public boolean matches(int x, int y, int z, Material material) {
            LevelChunk chunk = chunk(x >> 4, z >> 4);
            if (chunk == null) return false;
            int index = minecraft.level.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (index < 0 || index >= sections.length || sections[index] == null) return false;
            BlockState state = sections[index].getBlockState(x & 15, y & 15, z & 15);
            return matchesBlock(material, state.getBlock());
        }
        private LevelChunkSection section(int chunkX, int chunkZ, int sectionIndex) {
            LevelChunk chunk = chunk(chunkX, chunkZ);
            if (chunk == null) return null;
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
            return sections[sectionIndex];
        }
        private LevelChunk chunk(int chunkX, int chunkZ) {
            if (!loaded(chunkX, chunkZ)) return null;
            var loadedChunk = minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return loadedChunk instanceof LevelChunk chunk ? chunk : null;
        }
    }
}
