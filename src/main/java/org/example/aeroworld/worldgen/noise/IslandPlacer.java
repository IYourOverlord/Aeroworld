package org.example.aeroworld.worldgen.noise;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.example.aeroworld.worldgen.cache.ChunkKey;

/**
 * IslandPlacer — deterministic, seed-based island placement grid.
 *
 * Each layer defines a grid cell size (in chunks). For each cell, we check
 * whether an island spawns there, and if so, where its center is within the cell.
 * This ensures minimum spacing between islands and 100% deterministic generation.
 *
 * <p>Координаты центров упакованы в {@code long} через {@link ChunkKey#of(int,int)}:
 * старшие 32 бита — blockX, младшие 32 — blockZ. Нулевые аллокации на горячем пути.</p>
 */
public class IslandPlacer {

    /** Sentinel: ячейка пуста (остров не спавнится). */
    public static final long NO_ISLAND = Long.MIN_VALUE;

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
     * Returns all island centres (packed blockX/blockZ as {@code long} via
     * {@link ChunkKey#of}) that are relevant for the chunk at (chunkX, chunkZ).
     * Includes islands from neighbouring cells whose radius could reach into this chunk.
     *
     * <p>Zero heap allocations on the hot path: {@link LongArrayList} is a primitive
     * {@code long[]} wrapper from fastutil (already on classpath via Minecraft).</p>
     *
     * @param chunkX      chunk coordinate X
     * @param chunkZ      chunk coordinate Z
     * @param searchRadius extra cell radius to check for large islands (usually 1–2)
     */
    public LongArrayList getIslandCentresForChunk(int chunkX, int chunkZ, int searchRadius) {
        LongArrayList result = new LongArrayList();

        int cellX = Math.floorDiv(chunkX, gridSizeChunks);
        int cellZ = Math.floorDiv(chunkZ, gridSizeChunks);

        for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
            for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
                long centre = getCentreForCell(cellX + dcx, cellZ + dcz);
                if (centre != NO_ISLAND) {
                    result.add(centre);
                }
            }
        }
        return result;
    }

    /**
     * Returns the island centre packed as {@code long} ({@link ChunkKey#of(int,int)})
     * for the given grid cell, or {@link #NO_ISLAND} if no island spawns there.
     */
    public long getCentreForCell(int cellX, int cellZ) {
        long hash = hash(cellX, cellZ);

        // Spawn chance check
        double chance = ((hash >>> 1) & 0xFFFFFFL) / (double) 0xFFFFFFL;
        if (chance > spawnChance) return NO_ISLAND;

        // Place centre randomly within the cell, but not right at edges
        int margin  = 2; // chunks from cell edge
        int range   = gridSizeChunks - margin * 2;
        if (range <= 0) range = 1;

        int offsetChunksX = margin + (int)(((hash >> 24) & 0xFFL) % range);
        int offsetChunksZ = margin + (int)(((hash >> 32) & 0xFFL) % range);

        int blockX = (cellX * gridSizeChunks + offsetChunksX) * 16 + 8;
        int blockZ = (cellZ * gridSizeChunks + offsetChunksZ) * 16 + 8;

        return ChunkKey.of(blockX, blockZ);
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
