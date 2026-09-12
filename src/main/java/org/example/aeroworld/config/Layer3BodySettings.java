package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Параметры небесных тел Layer 3 (полый метеорит / планета с кольцами).
 *
 * <p>Вынесено в отдельный вложенный record, потому что Mojang's
 * {@code RecordCodecBuilder.Instance.group(...)} поддерживает не более 16
 * полей за один вызов — вместе с базовыми 6 полями {@link Layer3Settings}
 * (spawn_chance, grid_chunks, min_radius, max_radius, max_height,
 * noise_deform) 12 полей тела превысили бы этот лимит (итого 18).
 * Сериализуется как вложенный объект {@code "body"} внутри
 * {@code aero_settings.layer3} в JSON измерения — см. {@link Layer3Settings#CODEC}.
 */
public record Layer3BodySettings(
        double wallThicknessMin,
        double wallThicknessMax,
        double craterThreshold,
        int    craterMinBottomThickness,
        double ringInnerFactorMin,
        double ringInnerFactorMax,
        double ringMidFactorMin,
        double ringMidFactorMax,
        double ringOuterFactorMin,
        double ringOuterFactorMax,
        int    ringCellSize,
        int    ringSparsityMask
) {
    public static final double DEFAULT_WALL_THICKNESS_MIN = 5.0;
    public static final double DEFAULT_WALL_THICKNESS_MAX = 8.0;
    /** Порог шума кратера [-1..1]: выше — сквозная воронка в верхнем своде метеорита. */
    public static final double DEFAULT_CRATER_THRESHOLD = 0.35;
    /** Минимальная толщина дна нижней полусферы метеорита (блоки), не пробивается кратерами. */
    public static final int DEFAULT_CRATER_MIN_BOTTOM_THICKNESS = 4;

    public static final double DEFAULT_RING_INNER_FACTOR_MIN = 1.4;
    public static final double DEFAULT_RING_INNER_FACTOR_MAX = 1.7;
    public static final double DEFAULT_RING_MID_FACTOR_MIN   = 1.85;
    public static final double DEFAULT_RING_MID_FACTOR_MAX   = 2.15;
    public static final double DEFAULT_RING_OUTER_FACTOR_MIN = 2.3;
    public static final double DEFAULT_RING_OUTER_FACTOR_MAX = 2.6;
    public static final int    DEFAULT_RING_CELL_SIZE      = 6;
    /** Астероид в ячейке существует, если (cellHash & mask) == 0 — 1/4 плотность. */
    public static final int    DEFAULT_RING_SPARSITY_MASK  = 3;

    public static final Layer3BodySettings DEFAULT = new Layer3BodySettings(
            DEFAULT_WALL_THICKNESS_MIN, DEFAULT_WALL_THICKNESS_MAX,
            DEFAULT_CRATER_THRESHOLD, DEFAULT_CRATER_MIN_BOTTOM_THICKNESS,
            DEFAULT_RING_INNER_FACTOR_MIN, DEFAULT_RING_INNER_FACTOR_MAX,
            DEFAULT_RING_MID_FACTOR_MIN, DEFAULT_RING_MID_FACTOR_MAX,
            DEFAULT_RING_OUTER_FACTOR_MIN, DEFAULT_RING_OUTER_FACTOR_MAX,
            DEFAULT_RING_CELL_SIZE, DEFAULT_RING_SPARSITY_MASK
    );

    public static final Codec<Layer3BodySettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("wall_thickness_min", DEFAULT_WALL_THICKNESS_MIN).forGetter(Layer3BodySettings::wallThicknessMin),
            Codec.DOUBLE.optionalFieldOf("wall_thickness_max", DEFAULT_WALL_THICKNESS_MAX).forGetter(Layer3BodySettings::wallThicknessMax),
            Codec.DOUBLE.optionalFieldOf("crater_threshold", DEFAULT_CRATER_THRESHOLD).forGetter(Layer3BodySettings::craterThreshold),
            Codec.INT   .optionalFieldOf("crater_min_bottom_thickness", DEFAULT_CRATER_MIN_BOTTOM_THICKNESS).forGetter(Layer3BodySettings::craterMinBottomThickness),
            Codec.DOUBLE.optionalFieldOf("ring_inner_factor_min", DEFAULT_RING_INNER_FACTOR_MIN).forGetter(Layer3BodySettings::ringInnerFactorMin),
            Codec.DOUBLE.optionalFieldOf("ring_inner_factor_max", DEFAULT_RING_INNER_FACTOR_MAX).forGetter(Layer3BodySettings::ringInnerFactorMax),
            Codec.DOUBLE.optionalFieldOf("ring_mid_factor_min", DEFAULT_RING_MID_FACTOR_MIN).forGetter(Layer3BodySettings::ringMidFactorMin),
            Codec.DOUBLE.optionalFieldOf("ring_mid_factor_max", DEFAULT_RING_MID_FACTOR_MAX).forGetter(Layer3BodySettings::ringMidFactorMax),
            Codec.DOUBLE.optionalFieldOf("ring_outer_factor_min", DEFAULT_RING_OUTER_FACTOR_MIN).forGetter(Layer3BodySettings::ringOuterFactorMin),
            Codec.DOUBLE.optionalFieldOf("ring_outer_factor_max", DEFAULT_RING_OUTER_FACTOR_MAX).forGetter(Layer3BodySettings::ringOuterFactorMax),
            Codec.INT   .optionalFieldOf("ring_cell_size", DEFAULT_RING_CELL_SIZE).forGetter(Layer3BodySettings::ringCellSize),
            Codec.INT   .optionalFieldOf("ring_sparsity_mask", DEFAULT_RING_SPARSITY_MASK).forGetter(Layer3BodySettings::ringSparsityMask)
    ).apply(instance, Layer3BodySettings::new));

    public Layer3BodySettings {
        if (wallThicknessMin <= 0 || wallThicknessMax < wallThicknessMin)
            throw new IllegalArgumentException("layer3.body: wallThicknessMax must be >= wallThicknessMin > 0");
        if (craterMinBottomThickness < 1)
            throw new IllegalArgumentException("layer3.body.crater_min_bottom_thickness must be >= 1");
        if (ringCellSize < 1)
            throw new IllegalArgumentException("layer3.body.ring_cell_size must be >= 1");
        if (ringSparsityMask < 0)
            throw new IllegalArgumentException("layer3.body.ring_sparsity_mask must be >= 0");
        if (!(ringInnerFactorMax < ringMidFactorMin && ringMidFactorMax < ringOuterFactorMin))
            throw new IllegalArgumentException("layer3.body: ring factor ranges must be strictly increasing with Cassini gaps");
    }
}