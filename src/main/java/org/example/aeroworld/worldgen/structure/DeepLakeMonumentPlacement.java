package org.example.aeroworld.worldgen.structure;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import org.example.aeroworld.registry.AeroRegistries;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Размещение «ровно одна структура на глубокое озеро Layer 1»: старт идёт в чанке, где лежит центр озера
 * ({@link Layer1TerrainGenerator#getDeepLakeCentre}), и только там, где дно действительно плоское на
 * {@link Layer1TerrainGenerator#DEEP_LAKE_FLOOR_Y}. Нужен для {@code minecraft:monument}: ванильный
 * {@code random_spread} не умеет привязываться к озёрам.
 *
 * <p>Работает только внутри {@code AeroWorldChunkGenerator.createStructures} (флаг {@code IS_GENERATING}): набор
 * данных мода грузится во все измерения, а в чужих мирах это размещение не должно срабатывать.
 * {@code /locate} для этого типа размещения не работает (ваниль умеет искать только random_spread и кольца).</p>
 */
public final class DeepLakeMonumentPlacement extends StructurePlacement {

    public static final MapCodec<DeepLakeMonumentPlacement> CODEC = RecordCodecBuilder.mapCodec(
            instance -> placementCodec(instance).apply(instance, DeepLakeMonumentPlacement::new));

    /**
     * Низ ({@code minY}) монумента для текущего старта: выставляет {@code OceanMonumentStructureMixin} перед
     * созданием кусков, читает {@code OceanMonumentBuildingMixin}. {@code null} — ванильное поведение (Y 39).
     */
    public static final ThreadLocal<Integer> MONUMENT_MIN_Y = new ThreadLocal<>();

    /** На сколько блоков монумент заглублён в дно (ваниль: дно около Y 45, низ монумента на 39). */
    public static final int BURY_DEPTH = 4;

    private static final ConcurrentHashMap<Long, Layer1TerrainGenerator> TERRAIN = new ConcurrentHashMap<>();

    public DeepLakeMonumentPlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod,
                                     float frequency, int salt, Optional<ExclusionZone> exclusionZone) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone);
    }

    /** Рельеф по сиду: у размещения нет ссылки на генератор, а сеть озёр чистая функция от сида. */
    private static Layer1TerrainGenerator terrain(long seed) {
        Layer1TerrainGenerator t = TERRAIN.get(seed);
        if (t == null) {
            if (TERRAIN.size() > 3) TERRAIN.clear();
            t = TERRAIN.computeIfAbsent(seed, Layer1TerrainGenerator::new);
        }
        return t;
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        if (!AeroWorldChunkGenerator.IS_GENERATING.get()) return false;
        return terrain(state.getLevelSeed()).getDeepLakeCentre(chunkX, chunkZ) != null;
    }

    @Override
    public StructurePlacementType<?> type() {
        return AeroRegistries.DEEP_LAKE_MONUMENT_PLACEMENT.get();
    }
}
