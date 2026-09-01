package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.noise.IslandShape;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Layer2-специфичная точка входа для генерации Vault/Trial Spawner на островах
 * Layer 2 (Lower Islands).
 *
 * <p>Вся переиспользуемая логика (поиск точки внутри тела острова, постановка
 * блоков, NBT конфигов) находится в {@link IslandVaultTrialGenerator} и не знает
 * о существовании этого класса. Здесь — только то, что специфично для Layer 2:</p>
 * <ul>
 *   <li>выбор {@link VaultTrialSpawnTier} по острову (детерминированный хэш от
 *       координат центра острова + world seed);</li>
 *   <li>вероятности тиров для этого слоя;</li>
 *   <li>ссылка на {@link VaultTrialLootConfig#LAYER_2}.</li>
 * </ul>
 *
 * <p>Для другого слоя достаточно скопировать этот класс, поменять вероятности
 * тиров (если нужно) и {@code LayerNSettings}/{@code LayerNIslandGenerator}
 * ссылки, указав другой {@link VaultTrialLootConfig}.</p>
 */
public final class Layer2VaultTrialPlacer {

    /** Вероятности тиров для Layer 2. В сумме должны давать 1.0. */
    private static final double POOR_CHANCE   = 0.50;
    private static final double MEDIUM_CHANCE = 0.35;
    // RICH_CHANCE — остаток (0.15), вычисляется неявно.

    private static final double NOISE_DEFORM = 18.0; // должно совпадать с LowerIslandGenerator.NOISE_DEFORM

    private final long worldSeed;
    private final ChunkIslandCache sharedChunkCache;

    public Layer2VaultTrialPlacer(long worldSeed, ChunkIslandCache sharedChunkCache) {
        this.worldSeed = worldSeed;
        this.sharedChunkCache = sharedChunkCache;
    }

    /**
     * Вызывается из фазы декорации ({@code applyBiomeDecoration}) для каждого чанка.
     * Обрабатывает только острова, чей центр находится в этом чанке — избегает
     * повторной генерации структур при декорации соседних чанков региона.
     */
    public void placeForChunk(WorldGenLevel region, ChunkAccess chunk,
                               LowerIslandGenerator generator, IslandShape shape) {

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        LongArrayList centres = sharedChunkCache.get(
                LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> generator.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, generator.getSearchRadius()));

        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            int islandBlockX = ChunkKey.x(packed);
            int islandBlockZ = ChunkKey.z(packed);

            if ((islandBlockX >> 4) != chunkX || (islandBlockZ >> 4) != chunkZ) continue;

            IslandData island = generator.getIslandData(islandBlockX, islandBlockZ);
            VaultTrialSpawnTier tier = pickTier(islandBlockX, islandBlockZ);

            RandomSource rng = RandomSource.create(
                    worldSeed
                            ^ ((long) islandBlockX * 341873128712L)
                            ^ ((long) islandBlockZ * 132897987541L)
                            ^ 0xFACE5EEDL);

            IslandVaultTrialGenerator.placeForIsland(
                    region, shape, island, NOISE_DEFORM, tier, VaultTrialLootConfig.LAYER_2, rng,
                    chunkX, chunkZ);

        }
    }

    /**
     * Детерминированный выбор тира по координатам центра острова.
     * Не зависит от порядка генерации чанков — тот же остров всегда получит тот же тир.
     */
    private VaultTrialSpawnTier pickTier(int islandBlockX, int islandBlockZ) {
        long h = worldSeed
                ^ ((long) islandBlockX * 668265263L)
                ^ ((long) islandBlockZ * 341873128712L)
                ^ 0x51ED270B7DBL;
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);

        double roll = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);

        if (roll < POOR_CHANCE) return VaultTrialSpawnTier.POOR;
        if (roll < POOR_CHANCE + MEDIUM_CHANCE) return VaultTrialSpawnTier.MEDIUM;
        return VaultTrialSpawnTier.RICH;
    }
}
