package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Настройки Layer 3 — High Sky Islands (Y 1000–1100).
 * Форма: полые метеориты (50%) и сферические планеты с кольцами (50%).
 *
 * <p>Параметры метеорита/колец вынесены в {@link Layer3BodySettings} —
 * см. его javadoc за объяснением (лимит 16 полей в
 * {@code RecordCodecBuilder.Instance.group}).
 */
public record Layer3Settings(
        double spawnChance,
        int    gridChunks,
        double minRadius,
        double maxRadius,
        int    maxHeight,
        double noiseDeform,
        Layer3BodySettings body
) {
    public static final double DEFAULT_SPAWN_CHANCE  = 0.10;
    public static final int    DEFAULT_GRID_CHUNKS   = 26;
    public static final double DEFAULT_MIN_RADIUS    = 22.0;
    public static final double DEFAULT_MAX_RADIUS    = 30.0;
    public static final int    DEFAULT_MAX_HEIGHT    = 50;
    public static final double DEFAULT_NOISE_DEFORM  = 6.0;

    public static final Layer3Settings DEFAULT = new Layer3Settings(
            DEFAULT_SPAWN_CHANCE, DEFAULT_GRID_CHUNKS,
            DEFAULT_MIN_RADIUS, DEFAULT_MAX_RADIUS,
            DEFAULT_MAX_HEIGHT, DEFAULT_NOISE_DEFORM,
            Layer3BodySettings.DEFAULT
    );

    public static final Codec<Layer3Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("spawn_chance", DEFAULT_SPAWN_CHANCE).forGetter(Layer3Settings::spawnChance),
            Codec.INT   .optionalFieldOf("grid_chunks",  DEFAULT_GRID_CHUNKS ).forGetter(Layer3Settings::gridChunks),
            Codec.DOUBLE.optionalFieldOf("min_radius",   DEFAULT_MIN_RADIUS  ).forGetter(Layer3Settings::minRadius),
            Codec.DOUBLE.optionalFieldOf("max_radius",   DEFAULT_MAX_RADIUS  ).forGetter(Layer3Settings::maxRadius),
            Codec.INT   .optionalFieldOf("max_height",   DEFAULT_MAX_HEIGHT  ).forGetter(Layer3Settings::maxHeight),
            Codec.DOUBLE.optionalFieldOf("noise_deform", DEFAULT_NOISE_DEFORM).forGetter(Layer3Settings::noiseDeform),
            Layer3BodySettings.CODEC.optionalFieldOf("body", Layer3BodySettings.DEFAULT).forGetter(Layer3Settings::body)
    ).apply(instance, Layer3Settings::new));

    public Layer3Settings {
        if (spawnChance < 0.0 || spawnChance > 1.0)
            throw new IllegalArgumentException("layer3.spawn_chance must be in [0,1], got: " + spawnChance);
        if (gridChunks < 1)
            throw new IllegalArgumentException("layer3.grid_chunks must be >= 1, got: " + gridChunks);
        if (minRadius <= 0 || maxRadius <= minRadius)
            throw new IllegalArgumentException("layer3: maxRadius must be > minRadius > 0");
        if (body.wallThicknessMax() * 2.0 >= minRadius)
            throw new IllegalArgumentException("layer3: body.wallThicknessMax*2 must be < minRadius (иначе полость исчезает)");
    }

    // ── Удобные проброс-геттеры (обратная совместимость вызывающего кода) ────
    public double wallThicknessMin()         { return body.wallThicknessMin(); }
    public double wallThicknessMax()         { return body.wallThicknessMax(); }
    public double craterThreshold()          { return body.craterThreshold(); }
    public int    craterMinBottomThickness() { return body.craterMinBottomThickness(); }
    public double ringInnerFactorMin()       { return body.ringInnerFactorMin(); }
    public double ringInnerFactorMax()       { return body.ringInnerFactorMax(); }
    public double ringMidFactorMin()         { return body.ringMidFactorMin(); }
    public double ringMidFactorMax()         { return body.ringMidFactorMax(); }
    public double ringOuterFactorMin()       { return body.ringOuterFactorMin(); }
    public double ringOuterFactorMax()       { return body.ringOuterFactorMax(); }
    public int    ringCellSize()             { return body.ringCellSize(); }
    public int    ringSparsityMask()         { return body.ringSparsityMask(); }
}