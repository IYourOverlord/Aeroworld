package org.example.aeroworld.worldgen.layer;

/**
 * Чистая математика биома-плато {@code ashen_plateau}: маска пятен, подъём и террасирование высоты.
 * Без зависимостей от Minecraft, поэтому проверяется {@link PlateauTerraceSelfCheck}.
 * Единый источник для рельефа ({@link Layer1TerrainGenerator#getHeight}) и выбора биома
 * ({@code AeroBiomeSource}): оба берут маску из {@link Layer1TerrainGenerator#getPlateauMask}.
 */
public final class PlateauTerrace {

    /** Подъём каймы плато над местной землёй, блоков. */
    public static final double RISE = 30.0;
    /** Глубина каньона в центре плато относительно каймы, блоков; дно не ниже {@link #FLOOR_Y} (иначе затопит). */
    public static final double DEPTH = 60.0;
    public static final double FLOOR_Y = 8.0;
    /** Шаг террас по Y, блоков. */
    public static final double STEP = 6.0;
    /** Доля шага, занятая плоской площадкой (остальное, 10%, это почти отвесный уступ). */
    public static final double PLATFORM = 0.9;
    /** Частота шума пятен: длина волны около 6700 блоков, плато и каньон занимают километры. */
    public static final double NOISE_FREQ = 0.00015;
    /** Пороги каймы (подъём) и центра (каньон) по значению шума; широкие, чтобы склон давал много ярусов. */
    public static final double NOISE_LO = 0.12, NOISE_HI = 0.22;
    public static final double CORE_LO = 0.24, CORE_HI = 0.34;
    /** Сдвиг фазы террас шумом (частота, амплитуда в блоках): плиты лежат на разных уровнях, а не по ровным контурам. */
    public static final double JITTER_FREQ = 0.004, JITTER = 3.0;
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

    /** Центр плато (каньон) 0..1: растёт глубже каймы, по значению шума. */
    public static double core(double noise, double continentality, double ridge) {
        return smoothstep(CORE_LO, CORE_HI, noise) * gate(continentality, ridge);
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

    /** Высота с учётом плато без каньона и сдвига (кайма: подъём и террасы). */
    public static double apply(double height, double mask) {
        return apply(height, mask, 0.0, 0.0);
    }

    /**
     * Высота с учётом плато: кайма поднимается на {@link #RISE}, центр ({@code core}) вырезается на {@link #DEPTH}
     * (дно не ниже {@link #FLOOR_Y}, если земля и так не ниже), затем террасы со сдвигом фазы {@code jitter}.
     */
    public static double apply(double height, double mask, double core, double jitter) {
        if (mask <= 0.0) return height;
        double lowered = Math.max(height + RISE * mask - DEPTH * core, Math.min(height, FLOOR_Y));
        double shift = jitter * mask;
        return height + (terrace(lowered + shift, STEP) - shift - height) * mask;
    }

    /** Биом плато: маска, суша, вне хребтов, не холодно. */
    public static boolean isPlateauBiome(double mask, double continentality, double ridge, double temp) {
        return mask > BIOME_MASK_MIN && continentality >= BIOME_CONT_MIN
                && ridge < BIOME_RIDGE_MAX && temp >= 0.0;
    }
}
