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
    private final IslandVaultTrialCache sharedVaultTrialCache;

    public Layer3VaultTrialPlacer(long worldSeed, ChunkIslandCache sharedChunkCache,
                                   IslandVaultTrialCache sharedVaultTrialCache) {
        this.worldSeed = worldSeed;
        this.sharedChunkCache = sharedChunkCache;
        this.sharedVaultTrialCache = sharedVaultTrialCache;
    }

    /**
     * Вызывается из фазы декорации ({@code applyBiomeDecoration}) для каждого чанка.
     *
     * <p>Обрабатывает каждый чанк, чей 16×16-квадрат пересекает {@code island.radius}
     * от центра острова — недостающие структуры тира доразмещаются постепенно
     * между вызовами для разных чанков одного острова (см. {@link IslandVaultTrialCache}).</p>
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

            IslandData island = generator.getIslandData(islandBlockX, islandBlockZ);

            if (!chunkIntersectsRadius(chunkX, chunkZ, islandBlockX, islandBlockZ, island.radius)) continue;

            VaultTrialSpawnTier tier = pickTier(islandBlockX, islandBlockZ);

            IslandVaultTrialCache.Progress progress = sharedVaultTrialCache.getOrCreate(
                    islandBlockX, islandBlockZ, tier.vaultCount(), tier.trialSpawnerCount());
            if (progress.isComplete()) continue;

            RandomSource rng = RandomSource.create(
                    worldSeed
                            ^ ((long) islandBlockX * 341873128712L)
                            ^ ((long) islandBlockZ * 132897987541L)
                            ^ 0xFACE5EEDL
                            ^ L3_RNG_SALT // отличается от Layer2, чтобы не совпасть детерминированно
                            ^ ((long) chunkX * 0x9E3779B97F4A7C15L)
                            ^ ((long) chunkZ * 0xC2B2AE3D27D4EB4FL));

            IslandVaultTrialGenerator.placeForEllipsoidIsland(
                    region, generator, island, tier, VaultTrialLootConfig.LAYER_3, rng,
                    chunkX, chunkZ, progress);

        }
    }

    /** См. {@code Layer2VaultTrialPlacer.chunkIntersectsRadius} — идентичная грубая проверка XZ-пересечения. */
    private static boolean chunkIntersectsRadius(int chunkX, int chunkZ, int cx, int cz, double radius) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int nearestX = Math.max(minX, Math.min(cx, minX + 15));
        int nearestZ = Math.max(minZ, Math.min(cz, minZ + 15));
        double dx = nearestX - cx;
        double dz = nearestZ - cz;
        return dx * dx + dz * dz <= radius * radius;
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
