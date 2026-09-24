package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.biome.AeroBiomeSource;
import org.example.aeroworld.worldgen.cache.Layer1ColumnCache;
import org.example.aeroworld.worldgen.column.AeroColumnModel;
import org.example.aeroworld.worldgen.column.AeroColumnWriter;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Аналитический генератор мира для Distant Horizons (IDhApiWorldGenerator override).
 *
 * Генерирует LOD-данные напрямую по seed и математической модели слоёв AeroColumnModel,
 * полностью минуя штатный батчевый chunk-gen DH (DhChunkGenerator).
 */
public class AeroSeedWorldGenerator implements IDhApiWorldGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroSeedWorldGenerator.class);

    /** Реальная высота измерения (data/aeroworld/dimension_type/aeroworld.json: min_y=-64, height=2096 -> Y -64..2031). */
    private static final int WORLD_HEIGHT = 2096;

    private final AeroWorldChunkGenerator generator;
    private final IDhApiLevelWrapper levelWrapper;
    private final AeroColumnWriter columnWriter;
    private final AeroThroughputLimits throughputLimits;
    private final AeroFastDistantTerrain fastTerrain;

    public AeroSeedWorldGenerator(AeroWorldChunkGenerator generator, IDhApiLevelWrapper levelWrapper) {
        this.generator = generator;
        this.levelWrapper = levelWrapper;
        this.columnWriter = new AeroColumnWriter(levelWrapper);
        this.throughputLimits = new AeroThroughputLimits();
        this.fastTerrain = new AeroFastDistantTerrain(generator.getSettings().dhOverride());
    }

    /**
     * Переключено с API_CHUNKS на API_DATA_SOURCES: только generateLod поддерживает
     * по-настоящему грубую (coarse) генерацию — DhApiChunk (API_CHUNKS) всегда
     * full-resolution 16×16 вне зависимости от detailLevel (см. байткод DH 3.3.2:
     * DhApiChunk не имеет параметра detailLevel вообще). generateApiChunks ниже
     * не удалён — это путь отката, верните API_CHUNKS одной строкой при проблемах.
     */
    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    /**
     * DH API docs: валидация каждого DhApiChunk — "should be disabled during release".
     * При {@code true} (дефолт интерфейса) каждый столбец каждого чанка прогоняется через
     * {@code generateApiChunks -> LodDataBuilder.createFromApiChunkData -> validateOrThrowApiDataColumn}
     * и gap-проверку дата-поинтов — дорого для аналитического генератора (до 131 секции на столбец).
     * Отключение — чистый выигрыш throughput; корректность формата проверяется отдельно
     * командой {@code /aeroworld validateSeedGen} (AeroSeedGenValidation) и на dev-конфигурации.
     */
    @Override
    public boolean runApiValidation() {
        return false;
    }

    /**
     * Дефолт интерфейса — {@code EDhApiDetailLevel.BLOCK.detailLevel} (0). Это не просто
     * "самый грубый уровень, который мы честно умеем" — по байткоду DH 3.3.2
     * (GeneratedFullDataSourceProvider.lowestDataDetailLevel = 6 + getLargestDataDetailLevel())
     * это значение напрямую ограничивает, до какого грубого уровня секций DH вообще
     * СОГЛАСЕН запрашивать LOD у этого генератора. С дефолтом 0 DH никогда не вызвал бы
     * generateLod с detailLevel > 0 — весь coarse-путь ниже остался бы мёртвым кодом даже
     * после переключения на API_DATA_SOURCES. REGION (9) — максимум, определённый в
     * EDhApiDetailLevel, соответствует эффекту SeedGen (мгновенная геометрия на грубом LOD).
     */
    @Override
    public byte getLargestDataDetailLevel() {
        return EDhApiDetailLevel.REGION.detailLevel;
    }

    @Override
    public CompletableFuture<Void> generateApiChunks(
            int chunkX, int chunkZ, int genRequestWidth,
            byte detailLevel, EDhApiDistantGeneratorMode mode,
            ExecutorService executor, Consumer<DhApiChunk> resultConsumer) {

        return CompletableFuture.runAsync(() -> {
            try {
                int minY = generator.getMinY();
                int maxY = minY + WORLD_HEIGHT - 1;

                var l1Terrain = generator.getLayer1Terrain();
                var aeroBiomeSource = generator.getAeroBiomeSource();

                // ── По-слойное упрощение на дальних LOD (AeroFastDistantTerrain) ──
                // При detailLevel >= порога слоя генератор этого слоя заменяется на null,
                // и AeroColumnModel.buildSpans просто пропускает его геометрию (остров
                // исчезает на дальних LOD, оставляя лишь Layer 1 + силуэты ближних слоёв).
                // ЭТОТ метод сейчас недостижим (getReturnType() = API_DATA_SOURCES, DH вызывает
                // generateLod ниже) — оставлен как путь отката на API_CHUNKS. Если вернётесь
                // на API_CHUNKS, помните: DhApiChunk всегда full-resolution 16×16, поэтому
                // упрощение по detailLevel здесь реально сработает только если DH когда-либо
                // запросит detailLevel > 0 для этого пути (сейчас не запрашивает).
                var lower = fastTerrain.isLayer2Coarse(detailLevel) ? null : generator.getLowerIslands();
                var high  = fastTerrain.isLayer3Coarse(detailLevel) ? null : generator.getHighIslands();
                var upper = fastTerrain.isLayer4Coarse(detailLevel) ? null : generator.getUpperIslands();

                int endChunkX = chunkX + genRequestWidth;
                int endChunkZ = chunkZ + genRequestWidth;

                // Один Layer1ColumnCache на поток-исполнитель этой задачи: заполняется
                // один раз на чанк (256 вызовов getHeight/computeCaveTop/computeCaveBottom)
                // вместо повторного пересчёта тех же ~25 октав шума на каждую из 256 колонок
                // при последующих обращениях AeroColumnModel.buildSpans.
                Layer1ColumnCache columnCache = l1Terrain != null ? new Layer1ColumnCache() : null;

                for (int cx = chunkX; cx < endChunkX; cx++) {
                    for (int cz = chunkZ; cz < endChunkZ; cz++) {
                        DhApiChunk chunk = DhApiChunk.create(cx, cz, minY, maxY);
                        int baseBlockX = cx << 4;
                        int baseBlockZ = cz << 4;

                        if (columnCache != null) {
                            columnCache.initForChunk(cx, cz, l1Terrain);
                        }

                        for (int lx = 0; lx < 16; lx++) {
                            int bx = baseBlockX + lx;
                            for (int lz = 0; lz < 16; lz++) {
                                int bz = baseBlockZ + lz;

                                List<AeroColumnModel.Span> spans = AeroColumnModel.buildSpans(
                                        bx, bz, minY, maxY,
                                        l1Terrain, lower, high, upper,
                                        aeroBiomeSource, true, columnCache
                                );

                                List<DhApiTerrainDataPoint> points = columnWriter.toDataPoints(spans, minY, maxY);
                                chunk.setDataPoints(lx, lz, points);
                            }
                        }

                        resultConsumer.accept(chunk);
                        throughputLimits.recordChunksGenerated(1);
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to generate DH API chunks at chunk ({}, {}), width {}:",
                        chunkX, chunkZ, genRequestWidth, t);
            }
        }, executor);
    }

    /**
     * Единственный путь DH API с по-настоящему переменным разрешением (см. комментарий
     * у {@link #getReturnType()}). Сигнатура и семантика параметров подтверждены по байткоду
     * DH 3.3.2 (javap, не декомпиляция):
     * <ul>
     *   <li>{@code chunkPosMinX/Z} — чанк левого нижнего угла всего LOD-отпечатка
     *       (WorldGenerationQueue.startApiDataSourceGenerationEvent передаёт сюда то же,
     *       что generateApiChunks получает как chunkX/chunkZ) — база блока считается так же:
     *       {@code chunkPosMinX << 4};</li>
     *   <li>{@code lodPosX/Z} не используются: пересчитывать базовую позицию через них
     *       избыточно, раз chunkPosMinX/Z уже дают тот же абсолютный блок;</li>
     *   <li>{@code pooledFullDataSource.getWidthInDataColumns()} — количество колонок сетки
     *       (у FullDataSourceV2 это константа 64 на любом detailLevel — проверено байткодом);
     *       ширина ОДНОЙ колонки в блоках = {@code 1 << detailLevel} (EDhApiDetailLevel:
     *       BLOCK=0→1, CHUNK=4→16, REGION=9→512 — степени двойки);</li>
     *   <li><b>Y относительный, не абсолютный.</b> В отличие от DhApiChunk.setDataPoints (там DH
     *       сам вычитает chunk.bottomYBlockPos), FullDataSourceV2.setApiDataPointColumn паkует
     *       данные с offset=0 (см. LodDataBuilder.convertApiDataPointListToPackedLongArray) —
     *       bottomYBlockPos/topYBlockPos должны прийти уже relative к minY, отсюда
     *       {@code columnWriter.toDataPoints(spans, minY, maxY, minY)}.</li>
     * </ul>
     * Coarse-колонки (step > 1) не берут одну точку — голосование см. в {@link #buildDominantSpans}
     * (там же зафиксирован актуальный ponytail этого пути: фиксированное число сэмплов, не
     * адаптивное по detailLevel).
     */
    @Override
    public CompletableFuture<Void> generateLod(
            int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
            byte detailLevel, IDhApiFullDataSource pooledFullDataSource,
            EDhApiDistantGeneratorMode mode, ExecutorService executor,
            Consumer<IDhApiFullDataSource> resultConsumer) {

        return CompletableFuture.runAsync(() -> {
            try {
                int minY = generator.getMinY();
                int maxY = minY + WORLD_HEIGHT - 1;

                var l1Terrain = generator.getLayer1Terrain();
                var aeroBiomeSource = generator.getAeroBiomeSource();

                var lower = fastTerrain.isLayer2Coarse(detailLevel) ? null : generator.getLowerIslands();
                var high  = fastTerrain.isLayer3Coarse(detailLevel) ? null : generator.getHighIslands();
                var upper = fastTerrain.isLayer4Coarse(detailLevel) ? null : generator.getUpperIslands();

                int width = pooledFullDataSource.getWidthInDataColumns();
                int step = 1 << detailLevel;
                int baseBlockX = chunkPosMinX << 4;
                int baseBlockZ = chunkPosMinZ << 4;

                // Кэш чанка Layer1 полезен только при step == 1 (detailLevel 0): тогда соседние
                // колонки внутри сетки остаются в одном чанке. На coarse LOD (step > 16) каждая
                // сэмплируемая колонка попадает в свой чанк — кэш только мешает лишней проверкой.
                Layer1ColumnCache columnCache = (step == 1 && l1Terrain != null) ? new Layer1ColumnCache() : null;

                for (int relX = 0; relX < width; relX++) {
                    int bx = baseBlockX + relX * step;
                    for (int relZ = 0; relZ < width; relZ++) {
                        int bz = baseBlockZ + relZ * step;

                        List<AeroColumnModel.Span> spans;
                        if (step > 1) {
                            // Coarse LOD: одна точка на блок 2^detailLevel — aliasing (узкая полоса
                            // пляжа/бэдлендс-прожилка внутри джунглей/саванны красит весь блок LOD).
                            // См. buildDominantSpans ниже.
                            spans = buildDominantSpans(bx, bz, step, minY, maxY, l1Terrain, lower, high, upper, aeroBiomeSource);
                        } else {
                            if (columnCache != null) {
                                columnCache.initForChunk(bx >> 4, bz >> 4, l1Terrain);
                            }
                            spans = AeroColumnModel.buildSpans(
                                    bx, bz, minY, maxY,
                                    l1Terrain, lower, high, upper,
                                    aeroBiomeSource, true, columnCache
                            );
                        }

                        List<DhApiTerrainDataPoint> points = columnWriter.toDataPoints(spans, minY, maxY, minY);
                        pooledFullDataSource.setApiDataPointColumn(relX, relZ, points);
                    }
                }

                resultConsumer.accept(pooledFullDataSource);
                int footprintChunks = (width * step) >> 4;
                throughputLimits.recordChunksGenerated(footprintChunks * footprintChunks);
            } catch (Throwable t) {
                LOGGER.error("Failed to generate DH LOD data at chunk ({}, {}), detailLevel {}:",
                        chunkPosMinX, chunkPosMinZ, detailLevel, t);
            }
        }, executor);
    }

    /** Число сэмплов на сторону coarse-колонки для мажоритарного голосования (3×3=9 точек). */
    private static final int COARSE_SUBSAMPLES = 3;

    /**
     * На coarse LOD один сэмпл-столбец на угол блока стороной {@code step} даёт aliasing:
     * узкая полоса пляжа/бэдлендс-прожилка/структура внутри джунглей или саванны может
     * случайно попасть именно в сэмплируемую точку — и весь блок LOD красится в чужой материал
     * (репортится как "песок/бордовые блоки в густом лесу"). Вместо одной точки берём
     * {@code COARSE_SUBSAMPLES × COARSE_SUBSAMPLES} сэмплов, равномерно раскиданных по площади
     * колонки, и оставляем span-list того сэмпла, чей верхний блок — самый частый (majority vote
     * по top-material). Полная вертикальная структура (пещеры, острова 2-4 слоёв) берётся
     * целиком у сэмпла-победителя, а не усредняется по всем девяти — усложнять дальше не нужно,
     * цель именно убрать случайный шум поверхности, а не отрендерить точную геометрию на LOD,
     * для которого и так весь смысл в приближении.
     * <p>
     * ponytail: 9 сэмплов на coarse-колонку вместо 1 — это ×9 аналитической нагрузки именно на
     * дальних (coarse) LOD-запросах; на step==1 (ближний, точный LOD) этот путь не используется
     * вообще. Апгрейд при необходимости: адаптивный COARSE_SUBSAMPLES по detailLevel (меньше
     * сэмплов на самом грубом REGION, где площадь блока и так огромна) вместо фиксированной 3×3.
     */
    private List<AeroColumnModel.Span> buildDominantSpans(
            int bx, int bz, int step, int minY, int maxY,
            Layer1TerrainGenerator l1Terrain, LowerIslandGenerator lower, HighIslandGenerator high,
            UpperIslandGenerator upper, AeroBiomeSource aeroBiomeSource) {

        int subStep = Math.max(1, step / COARSE_SUBSAMPLES);
        Map<BlockState, List<AeroColumnModel.Span>> spansByTopBlock = new HashMap<>(COARSE_SUBSAMPLES * COARSE_SUBSAMPLES);
        Map<BlockState, Integer> votes = new HashMap<>(COARSE_SUBSAMPLES * COARSE_SUBSAMPLES);
        BlockState winner = null;
        int winnerVotes = -1;

        for (int i = 0; i < COARSE_SUBSAMPLES; i++) {
            int sx = bx + i * subStep;
            for (int j = 0; j < COARSE_SUBSAMPLES; j++) {
                int sz = bz + j * subStep;

                List<AeroColumnModel.Span> spans = AeroColumnModel.buildSpans(
                        sx, sz, minY, maxY, l1Terrain, lower, high, upper, aeroBiomeSource, true, null);

                BlockState top = spans.isEmpty()
                        ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                        : spans.get(spans.size() - 1).state();

                spansByTopBlock.putIfAbsent(top, spans);
                int count = votes.merge(top, 1, Integer::sum);
                if (count > winnerVotes) {
                    winnerVotes = count;
                    winner = top;
                }
            }
        }
        return spansByTopBlock.get(winner);
    }

    @Override
    public void preGeneratorTaskStart() {
        throughputLimits.onTaskStart();
    }

    @Override
    public void close() {
        LOGGER.info("[AeroWorld DH SeedGen] Closed generator for level: {}", levelWrapper.getDimensionName());
    }

    public AeroWorldChunkGenerator getGenerator() { return generator; }
    public IDhApiLevelWrapper getLevelWrapper() { return levelWrapper; }
    public AeroThroughputLimits getThroughputLimits() { return throughputLimits; }
    public AeroFastDistantTerrain getFastTerrain() { return fastTerrain; }
}