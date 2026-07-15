package org.example.aeroworld.worldgen.layer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.noise.IslandShape;

import java.util.List;

/**
 * Layer2StructurePlacer — регистрирует позиции для структур на островах Layer 2
 * через {@link org.example.aeroworld.structure.IslandStructureScheduler}.
 *
 */
public final class Layer2StructurePlacer {

    /** Id структуры-танка в PhysicalStructures. Без суффикса .nbt и без пути structures/. */
    private static final ResourceLocation TANK_ID =
            ResourceLocation.fromNamespaceAndPath("physical_structures", "tank_11");

    private final ChunkIslandCache sharedChunkCache;

    public Layer2StructurePlacer(long worldSeed) {
        this(worldSeed, new ChunkIslandCache());
    }

    public Layer2StructurePlacer(long worldSeed, ChunkIslandCache sharedChunkCache) {
        this.sharedChunkCache = sharedChunkCache;
    }

    /**
     * Для каждого острова в чанке ставит XZ-центр в очередь планировщика.
     *
     * <p>Y <b>намеренно не вычисляется</b> — см. описание класса.</p>
     *
     * @param chunk     генерируемый чанк
     * @param generator генератор нижних островов (для получения списка центров)
     * @param rng       источник случайных чисел (не используется в текущей реализации,
     *                  оставлен для совместимости с сигнатурой вызова из ChunkGenerator)
     */
    public void placeForChunk(ChunkAccess chunk,
                              LowerIslandGenerator generator,
                              RandomSource rng) {

        if (AeroWorld.structureScheduler == null) return;

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        List<int[]> centres = sharedChunkCache.get(
                LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> generator.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, 1));

        for (int[] centre : centres) {
            int islandBlockX = centre[0];
            int islandBlockZ = centre[1];

            // Обрабатываем только острова, чей центр находится в текущем чанке,
            // чтобы избежать двойной регистрации при обработке соседних чанков.
            if ((islandBlockX >> 4) != chunkX || (islandBlockZ >> 4) != chunkZ) continue;

            AeroWorld.structureScheduler.enqueue(islandBlockX, islandBlockZ, TANK_ID);

            AeroWorld.LOGGER.debug(
                    "[AeroWorld] Layer2StructurePlacer: scheduled '{}' for island centre ({},{}).",
                    TANK_ID, islandBlockX, islandBlockZ);
        }
    }
}