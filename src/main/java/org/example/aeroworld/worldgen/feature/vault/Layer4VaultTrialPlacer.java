package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

import java.util.List;

/**
 * Layer4-специфичная точка входа для генерации Vault/Trial Spawner на островах
 * Layer 4 (Upper Sky Islands, медузы — купол + щупальца).
 *
 * <p>Копия {@link Layer3VaultTrialPlacer} по структуре, но:</p>
 * <ul>
 *   <li>вызывает {@link IslandVaultTrialGenerator#placeForJellyfishIsland}, а не
 *       {@code placeForIsland}/{@code placeForEllipsoidIsland} — у Layer 4
 *       структуры ставятся только на купол острова, не в произвольную точку
 *       тела (щупальца слишком тонкие, см. javadoc
 *       {@link IslandVaultTrialGenerator#placeForJellyfishIsland});</li>
 *   <li>использует {@link VaultTrialLootConfig#LAYER_4} (алмаз/изумруд/лазурит
 *       вместо золота/редстоуна/железа у Layer 3).</li>
 * </ul>
 *
 * <p>Вероятности тиров и список мобов оставлены такими же, как у Layer 2/3 —
 * см. {@link VaultTrialLootConfig#LAYER_4}.</p>
 */
public final class Layer4VaultTrialPlacer {

    /** Вероятности тиров для Layer 4. Те же, что и у Layer 2/3. В сумме дают 1.0. */
    private static final double POOR_CHANCE   = 0.50;
    private static final double MEDIUM_CHANCE = 0.35;
    // RICH_CHANCE — остаток (0.15), вычисляется неявно.

    /** Отдельная соль для RNG постановки блоков — отличает Layer 4 от Layer 2/3 при одинаковых координатах острова. */
    private static final long L4_RNG_SALT = 0x2468BDF135790CEL;

    private final long worldSeed;
    private final ChunkIslandCache sharedChunkCache;

    public Layer4VaultTrialPlacer(long worldSeed, ChunkIslandCache sharedChunkCache) {
        this.worldSeed = worldSeed;
        this.sharedChunkCache = sharedChunkCache;
    }

    /**
     * Вызывается из фазы декорации ({@code applyBiomeDecoration}) для каждого чанка.
     * Обрабатывает только острова, чей центр находится в этом чанке — избегает
     * повторной генерации структур при декорации соседних чанков региона.
     */
    public void placeForChunk(WorldGenLevel region, ChunkAccess chunk,
                               UpperIslandGenerator generator) {

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        List<int[]> centres = sharedChunkCache.get(
                UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> generator.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, generator.getSearchRadius()));

        for (int[] centre : centres) {
            int islandBlockX = centre[0];
            int islandBlockZ = centre[1];

            if ((islandBlockX >> 4) != chunkX || (islandBlockZ >> 4) != chunkZ) continue;

            IslandData island = generator.getIslandData(islandBlockX, islandBlockZ);
            VaultTrialSpawnTier tier = pickTier(islandBlockX, islandBlockZ);

            RandomSource rng = RandomSource.create(
                    worldSeed
                            ^ ((long) islandBlockX * 341873128712L)
                            ^ ((long) islandBlockZ * 132897987541L)
                            ^ 0xFACE5EEDL
                            ^ L4_RNG_SALT); // отличается от Layer2/3, чтобы не совпасть детерминированно

            IslandVaultTrialGenerator.placeForJellyfishIsland(
                    region, generator, island, tier, VaultTrialLootConfig.LAYER_4, rng,
                    chunkX, chunkZ);

            AeroWorld.LOGGER.debug(
                    "[AeroWorld] Layer4VaultTrialPlacer: island ({},{}) got tier {}.",
                    islandBlockX, islandBlockZ, tier);
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
                ^ L4_RNG_SALT;
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);

        double roll = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);

        if (roll < POOR_CHANCE) return VaultTrialSpawnTier.POOR;
        if (roll < POOR_CHANCE + MEDIUM_CHANCE) return VaultTrialSpawnTier.MEDIUM;
        return VaultTrialSpawnTier.RICH;
    }
}
