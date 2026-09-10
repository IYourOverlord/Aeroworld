package org.example.aeroworld.worldgen.layer;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Layer1FlatGenerator — обёртка над Layer1TerrainGenerator для сохранения обратной совместимости.
 */
public class Layer1FlatGenerator {

    // ── Границы слоя ──────────────────────────────────────────────────────────
    public static final int LAYER_MIN_Y  = Layer1TerrainGenerator.MIN_Y;
    public static final int LAYER_MAX_Y  = Layer1TerrainGenerator.MAX_Y;

    public static final int WATER_LEVEL = Layer1TerrainGenerator.SEA_LEVEL;
    public static final int BASE_SURFACE_Y = 12;
    public static final int PUBLIC_BASE_SURFACE_Y = BASE_SURFACE_Y;

    private final long seed;
    private final Layer1TerrainGenerator terrainGenerator;

    public Layer1FlatGenerator(long worldSeed) {
        this.seed = worldSeed;
        this.terrainGenerator = new Layer1TerrainGenerator(worldSeed);
    }

    public Layer1FlatGenerator(Layer1TerrainGenerator terrainGen) {
        this.seed = terrainGen.getSeed();
        this.terrainGenerator = terrainGen;
    }

    public Layer1TerrainGenerator getTerrainGenerator() {
        return terrainGenerator;
    }

    public void setVanillaSource(NoiseBasedChunkGenerator vanillaGenerator, RandomState randomState) {
        // No-op: ванильный источник больше не используется для Layer 1
    }

    public int surfaceHeight(int wx, int wz) {
        return terrainGenerator.getHeight(wx, wz);
    }

    public int topmostHeight(int wx, int wz) {
        return terrainGenerator.getTopmostHeight(wx, wz);
    }
}