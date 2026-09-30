package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.example.aeroworld.config.AeroWorldConfig;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.biome.AeroBiomeSource;
import org.example.aeroworld.worldgen.cache.Layer1ColumnCache;
import org.example.aeroworld.worldgen.column.AeroColumnModel;
import org.example.aeroworld.worldgen.column.AeroColumnWriter;
import org.example.aeroworld.worldgen.column.SliceVote;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** Реальный DH chunk-gen для листьев (detail 0) в радиусе R; создаётся лениво — уровень DH регистрируется после load-события. */
    private volatile DhWorldGenerator realGen;
    private final String dimension;
    private final AeroLeafLedger leafLedger;

    public AeroSeedWorldGenerator(AeroWorldChunkGenerator generator, IDhApiLevelWrapper levelWrapper, ServerLevel serverLevel) {
        this.generator = generator;
        this.levelWrapper = levelWrapper;
        this.dimension = serverLevel.dimension().location().toString();
        AeroLeafLedger ledger = null;
        try {
            Path file = serverLevel.getServer().getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve("aeroworld_dh_leaves_" + dimension.replaceAll("[^a-z0-9_.-]", "_") + ".bin");
            ledger = new AeroLeafLedger(file);
        } catch (IOException e) {
            LOGGER.error("[AeroWorld DH] Leaf ledger unavailable, hybrid real-chunk phase disabled:", e);
        }
        this.leafLedger = ledger;
        LOGGER.info("[AeroWorld DH] Real-chunk region border = {} (-Daeroworld.dhBorder, default 0), real radius = {} chunks",
                AeroThroughputLimits.WORLD_GEN_BORDER, AeroWorldConfig.DH_REAL_CHUNK_RADIUS.get());
        // ponytail: проба статическая, при нескольких измерениях с генератором побеждает последнее созданное; хватает для диагностики.
        AeroHybridStats.stuckProbe = (x, z) -> {
            int cx = x * 64 + 32, cz = z * 64 + 32;
            long pos = com.seibel.distanthorizons.core.pos.DhSectionPos.encode(
                    com.seibel.distanthorizons.core.pos.DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, x, z);
            return "block(" + cx + "," + cz + ") distToPlayer=" + AeroPlayerAnchors.nearestChebyshev(dimension, cx, cz)
                    + " R=" + (AeroWorldConfig.DH_REAL_CHUNK_RADIUS.get() << 4)
                    + " inLedger=" + leafLedger.contains(AeroLeafLedger.key(x, z))
                    + " cacheSaysComplete=" + AeroCompleteSectionCache.isConfirmedComplete(pos,
                    com.seibel.distanthorizons.core.pos.DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL);
        };
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
     * Coarse-колонки (step > 1) не берут одну точку — слияние сэмплов по Y-срезам см. в
     * {@link #buildDominantSpans}.
     */
    @Override
    public CompletableFuture<Void> generateLod(
            int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
            byte detailLevel, IDhApiFullDataSource pooledFullDataSource,
            EDhApiDistantGeneratorMode mode, ExecutorService executor,
            Consumer<IDhApiFullDataSource> resultConsumer) {

        // Гибрид: лист (detail 0) в радиусе R вокруг игрока. Фаза 1 — мгновенная аналитика со шагом SURFACE
        // (DH сам запросит лист повторно, т.к. для листа нужен FEATURES); фаза 2 — реальный chunk-gen DH.
        // Вне R и при выключенном chunk-gen аналитика ставит FEATURES, лист не перезапрашивается.
        long leafKey = AeroLeafLedger.key(lodPosX, lodPosZ);
        boolean hybridLeaf = false;
        if (detailLevel == 0 && leafLedger != null && mode != EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY
                && Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) {
            int radiusBlocks = AeroWorldConfig.DH_REAL_CHUNK_RADIUS.get() << 4;
            // центр секции 64x64 (= 4x4 чанка) + её полуразмер
            hybridLeaf = radiusBlocks > 0
                    && AeroPlayerAnchors.isWithin(dimension, (chunkPosMinX << 4) + 32, (chunkPosMinZ << 4) + 32, radiusBlocks + 32);
        }
        boolean realUnavailable = false;
        if (hybridLeaf && leafLedger.contains(leafKey)) {
            DhWorldGenerator real = realGenerator();
            if (real != null) {
                AeroHybridStats.onSection(detailLevel, lodPosX, lodPosZ, AeroHybridStats.Branch.PHASE2_REAL);
                return AeroHybridStats.timeReal(real.generateLod(chunkPosMinX, chunkPosMinZ, lodPosX, lodPosZ, detailLevel,
                        pooledFullDataSource, mode, executor, resultConsumer));
            }
            hybridLeaf = false; // DH-уровень ещё недоступен: не крутим перезапросы, отдаём FEATURES
            realUnavailable = true;
        }
        final boolean phaseOne = hybridLeaf;
        AeroHybridStats.onSection(detailLevel, lodPosX, lodPosZ,
                detailLevel > 0 ? AeroHybridStats.Branch.COARSE
                        : phaseOne ? AeroHybridStats.Branch.PHASE1
                        : realUnavailable ? AeroHybridStats.Branch.REAL_UNAVAILABLE
                        : AeroHybridStats.Branch.OUTSIDE_FEATURES);

        return CompletableFuture.runAsync(() -> {
            try {
                long statsStartNs = System.nanoTime();
                int minY = generator.getMinY();
                int maxY = minY + WORLD_HEIGHT - 1;

                var l1Terrain = generator.getLayer1Terrain();
                var aeroBiomeSource = generator.getAeroBiomeSource();

                var lower = fastTerrain.isLayer2Coarse(detailLevel) ? null : generator.getLowerIslands();
                var high  = fastTerrain.isLayer3Coarse(detailLevel) ? null : generator.getHighIslands();
                var upper = fastTerrain.isLayer4Coarse(detailLevel) ? null : generator.getUpperIslands();

                int width = pooledFullDataSource.getWidthInDataColumns();
                int step = 1 << detailLevel;
                // Листья (detail 0) DH считает готовыми только с FEATURES, грубые уровни — с SURFACE
                // (GeneratedFullDataSourceProvider.getPositionsToRetrieve). Лист со SURFACE запрашивается повторно.
                EDhApiWorldGenerationStep genStep = (detailLevel > 0 || phaseOne)
                        ? EDhApiWorldGenerationStep.SURFACE : EDhApiWorldGenerationStep.FEATURES;
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
                            // Coarse LOD: сетка сэмплов (число растёт к игроку, см.
                            // AeroFastDistantTerrain.subsamplesForDetailLevel) вместо одной точки.
                            int subsamples = AeroFastDistantTerrain.subsamplesForDetailLevel(detailLevel);
                            spans = buildDominantSpans(bx, bz, step, subsamples, minY, maxY, l1Terrain, lower, high, upper, aeroBiomeSource);
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
                        pooledFullDataSource.setApiDataPointColumn(relX, relZ, genStep, points);
                    }
                }

                resultConsumer.accept(pooledFullDataSource);
                if (phaseOne) leafLedger.add(leafKey); // после отдачи данных: сбой выше не загонит лист в фазу 2 вхолостую
                int footprintChunks = (width * step) >> 4;
                throughputLimits.recordChunksGenerated(footprintChunks * footprintChunks);
                AeroHybridStats.onAnalytic(detailLevel, System.nanoTime() - statsStartNs);
            } catch (Throwable t) {
                LOGGER.error("Failed to generate DH LOD data at chunk ({}, {}), detailLevel {}:",
                        chunkPosMinX, chunkPosMinZ, detailLevel, t);
            }
        }, executor);
    }

    /**
     * Coarse-колонка стороной {@code step}: сетка {@code n×n} сэмплов по центрам равных долей колонки
     * ({@code n = min(subsamples, step)}, иначе сэмплы вышли бы за колонку), затем колонки сливаются
     * по Y-срезам через {@link SliceVote} (как в DH): твёрдое побеждает пустоту, среди твёрдых — большинство.
     * Остров над лесом и суша больше не конкурируют за один «верхний блок».
     * <p>
     * ponytail: {@code n²} вызовов buildSpans на колонку (36 на detail 1); потолок — стоимость coarse-секции.
     * Апгрейд: брать сэмплы только там, где по соседям видна смена материала.
     */
    public static List<AeroColumnModel.Span> buildDominantSpans(
            int bx, int bz, int step, int subsamples, int minY, int maxY,
            Layer1TerrainGenerator l1Terrain, LowerIslandGenerator lower, HighIslandGenerator high,
            UpperIslandGenerator upper, AeroBiomeSource aeroBiomeSource) {

        int n = Math.max(1, Math.min(subsamples, step));
        List<List<SliceVote.Seg<AeroColumnModel.Span>>> cols = new ArrayList<>(n * n);
        for (int i = 0; i < n; i++) {
            int sx = bx + ((2 * i + 1) * step) / (2 * n);
            for (int j = 0; j < n; j++) {
                int sz = bz + ((2 * j + 1) * step) / (2 * n);
                List<AeroColumnModel.Span> spans = AeroColumnModel.buildSpans(
                        sx, sz, minY, maxY, l1Terrain, lower, high, upper, aeroBiomeSource, true, null);
                List<SliceVote.Seg<AeroColumnModel.Span>> col = new ArrayList<>(spans.size());
                for (AeroColumnModel.Span sp : spans) {
                    col.add(new SliceVote.Seg<>(sp.bottomY(), sp.topY() + 1, sp));
                }
                cols.add(col);
            }
        }

        List<SliceVote.Seg<AeroColumnModel.Span>> voted =
                SliceVote.vote(cols, AeroColumnModel.Span::state, sp -> sp.state().isAir());
        List<AeroColumnModel.Span> out = new ArrayList<>(voted.size());
        for (SliceVote.Seg<AeroColumnModel.Span> seg : voted) {
            AeroColumnModel.Span sp = seg.value();
            out.add(new AeroColumnModel.Span(seg.bottom(), seg.top() - 1, sp.state(), sp.biomeName(), sp.biomeHolder()));
        }
        return out;
    }

    /** Штатный DH chunk-gen этого уровня или {@code null}, если уровень DH ещё не зарегистрирован. */
    private DhWorldGenerator realGenerator() {
        DhWorldGenerator real = realGen;
        if (real != null) return real;
        synchronized (this) {
            if (realGen == null) {
                AbstractDhWorld world = SharedApi.getAbstractDhWorld();
                if (world != null && levelWrapper instanceof ILevelWrapper wrapper
                        && world.getLevel(wrapper) instanceof IDhServerLevel serverLevel) {
                    realGen = new DhWorldGenerator(serverLevel);
                }
            }
            return realGen;
        }
    }

    @Override
    public void preGeneratorTaskStart() {
        throughputLimits.onTaskStart();
        DhWorldGenerator real = realGen;
        if (real != null) real.preGeneratorTaskStart();
    }

    @Override
    public void close() {
        DhWorldGenerator real = realGen;
        if (real != null) real.close();
        if (leafLedger != null) leafLedger.close();
        LOGGER.info("[AeroWorld DH SeedGen] Closed generator for level: {}", levelWrapper.getDimensionName());
    }

    public AeroWorldChunkGenerator getGenerator() { return generator; }
    public IDhApiLevelWrapper getLevelWrapper() { return levelWrapper; }
    public AeroThroughputLimits getThroughputLimits() { return throughputLimits; }
    public AeroFastDistantTerrain getFastTerrain() { return fastTerrain; }
}