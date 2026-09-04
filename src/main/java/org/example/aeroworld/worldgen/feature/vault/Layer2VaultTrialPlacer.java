package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.noise.IslandPlacer;
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
 *   <li>выбор {@link VaultTrialSpawnTier} по острову: обычные острова —
 *       детерминированный хэш от координат центра острова + world seed;
 *       центр архипелага — всегда {@code MEDIUM}; спутник архипелага —
 *       всегда {@code POOR};</li>
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

    /** Шанс, что вольт/спавнер вообще появится на спутнике архипелага (в отличие от обычного острова/центра — там 100%). */
    private static final double SATELLITE_SPAWN_CHANCE = 0.25;

    private static final double NOISE_DEFORM = 18.0; // должно совпадать с LowerIslandGenerator.NOISE_DEFORM

    private final long worldSeed;
    private final ChunkIslandCache sharedChunkCache;
    private final IslandVaultTrialCache sharedVaultTrialCache;

    public Layer2VaultTrialPlacer(long worldSeed, ChunkIslandCache sharedChunkCache,
                                  IslandVaultTrialCache sharedVaultTrialCache) {
        this.worldSeed = worldSeed;
        this.sharedChunkCache = sharedChunkCache;
        this.sharedVaultTrialCache = sharedVaultTrialCache;
    }

    /**
     * Вызывается из фазы декорации ({@code applyBiomeDecoration}) для каждого чанка.
     *
     * <p>В отличие от прежней версии, обрабатывает не только чанк центра
     * острова, а КАЖДЫЙ чанк, чей 16×16-квадрат пересекает {@code island.radius}
     * от центра — недостающие структуры тира доразмещаются постепенно, по мере
     * генерации разных чанков одного острова (см. {@link IslandVaultTrialCache}
     * и javadoc {@link IslandVaultTrialGenerator#placeForIsland}). Грубая
     * XZ-проверка пересечения ниже отсеивает подавляющее большинство чанков в
     * радиусе поиска {@code searchRadius}, для которых остров физически не
     * может иметь тело в этом чанке.</p>
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

            IslandPlacer placer = generator.getPlacer();
            boolean isArchipelagoCentre = placer.isArchipelagoCentre(packed);
            boolean isSatellite = !isArchipelagoCentre
                    && placer.findArchipelagoCentreFor(islandBlockX, islandBlockZ, generator.getSearchRadius())
                    != IslandPlacer.NO_ISLAND;
            boolean isArchipelagoIsland = isArchipelagoCentre || isSatellite;

            if (isSatellite && !rollSatelliteSpawn(islandBlockX, islandBlockZ)) continue;

            IslandData island = generator.getIslandData(islandBlockX, islandBlockZ);

            // Грубая проверка пересечения XZ: этот чанк должен реально лежать
            // в пределах тела острова (иначе findBuriedSpot* заведомо не найдёт
            // валидную колонку в нём — не стоит тратить RNG/CAS на пустой чанк).
            // Используем ближайшую точку чанка 16×16 до центра острова, а не
            // только угол/центр чанка — иначе диагональные чанки на границе
            // radius ошибочно отсекались бы.
            if (!chunkIntersectsRadius(chunkX, chunkZ, islandBlockX, islandBlockZ, island.radius)) continue;

            // Тир островов архипелага фиксирован: центр всегда MEDIUM,
            // спутники всегда POOR. RICH недостижим ни для одного из них —
            // только для обычных (неархипелажных) островов.
            VaultTrialSpawnTier tier;
            if (isArchipelagoCentre) {
                tier = VaultTrialSpawnTier.MEDIUM;
            } else if (isSatellite) {
                tier = VaultTrialSpawnTier.POOR;
            } else {
                tier = pickTier(islandBlockX, islandBlockZ);
            }
            IslandVaultTrialCache.Progress progress = sharedVaultTrialCache.getOrCreate(
                    LowerIslandGenerator.LAYER_ID, islandBlockX, islandBlockZ, tier.vaultCount(), tier.trialSpawnerCount(),
                    null);
            if (progress.isComplete()) continue;

            RandomSource rng = RandomSource.create(
                    worldSeed
                            ^ ((long) islandBlockX * 341873128712L)
                            ^ ((long) islandBlockZ * 132897987541L)
                            ^ 0xFACE5EEDL
                            ^ ((long) chunkX * 0x9E3779B97F4A7C15L)
                            ^ ((long) chunkZ * 0xC2B2AE3D27D4EB4FL));

            IslandVaultTrialGenerator.placeForIsland(
                    region, shape, island, NOISE_DEFORM, tier, VaultTrialLootConfig.LAYER_2, rng,
                    chunkX, chunkZ, !isArchipelagoIsland, progress);

        }
    }

    /**
     * Грубая проверка пересечения квадрата чанка 16×16 со сферой радиуса
     * {@code radius} вокруг центра острова — ближайшая точка чанка к центру
     * не дальше {@code radius} (расстояние по осям клампится в границы чанка).
     */
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
     * Детерминированно решает, появится ли вольт/спавнер на спутнике архипелага
     * (шанс {@link #SATELLITE_SPAWN_CHANCE}). Центры архипелага и обычные острова
     * получают структуру всегда — этот roll применяется только к спутникам.
     */
    private boolean rollSatelliteSpawn(int islandBlockX, int islandBlockZ) {
        long h = worldSeed
                ^ ((long) islandBlockX * 2246822519L)
                ^ ((long) islandBlockZ * 3266489917L)
                ^ 0x5A7E177EL;
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);
        double roll = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
        return roll < SATELLITE_SPAWN_CHANCE;
    }

    /**
     * Тир центра архипелага — фиксированно {@link VaultTrialSpawnTier#MEDIUM}.
     * Используется внешним кодом (например, командой {@code /aeroworld findIsland2}).
     */
    public static VaultTrialSpawnTier archipelagoCentreTier() {
        return VaultTrialSpawnTier.MEDIUM;
    }

    /**
     * Тир спутника архипелага — фиксированно {@link VaultTrialSpawnTier#POOR}.
     * Используется внешним кодом (например, командой {@code /aeroworld findIsland2}).
     */
    public static VaultTrialSpawnTier satelliteTier() {
        return VaultTrialSpawnTier.POOR;
    }

    /**
     * Детерминированный выбор тира по координатам центра острова.
     * Применяется только к обычным островам вне архипелагов —
     * центры и спутники архипелага получают фиксированный тир (см. {@link #placeForChunk}).
     * Не зависит от порядка генерации чанков — тот же остров всегда получит тот же тир.
     */
    private VaultTrialSpawnTier pickTier(int islandBlockX, int islandBlockZ) {
        return pickTierStatic(worldSeed, islandBlockX, islandBlockZ, POOR_CHANCE, MEDIUM_CHANCE);
    }

    /**
     * Статический вариант {@link #pickTier(int, int)} с тем же хэшем/порогами Layer 2 —
     * используется внешним кодом (например, {@code Layer2StructurePlacer}), которому
     * нужно узнать тир острова без создания экземпляра {@code Layer2VaultTrialPlacer}.
     * Результат идентичен тому, что реально выберет плейсер вольтов/спавнеров для
     * данного острова на данном сиде.
     */
    public static VaultTrialSpawnTier pickTierStatic(long worldSeed, int islandBlockX, int islandBlockZ) {
        return pickTierStatic(worldSeed, islandBlockX, islandBlockZ, POOR_CHANCE, MEDIUM_CHANCE);
    }

    private static VaultTrialSpawnTier pickTierStatic(long worldSeed, int islandBlockX, int islandBlockZ,
                                                      double poorChance, double mediumChance) {
        long h = worldSeed
                ^ ((long) islandBlockX * 668265263L)
                ^ ((long) islandBlockZ * 341873128712L)
                ^ 0x51ED270B7DBL;
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);

        double roll = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);

        if (roll < poorChance) return VaultTrialSpawnTier.POOR;
        if (roll < poorChance + mediumChance) return VaultTrialSpawnTier.MEDIUM;
        return VaultTrialSpawnTier.RICH;
    }
}