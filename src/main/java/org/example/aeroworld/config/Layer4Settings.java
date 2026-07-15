package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Настройки Layer 4 — Upper Sky Islands (Y 1900–2031).
 * Форма: медузы — купол + щупальца.
 */
public record Layer4Settings(
        double spawnChance,
        int    gridChunks,
        double minRadius,
        double maxRadius,
        int    minHeight,
        int    maxHeight,
        int    tentacleCount,
        int    tentacleMinLen,
        int    tentacleMaxLen,
        double tentacleBend
) {
    public static final double DEFAULT_SPAWN_CHANCE    = 0.05;
    public static final int    DEFAULT_GRID_CHUNKS     = 30;
    public static final double DEFAULT_MIN_RADIUS      = 25.0;
    public static final double DEFAULT_MAX_RADIUS      = 35.0;
    public static final int    DEFAULT_MIN_HEIGHT      = 10;
    public static final int    DEFAULT_MAX_HEIGHT      = 15;
    public static final int    DEFAULT_TENTACLE_COUNT  = 10;
    public static final int    DEFAULT_TENTACLE_MIN    = 90;
    public static final int    DEFAULT_TENTACLE_MAX    = 120;
    public static final double DEFAULT_TENTACLE_BEND   = 42.0;

    public static final Layer4Settings DEFAULT = new Layer4Settings(
            DEFAULT_SPAWN_CHANCE, DEFAULT_GRID_CHUNKS,
            DEFAULT_MIN_RADIUS, DEFAULT_MAX_RADIUS,
            DEFAULT_MIN_HEIGHT, DEFAULT_MAX_HEIGHT,
            DEFAULT_TENTACLE_COUNT, DEFAULT_TENTACLE_MIN,
            DEFAULT_TENTACLE_MAX, DEFAULT_TENTACLE_BEND
    );

    public static final Codec<Layer4Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("spawn_chance",     DEFAULT_SPAWN_CHANCE  ).forGetter(Layer4Settings::spawnChance),
            Codec.INT   .optionalFieldOf("grid_chunks",      DEFAULT_GRID_CHUNKS   ).forGetter(Layer4Settings::gridChunks),
            Codec.DOUBLE.optionalFieldOf("min_radius",       DEFAULT_MIN_RADIUS    ).forGetter(Layer4Settings::minRadius),
            Codec.DOUBLE.optionalFieldOf("max_radius",       DEFAULT_MAX_RADIUS    ).forGetter(Layer4Settings::maxRadius),
            Codec.INT   .optionalFieldOf("min_height",       DEFAULT_MIN_HEIGHT    ).forGetter(Layer4Settings::minHeight),
            Codec.INT   .optionalFieldOf("max_height",       DEFAULT_MAX_HEIGHT    ).forGetter(Layer4Settings::maxHeight),
            Codec.INT   .optionalFieldOf("tentacle_count",   DEFAULT_TENTACLE_COUNT).forGetter(Layer4Settings::tentacleCount),
            Codec.INT   .optionalFieldOf("tentacle_min_len", DEFAULT_TENTACLE_MIN  ).forGetter(Layer4Settings::tentacleMinLen),
            Codec.INT   .optionalFieldOf("tentacle_max_len", DEFAULT_TENTACLE_MAX  ).forGetter(Layer4Settings::tentacleMaxLen),
            Codec.DOUBLE.optionalFieldOf("tentacle_bend",    DEFAULT_TENTACLE_BEND ).forGetter(Layer4Settings::tentacleBend)
    ).apply(instance, Layer4Settings::new));

    public Layer4Settings {
        if (spawnChance < 0.0 || spawnChance > 1.0)
            throw new IllegalArgumentException("layer4.spawn_chance must be in [0,1], got: " + spawnChance);
        if (tentacleCount < 0 || tentacleCount > 32)
            throw new IllegalArgumentException("layer4.tentacle_count must be in [0,32], got: " + tentacleCount);
        if (tentacleMinLen < 0 || tentacleMaxLen < tentacleMinLen)
            throw new IllegalArgumentException("layer4: tentacleMaxLen must be >= tentacleMinLen >= 0");
    }
}
