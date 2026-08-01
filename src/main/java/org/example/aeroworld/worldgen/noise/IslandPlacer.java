package org.example.aeroworld.worldgen.noise;

import java.util.ArrayList;
import java.util.List;

/**
 * IslandPlacer — deterministic, seed-based island placement grid.
 *
 * Each layer defines a grid cell size (in chunks). For each cell, we check
 * whether an island spawns there, and if so, where its center is within the cell.
 * This ensures minimum spacing between islands and 100% deterministic generation.
 */
public class IslandPlacer {

    private final long worldSeed;
    private final int  gridSizeChunks;   // minimum spacing in chunks between island centres
    private final double spawnChance;    // 0.0 – 1.0, probability an island spawns in a cell

    /**
     * @param worldSeed       the world seed
     * @param gridSizeChunks  cell size in chunks; islands are at most 1 per cell
     * @param spawnChance     probability [0,1] that a cell actually has an island
     */
    public IslandPlacer(long worldSeed, int gridSizeChunks, double spawnChance) {
        this.worldSeed      = worldSeed;
        this.gridSizeChunks = gridSizeChunks;
        this.spawnChance    = spawnChance;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all island centres (block X/Z) that are relevant for the chunk
     * at (chunkX, chunkZ). Includes islands from neighbouring cells whose radius
     * could reach into this chunk.
     *
     * @param chunkX      chunk coordinate X
     * @param chunkZ      chunk coordinate Z
     * @param searchRadius extra cell radius to check for large islands (usually 1–2)
     */
    public List<int[]> getIslandCentresForChunk(int chunkX, int chunkZ, int searchRadius) {
        List<int[]> result = new ArrayList<>();

        int cellX = Math.floorDiv(chunkX, gridSizeChunks);
        int cellZ = Math.floorDiv(chunkZ, gridSizeChunks);

        for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
            for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
                int cx = cellX + dcx;
                int cz = cellZ + dcz;
                int[] centre = getCentreForCell(cx, cz);
                if (centre != null) {
                    result.add(centre);
                }
            }
        }
        return result;
    }

    /**
     * Returns the island centre block position [blockX, blockZ] for the given
     * grid cell, or null if no island spawns there.
     */
    public int[] getCentreForCell(int cellX, int cellZ) {
        long hash = hash(cellX, cellZ);

        // Spawn chance check
        double chance = ((hash >>> 1) & 0xFFFFFFL) / (double) 0xFFFFFFL;
        if (chance > spawnChance) return null;

        // Place centre randomly within the cell, but not right at edges
        int margin  = 2; // chunks from cell edge
        int range   = gridSizeChunks - margin * 2;
        if (range <= 0) range = 1;

        int offsetChunksX = margin + (int)(((hash >> 24) & 0xFFL) % range);
        int offsetChunksZ = margin + (int)(((hash >> 32) & 0xFFL) % range);

        int blockX = (cellX * gridSizeChunks + offsetChunksX) * 16 + 8;
        int blockZ = (cellZ * gridSizeChunks + offsetChunksZ) * 16 + 8;

        return new int[]{blockX, blockZ};
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Размер ячейки сетки в чанках. Нужен внешнему коду (напр. debug-командам) для спирального поиска. */
    public int gridSizeChunks() { return gridSizeChunks; }

    /** Fast integer hash combining cell coords and world seed. */
    private long hash(int cellX, int cellZ) {
        long h = worldSeed
                ^ ((long) cellX * 341873128712L)
                ^ ((long) cellZ * 132897987541L);
        h = h ^ (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h = h ^ (h >>> 27);
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
