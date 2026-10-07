package org.example.aeroworld.worldgen.layer;

/**
 * Чистая математика биома-плато {@code ashen_plateau}: маска пятен, подъём и террасирование высоты.
 * Без зависимостей от Minecraft, поэтому проверяется {@link PlateauTerraceSelfCheck}.
 * Единый источник для рельефа ({@link Layer1TerrainGenerator#getHeight}) и выбора биома
 * ({@code AeroBiomeSource}): оба берут маску из {@link Layer1TerrainGenerator#getPlateauMask}.
 */
public final class PlateauTerrace {

    /** Подъём плато над местной землёй, блоков. */
    public static final double RISE = 45.0;
    /** Шаг террас по Y, блоков. */
    public static final double STEP = 6.0;
    /** Доля шага, занятая плоской площадкой (остальное, 25%, это уступ). */
    public static final double PLATFORM = 0.75;
    /** Частота шума пятен. */
    public static final double NOISE_FREQ = 0.0006;
    /** Пороги маски по значению шума. */
    public static final double NOISE_LO = 0.30, NOISE_HI = 0.36;
    /** Порог маски, выше которого колонка получает биом плато. */
    public static final double BIOME_MASK_MIN = 0.5;
    /** Условия биома: суша и вне хребтов. */
    public static final double BIOME_CONT_MIN = 0.10, BIOME_RIDGE_MAX = 0.3;

    private PlateauTerrace() {}

    public static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    /** Множитель суши и хребтов: 0 означает, что шум считать не нужно. */
    public static double gate(double continentality, double ridge) {
        return smoothstep(0.04, 0.22, continentality) * (1.0 - ridge);
    }

    /** Маска плато 0..1 по значению шума пятен, континентальности и силе хребта. */
    public static double mask(double noise, double continentality, double ridge) {
        return smoothstep(NOISE_LO, NOISE_HI, noise) * gate(continentality, ridge);
    }

    /**
     * Ступенчатая высота: на каждом шаге площадка (доля {@link #PLATFORM}), затем крутой уступ
     * на следующий уровень. Монотонна и непрерывна.
     */
    public static double terrace(double h, double step) {
        double t = h / step;
        double fl = Math.floor(t);
        return step * (fl + smoothstep(PLATFORM, 1.0, t - fl));
    }

    /** Высота с учётом плато: подъём на {@link #RISE} и террасы, смешанные по маске. */
    public static double apply(double height, double mask) {
        if (mask <= 0.0) return height;
        double raised = height + RISE * mask;
        return height + (terrace(raised, STEP) - height) * mask;
    }

    /** Биом плато: маска, суша, вне хребтов, не холодно. */
    public static boolean isPlateauBiome(double mask, double continentality, double ridge, double temp) {
        return mask > BIOME_MASK_MIN && continentality >= BIOME_CONT_MIN
                && ridge < BIOME_RIDGE_MAX && temp >= 0.0;
    }
}
