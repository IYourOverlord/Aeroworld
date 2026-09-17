package org.example.aeroworld.mixin.structure;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
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

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
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

    /**
     * Кэшированное приватное поле {@code templateName} (тип
     * {@link ResourceLocation}) в {@code TemplateStructurePiece} — базовом
     * классе {@code EndCityPieces.EndCityPiece}. Хранит имя NBT-шаблона
     * пивса (например {@code "end_city/ship"}); по нему пост-фильтр отличает
     * пивс корабля от пивсов башен/лестниц/мостов той же структуры.
     * {@code setAccessible(true)} выполняется один раз на JVM в статическом
     * инициализаторе — дешёво, повторного вызова на каждый пивс не требуется.
     */
    private static final Field TEMPLATE_NAME_FIELD;

    /**
     * Кэшированное приватное поле {@code pieces} (тип {@code List<StructurePiece>})
     * в самом {@link StructurePiecesBuilder}. Реальный ванильный класс (см.
     * decompile) не публикует геттер списка пивсов — {@code build()} только
     * оборачивает его в {@code PiecesContainer}, а {@code clear()}/
     * {@code isEmpty()} работают с ним же, не давая доступа наружу. Поэтому
     * пост-фильтр обязан читать это поле рефлексией напрямую.
     */
    private static final Field PIECES_FIELD;

    static {
        Field templateNameField = null;
        try {
            Class<?> templateStructurePiece = Class.forName(
                    "net.minecraft.world.level.levelgen.structure.TemplateStructurePiece");
            templateNameField = templateStructurePiece.getDeclaredField("templateName");
            templateNameField.setAccessible(true);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("[AeroWorld] Не удалось получить доступ к TemplateStructurePiece.templateName рефлексией — фильтр корабля End City отключён", e);
        }
        TEMPLATE_NAME_FIELD = templateNameField;

        Field piecesField = null;
        try {
            piecesField = StructurePiecesBuilder.class.getDeclaredField("pieces");
            piecesField.setAccessible(true);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("[AeroWorld] Не удалось получить доступ к StructurePiecesBuilder.pieces рефлексией — фильтр корабля End City отключён", e);
        }
        PIECES_FIELD = piecesField;
    }

    /**
     * Возвращает {@code true}, если переданный пивс — это пивс корабля
     * ({@code end_city/ship}), определяемый по имени NBT-шаблона в поле
     * {@code templateName}. При любой ошибке рефлексии (поле отсутствует/
     * недоступно, пивс не того типа) — возвращает {@code false}, оставляя
     * пивс нетронутым (безопасный дефолт: лучше не удалить лишнего, чем
     * случайно вырезать часть города).
     */
    private static boolean aeroworld$isShipPiece(StructurePiece piece) {
        if (TEMPLATE_NAME_FIELD == null) return false;
        try {
            Object value = TEMPLATE_NAME_FIELD.get(piece);
            if (value instanceof ResourceLocation location) {
                return location.getPath().contains("ship");
            }
        } catch (IllegalAccessException | IllegalArgumentException ignored) {
            // Пивс не TemplateStructurePiece или поле недоступно — не корабль.
        }
        return false;
    }

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

            // data.topY — верх ограничивающего бокса острова (botY + islandH,
            // клампится по LAYER_MAX_Y), а НЕ фактическая макушка сферы/
            // эллипсоида в центральной колонке (cx, cz). Для сплюснутых или
            // вытянутых по XZ планет (ellipsoidAxes != бокс) реальная
            // поверхность в центре заметно ниже topY, из-за чего структура
            // стартовала выше фактического острова — "парила" над ним на
            // неопределённой высоте. Берём реальную высоту оболочки в точке
            // центра через getEllipsoidTopY (тот же расчёт, что использует
            // LOD/заполнение чанков), затем поднимаем с запасом, чтобы вся
            // структура (растёт и вверх, и вниз от стартового пивса) оказалась
            // над поверхностью.
            int surfaceY = highIslands.getEllipsoidTopY(data.cx, data.cz, data);
            int startY = surfaceY + END_CITY_CLEARANCE;

            BlockPos startPos = new BlockPos(data.cx, startY, data.cz);
            Rotation rotation = Rotation.getRandom(context.random());

            LOGGER.info("[AeroWorld] EndCity start forced: chunk=({},{}) planetCentre=({},{}) boundingTopY={} surfaceY={} startY={} rotation={}",
                    chunkX, chunkZ, data.cx, data.cz, data.topY, surfaceY, startY, rotation);

            cir.setReturnValue(Optional.of(new Structure.GenerationStub(startPos, builder -> {
                EndCityStructureAccessor accessor = (EndCityStructureAccessor) (Object) this;
                accessor.aeroworld$invokeGeneratePieces(builder, startPos, rotation, context);
                aeroworld$stripShipPieces(builder, chunkX, chunkZ);
            })));
            return;
        }

        cir.setReturnValue(Optional.empty());
    }

    /**
     * Пост-фильтр по builder (вариант 1): после ванильной генерации пивсов
     * End City убирает уже посчитанные пивсы корабля ({@code end_city/ship})
     * из приватного поля {@code pieces} билдера (доступ рефлексией — публичного
     * геттера в ванильном {@link StructurePiecesBuilder} нет) до записи
     * структуры в чанк. Саму генерацию/NBT не трогает — корабль всё ещё
     * считается ванильным кодом, но не попадает в финальный список пивсов,
     * поэтому не материализуется в мире. Никакой доп. нагрузки на диск/датапак.
     */
    private void aeroworld$stripShipPieces(StructurePiecesBuilder builder, int chunkX, int chunkZ) {
        if (PIECES_FIELD == null) return;

        List<StructurePiece> pieces;
        try {
            //noinspection unchecked
            pieces = (List<StructurePiece>) PIECES_FIELD.get(builder);
        } catch (IllegalAccessException | ClassCastException e) {
            LOGGER.error("[AeroWorld] Не удалось прочитать StructurePiecesBuilder.pieces — фильтр корабля End City пропущен", e);
            return;
        }

        int removed = 0;
        Iterator<StructurePiece> it = pieces.iterator();
        while (it.hasNext()) {
            StructurePiece piece = it.next();
            if (aeroworld$isShipPiece(piece)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("[AeroWorld] EndCity ship piece(s) removed at chunk=({},{}): count={}", chunkX, chunkZ, removed);
        }
    }
}