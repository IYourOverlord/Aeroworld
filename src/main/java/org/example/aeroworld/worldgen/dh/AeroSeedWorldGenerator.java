package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
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
                var lower = generator.getLowerIslands();
                var high = generator.getHighIslands();
                var upper = generator.getUpperIslands();
                var aeroBiomeSource = generator.getAeroBiomeSource();

                int endChunkX = chunkX + genRequestWidth;
                int endChunkZ = chunkZ + genRequestWidth;

                for (int cx = chunkX; cx < endChunkX; cx++) {
                    for (int cz = chunkZ; cz < endChunkZ; cz++) {
                        DhApiChunk chunk = DhApiChunk.create(cx, cz, minY, maxY);
                        int baseBlockX = cx << 4;
                        int baseBlockZ = cz << 4;

                        for (int lx = 0; lx < 16; lx++) {
                            int bx = baseBlockX + lx;
                            for (int lz = 0; lz < 16; lz++) {
                                int bz = baseBlockZ + lz;

                                List<AeroColumnModel.Span> spans = AeroColumnModel.buildSpans(
                                        bx, bz, minY, maxY,
                                        l1Terrain, lower, high, upper,
                                        aeroBiomeSource, true
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