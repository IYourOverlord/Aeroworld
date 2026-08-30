package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Layer3-специфичная точка входа для генерации Vault/Trial Spawner на островах
 * Layer 3 (High Sky Islands, эллипсоиды).
 *
 * <p>Копия {@link Layer2VaultTrialPlacer} по структуре, но:</p>
 * <ul>
 *   <li>вызывает {@link IslandVaultTrialGenerator#placeForEllipsoidIsland}, а не
 *       {@link IslandVaultTrialGenerator#placeForIsland} — у Layer 3 нет
 *       {@code IslandShape}, геометрия острова эллипсоидная
 *       ({@code IslandData.ellipsoidAxes}, см. {@code HighIslandGenerator.fillChunk});</li>
 *   <li>не передаёт {@code noiseDeform} отдельным параметром — эллипсоидные
 *       методы {@code HighIslandGenerator} ({@code getEllipsoidTopY}/
 *       {@code getEllipsoidBottomY}/{@code computeXZSq}) уже инкапсулируют
 *       собственный {@code edgeNoise}/{@code noiseDeform} внутри генератора;</li>
 *   <li>использует {@link VaultTrialLootConfig#LAYER_3} (золото/редстоун/железо
 *       вместо меди/железа/угля).</li>
 * </ul>
 *
 * <p>Вероятности тиров и список мобов оставлены такими же, как у Layer 2 —
 * см. {@link VaultTrialLootConfig#LAYER_3}.</p>
 */
public final class Layer3VaultTrialPlacer {

    /** Вероятности тиров для Layer 3. Те же, что и у Layer 2. В сумме дают 1.0. */
    private static final double POOR_CHANCE   = 0.50;
    private static final double MEDIUM_CHANCE = 0.35;
    // RICH_CHANCE — остаток (0.15), вычисляется неявно.

    /** Отдельная соль для RNG постановки блоков — отличает Layer 3 от Layer 2 при одинаковых координатах острова. */
    private static final long L3_RNG_SALT = 0x1357ACE9BEEF01L;

    private final long worldSeed;
    private final ChunkIslandCache sharedChunkCache;

    public Layer3VaultTrialPlacer(long worldSeed, ChunkIslandCache sharedChunkCache) {
        this.worldSeed = worldSeed;
        this.sharedChunkCache = sharedChunkCache;
    }

    /**
     * Вызывается из фазы декорации ({@code applyBiomeDecoration}) для каждого чанка.
     * Обрабатывает только острова, чей центр находится в этом чанке — избегает
     * повторной генерации структур при декорации соседних чанков региона.
     */
    public void placeForChunk(WorldGenLevel region, ChunkAccess chunk,
                               HighIslandGenerator generator) {

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        LongArrayList centres = sharedChunkCache.get(
                HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
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
                            ^ 0xFACE5EEDL
                            ^ L3_RNG_SALT); // отличается от Layer2, чтобы не совпасть детерминированно

            IslandVaultTrialGenerator.placeForEllipsoidIsland(
                    region, generator, island, tier, VaultTrialLootConfig.LAYER_3, rng,
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
                ^ 0x51ED270B7DBL
                ^ L3_RNG_SALT;
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);

        double roll = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);

        if (roll < POOR_CHANCE) return VaultTrialSpawnTier.POOR;
        if (roll < POOR_CHANCE + MEDIUM_CHANCE) return VaultTrialSpawnTier.MEDIUM;
        return VaultTrialSpawnTier.RICH;
    }
}
