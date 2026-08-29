package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

public class Layer1FlatGenerator {

    // ── Границы слоя ──────────────────────────────────────────────────────────
    public static final int LAYER_MIN_Y  = -64;
    public static final int LAYER_MAX_Y  = 300;

    // Базовая высота равнин (старое фиксированное значение SURFACE_Y)
    private static final int BASE_SURFACE_Y   = 48;
    /** Публичный дубликат {@link #BASE_SURFACE_Y} */
    public static final int PUBLIC_BASE_SURFACE_Y = BASE_SURFACE_Y;

    // ── Реки / озёра ──────────────────────────────────────────────────────────
    public static final int WATER_LEVEL = BASE_SURFACE_Y - 4; // 44

    private final long seed;

    public Layer1FlatGenerator(long worldSeed) {
        this.seed = worldSeed;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Ванильный рельеф/вода
    // ══════════════════════════════════════════════════════════════════════════

    private volatile NoiseBasedChunkGenerator vanillaSource;
    private volatile RandomState vanillaRandomState;

    /** Итог расчёта колонки: где дно (твёрдая порода) и есть ли сверху вода. */
    public static final class ColumnProfile {
        public final int groundY;
        public final int waterY;
        public final boolean isShoreEdge;

        ColumnProfile(int groundY, int waterY) {
            this(groundY, waterY, false);
        }

        ColumnProfile(int groundY, int waterY, boolean isShoreEdge) {
            this.groundY     = groundY;
            this.waterY      = waterY;
            this.isShoreEdge = isShoreEdge;
        }
    }

    private static final LevelHeightAccessor VANILLA_COLUMN_HEIGHT = new LevelHeightAccessor() {
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight()         { return 384; }
    };

    public void setVanillaSource(NoiseBasedChunkGenerator vanillaGenerator, RandomState randomState) {
        this.vanillaSource      = vanillaGenerator;
        this.vanillaRandomState = randomState;
    }

    public boolean isInsideRingValley(int wx, int wz) {
        return false;
    }

    public String ringValleyBiome(int wx, int wz) {
        return "forest";
    }

    public int surfaceHeight(int wx, int wz) {
        if (vanillaSource == null || vanillaRandomState == null) return BASE_SURFACE_Y;
        return vanillaSource.getBaseHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, VANILLA_COLUMN_HEIGHT, vanillaRandomState);
    }

    public int topmostHeight(int wx, int wz) {
        if (vanillaSource == null || vanillaRandomState == null) return BASE_SURFACE_Y;
        return vanillaSource.getBaseHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, VANILLA_COLUMN_HEIGHT, vanillaRandomState);
    }

    @FunctionalInterface
    public interface BiomeResolver {
        ResourceLocation get(int wx, int wz);
    }
}