package org.example.aeroworld.worldgen.layer;

import org.example.aeroworld.worldgen.noise.AeroNoise;

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
    /** Пороги каймы (подъём) по значению шума: узкие, чтобы край плато был обрывом с несколькими уступами. */
    public static final double NOISE_LO = 0.12, NOISE_HI = 0.15;
    /**
     * Каньон: узкая извилистая линия по нулевому контуру отдельного шума (длина волны около 830 блоков).
     * {@code |шум| < CANYON_W0} даёт плоское дно, до {@code CANYON_W1} идёт стена (в блоках это десятки: градиент шума
     * около 0.0015 на блок), поэтому стена крутая и террасы шагом {@link #STEP} превращаются в узкие карнизы.
     */
    public static final double CANYON_FREQ = 0.0012;
    public static final double CANYON_W0 = 0.008, CANYON_W1 = 0.09;
    /** Каньон, овраги и останцы есть только в глубине плато (маска выше), чтобы не резать кайму насквозь. */
    public static final double CANYON_MASK_LO = 0.85, CANYON_MASK_HI = 1.0;
    /** Овраги: трещины помельче (длина волны около 220 блоков), глубиной {@code GULLY_DEPTH} от {@link #DEPTH}. */
    public static final double GULLY_FREQ = 0.0045, GULLY_W0 = 0.012, GULLY_W1 = 0.05, GULLY_DEPTH = 0.35;
    /** Останцы: пики шума (длина волны около 50 блоков, пик шире у основания) поднимают землю до {@code PILLAR_H}; террасы дают им карнизы. */
    public static final double PILLAR_FREQ = 0.02, PILLAR_LO = 0.40, PILLAR_HI = 0.65, PILLAR_H = 32.0;
    /**
     * Мосты: поперёк каньона по контуру шума {@code BRIDGE_FREQ} (в блоках: плита около 8, с краями около 20) каньон
     * прерывается плитой на уровне каймы. Мост есть не на каждом пересечении, а только где шум отбора выше
     * {@code BRIDGE_GATE_MIN}, и только если полоса пересекает каньон под углом (не вдоль него, иначе выйдет туннель).
     */
    public static final double BRIDGE_FREQ = 0.003, BRIDGE_W0 = 0.012, BRIDGE_W1 = 0.03;
    /** Шаг конечной разности (блоков) для направлений каньона и моста, косинус угла между нормалями, минимальный наклон шума моста. */
    public static final int BRIDGE_EPS = 2;
    public static final double BRIDGE_COS_LO = 0.35, BRIDGE_COS_HI = 0.65, BRIDGE_MIN_GRAD = 0.0015;
    public static final double BRIDGE_GATE_FREQ = 0.0009, BRIDGE_GATE_MIN = 0.1;
    /** Пустота под мостом вырезается там, где мост выше этого порога. */
    public static final double BRIDGE_VOID_MIN = 0.5;
    /** Толщина плиты моста и минимальный просвет под ней, блоков. */
    public static final int DECK = 4, CLEARANCE = 3;
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

    /** Полоса вокруг нулевого контура шума: 1 на контуре, 0 дальше {@code w1}. */
    private static double band(double n, double w0, double w1) {
        return 1.0 - smoothstep(w0, w1, Math.abs(n));
    }

    /** Глубина плато 0..1: 0 у каймы, 1 внутри. */
    private static double inner(double mask) {
        return smoothstep(CANYON_MASK_LO, CANYON_MASK_HI, mask);
    }

    /** Каньон 0..1 по шуму каньона {@code canyonNoise} и маске плато: 1 на линии нулевого контура, 0 вне стен и у каймы. */
    public static double core(double canyonNoise, double mask) {
        return band(canyonNoise, CANYON_W0, CANYON_W1) * inner(mask);
    }

    /** Овраг 0..{@link #GULLY_DEPTH} (в долях {@link #DEPTH}) по шуму оврагов и маске плато. */
    public static double gully(double gullyNoise, double mask) {
        return GULLY_DEPTH * band(gullyNoise, GULLY_W0, GULLY_W1) * inner(mask);
    }

    /** Останец 0..1 (в долях {@link #PILLAR_H}) по шуму останцев и маске плато. */
    public static double pillar(double pillarNoise, double mask) {
        return smoothstep(PILLAR_LO, PILLAR_HI, pillarNoise) * inner(mask);
    }

    /** Мост 0..1: полоса вокруг контура шума моста, отобранная шумом отбора. */
    public static double bridge(double bridgeNoise, double gateNoise) {
        return band(bridgeNoise, BRIDGE_W0, BRIDGE_W1) * smoothstep(BRIDGE_GATE_MIN - 0.05, BRIDGE_GATE_MIN + 0.05, gateNoise);
    }

    public static double canyonNoise(AeroNoise n, int wx, int wz) {
        return n.fbm2D(wx * CANYON_FREQ + 7919.0, wz * CANYON_FREQ - 7919.0, 2, 2.0, 0.5);
    }

    private static double bridgeNoise(AeroNoise n, int wx, int wz) {
        return n.noise2D(wx * BRIDGE_FREQ - 2293.0, wz * BRIDGE_FREQ + 6101.0);
    }

    /**
     * Сила моста в колонке. Сначала дёшево (два шума), направления считаются только на полосе моста: нормали полосы
     * и каньона должны быть почти перпендикулярны (полоса пересекает каньон), а наклон шума моста не мал
     * (иначе полоса расплывается в длинную плиту).
     */
    public static double bridgeAt(AeroNoise n, int wx, int wz) {
        double bn = bridgeNoise(n, wx, wz);
        double b = bridge(bn, n.noise2D(wx * BRIDGE_GATE_FREQ + 5003.0, wz * BRIDGE_GATE_FREQ - 1307.0));
        if (b <= 0.0) return 0.0;
        double cn = canyonNoise(n, wx, wz);
        double bx = bridgeNoise(n, wx + BRIDGE_EPS, wz) - bn, bz = bridgeNoise(n, wx, wz + BRIDGE_EPS) - bn;
        double cx = canyonNoise(n, wx + BRIDGE_EPS, wz) - cn, cz = canyonNoise(n, wx, wz + BRIDGE_EPS) - cn;
        double bl = Math.hypot(bx, bz), cl = Math.hypot(cx, cz);
        if (cl < 1e-9) return 0.0;
        double cos = Math.abs(bx * cx + bz * cz) / (bl * cl);
        return b * (1.0 - smoothstep(BRIDGE_COS_LO, BRIDGE_COS_HI, cos))
                * smoothstep(BRIDGE_MIN_GRAD * BRIDGE_EPS, 2.0 * BRIDGE_MIN_GRAD * BRIDGE_EPS, bl);
    }

    /**
     * Высота колонки с учётом плато: кайма, каньон (с мостами при {@code bridges}), овраги, останцы, террасы.
     * Шумы каньона, оврагов и останцев считаются только в глубине плато.
     */
    public static double column(AeroNoise n, double height, int wx, int wz, double mask, boolean bridges) {
        if (mask <= 0.0) return height;
        double jitter = n.noise2D(wx * JITTER_FREQ, wz * JITTER_FREQ) * JITTER;
        double core = 0.0, pillar = 0.0;
        if (mask > CANYON_MASK_LO) {
            core = core(canyonNoise(n, wx, wz), mask);
            if (bridges && core > 0.0) core *= 1.0 - bridgeAt(n, wx, wz);
            core = Math.max(core, gully(n.fbm2D(wx * GULLY_FREQ + 3571.0, wz * GULLY_FREQ + 1237.0, 2, 2.0, 0.5), mask));
            pillar = pillar(n.noise2D(wx * PILLAR_FREQ + 911.0, wz * PILLAR_FREQ + 4177.0), mask);
        }
        return apply(height, mask, core, jitter, pillar);
    }

    /** Есть ли пустота под мостом: мост достаточно выражен и между дном каньона и плитой есть просвет. */
    public static boolean hasBridgeVoid(double bridge, int floorY, int surfaceY) {
        return bridge >= BRIDGE_VOID_MIN && floorY + CLEARANCE <= surfaceY - DECK;
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
        return apply(height, mask, core, jitter, 0.0);
    }

    /** То же с останцами: {@code pillar} 0..1 поднимает землю на {@link #PILLAR_H} до террасирования. */
    public static double apply(double height, double mask, double core, double jitter, double pillar) {
        if (mask <= 0.0) return height;
        double lowered = Math.max(height + RISE * mask - DEPTH * core, Math.min(height, FLOOR_Y)) + PILLAR_H * pillar * mask;
        double shift = jitter * mask;
        return height + (terrace(lowered + shift, STEP) - shift - height) * mask;
    }

    /** Биом плато: маска, суша, вне хребтов, не холодно. */
    public static boolean isPlateauBiome(double mask, double continentality, double ridge, double temp) {
        return mask > BIOME_MASK_MIN && continentality >= BIOME_CONT_MIN
                && ridge < BIOME_RIDGE_MAX && temp >= 0.0;
    }
}
