package org.example.aeroworld.worldgen.layer;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;

import java.util.List;

/**
 * Layer3StructurePlacer — регистрирует позиции для структур на островах Layer 3
 * (High Sky Islands, Y 1000–1100) через {@link org.example.aeroworld.structure.IslandStructureScheduler}.
 *
 * <p>Паттерн идентичен {@link Layer2StructurePlacer}: XZ-центры ставятся в очередь
 * во время worldgen, реальный Y ищется позже в ProximityTriggerHandler на server-thread.</p>
 */
public final class Layer3StructurePlacer {

    /**
     * Id структуры HAUL-01.
     *
     * <p><b>Важно:</b> namespace {@code excraft} — это НЕ id из
     * {@code PhysicalStructureRegistry}. HAUL-01.excraft — снимок Sable
     * sub-level'а (формат Toolgun'а), а не ванильная NBT-структура, поэтому
     * его размещением занимается {@code StructureSourceProviderRegistry}
     * (провайдер {@code physical_structures:excraft_toolgun}), которая ищет
     * файл по пути {@code <gamedir>/blueprints/HAUL-01.excraft} — путь берётся
     * из {@code id.getPath()}, поэтому путь ({@code "HAUL-01"}) должен точно
     * совпадать с именем файла без расширения.</p>
     */
    private static final ResourceLocation HAUL_01_ID =
            ResourceLocation.fromNamespaceAndPath("excraft", "HAUL-01");

    private final ChunkIslandCache sharedChunkCache;

    public Layer3StructurePlacer(long worldSeed, ChunkIslandCache sharedChunkCache) {
        this.sharedChunkCache = sharedChunkCache;
    }

    /**
     * Для каждого острова Layer 3 в чанке ставит XZ-центр в очередь планировщика.
     *
     * @param chunk         генерируемый чанк
     * @param generator     генератор высоких островов (для получения списка центров)
     * @param rng           источник случайных чисел (зарезервирован для будущего
     *                      вероятностного спавна, сейчас не используется)
     */
    public void placeForChunk(ChunkAccess chunk,
                              HighIslandGenerator generator,
                              RandomSource rng) {

        if (AeroWorld.structureScheduler == null) return;

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        LongArrayList centres = sharedChunkCache.get(
                HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> generator.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, generator.getSearchRadius()));

        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            int islandBlockX = ChunkKey.x(packed);
            int islandBlockZ = ChunkKey.z(packed);

            // Обрабатываем только острова, чей центр находится в текущем чанке,
            // чтобы избежать двойной регистрации при обработке соседних чанков.
            if ((islandBlockX >> 4) != chunkX || (islandBlockZ >> 4) != chunkZ) continue;

            AeroWorld.structureScheduler.enqueue(islandBlockX, islandBlockZ, HAUL_01_ID);

            AeroWorld.LOGGER.debug(
                    "[AeroWorld] Layer3StructurePlacer: scheduled '{}' for island centre ({},{}).",
                    HAUL_01_ID, islandBlockX, islandBlockZ);
        }
    }
}