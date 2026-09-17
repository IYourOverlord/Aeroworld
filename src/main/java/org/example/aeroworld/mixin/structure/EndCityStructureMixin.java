package org.example.aeroworld.mixin.structure;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.EndCityStructure;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.cache.BodyType;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.noise.IslandPlacer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * {@code minecraft:end_city} — ванильная jigsaw-структура (набор пивсов этот
 * мискин не трогает, генерация переиспользуется через
 * {@link EndCityStructureAccessor}). Ванильный {@code findGenerationPoint}
 * ищет стартовую точку через protected-хелпер базового класса
 * {@code Structure} ({@code getLowestYIn5by5BoxOffset7Blocks}), который
 * сканирует heightmap вокруг случайной XZ-точки чанка. Внутри Layer 3
 * (полые метеориты/планеты в пустоте, без heightmap-поверхности) это либо
 * возвращает мусорный Y, либо ловит воздух — структура не находит себе
 * место или спавнится где попало.
 *
 * <p>Тот protected-хелпер объявлен в родительском {@code Structure}, а не в
 * {@code EndCityStructure} — {@code @Redirect} на его INVOKE Mixin не
 * резолвит (protected inherited call), поэтому вместо точечного редиректа
 * метод {@code findGenerationPoint} целиком перехватывается через
 * {@code @Inject(at = "HEAD", cancellable = true)}: для чанков AeroWorld
 * Layer 3 с центром острова-планеты возвращается готовый
 * {@link Structure.GenerationStub} с координатами центра, минуя ванильный
 * поиск по heightmap вовсе; для всех остальных случаев (не AeroWorld, не
 * планета) выполнение продолжается оригинальным кодом метода как обычно.</p>
 *
 * <p>По требованию: End City должен появляться строго по центру КАЖДОГО
 * острова типа {@link BodyType#PLANET} в Layer 3 (не метеоритов — только
 * планет с кольцами астероидов).</p>
 *
 * <p>Условие "остров-планета в текущем чанке" проверяется тем же способом,
 * что и {@code Layer2StructurePlacer}: берём список центров островов для
 * этого чанка через {@link IslandPlacer#getIslandCentresForChunk}, оставляем
 * только тот, чей чанк совпадает с текущим (структура спавнится ровно один
 * раз на остров), и фильтруем по {@link BodyType#PLANET}. Частота проверки
 * чанков задаётся {@code data/minecraft/worldgen/structure_set/end_cities.json}
 * ({@code spacing=1} — проверяется каждый чанк, реальную фильтрацию делает
 * этот мискин).</p>
 *
 * <p>Затрагивает ТОЛЬКО измерение AeroWorld: guard по
 * {@code instanceof AeroWorldChunkGenerator}. Ванильный Край и любые другие
 * измерения используют {@code NoiseBasedChunkGenerator} напрямую — под
 * условие не попадают, метод выполняется как обычно.</p>
 */
@Mixin(value = EndCityStructure.class, remap = false)
public abstract class EndCityStructureMixin {

    /**
     * Запас (блоков) над макушкой сферы планеты, на который поднимается
     * стартовая точка End City. {@code EndCityPieces.startHouseTower} ставит
     * базовый пивс, от которого структура растёт и вверх (башни), и вниз
     * (опорные конструкции/лестницы у основания) — без запаса нижняя часть
     * города уходит внутрь тела планеты.
     *
     * <p>Должен быть строго больше {@code TerrainColumnSampler.SKY_MIN_CLEARANCE}
     * (8 блоков) — validateSkyFloating отклоняет структуру, если зазор до
     * ближайшего острова снизу меньше этого порога. 16 даёт трёхкратный
     * запас сверх минимума на случай, если реальная нижняя точка
     * сгенерированных пивсов окажется на пару блоков ниже стартового Y
     * (например, из-за случайного поворота базовой площадки).</p>
     */
    private static final int END_CITY_CLEARANCE = 16;

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(
            method = "findGenerationPoint",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void aeroworld$startAtPlanetCentre(Structure.GenerationContext context,
                                               CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!(context.chunkGenerator() instanceof AeroWorldChunkGenerator aeroGen)) {
            return; // не AeroWorld — оригинальный ванильный код выполняется как обычно
        }

        HighIslandGenerator highIslands = aeroGen.getHighIslands();
        if (highIslands == null) {
            // Слой ещё не инициализирован (сид не задан) — структуру не ставим.
            cir.setReturnValue(Optional.empty());
            return;
        }

        int chunkX = context.chunkPos().x;
        int chunkZ = context.chunkPos().z;

        IslandPlacer placer = highIslands.getPlacer();
        LongArrayList centres = placer.getIslandCentresForChunk(chunkX, chunkZ, highIslands.getSearchRadius());

        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            int islandBlockX = ChunkKey.x(packed);
            int islandBlockZ = ChunkKey.z(packed);

            // Совпадение по чанку центра — End City спавнится ровно один раз на
            // остров, привязанный к чанку, где физически лежит центр (тот же
            // guard, что использует Layer2StructurePlacer для tank21).
            if ((islandBlockX >> 4) != chunkX || (islandBlockZ >> 4) != chunkZ) continue;

            IslandData data = highIslands.getIslandData(islandBlockX, islandBlockZ);
            if (data.bodyType != BodyType.PLANET) {
                cir.setReturnValue(Optional.empty());
                return;
            }

            // data.topY — верхняя точка сферы планеты. End City растёт и вверх,
            // и вниз от стартового пивса (EndCityPieces.startHouseTower ставит
            // башню, часть которой опускается ниже стартового Y) — при старте
            // ровно на topY нижняя часть города уходит внутрь тела планеты
            // (видно на скриншоте: постройка наполовину утоплена в снежной
            // сфере). Поднимаем старт с запасом над макушкой, чтобы весь город
            // гарантированно оказался над поверхностью.
            int startY = data.topY + END_CITY_CLEARANCE;

            BlockPos startPos = new BlockPos(data.cx, startY, data.cz);
            Rotation rotation = Rotation.getRandom(context.random());

            LOGGER.info("[AeroWorld] EndCity start forced: chunk=({},{}) planetCentre=({},{}) topY={} startY={} rotation={}",
                    chunkX, chunkZ, data.cx, data.cz, data.topY, startY, rotation);

            cir.setReturnValue(Optional.of(new Structure.GenerationStub(startPos, builder -> {
                EndCityStructureAccessor accessor = (EndCityStructureAccessor) (Object) this;
                accessor.aeroworld$invokeGeneratePieces(builder, startPos, rotation, context);
                LOGGER.info("[AeroWorld] EndCity generatePieces invoked at chunk=({},{}), builder pieceCount after={}",
                        chunkX, chunkZ, builder.getBoundingBox());
            })));
            return;
        }

        cir.setReturnValue(Optional.empty());
    }
}