package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Настройки Layer 3 — High Sky Islands (Y 1000–1100).
 * Форма: шары и эллипсоиды.
 */
public record Layer3Settings(
        double spawnChance,
        int    gridChunks,
        double minRadius,
        double maxRadius,
        int    maxHeight,
        double noiseDeform
) {
    public static final double DEFAULT_SPAWN_CHANCE  = 0.10;
    public static final int    DEFAULT_GRID_CHUNKS   = 26;
    public static final double DEFAULT_MIN_RADIUS    = 18.0;
    public static final double DEFAULT_MAX_RADIUS    = 50.0;
    public static final int    DEFAULT_MAX_HEIGHT    = 50;
    public static final double DEFAULT_NOISE_DEFORM  = 6.0;

    public static final Layer3Settings DEFAULT = new Layer3Settings(
            DEFAULT_SPAWN_CHANCE, DEFAULT_GRID_CHUNKS,
            DEFAULT_MIN_RADIUS, DEFAULT_MAX_RADIUS,
            DEFAULT_MAX_HEIGHT, DEFAULT_NOISE_DEFORM
    );

    public static final Codec<Layer3Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("spawn_chance", DEFAULT_SPAWN_CHANCE).forGetter(Layer3Settings::spawnChance),
            Codec.INT   .optionalFieldOf("grid_chunks",  DEFAULT_GRID_CHUNKS ).forGetter(Layer3Settings::gridChunks),
            Codec.DOUBLE.optionalFieldOf("min_radius",   DEFAULT_MIN_RADIUS  ).forGetter(Layer3Settings::minRadius),
            Codec.DOUBLE.optionalFieldOf("max_radius",   DEFAULT_MAX_RADIUS  ).forGetter(Layer3Settings::maxRadius),
            Codec.INT   .optionalFieldOf("max_height",   DEFAULT_MAX_HEIGHT  ).forGetter(Layer3Settings::maxHeight),
            Codec.DOUBLE.optionalFieldOf("noise_deform", DEFAULT_NOISE_DEFORM).forGetter(Layer3Settings::noiseDeform)
    ).apply(instance, Layer3Settings::new));

    public Layer3Settings {
        if (spawnChance < 0.0 || spawnChance > 1.0)
            throw new IllegalArgumentException("layer3.spawn_chance must be in [0,1], got: " + spawnChance);
        if (gridChunks < 1)
            throw new IllegalArgumentException("layer3.grid_chunks must be >= 1, got: " + gridChunks);
        if (minRadius <= 0 || maxRadius <= minRadius)
            throw new IllegalArgumentException("layer3: maxRadius must be > minRadius > 0");
    }
}
