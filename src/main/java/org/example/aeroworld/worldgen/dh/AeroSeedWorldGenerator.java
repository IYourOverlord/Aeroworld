package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.cache.Layer1ColumnCache;
import org.example.aeroworld.worldgen.column.AeroColumnModel;
import org.example.aeroworld.worldgen.column.AeroColumnWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public AeroSeedWorldGenerator(AeroWorldChunkGenerator generator, IDhApiLevelWrapper levelWrapper) {
        this.generator = generator;
        this.levelWrapper = levelWrapper;
        this.columnWriter = new AeroColumnWriter(levelWrapper);
        this.throughputLimits = new AeroThroughputLimits();
        this.fastTerrain = new AeroFastDistantTerrain(generator.getSettings().dhOverride());
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_CHUNKS;
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
                // ВАЖНО: на текущем пути API_CHUNKS DH всегда запрашивает detailLevel=0
                // (AeroSeedWorldGenerator не переопределяет getLargestDataDetailLevel(),
                // поэтому все задачи сплитятся вниз до SECTION_BLOCK_DETAIL_LEVEL = 4×4 чанка,
                // см. WorldGenerationQueue.canGenerateDetailLevel). Значит с дефолтными
                // порогами (4/5/6) упрощение сейчас не срабатывает — оно станет активным,
                // если в будущем добавить coarse-генерацию (override getLargestDataDetailLevel
                // + субдискретизация столбцов в этом методе).
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