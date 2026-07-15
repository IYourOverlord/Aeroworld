package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;


public record Layer2Settings(
        double spawnChance,
        int    gridChunks,
        double minRadius,
        double maxRadius,
        int    maxHeight,
        int    yVariance,
        double bridgeChance,
        int    bridgeMaxRange
) {
    // ── Значения по умолчанию ─────────────────────────────────────────────────
    public static final double DEFAULT_SPAWN_CHANCE     = 0.20;
    public static final int    DEFAULT_GRID_CHUNKS      = 25;
    public static final double DEFAULT_MIN_RADIUS       = 25.0;
    public static final double DEFAULT_MAX_RADIUS       = 110.0;
    public static final int    DEFAULT_MAX_HEIGHT       = 60;
    public static final int    DEFAULT_Y_VARIANCE       = 15;
    public static final double DEFAULT_BRIDGE_CHANCE    = 0.35;
    public static final int    DEFAULT_BRIDGE_MAX_RANGE = 80;

    /** Пресет по умолчанию. */
    public static final Layer2Settings DEFAULT = new Layer2Settings(
            DEFAULT_SPAWN_CHANCE, DEFAULT_GRID_CHUNKS,
            DEFAULT_MIN_RADIUS, DEFAULT_MAX_RADIUS, DEFAULT_MAX_HEIGHT,
            DEFAULT_Y_VARIANCE, DEFAULT_BRIDGE_CHANCE, DEFAULT_BRIDGE_MAX_RANGE
    );

    // ── Codec ─────────────────────────────────────────────────────────────────
    public static final Codec<Layer2Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("spawn_chance",    DEFAULT_SPAWN_CHANCE )  .forGetter(Layer2Settings::spawnChance),
            Codec.INT   .optionalFieldOf("grid_chunks",     DEFAULT_GRID_CHUNKS  )  .forGetter(Layer2Settings::gridChunks),
            Codec.DOUBLE.optionalFieldOf("min_radius",      DEFAULT_MIN_RADIUS   )  .forGetter(Layer2Settings::minRadius),
            Codec.DOUBLE.optionalFieldOf("max_radius",      DEFAULT_MAX_RADIUS   )  .forGetter(Layer2Settings::maxRadius),
            Codec.INT   .optionalFieldOf("max_height",      DEFAULT_MAX_HEIGHT   )  .forGetter(Layer2Settings::maxHeight),
            Codec.INT   .optionalFieldOf("y_variance",      DEFAULT_Y_VARIANCE   )  .forGetter(Layer2Settings::yVariance),
            Codec.DOUBLE.optionalFieldOf("bridge_chance",   DEFAULT_BRIDGE_CHANCE)  .forGetter(Layer2Settings::bridgeChance),
            Codec.INT   .optionalFieldOf("bridge_max_range",DEFAULT_BRIDGE_MAX_RANGE).forGetter(Layer2Settings::bridgeMaxRange)
    ).apply(instance, Layer2Settings::new));

    // ── Валидация ─────────────────────────────────────────────────────────────
    public Layer2Settings {
        if (spawnChance < 0.0 || spawnChance > 1.0)
            throw new IllegalArgumentException("layer2.spawn_chance must be in [0,1], got: " + spawnChance);
        if (gridChunks < 1)
            throw new IllegalArgumentException("layer2.grid_chunks must be >= 1, got: " + gridChunks);
        if (minRadius <= 0 || maxRadius <= minRadius)
            throw new IllegalArgumentException("layer2: maxRadius must be > minRadius > 0");
        if (maxHeight <= 0)
            throw new IllegalArgumentException("layer2.max_height must be > 0, got: " + maxHeight);
        if (bridgeChance < 0.0 || bridgeChance > 1.0)
            throw new IllegalArgumentException("layer2.bridge_chance must be in [0,1], got: " + bridgeChance);
    }
}
