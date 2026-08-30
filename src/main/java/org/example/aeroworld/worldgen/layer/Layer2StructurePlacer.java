package org.example.aeroworld.worldgen.layer;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer;
import org.example.aeroworld.worldgen.feature.vault.VaultTrialSpawnTier;
import org.example.aeroworld.worldgen.noise.IslandPlacer;
import org.example.aeroworld.worldgen.noise.IslandShape;

import java.util.List;

/**
 * Layer2StructurePlacer — регистрирует позиции для структур на островах Layer 2
 * через {@link org.example.aeroworld.structure.IslandStructureScheduler}.
 *
 * <p>tank21 ставится только на "самых крупных" островах Layer 2: обычных
 * (не архипелажных) островах, для которых тир вольтов/спавнеров испытаний
 * ({@link VaultTrialSpawnTier}) — {@code RICH}. Любой остров архипелага
 * (и центр, и спутники) исключён всегда — RICH-тир на них в принципе
 * невозможен по правилам {@code Layer2VaultTrialPlacer}.</p>
 */
public final class Layer2StructurePlacer {

    /** Id структуры-танка в PhysicalStructures. Без суффикса .nbt и без пути structures/. */
    private static final ResourceLocation TANK_ID =
            ResourceLocation.fromNamespaceAndPath("physical_structures", "tank21");

    private final long worldSeed;
    private final ChunkIslandCache sharedChunkCache;

    public Layer2StructurePlacer(long worldSeed) {
        this(worldSeed, new ChunkIslandCache());
    }

    public Layer2StructurePlacer(long worldSeed, ChunkIslandCache sharedChunkCache) {
        this.worldSeed = worldSeed;
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

        LongArrayList centres = sharedChunkCache.get(
                LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> generator.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, 1));

        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            int islandBlockX = ChunkKey.x(packed);
            int islandBlockZ = ChunkKey.z(packed);

            // Обрабатываем только острова, чей центр находится в текущем чанке,
            // чтобы избежать двойной регистрации при обработке соседних чанков.
            if ((islandBlockX >> 4) != chunkX || (islandBlockZ >> 4) != chunkZ) continue;

            if (!isEligibleForTank(generator, islandBlockX, islandBlockZ)) continue;

            AeroWorld.structureScheduler.enqueue(islandBlockX, islandBlockZ, TANK_ID);

        }
    }

    /**
     * tank21 разрешён только на островах, где Vault/Trial Spawner получит
     * максимальный тир ({@link VaultTrialSpawnTier#RICH}) — это значит, что
     * остров не спутник архипелага (RICH на спутниках невозможен по правилам
     * {@code Layer2VaultTrialPlacer}) и попал в верхнюю часть распределения
     * тиров по хэшу координат центра.
     */
    private boolean isEligibleForTank(LowerIslandGenerator generator, int islandBlockX, int islandBlockZ) {
        long packed = ChunkKey.of(islandBlockX, islandBlockZ);
        IslandPlacer placer = generator.getPlacer();

        boolean isArchipelagoCentre = placer.isArchipelagoCentre(packed);
        boolean isSatellite = !isArchipelagoCentre
                && placer.findArchipelagoCentreFor(islandBlockX, islandBlockZ, generator.getSearchRadius())
                        != IslandPlacer.NO_ISLAND;
        // RICH (и, следовательно, tank21) запрещён для ЛЮБОГО острова архипелага —
        // и для центра, и для спутников. Реальный тир архипелажных островов
        // выбирается через pickSatelliteTier (POOR/MEDIUM only, см.
        // Layer2VaultTrialPlacer), поэтому pickTierStatic ниже вызывается
        // только для действительно обычных островов.
        if (isArchipelagoCentre || isSatellite) return false;

        VaultTrialSpawnTier tier = Layer2VaultTrialPlacer.pickTierStatic(worldSeed, islandBlockX, islandBlockZ);
        return tier == VaultTrialSpawnTier.RICH;
    }
}