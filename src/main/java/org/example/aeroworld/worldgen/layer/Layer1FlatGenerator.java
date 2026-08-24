package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.noise.AeroNoise;


public class Layer1FlatGenerator {

    // ── Границы слоя ──────────────────────────────────────────────────────────
    public static final int LAYER_MIN_Y  = -64;
    // Поднято с 50 до 300 по просьбе: слой 1 должен генерироваться близко
    // к ванильным значениям высоты (горы/холмы), а не быть обрезанным на 50.
    public static final int LAYER_MAX_Y  = 300;

    // Базовая высота равнин (старое фиксированное значение SURFACE_Y) —
    // теперь это лишь ОТПРАВНАЯ точка шумовой heightmap, а не потолок.
    private static final int BASE_SURFACE_Y   = 48;
    /** Публичный дубликат {@link #BASE_SURFACE_Y} — используется вне класса
     *  (например {@link MountainForestScatter}) для определения "высоты горы"
     *  относительно базового уровня равнины. */
    public static final int PUBLIC_BASE_SURFACE_Y = BASE_SURFACE_Y;
    // Насколько высоко шум может поднять рельеф над базовой высотой равнин.
    // Было 130 — при таком размахе даже "полная" гора выглядела как высокий
    // холм, а не гора, и визуально сливалась с предгорьями. Подняли до 190,
    // чтобы настоящие пики/плато были ощутимо выше окружающих холмов —
    // сейчас основная жалоба именно на отсутствие выраженных гор.
    private static final int MAX_EXTRA_HEIGHT = 190;
    // ── Внутрихребтовая рябь (см. computeLandHeight, блок "detailNoise") ────
    // Амплитуды в блоках для трёх масштабов шума, добавляемого прямо к
    // высоте внутри горных регионов — даёт вторичные вершины, седловины и
    // мелкие скальные уступы поверх гладкой огибающей архетипа.
    private static final double MOUNTAIN_DETAIL_LARGE_AMPL = 22.0; // вторичные гребни/седловины (~40-90 блоков)
    private static final double MOUNTAIN_DETAIL_MED_AMPL   = 11.0; // средние уступы (~15-30 блоков)
    private static final double MOUNTAIN_DETAIL_FINE_AMPL  = 5.0;  // мелкая скальная рябь (~8-15 блоков)
    // Не даём пикам подходить ближе чем на 30 блоков к потолку слоя —
    // чтобы оставался запас неба над самой высокой горой.
    private static final int PEAK_SKY_BUFFER  = 30;
    // Амплитуда лёгкой холмистости на равнинах (там, где mountainMask≈0) —
    // равнины не идеально плоские, но и не похожи на горы.
    // Было 6 — почти незаметно с высоты полёта, подняли до 14.
    private static final int FLATLAND_BUMP    = 14;


    // ══════════════════════════════════════════════════════════════════════════
    // ЭТАП 1 — Многослойный noise router (см. ТЗ "переход генератора рельефа
    // AeroWorld на многослойный noise router").
    //
    // Архитектура по образцу Minecraft 1.18+ density functions: несколько
    // НЕЗАВИСИМЫХ низкочастотных скалярных полей шума + явные spline-таблицы
    // (input, output), которые их комбинируют в итоговую высоту. Значение
    // высоты в любой точке (wx, wz) — ЧИСТАЯ функция этих полей, без понятия
    // "региональная ячейка"/"архетип" и без блендинга между ячейками (то,
    // что раньше упиралось в CELL_BLEND_WIDTH/homeRidge-подавление, теперь
    // отсутствует как класс проблем — рельеф везде считается одной и той же
    // формулой).
    //
    // Что ВРЕМЕННО отсутствует на этом этапе (см. ТЗ, "Этап 2"):
    //   • Региональные архетипы хребтов (PARALLEL/RING/ALPINE/PLATEAU/
    //     TERRACE/SHATTERED) и вся система CELL_SIZE-ячеек — удалены
    //     безвозвратно, они и были источником швов.
    //   • Каньон (CANYON_*, hasCanyon) — временно недоступен, возвращается
    //     на этапе 2 как модификатор поверх erosion/PV, а не отдельная
    //     региональная фича.
    //   • riverMountainRepel()/расширенная адаптивная coastalShoreBlend —
    //     не требуются: базовая высота уже физически низкая на низком
    //     continentalness (океан) и высокая на высоком PV (горы), поэтому
    //     существующий SHORE_BLEND/BEACH_LEDGE-код ниже по файлу продолжает
    //     работать как есть, без специальных случаев.
    // ══════════════════════════════════════════════════════════════════════════

    // ── Поле 1: continentalness ──────────────────────────────────────────
    // "Насколько вглубь материка": -1 = океан/побережье, +1 = глубокий
    // материк. Самая низкая частота в файле — континенты должны быть
    // КРУПНЕЕ прежних океанских пятен (raw fbm2D диапазон примерно ±0.7 при
    // 4 октавах, нормализуем делением на NORM ниже до ~[-1,1]).
    private static final double CONTINENTALNESS_FREQ = 0.00050;
    private static final int    CONTINENTALNESS_OCTAVES = 4;
    private static final double CONTINENTALNESS_NORM  = 0.75; // делитель raw fbm -> примерно [-1,1]

    // ── Поле 2: erosion ────────────────────────────────────────────────
    // "Насколько рельеф сглажен": +1 = плоские равнины, -1 = резкий,
    // изрезанный рельеф с большим разбросом высот. Частота выше, чем у
    // continentalness, но ниже, чем у PV — своя, независимая, отдельный seed.
    private static final double EROSION_FREQ    = 0.0018;
    private static final int    EROSION_OCTAVES = 3;
    private static final double EROSION_NORM    = 0.72;

    // ── Поле 3: peaksAndValleys (PV) ──────────────────────────────────────
    // Локальная "пикообразность" [-1,1]: -1 глубокая долина, 0 предгорья,
    // +1 острый пик. Более высокая частота, чем у erosion — формирует
    // силуэт отдельных гор/долин напрямую (замена mountainMask+ridge).
    private static final double PV_FREQ    = 0.0055;
    private static final int    PV_OCTAVES = 4;

    // ── Поле 4: weirdness ──────────────────────────────────────────────
    // Вспомогательное поле для асимметрии PV — тот же приём, что в ваниле:
    // PV' = 1 - |3|weirdness| - 2|. Собственная частота/seed, независимая
    // от остальных трёх полей.
    private static final double WEIRDNESS_FREQ    = 0.0027;
    private static final int    WEIRDNESS_OCTAVES = 3;
    private static final double WEIRDNESS_NORM    = 0.72;

    // ── Сплайн 1: baseByContinentalness(continentalness) → базовая высота ──
    // Точки (x=continentalness, y=высота в блоках). BASE_SURFACE_Y=48 —
    // текущая равнина при continentalness≈0, как и раньше.
    private static final double[][] BASE_BY_CONTINENTALNESS = {
            { -1.00, -40 },  // глубокий океан
            { -0.55, -12 },  // шельф
            { -0.30,  40 },  // берег
            {  0.00,  48 },  // равнина = BASE_SURFACE_Y
            {  0.40,  55 },  // предгорья
            {  1.00,  60 },  // глубокий материк
    };

    // ── Сплайн 2: amplitudeByErosion(erosion) → амплитуда рельефа ─────────
    // Высокая erosion (сглажено) => мало амплитуды; низкая (изрезано) =>
    // максимальная амплитуда MAX_EXTRA_HEIGHT, как раньше у гор.
    private static final double[][] AMPLITUDE_BY_EROSION = {
            { -1.00, MAX_EXTRA_HEIGHT },  // максимально изрезано
            { -0.30, 150 },
            {  0.00,  90 },               // средне
            {  0.55,  30 },
            {  1.00,   5 },               // почти плоско
    };

    // ── Сплайн 3: peakShape(PV) → форма пика [-0.2..1.0] ───────────────────
    private static final double[][] PEAK_SHAPE_BY_PV = {
            { -1.00, -0.20 }, // лёгкая ложбина
            { -0.30,  0.00 },
            {  0.00,  0.00 }, // средний рельеф/предгорья
            {  0.60,  0.50 }, // предгорье
            {  1.00,  1.00 }, // полный пик
    };

    // ── Сплайн 4: roughnessSpline(erosion) → амплитуда мелкого шума ──────
    // Заменяет старый roughFactor/hills01/FLATLAND_BUMP одной кривой: чем
    // ниже erosion (изрезаннее регион), тем сильнее мелкая рябь поверх
    // базовой сплайновой высоты.
    private static final double[][] ROUGHNESS_BY_EROSION = {
            { -1.00, 1.00 },  // сильно изрезано — максимум мелкого шума
            {  0.00, 0.55 },
            {  1.00, 0.15 },  // почти плоско — минимум ряби
    };
    // Амплитуда мелкого шума в блоках при roughnessSpline()=1.0 (масштабируется
    // самой сплайн-кривой ниже 1.0 на равнинах). Заменяет старый FLATLAND_BUMP.
    private static final double ROUGHNESS_MAX_AMPL = FLATLAND_BUMP;

    /**
     * Линейная интерполяция по явной таблице точек {@code (x, y)},
     * ОТСОРТИРОВАННОЙ по x по возрастанию. За пределами диапазона таблицы —
     * значение крайней точки (clamp), без экстраполяции.
     *
     * <p>Единственная точка входа для всех сплайнов этапа 1 — намеренно
     * тривиальная реализация (линейная, не smoothstep), чтобы таблицы можно
     * было калибровать по скриншотам без изменения структуры кода (см. ТЗ,
     * п.5 общих принципов).
     */
    private static double splineLinear(double[][] points, double x) {
        if (x <= points[0][0]) return points[0][1];
        int last = points.length - 1;
        if (x >= points[last][0]) return points[last][1];
        for (int i = 0; i < last; i++) {
            double x0 = points[i][0],     y0 = points[i][1];
            double x1 = points[i + 1][0], y1 = points[i + 1][1];
            if (x >= x0 && x <= x1) {
                double t = (x1 == x0) ? 0.0 : (x - x0) / (x1 - x0);
                return y0 + (y1 - y0) * t;
            }
        }
        return points[last][1]; // недостижимо при отсортированной таблице
    }

    /**
     * Всегда false на этапе 1 — кольцевые кальдеры (RidgeArchetype.RING)
     * упразднены вместе со всей региональной системой архетипов (см. ТЗ,
     * п.1.4). Метод оставлен как совместимая заглушка, т.к. вызывается
     * извне из {@link org.example.aeroworld.worldgen.biome.AeroBiomeSource}
     * (см. javadoc там же) — убирать сигнатуру без правки вызывающего кода
     * нельзя. Вернётся к реальной логике, если на будущем этапе кольцевые
     * низины будут пересажены на новую основу (сейчас в ТЗ не запланировано).
     */
    public boolean isInsideRingValley(int wx, int wz) {
        return false;
    }

    /**
     * См. {@link #isInsideRingValley} — не вызывается ни при каких условиях,
     * пока {@link #isInsideRingValley} возвращает false, но сигнатура
     * сохранена для совместимости с {@code AeroBiomeSource}.
     */
    public String ringValleyBiome(int wx, int wz) {
        return "forest";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Высота поверхности (горы/холмы) — noise router (4 скалярных поля + сплайны)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Высота поверхности (верхний твёрдый блок) в колонке (wx, wz).
     *
     * <p>Этап 1 переход на noise router (см. ТЗ): вместо региональных ячеек
     * и архетипов хребтов высота теперь — чистая функция четырёх независимых
     * скалярных полей (continentalness, erosion, peaksAndValleys, weirdness)
     * и явных spline-таблиц, которые их комбинируют:
     *
     * <pre>
     *   height = baseByContinentalness(continentalness)
     *          + amplitudeByErosion(erosion) * peakShape(PV')
     * </pre>
     *
     * где PV' — peaksAndValleys, скорректированный weirdness по стандартной
     * ванильной формуле {@code PV' = 1 - |3|weirdness| - 2|}, дающей PV
     * асимметрию (пики не идеально симметричны относительно 0).
     *
     * <p>Пещеры/горные туннели (FLOOR_*, CEIL_*, MTUN_*) не тронуты — они
     * читают только итоговый {@code groundY}, а не внутреннее устройство
     * этого метода. Реки/озёра/океан (columnProfile) тоже не тронуты на
     * этом этапе — они по-прежнему сравнивают {@code computeLandHeight}
     * с {@code WATER_MAX_LAND_Y} постфактум (Этап 2 пересадит их на
     * continentalness/erosion/PV напрямую).
     *
     * <p>ВАЖНО: этот метод — источник истины для высоты поверхности.
     * {@code AeroWorldChunkGenerator} (getBaseHeight/getBaseColumn/
     * applyLayer1Surface) и {@code TerrainColumnSampler} обязаны вызывать
     * именно его, а не полагаться на старую константу SURFACE_Y — иначе
     * height-map запросы, покраска поверхности и валидация структур разъедутся
     * с фактическим рельефом, который рисует {@link #fillChunk}.
     */
    private int computeLandHeight(int wx, int wz) {
        // ── 4 независимых скалярных поля, каждое — свой участок частотного
        //    спектра и свой офсет шума (дешёвый способ развести поля без
        //    отдельных AeroNoise-инстансов на каждое, как уже принято в
        //    этом файле для detailLarge/detailMed/detailFine и т.п.) ──────
        double continentalnessRaw = heightNoise.fbm2D(
                wx * CONTINENTALNESS_FREQ + 700000, wz * CONTINENTALNESS_FREQ + 700000,
                CONTINENTALNESS_OCTAVES, 2.0, 0.5);
        double continentalness = clamp11(continentalnessRaw / CONTINENTALNESS_NORM);

        double erosionRaw = heightNoise.fbm2D(
                wx * EROSION_FREQ + 800000, wz * EROSION_FREQ + 800000,
                EROSION_OCTAVES, 2.0, 0.5);
        double erosion = clamp11(erosionRaw / EROSION_NORM);

        double pvRaw = heightNoise.fbm2D(
                wx * PV_FREQ + 900000, wz * PV_FREQ + 900000,
                PV_OCTAVES, 2.0, 0.5);
        double pv = clamp11(pvRaw); // fbm2D уже отдаёт примерно [-1,1] при 4 октавах, доп. нормализация не нужна

        double weirdnessRaw = heightNoise.fbm2D(
                wx * WEIRDNESS_FREQ + 1000000, wz * WEIRDNESS_FREQ + 1000000,
                WEIRDNESS_OCTAVES, 2.0, 0.5);
        double weirdness = clamp11(weirdnessRaw / WEIRDNESS_NORM);

        // Стандартная ванильная формула "peaks and valleys from weirdness":
        // даёт асимметрию PV (пики острее долин, как в реальном рельефе)
        // вместо идеально симметричного профиля вокруг 0.
        double pvPrime = 1.0 - Math.abs(3.0 * Math.abs(weirdness) - 2.0);
        // Смешиваем "сырой" PV с его weirdness-скорректированной версией —
        // полностью заменять pv на pvPrime не нужно (PV и так несёт
        // основную пространственную структуру силуэта), нужна только
        // асимметрия поверх неё.
        double pvFinal = clamp11(0.6 * pv + 0.4 * pvPrime);

        // ── Сплайны: базовая высота + амплитуда * форма пика ────────────
        double base      = splineLinear(BASE_BY_CONTINENTALNESS, continentalness);
        double amplitude = splineLinear(AMPLITUDE_BY_EROSION, erosion);
        double peakShape  = splineLinear(PEAK_SHAPE_BY_PV, pvFinal);

        double landHeightD = base + amplitude * peakShape;

        // ── roughnessSpline(erosion): мелкий шум поверх сплайновой высоты,
        //    везде (в т.ч. на равнинах), но амплитуда зависит от erosion —
        //    заменяет старые roughFactor/hills01/FLATLAND_BUMP одной кривой.
        double roughness01 = splineLinear(ROUGHNESS_BY_EROSION, erosion);
        double hillsRaw = heightNoise.fbm2D(wx * 0.02 + 3000, wz * 0.02 + 3000, 3, 2.0, 0.5);
        landHeightD += hillsRaw * ROUGHNESS_MAX_AMPL * roughness01;

        int h = (int) Math.round(landHeightD);

        // Запас неба над самым высоким пиком — тот же приём, что и раньше
        // (мягкий clamp не требуется здесь: сплайн AMPLITUDE_BY_EROSION уже
        // ограничен MAX_EXTRA_HEIGHT сверху, поэтому итоговая высота не
        // подходит к потолку слоя резко/линейно так, как это делал старый
        // heightBoost у ALPINE/SHATTERED архетипов — их больше нет).
        return Math.min(h, LAYER_MAX_Y - PEAK_SKY_BUFFER);
    }

    /** Классический smoothstep: 0 ниже edge0, 1 выше edge1, плавный переход между ними. */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Зажимает x в диапазон [-1, 1] — используется полями noise router'а этапа 1. */
    private static double clamp11(double x) {
        return Math.max(-1.0, Math.min(1.0, x));
    }


    /**
     * true, если (wx, wz) — часть переходной пляжной полосы у океана: суша,
     * которая уже находится в зоне блендинга с океаном (см. columnProfile),
     * но ещё не ушла под воду. Использует ТОЧНО ту же формулу, что и
     * океанская ветка columnProfile, чтобы граница биома "aeroworld:beach"
     * (см. AeroBiomeSource) совпадала с фактической полосой песка в рельефе.
     */
    public boolean isBeachColumn(int wx, int wz) {
        int landHeight = computeLandHeight(wx, wz);
        if (landHeight > WATER_MAX_LAND_Y) return false; // горы — пляжа не бывает

        double oceanN = heightNoise.fbm2D(wx * 0.0009 + 90000, wz * 0.0009 + 90000, 5, 2.0, 0.5);
        double oceanW = smoothstep(OCEAN_THRESHOLD + SHORE_BLEND, OCEAN_THRESHOLD - SHORE_BLEND, oceanN);
        if (oceanW <= 0.15) return false; // далеко от океана вообще

        double depth01 = smoothstep(OCEAN_THRESHOLD, OCEAN_DEEP_AT, oceanN);
        int bedDepth = OCEAN_MIN_DEPTH + (int) Math.round(depth01 * (OCEAN_MAX_DEPTH - OCEAN_MIN_DEPTH));
        int oceanFloorY = WATER_LEVEL - bedDepth;
        int blendedY = (int) Math.round(landHeight + (oceanFloorY - landHeight) * oceanW);

        // Ещё суша (не ушла под уровень воды) — значит именно пляжная кромка,
        // а не сам океан (тот уже отдельно обрабатывается в columnProfile).
        return blendedY >= WATER_LEVEL;
    }

    /**
     * Есть ли в точке (wx, wz) открытая океанская вода (не пляж, не река,
     * не мелкое озеро)? Использует ТОЧНО ту же формулу, что и океанская
     * ветка columnProfile/isBeachColumn — чтобы биом "aeroworld:*_ocean"
     * (см. AeroBiomeSource) совпадал с фактической водой в рельефе.
     *
     * <p>Только чтение — не изменяет и не дублирует физическую генерацию
     * рельефа, лишь переиспользует тот же 2D-шум для решения о биоме.
     */
    public boolean isOceanColumn(int wx, int wz) {
        int landHeight = computeLandHeight(wx, wz);
        if (landHeight > WATER_MAX_LAND_Y) return false;

        double oceanN = heightNoise.fbm2D(wx * 0.0009 + 90000, wz * 0.0009 + 90000, 5, 2.0, 0.5);
        double oceanW = smoothstep(OCEAN_THRESHOLD + SHORE_BLEND, OCEAN_THRESHOLD - SHORE_BLEND, oceanN);
        if (oceanW <= 0.0) return false;

        double depth01 = smoothstep(OCEAN_THRESHOLD, OCEAN_DEEP_AT, oceanN);
        int bedDepth = OCEAN_MIN_DEPTH + (int) Math.round(depth01 * (OCEAN_MAX_DEPTH - OCEAN_MIN_DEPTH));
        int oceanFloorY = WATER_LEVEL - bedDepth;
        int blendedY = (int) Math.round(landHeight + (oceanFloorY - landHeight) * oceanW);
        return blendedY < WATER_LEVEL;
    }

    /**
     * Глубина океана в точке (wx, wz) как доля 0..1 (0 = мелко/у берега,
     * 1 = максимальная глубина OCEAN_DEEP_AT). Вызывать только после
     * {@link #isOceanColumn} вернувшего true — иначе результат не имеет
     * смысла. Используется чтобы отличить обычный "aeroworld:ocean" от
     * "aeroworld:deep_ocean" по фактической глубине воды в этой точке.
     */
    public double oceanDepth01(int wx, int wz) {
        double oceanN = heightNoise.fbm2D(wx * 0.0009 + 90000, wz * 0.0009 + 90000, 5, 2.0, 0.5);
        return smoothstep(OCEAN_THRESHOLD, OCEAN_DEEP_AT, oceanN);
    }

    /**
     * Есть ли в точке (wx, wz) река? Та же формула, что использует
     * columnProfile для русла реки — только чтение, для решения о биоме.
     */
    public boolean isRiverColumn(int wx, int wz) {
        int landHeight = computeLandHeight(wx, wz);
        if (landHeight > WATER_MAX_LAND_Y) return false;
        if (isOceanColumn(wx, wz)) return false; // океан имеет приоритет, как и в columnProfile

        double riverN    = heightNoise.fbm2D(wx * 0.003 + 50000, wz * 0.003 + 50000, 4, 2.0, 0.5);
        double riverDist = Math.abs(riverN);
        double riverW = smoothstep(RIVER_HALF_WIDTH + SHORE_BLEND, RIVER_HALF_WIDTH - SHORE_BLEND, riverDist);
        if (riverW <= 0.0) return false;

        int riverFloorY = Math.min(landHeight, WATER_LEVEL) - RIVER_BED_DEPTH;
        int blendedY = (int) Math.round(landHeight + (riverFloorY - landHeight) * riverW);
        return blendedY < WATER_LEVEL;
    }

    /** Итог расчёта колонки: где дно (твёрдая порода) и есть ли сверху вода. */
    public static final class ColumnProfile {
        /** Верхний твёрдый блок (дно реки/озера, если waterY != -1; иначе сама поверхность). */
        public final int groundY;
        /** Y поверхности воды, или -1 если это суша. */
        public final int waterY;
        /**
         * true — колонка попадает в узкую прибрежную полосу (BEACH_EDGE_WIDTH +
         * BEACH_LEDGE_WIDTH от уреза воды), где рельеф прижат к ступенчатому
         * профилю applyBeachFlat. Используется fillChunk/applyLayer1Surface,
         * чтобы вместо монолитного песка оставить только тонкую непроницаемую
         * линию у воды, а под ней — полую полость (см. SHORE_HOLLOW_DEPTH).
         * Для суши без водоёма рядом (applyBeachFlat не вызывался) — всегда false.
         */
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

    /**
     * Прижимает высоту суши рядом с кромкой воды к ступенчатому профилю —
     * см. константы BEACH_EDGE_ BEACH_LEDGE_*. Вызывать сразу после
     * вычисления blendedY (суша, ещё выше уровня воды) в океан/река/озеро
     * ветках {@link #columnProfile}.
     *
     * <p>Расстояние до уреза воды оценивается ГЕОМЕТРИЧЕСКИ (в блоках по
     * XZ), а не по перепаду высоты — иначе на крутом берегу высотная
     * эвристика недооценивала бы расстояние. Оценка через локальный
     * градиент weight: если между точкой (wx,wz) и точкой в 1 блоке в
     * сторону суши weight падает на Δw, то grad = Δw / 1, и полный переход
     * (Δw=1) занимает примерно 1/grad блоков — линеаризация плавного
     * smoothstep-порога.
     *
     * <p>Профиль по расстоянию от уреза, три зоны подряд:
     * <ol>
     *   <li>0..BEACH_EDGE_WIDTH (1 блок) — высота ЖЁСТКО BEACH_EDGE_Y,
     *       вровень с водой, касается её.</li>
     *   <li>BEACH_EDGE_WIDTH..+BEACH_LEDGE_WIDTH (6 блоков) — высота
     *       ЖЁСТКО BEACH_LEDGE_Y, на 1 блок ниже первой линии (уже под
     *       уровнем воды) — сюда вода может растекаться, если займёт
     *       место песка, не упираясь в стену.</li>
     *   <li>дальше, на протяжении BEACH_LEDGE_BLEND — плавный (smoothstep)
     *       подъём от BEACH_LEDGE_Y обратно к обычной высоте суши.</li>
     * </ol>
     *
     * @param blendedY  высота суши, уже посчитанная обычным способом
     * @param weight    вес воды в этой точке (0..1) — oceanW/riverW/lakeW
     * @param gradX     |d(weight)/dx| — конечная разность на 1 блок по X
     * @param gradZ     |d(weight)/dz| — конечная разность на 1 блок по Z.
     *                  Берём ПОЛНУЮ 2D-норму градиента, а не только вдоль
     *                  X — если береговая линия идёт по диагонали, градиент
     *                  вдоль одной оси занижен относительно истинного
     *                  градиента по нормали к берегу, и оценка расстояния
     *                  через него завышается (карниз "растягивается" на
     *                  местности сильнее заданных 6 блоков — именно это
     *                  было видно на скриншоте: широкая плоская зона у
     *                  воды без чёткой ступени).
     **/
    /** Результат {@link #applyBeachFlat}: итоговая высота + признак "это узкая
     *  прибрежная полоса" (в пределах BEACH_EDGE_WIDTH+BEACH_LEDGE_WIDTH от
     *  уреза воды), которую fillChunk/applyLayer1Surface должны делать полой
     *  внутри (см. SHORE_HOLLOW_DEPTH). */
    private static final class BeachFlatResult {
        final int y;
        final boolean isShoreEdge;
        BeachFlatResult(int y, boolean isShoreEdge) {
            this.y = y;
            this.isShoreEdge = isShoreEdge;
        }
    }

    private BeachFlatResult applyBeachFlat(int blendedY, double weight, double gradX, double gradZ) {
        double gradPerBlock = Math.sqrt(gradX * gradX + gradZ * gradZ);
        if (weight <= 0.0 || gradPerBlock <= 1e-6) return new BeachFlatResult(blendedY, false);

        // Расстояние вглубь суши от точки, где weight пересекает "линию
        // уреза" — см. подробное объяснение в javadoc выше.
        double distanceBlocks = weight / gradPerBlock;

        if (distanceBlocks <= BEACH_EDGE_WIDTH) {
            // Первая линия — вровень с водой, касается её напрямую.
            return new BeachFlatResult(Math.min(blendedY, BEACH_EDGE_Y), true);
        }

        double ledgeEnd = BEACH_EDGE_WIDTH + BEACH_LEDGE_WIDTH;
        if (distanceBlocks <= ledgeEnd) {
            // Карниз — жёстко на 1 блок ниже первой линии, никакого
            // смешивания с исходной blendedY. Это и даёт видимое
            // понижение рельефа сразу после береговой кромки, а не просто
            // отодвинутую вглубь линию воды.
            return new BeachFlatResult(Math.min(blendedY, BEACH_LEDGE_Y), true);
        }

        // ── Адаптивная ширина подъёма от карниза к полной высоте суши ────────
        // Раньше BEACH_LEDGE_BLEND (фиксированные 3 блока) использовался
        // напрямую как ширина smoothstep-перехода — независимо от того,
        // насколько высоко нужно подняться. У равнины (blendedY ~50-60)
        // это давало пологий подъём и смотрелось нормально. Но у подножия
        // высокой горы рядом с водой (blendedY может быть 150-250+) те же
        // 3 блока означали подъём на сотню с лишним блоков практически
        // вертикально — ровная "стена" из голой породы прямо у кромки
        // воды (см. баг-репорт со скриншотом: терракота/камень обрывом
        // у подножия горы).
        //
        // Растягиваем зону пропорционально перепаду высоты: чем выше нужно
        // подняться от карниза до blendedY, тем шире (в блоках) должна быть
        // зона перехода, чтобы угол склона оставался физически разумным.
        // BEACH_LEDGE_BLEND остаётся минимальной шириной (для низких
        // равнинных берегов ничего не меняется), риск-фактор HEIGHT_TO_
        // BLEND_RATIO задаёт максимальный "уклон" подъёма в блоках
        // перехода на 1 блок высоты.
        double heightToClimb = Math.max(0.0, blendedY - BEACH_LEDGE_Y);
        double adaptiveBlend = Math.max(BEACH_LEDGE_BLEND, heightToClimb * BEACH_LEDGE_HEIGHT_RATIO);

        double blendDist = distanceBlocks - ledgeEnd; // 0 сразу после карниза
        if (blendDist >= adaptiveBlend) return new BeachFlatResult(blendedY, false); // уже обычный рельеф

        // Плавный подъём от карниза к обычной высоте суши — это уже НЕ узкая
        // кромка, isShoreEdge = false (тут рельеф обычный монолит, никакой
        // полости под ним быть не должно).
        double riseT = smoothstep(0.0, adaptiveBlend, blendDist);
        int blended = (int) Math.round(lerp(BEACH_LEDGE_Y, blendedY, riseT));
        return new BeachFlatResult(Math.min(blendedY, blended), false);
    }

    /**
     * Полный профиль колонки (wx, wz): высота дна + есть ли вода сверху.
     *
     * <p>Порядок проверки (первое совпадение побеждает):
     * <ol>
     *   <li><b>Океан</b> — самый широкий шум (масштаб материков). Ниже порога
     *       {@link #OCEAN_THRESHOLD} — открытая вода, глубина растёт по мере
     *       удаления от условного "берега" (не одинаковая яма везде).</li>
     *   <li><b>Река</b> — полоса вдоль контура {@code |noise| < RIVER_HALF_WIDTH}
     *       (тот же приём, что в дошумовых версиях ванили: горизонтали шума
     *       как русло).</li>
     *   <li><b>Озеро</b> — отдельный шум, где значение выше порога считается
     *       "впадиной" и заливается водой.</li>
     * </ol>
     * Все три режутся ТОЛЬКО в низинах (mountainMask ≈ 0, {@code landHeight
     * <= WATER_MAX_LAND_Y}) — ни один водоём не прорезает склон горы.
     */
    public ColumnProfile columnProfile(int wx, int wz) {
        int landHeight = computeLandHeight(wx, wz);
        // Признак "узкая прибрежная кромка" — проставляется применением
        // applyBeachFlat в любой из веток (океан/река/озеро) ниже. Нужен на
        // выходе, чтобы fillChunk/applyLayer1Surface знали, где оставлять
        // только тонкую линию песка с полостью под ней (см. ColumnProfile).
        boolean shoreEdge = false;

        // ── Ранний выход был здесь раньше ────────────────────────────────────
        // Старая версия: `if (landHeight > WATER_MAX_LAND_Y) return new
        // ColumnProfile(landHeight, -1);` — сразу для ЛЮБОГО водоёма, если
        // высота суши превышала WATER_MAX_LAND_Y (48+12=60, т.е. почти сразу
        // над уровнем воды 44).
        //
        // Порог задумывался только для рек/озёр ("не резать русло сквозь
        // склон горы" — см. комментарий у WATER_MAX_LAND_Y), но блокировал
        // и океанскую ветку, у которой уже есть собственный плавный
        // SHORE_BLEND-переход дна. В результате высокая гора почти вплотную
        // к океану обрубалась ДО того, как океанский градиент успевал
        // докатиться по склону — соседний столбец уходил в oceanFloorY
        // (может быть на много блоков ниже уровня воды), а этот столбец
        // оставался на полной высоте landHeight. Разница в 1 блок по XZ —
        // и получалась вертикальная стена прямо у кромки воды (см. баг-
        // репорт со скриншотом: гора «обрывом» уходит в воду).
        //
        // Решение: считаем oceanW/oceanN ДО решения о раннем выходе. Река и
        // озеро — по-прежнему не режут склон горы (их проверки остаются
        // внутри блока landHeight <= WATER_MAX_LAND_Y ниже), но океан
        // получает шанс мягко подвести берег даже к высокой горе.
        double earlyOceanN = heightNoise.fbm2D(wx * 0.0009 + 90000, wz * 0.0009 + 90000, 5, 2.0, 0.5);
        double earlyOceanW = smoothstep(OCEAN_THRESHOLD + SHORE_BLEND, OCEAN_THRESHOLD - SHORE_BLEND, earlyOceanN);
        if (landHeight > WATER_MAX_LAND_Y && earlyOceanW <= 0.0) {
            return new ColumnProfile(landHeight, -1); // горы/холмы вдали от океана — суша всегда
        }

        // ── Океан (проверяется первым — самый широкий водоём) ────────────────
        // oceanN/oceanW уже посчитаны выше как earlyOceanN/earlyOceanW —
        // переиспользуем, чтобы не считать один и тот же fbm2D дважды и не
        // разойтись в значениях между условием раннего выхода и веткой.
        double oceanN = earlyOceanN;
        double oceanW = earlyOceanW;
        if (oceanW > 0.0) {
            double depth01 = smoothstep(OCEAN_THRESHOLD, OCEAN_DEEP_AT, oceanN);
            int bedDepth = OCEAN_MIN_DEPTH
                    + (int) Math.round(depth01 * (OCEAN_MAX_DEPTH - OCEAN_MIN_DEPTH));
            int oceanFloorY = WATER_LEVEL - bedDepth;
            int blendedY = (int) Math.round(landHeight + (oceanFloorY - landHeight) * oceanW);
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            // Оцениваем полный 2D-градиент |∇oceanW| конечными разностями
            // на 1 блок по X и по Z — линеаризация плавного smoothstep-
            // порога. Только X было недостаточно: на диагональной
            // береговой линии это занижало градиент и растягивало карниз
            // сильнее заданной ширины (см. правку от предыдущего теста).
            double oceanNdx = heightNoise.fbm2D((wx + 1) * 0.0009 + 90000, wz * 0.0009 + 90000, 5, 2.0, 0.5);
            double oceanWdx = smoothstep(OCEAN_THRESHOLD + SHORE_BLEND, OCEAN_THRESHOLD - SHORE_BLEND, oceanNdx);
            double oceanGradX = Math.abs(oceanW - oceanWdx);
            double oceanNdz = heightNoise.fbm2D(wx * 0.0009 + 90000, (wz + 1) * 0.0009 + 90000, 5, 2.0, 0.5);
            double oceanWdz = smoothstep(OCEAN_THRESHOLD + SHORE_BLEND, OCEAN_THRESHOLD - SHORE_BLEND, oceanNdz);
            double oceanGradZ = Math.abs(oceanW - oceanWdz);
            BeachFlatResult oceanBeach = applyBeachFlat(blendedY, oceanW, oceanGradX, oceanGradZ);
            blendedY = oceanBeach.y;
            shoreEdge = shoreEdge || oceanBeach.isShoreEdge;
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            landHeight = blendedY; // пологий пляж выше уровня воды — суша чуть ниже
        }

        // ── Река ──────────────────────────────────────────────────────────────
        double riverN    = heightNoise.fbm2D(wx * 0.003 + 50000, wz * 0.003 + 50000, 4, 2.0, 0.5);
        double riverDist = Math.abs(riverN);
        // 1 в самом русле (riverDist≈0), 0 за пределами ширины+блендинга.
        double riverW = smoothstep(RIVER_HALF_WIDTH + SHORE_BLEND, RIVER_HALF_WIDTH - SHORE_BLEND, riverDist);
        if (riverW > 0.0) {
            int riverFloorY = Math.min(landHeight, WATER_LEVEL) - RIVER_BED_DEPTH;
            int blendedY = (int) Math.round(landHeight + (riverFloorY - landHeight) * riverW);
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            double riverNdx    = heightNoise.fbm2D((wx + 1) * 0.003 + 50000, wz * 0.003 + 50000, 4, 2.0, 0.5);
            double riverDistDx = Math.abs(riverNdx);
            double riverWdx = smoothstep(RIVER_HALF_WIDTH + SHORE_BLEND, RIVER_HALF_WIDTH - SHORE_BLEND, riverDistDx);
            double riverGradX = Math.abs(riverW - riverWdx);
            double riverNdz    = heightNoise.fbm2D(wx * 0.003 + 50000, (wz + 1) * 0.003 + 50000, 4, 2.0, 0.5);
            double riverDistDz = Math.abs(riverNdz);
            double riverWdz = smoothstep(RIVER_HALF_WIDTH + SHORE_BLEND, RIVER_HALF_WIDTH - SHORE_BLEND, riverDistDz);
            double riverGradZ = Math.abs(riverW - riverWdz);
            BeachFlatResult riverBeach = applyBeachFlat(blendedY, riverW, riverGradX, riverGradZ);
            blendedY = riverBeach.y;
            shoreEdge = shoreEdge || riverBeach.isShoreEdge;
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            landHeight = blendedY;
        }

        // ── Озеро ────────────────────────────────────────────────────────────
        double lakeN = heightNoise.fbm2D(wx * 0.006 + 70000, wz * 0.006 + 70000, 3, 2.0, 0.5);
        double lakeW = smoothstep(LAKE_THRESHOLD - SHORE_BLEND, LAKE_THRESHOLD + SHORE_BLEND, lakeN);
        if (lakeW > 0.0) {
            int lakeFloorY = Math.min(landHeight, WATER_LEVEL) - LAKE_BED_DEPTH;
            int blendedY = (int) Math.round(landHeight + (lakeFloorY - landHeight) * lakeW);
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            double lakeNdx = heightNoise.fbm2D((wx + 1) * 0.006 + 70000, wz * 0.006 + 70000, 3, 2.0, 0.5);
            double lakeWdx = smoothstep(LAKE_THRESHOLD - SHORE_BLEND, LAKE_THRESHOLD + SHORE_BLEND, lakeNdx);
            double lakeGradX = Math.abs(lakeW - lakeWdx);
            double lakeNdz = heightNoise.fbm2D(wx * 0.006 + 70000, (wz + 1) * 0.006 + 70000, 3, 2.0, 0.5);
            double lakeWdz = smoothstep(LAKE_THRESHOLD - SHORE_BLEND, LAKE_THRESHOLD + SHORE_BLEND, lakeNdz);
            double lakeGradZ = Math.abs(lakeW - lakeWdz);
            BeachFlatResult lakeBeach = applyBeachFlat(blendedY, lakeW, lakeGradX, lakeGradZ);
            blendedY = lakeBeach.y;
            shoreEdge = shoreEdge || lakeBeach.isShoreEdge;
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            landHeight = blendedY;
        }

        return new ColumnProfile(landHeight, -1, shoreEdge);
    }

    /**
     * Высота дна (твёрдой поверхности) в колонке — для рек/озёр это дно
     * ПОД водой, не уровень воды. См. {@link #columnProfile} за полным профилем.
     *
     * <p>ВАЖНО: этот метод — источник истины для высоты поверхности.
     * {@code AeroWorldChunkGenerator} (getBaseHeight/getBaseColumn/
     * applyLayer1Surface) и {@code TerrainColumnSampler} обязаны вызывать
     * именно его (или {@link #columnProfile}), а не полагаться на старую
     * константу SURFACE_Y — иначе height-map запросы, покраска поверхности
     * и валидация структур разъедутся с фактическим рельефом, который рисует
     * {@link #fillChunk}.
     */
    public int surfaceHeight(int wx, int wz) {
        return columnProfile(wx, wz).groundY;
    }

    /**
     * Высота ВЕРХНЕЙ видимой поверхности колонки — дно суши, либо поверхность
     * воды, если колонка залита (озеро/река/океан) и вода выше дна.
     *
     * <p>В отличие от {@link #surfaceHeight}, который всегда возвращает дно
     * (это нужно для рельефа/пещер/fillChunk), этот метод отвечает на вопрос
     * "куда упадёт предмет/где встанет структура сверху" — то есть ведёт себя
     * как ванильный {@code Heightmap.Types.WORLD_SURFACE}: над водой это её
     * поверхность, на суше — то же дно.
     *
     * <p>Используется в {@code getBaseHeight} для heightmap-типов, которые
     * ищут поверхность мира "сверху" (WORLD_SURFACE, WORLD_SURFACE_WG,
     * MOTION_BLOCKING...) — чтобы структуры, размещаемые ванильной системой
     * (деревни, аванпосты и т.п.), вставали на поверхность воды, а не
     * продавливались на дно озера/океана.
     */
    public int topmostHeight(int wx, int wz) {
        ColumnProfile p = columnProfile(wx, wz);
        return (p.waterY != -1) ? Math.max(p.groundY, p.waterY) : p.groundY;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Главный метод заполнения чанка
    // ══════════════════════════════════════════════════════════════════════════

    public void fillChunk(ChunkAccess chunk, int chunkX, int chunkZ,
                          BiomeResolver biomeAtColumn) {

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // ── Предрасчёт геометрии пещеры: билинейная интерполяция по 4 углам ──
        // computeCaveColumn вызывается только 4 раза вместо 256.
        // Поля (floorY, ceilY, stagTopY, stalacBotY) меняются плавно на масштабе
        // чанка (bigScale=0.004 → период ~250 блоков), поэтому линейная
        // интерполяция по 16 блокам даёт визуально неотличимый результат.
        // Нелинейный clamp (просвет < 20 блоков) применяется ПОСЛЕ интерполяции
        // на каждой колонне, что сохраняет корректность геометрии.
        int[][] floorY   = new int[16][16];
        int[][] ceilY    = new int[16][16];
        int[][] stagTopY = new int[16][16];
        int[][] stacBotY = new int[16][16];
        {
            // Сырые значения (до clamp) в 4 углах: индекс [corner][field]
            // corners: 00=(0,0), 10=(15,0), 01=(0,15), 11=(15,15)
            double[] r00 = computeCaveRaw(baseX,      baseZ     );
            double[] r10 = computeCaveRaw(baseX + 15, baseZ     );
            double[] r01 = computeCaveRaw(baseX,      baseZ + 15);
            double[] r11 = computeCaveRaw(baseX + 15, baseZ + 15);

            // Предвычисляем шаги интерполяции по X один раз за lx-итерацию,
            // чтобы внутренний lz-цикл обходился только сложением (нет делений).
            for (int lx = 0; lx < 16; lx++) {
                double tx = lx / 15.0;
                // Интерполяция по X — 4 скалярных lerp, без аллокаций
                double f0 = r00[0] + (r10[0] - r00[0]) * tx; // floorY   при Z=0
                double c0 = r00[1] + (r10[1] - r00[1]) * tx; // ceilY    при Z=0
                double g0 = r00[2] + (r10[2] - r00[2]) * tx; // stagTopY при Z=0
                double a0 = r00[3] + (r10[3] - r00[3]) * tx; // stacBotY при Z=0
                double f1 = r01[0] + (r11[0] - r01[0]) * tx; // floorY   при Z=15
                double c1 = r01[1] + (r11[1] - r01[1]) * tx; // ceilY    при Z=15
                double g1 = r01[2] + (r11[2] - r01[2]) * tx; // stagTopY при Z=15
                double a1 = r01[3] + (r11[3] - r01[3]) * tx; // stacBotY при Z=15

                for (int lz = 0; lz < 16; lz++) {
                    double tz = lz / 15.0;
                    // Интерполяция по Z — 4 сложения, 0 аллокаций
                    double rawFloor   = f0 + (f1 - f0) * tz;
                    double rawCeil    = c0 + (c1 - c0) * tz;
                    double rawStagTop = g0 + (g1 - g0) * tz;
                    double rawStacBot = a0 + (a1 - a0) * tz;

                    // Применяем clamp: минимальный просвет 20 блоков
                    if (rawCeil - rawFloor < 20) {
                        double mid = (rawFloor + rawCeil) * 0.5;
                        rawFloor = mid - 10; rawCeil = mid + 10;
                    }
                    int fY = (int) Math.round(rawFloor);
                    int cY = (int) Math.round(rawCeil);

                    // Ограничения сталагмитов/сталактитов относительно clamp-значений
                    int stgY = Math.min((int) Math.round(rawStagTop), fY + (cY - fY) * 6 / 10);
                    int stcY = Math.max((int) Math.round(rawStacBot), cY - (cY - fY) * 6 / 10);

                    floorY  [lx][lz] = fY;
                    ceilY   [lx][lz] = cY;
                    stagTopY[lx][lz] = stgY;
                    stacBotY[lx][lz] = stcY;
                }
            }
        }

        // ── Сбор колонн, влияющих на этот чанк ───────────────────────────────
        int reach  = (COLUMN_RADIUS_MAX / COLUMN_GRID_SIZE) + 2;
        int gridX0 = Math.floorDiv(baseX,      COLUMN_GRID_SIZE) - reach;
        int gridX1 = Math.floorDiv(baseX + 15, COLUMN_GRID_SIZE) + reach;
        int gridZ0 = Math.floorDiv(baseZ,      COLUMN_GRID_SIZE) - reach;
        int gridZ1 = Math.floorDiv(baseZ + 15, COLUMN_GRID_SIZE) + reach;

        int   columnCount = 0;
        int[] colCX       = new int[256];
        int[] colCZ       = new int[256];
        int[] colBaseR    = new int[256]; // радиус основания (у пола/потолка)

        for (int gx = gridX0; gx <= gridX1 && columnCount < 256; gx++) {
            for (int gz = gridZ0; gz <= gridZ1 && columnCount < 256; gz++) {
                long h = columnHash(gx, gz);
                double chance = ((double)(h & 0xFFFFFFFFL)) / 0x100000000L;
                if (chance >= COLUMN_CHANCE) continue;

                int offX  = (int)(((h >> 8)  & 0xFFL) % COLUMN_GRID_SIZE);
                int offZ  = (int)(((h >> 16) & 0xFFL) % COLUMN_GRID_SIZE);
                int rRange = COLUMN_BASE_MAX - COLUMN_BASE_MIN + 1;
                int baseR  = COLUMN_BASE_MIN + (int)(((h >> 24) & 0xFFL) % rRange);

                colCX   [columnCount] = gx * COLUMN_GRID_SIZE + offX;
                colCZ   [columnCount] = gz * COLUMN_GRID_SIZE + offZ;
                colBaseR[columnCount] = baseR;
                columnCount++;
            }
        }

        // ── Заполняем блоки колонки за колонкой ───────────────────────────────
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                ResourceLocation biomeKey = biomeAtColumn.get(wx, wz);
                boolean isSandy    = isSandBiome(biomeKey);
                boolean isBadlands = isBadlandsBiome(biomeKey);

                int fY   = floorY  [lx][lz];
                int cY   = ceilY   [lx][lz];
                int stgY = stagTopY[lx][lz];
                int stcY = stacBotY[lx][lz];

                // ── Ищем колонну: проверяем с учётом формы песочных часов ──────
                // Для каждой колонны вычисляем эффективный радиус на данной высоте Y
                // и смотрим — попадает ли точка (wx,wz) в этот радиус.
                // Но Y пока неизвестен на этом этапе, поэтому сохраняем
                // base-радиус, а фактическую проверку делаем в resolveBlock.
                //
                // Здесь ищем колонны, в чей BASE-радиус попадает XZ (максимально
                // возможный радиус — у основания), чтобы не пропустить ни одну.
                // colTSq = (dist/baseR)² = distSq/baseR² — без sqrt.
                // resolveBlock сравнивает colTSq < rFrac*rFrac вместо colT < rFrac.
                double colTSq    = 4.0; // > 1.0² = вне колонны (2² = 4)
                int    colBaseRv = 0;
                for (int i = 0; i < columnCount; i++) {
                    int dx = wx - colCX[i];
                    int dz = wz - colCZ[i];
                    int distSq   = dx*dx + dz*dz;
                    int baseR    = colBaseR[i];
                    int baseRSq  = baseR * baseR;
                    if (distSq < baseRSq) {
                        double tSq = (double) distSq / baseRSq;
                        if (colBaseRv == 0 || tSq < colTSq) {
                            colTSq    = tSq;
                            colBaseRv = colBaseR[i];
                        }
                    }
                }
                boolean inColumnBase = colBaseRv > 0;

                // ── Профиль колонки: дно + есть ли вода сверху (реки/озёра) ─────
                ColumnProfile profile = columnProfile(wx, wz);
                int groundY  = profile.groundY;
                int waterY   = profile.waterY;   // -1 = суша
                boolean isWaterCol = waterY != -1;
                // Полая песчаная кромка (см. ColumnProfile.isShoreEdge): узкая
                // прибрежная полоса, где вместо монолитного песка нужна одна
                // непроницаемая линия у поверхности + воздушная полость под
                // ней на SHORE_HOLLOW_DEPTH блоков. Раздуваем толщину
                // "подкожного" слоя (обычно SURFACE_SKIN=3) ровно настолько,
                // чтобы полость уместилась целиком, не упираясь в dirtMinY —
                // иначе на плоском берегу (SURFACE_SKIN=3) полость обрезалась
                // бы до 2 блоков вместо заданных SHORE_HOLLOW_DEPTH.
                boolean hollowShore = !isWaterCol && isSandy && profile.isShoreEdge;
                int skinDepth = hollowShore ? Math.max(SURFACE_SKIN, SHORE_HOLLOW_DEPTH + 2) : SURFACE_SKIN;
                int dirtMinY = groundY - skinDepth;

                // ── Bedrock ───────────────────────────────────────────────────
                pos.set(wx, LAYER_MIN_Y, wz);
                chunk.setBlockState(pos, BS_BEDROCK, false);

                // ── Основная колонка Y ────────────────────────────────────────
                // ВАЖНО: для водных колонок (океан/озеро/река) используем
                // СПЛОШНОЙ камень, а не resolveBlock. resolveBlock оставляет
                // воздух внутри пещерной пустоты (между fY и cY), а дно
                // водоёма часто попадает как раз в этот диапазон высот —
                // без этой развилки песчаное дно повисало бы прямо над
                // пещерой без опоры, и достаточно было сломать один блок,
                // чтобы весь песок посыпался вниз (гравитационный каскад).
                // Полая кромка (hollowShore) — та же логика: полость должна
                // опираться на сплошную породу снизу, а не на пещерный
                // воздух, иначе полости сольются в одну и берег обвалится
                // при первом обновлении блока.
                for (int y = LAYER_MIN_Y + 1; y < dirtMinY; y++) {
                    pos.set(wx, y, wz);
                    BlockState rock = (isWaterCol || hollowShore)
                            ? (y < DEEPSLATE_TOP ? BS_DEEPSLATE : BS_STONE)
                            : resolveBlock(wx, y, wz, fY, cY, stgY, stcY,
                                    inColumnBase, colTSq, colBaseRv, groundY);
                    chunk.setBlockState(pos, rock, false);
                }

                // ── Dirt / Sand / Terracotta подслой ─────────────────────────
                // Русло реки/озера — всегда песчаное дно (как в ванили),
                // независимо от биома. На суше — grass/dirt и т.п. по биому.
                // groundY и groundY-1 (последние 2 блока перед поверхностью)
                // для СУШИ принадлежат buildSurface (см. applyLayer1Surface) —
                // заглушки здесь не нужны, пишем только до groundY - 2.
                // Для ВОДЫ applyLayer1Surface этот столбец пропускает целиком,
                // поэтому дно (включая последние 2 блока) кладём прямо тут.
                // ── Полая песчаная кромка (hollowShore вычислен выше) ───────────
                // Узкая прибрежная полоса: вместо монолитного песчаника —
                // одна линия SANDSTONE вровень с groundY (см. applyLayer1Surface,
                // который красит groundY/groundY-1 сверху), затем воздух
                // (SHORE_HOLLOW_DEPTH блоков), затем снова сплошная порода.
                // Для воды / несандовых биомов / переходной зоны (isShoreEdge==false)
                // поведение не меняется — обычный монолит, как раньше.
                int dirtLoopTop = isWaterCol ? groundY + 1 : groundY - 1;
                if (hollowShore) {
                    int hollowBottom = Math.max(dirtMinY, dirtLoopTop - SHORE_HOLLOW_DEPTH);
                    for (int y = dirtMinY; y < dirtLoopTop; y++) {
                        pos.set(wx, y, wz);
                        BlockState fill = (y >= hollowBottom) ? BS_AIR : BS_SANDSTONE;
                        chunk.setBlockState(pos, fill, false);
                    }
                } else {
                    for (int y = dirtMinY; y < dirtLoopTop; y++) {
                        pos.set(wx, y, wz);
                        BlockState fill;
                        if (isWaterCol) {
                            fill = BS_SAND;
                        } else if (isSandy) {
                            fill = BS_SANDSTONE;
                        } else if (isBadlands) {
                            fill = BS_TERRACOTTA;
                        } else {
                            fill = BS_DIRT;
                        }
                        chunk.setBlockState(pos, fill, false);
                    }
                }

                // ── Вода поверх дна (реки/озёра) ─────────────────────────────
                if (isWaterCol) {
                    for (int y = groundY + 1; y <= waterY; y++) {
                        pos.set(wx, y, wz);
                        chunk.setBlockState(pos, BS_WATER, false);
                    }
                }

                // ── Воздух выше поверхности/воды ─────────────────────────────
                // Ограничиваем зачистку небольшим запасом, а не до самого
                // LAYER_MAX_Y (300) — иначе на каждую колонку пришлось бы до
                // ~250 лишних чтений/записей блоков впустую (ProtoChunk и так
                // изначально заполнен воздухом там, где мы ничего не писали).
                int topOfColumn = isWaterCol ? waterY : groundY;
                int airClearTop = Math.min(LAYER_MAX_Y, topOfColumn + 16);
                for (int y = topOfColumn + 1; y <= airClearTop; y++) {
                    pos.set(wx, y, wz);
                    if (!chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, BS_AIR, false);
                    }
                }
            }
        }

        // ── Руды ──────────────────────────────────────────────────────────────
        // Генерация руды отключена полностью.
        // long base = seed ^ ((long)chunkX * 341873128712L + (long)chunkZ * 132897987541L);
        // generateOres(chunk, RandomSource.create(base ^ 0xCC01L), chunkX, chunkZ);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Геометрия пещеры для одной XZ-колонки
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Возвращает сырые (до clamp) значения пещерной геометрии для точки (wx, wz).
     * double[0] = rawFloorY, [1] = rawCeilY, [2] = rawStagTopY, [3] = rawStacBotY.
     *
     * <p>Clamp (просвет < 20) и min/max для сталагмитов/сталактитов применяются
     * ПОСЛЕ билинейной интерполяции, чтобы граничные условия не нарушались.
     */
    private double[] computeCaveRaw(int wx, int wz) {
        // Крупный, средний и мелкий масштаб шума
        final double bigScale  = 0.004;
        final double midScale  = 0.012;
        final double fineScale = 0.030;

        // ── Пол: крупная волна + средняя деталь ──────────────────────────────
        double fn  = floorNoise.fbm2D(wx * bigScale,       wz * bigScale,       4, 2.1, 0.50);
        double fn2 = floorNoise.fbm2D(wx * midScale + 77,  wz * midScale + 77,  3, 2.0, 0.45);
        double rawFloorY = FLOOR_BASE_Y + (fn * 0.70 + fn2 * 0.30) * FLOOR_VAR;

        // ── Потолок ───────────────────────────────────────────────────────────
        double cn  = ceilNoise.fbm2D(wx * bigScale + 500,  wz * bigScale + 500,  4, 2.1, 0.50);
        double cn2 = ceilNoise.fbm2D(wx * midScale + 600,  wz * midScale + 600,  3, 2.0, 0.45);
        double rawCeilY = CEIL_BASE_Y + (cn * 0.70 + cn2 * 0.30) * CEIL_VAR;

        // ── Сталагмиты ────────────────────────────────────────────────────────
        double stagN       = stalagNoise.fbm2D(wx * fineScale,          wz * fineScale,          3, 2.2, 0.48);
        double stagCluster = stalagNoise.fbm2D(wx * midScale + 1000,    wz * midScale + 1000,    2, 2.0, 0.50);
        double stagT       = Math.pow(Math.max(0.0, (stagN + 1.0) * 0.5), 2.8);
        double stagMult    = stagCluster > 0.10 ? 1.0 : 0.35;
        double rawStagTopY = rawFloorY + stagT * STALAGMITE_MAX * stagMult;

        // ── Сталактиты ────────────────────────────────────────────────────────
        double stacN       = stalacNoise.fbm2D(wx * fineScale + 2000,   wz * fineScale + 2000,   3, 2.2, 0.48);
        double stacCluster = stalacNoise.fbm2D(wx * midScale  + 3000,   wz * midScale  + 3000,   2, 2.0, 0.50);
        double stacT       = Math.pow(Math.max(0.0, (stacN + 1.0) * 0.5), 2.8);
        double stacMult    = stacCluster > 0.10 ? 1.0 : 0.35;
        double rawStacBotY = rawCeilY - stacT * STALACTITE_MAX * stacMult;

        return new double[]{ rawFloorY, rawCeilY, rawStagTopY, rawStacBotY };
    }

    /**
     * "Лапшовый" туннель/арка внутри тела горы (выше базовой пещерной
     * системы). Возвращает {@code null}, если точка вне туннеля (порода
     * должна остаться сплошной), иначе BS_AIR или BS_WATER.
     *
     * <p>Активно только там, где колонка достаточно высокая (настоящая
     * гора, не холм) и Y лежит в полосе между потолком базовых пещер и
     * поверхностью/пиком. Полоса завязана на groundY ЭТОЙ колонки — если у
     * соседней колонки поверхность ниже уровня туннеля, там породы уже нет
     * (см. columnProfile/fillChunk), и туннель естественно превращается в
     * открытую арку/окно наружу — без отдельной логики.
     */
    private BlockState resolveMountainTunnel(int wx, int y, int wz, int groundY) {
        if (groundY < MTUN_MIN_GROUND_Y) return null;

        int lowY  = CEIL_BASE_Y + CEIL_VAR + MTUN_LOW_MARGIN;
        int highY = groundY - MTUN_HIGH_MARGIN;
        if (highY - lowY < 20 || y < lowY || y > highY) return null;

        double n1 = mtunNoiseA.noise3D(
                wx * MTUN_FREQ_XZ,
                y  * MTUN_FREQ_XZ * MTUN_FREQ_Y_MULT,
                wz * MTUN_FREQ_XZ);
        double n2 = mtunNoiseB.noise3D(
                wx * MTUN_FREQ_XZ + 4096,
                y  * MTUN_FREQ_XZ * MTUN_FREQ_Y_MULT,
                wz * MTUN_FREQ_XZ + 4096);
        double dist = Math.sqrt(n1 * n1 + n2 * n2);
        if (dist >= MTUN_THRESHOLD) return null;

        // Внутри туннеля: нижняя часть его сечения залита водой (ручей/
        // озеро, по которому можно проплыть на лодке), верх — воздух.
        // Уровень воды — ОДНА фиксированная мировая отметка на всю систему
        // туннелей (см. MTUN_WATER_LEVEL_Y) с лёгкой рябью берега, а не доля
        // локального диапазона — иначе поверхность повторяет купол горы и
        // выглядит параболой вместо ровной воды.
        double shoreWobble = mtunWaterNoise.fbm2D(wx * 0.015, wz * 0.015, 3, 2.0, 0.5) * MTUN_WATER_SHORE_WOBBLE;
        int    waterY      = MTUN_WATER_LEVEL_Y + (int) Math.round(shoreWobble);
        if (y <= waterY) {
            return BS_WATER;
        }
        return BS_AIR;
    }



    // ══════════════════════════════════════════════════════════════════════════
    // Определение блока для позиции (wx, y, wz)
    // ══════════════════════════════════════════════════════════════════════════

    private BlockState resolveBlock(int wx, int y, int wz,
                                    int fY, int cY, int stgTopY, int stcBotY,
                                    boolean inColumnBase, double colTSq, int colBaseR,
                                    int groundY) {
        // Ниже пола → сплошная порода
        if (y <= fY) {
            return y < DEEPSLATE_TOP ? deepslateBlock(wx, y, wz) : stoneBlock(wx, y, wz);
        }
        // Выше потолка → сплошная порода, если только тут не проходит
        // горный туннель/арка (см. resolveMountainTunnel).
        if (y >= cY) {
            BlockState tunnel = resolveMountainTunnel(wx, y, wz, groundY);
            if (tunnel != null) return tunnel;
            return stoneBlock(wx, y, wz);
        }

        // ──── Зона пещеры: fY < y < cY ──────────────────────────────────────

        // ── КОЛОННА: форма ПЕСОЧНЫХ ЧАСОВ ────────────────────────────────────
        //
        // yNorm = 0.0 → пол (fY+1), yNorm = 1.0 → потолок (cY-1)
        // waistFrac = COLUMN_WAIST_FRAC → радиус талии = baseR * waistFrac
        //
        // Профиль: r(t) = waistR + (baseR - waistR) * |2*t - 1|^exp
        //   t=0 (пол):    r = waistR + (baseR-waistR)*1 = baseR  ✓
        //   t=0.5 (сер.): r = waistR                             ✓
        //   t=1 (потолок):r = baseR                              ✓
        //
        // exp=1.6 даёт плавную кривую (не острую линию, не прямую).
        //
        // colTSq = (dist/baseR)² ∈ [0..1) — квадрат нормированного расстояния.
        // Блок принадлежит колонне, если: colTSq < rFrac² (без sqrt).
        if (inColumnBase) {
            int    caveH    = Math.max(1, cY - fY);
            double yNorm    = (double)(y - fY) / caveH;           // 0..1
            double waistFrac = COLUMN_WAIST_FRAC;                  // ~0.22
            // |2t-1|: 1 у пола/потолка, 0 в середине
            double edge     = Math.abs(2.0 * yNorm - 1.0);
            // Плавный переход со степенью 1.6
            double rFrac    = waistFrac + (1.0 - waistFrac) * Math.pow(edge, 1.6);
            // Если точка XZ внутри r(y) — это блок колонны
            if (colTSq < rFrac * rFrac) {
                return y < DEEPSLATE_TOP ? deepslateBlock(wx, y, wz) : stoneBlock(wx, y, wz);
            }
        }

        // ── Сталагмит (от пола вверх) ─────────────────────────────────────
        if (stgTopY > fY && y <= stgTopY) {
            return y < DEEPSLATE_TOP ? deepslateBlock(wx, y, wz) : stoneBlock(wx, y, wz);
        }

        // ── Сталактит (от потолка вниз) ──────────────────────────────────
        if (stcBotY < cY && y >= stcBotY) {
            return stoneBlock(wx, y, wz);
        }

        // Чистый воздух — вода тоже убирается
        return BS_AIR;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Детерминированный хэш ячейки сетки колонн
    // ══════════════════════════════════════════════════════════════════════════

    private long columnHash(int gridX, int gridZ) {
        long h = seed;
        h ^= (long)gridX * 0x9E3779B97F4A7C15L;
        h ^= (long)gridZ * 0x6C62272E07BB0142L;
        h  = h ^ (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h  = h ^ (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h  = h ^ (h >>> 31);
        return h;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Data-классы
    // ══════════════════════════════════════════════════════════════════════════



    // ══════════════════════════════════════════════════════════════════════════
    // Блоки породы (геологическое разнообразие)
    // ══════════════════════════════════════════════════════════════════════════

    private BlockState deepslateBlock(int wx, int wy, int wz) {
        double n = stoneVariance.noise3D(wx * 0.07, wy * 0.12, wz * 0.07);
        if (n > 0.70)  return BS_TUFF;
        if (n > 0.58)  return BS_GRAVEL;
        if (n < -0.72) return BS_GRANITE;
        if (n < -0.60) return BS_DIORITE;
        return BS_DEEPSLATE;
    }

    private BlockState stoneBlock(int wx, int wy, int wz) {
        double n = stoneVariance.noise3D(wx * 0.06, wy * 0.10, wz * 0.06);
        if (n > 0.68)  return BS_GRANITE;
        if (n > 0.56)  return BS_DIORITE;
        if (n > 0.45)  return BS_ANDESITE;
        if (n < -0.70) return BS_GRAVEL;
        return BS_STONE;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Биом-хелперы
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isSandBiome(ResourceLocation biomeKey) {
        if (biomeKey == null) return false;
        String p = biomeKey.getPath();
        return p.equals("desert") || p.equals("beach") || p.equals("snowy_beach");
    }

    private boolean isBadlandsBiome(ResourceLocation biomeKey) {
        if (biomeKey == null) return false;
        String p = biomeKey.getPath();
        return p.equals("badlands") || p.equals("wooded_badlands") || p.equals("eroded_badlands");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Генерация руд
    // ══════════════════════════════════════════════════════════════════════════

    private void generateOres(ChunkAccess chunk, RandomSource rng, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Таблица руд: surf-блок, deep-блок (null = нет deepslate-варианта),
        // minY, maxY, размер жилы, количество попыток.
        // Ссылки на static final BS_* — нет вызовов defaultBlockState() на чанк.
        Object[][] ores = {
                { BS_COAL_ORE,      null,
                        0,   44, 4, 2 },
                { BS_IRON_ORE,      BS_DEEPSLATE_IRON,
                        -63, 44, 3, 2 },
                { BS_COPPER_ORE,    BS_DEEPSLATE_COPPER,
                        -16, 44, 3, 1 },
                { BS_GOLD_ORE,      BS_DEEPSLATE_GOLD,
                        -63, 32, 3, 1 },
                { BS_REDSTONE_ORE,  BS_DEEPSLATE_RED,
                        -63, 15, 3, 1 },
        };

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Object[] ore : ores) {
            BlockState surf  = (BlockState) ore[0];
            BlockState deep  = (BlockState) ore[1];
            int minY   = (int) ore[2];
            int maxY   = (int) ore[3];
            int veinSz = (int) ore[4];
            int tries  = (int) ore[5];

            for (int i = 0; i < tries; i++) {
                int vx = baseX + rng.nextInt(16);
                int vy = minY + rng.nextInt(Math.max(1, maxY - minY));
                int vz = baseZ + rng.nextInt(16);
                BlockState toPlace = (deep != null && vy < DEEPSLATE_TOP) ? deep : surf;
                placeOreVein(chunk, rng, vx, vy, vz, toPlace, veinSz);
            }
        }
    }

    private void placeOreVein(ChunkAccess chunk, RandomSource rng,
                              int cx, int cy, int cz, BlockState ore, int size) {
        double radius = Math.cbrt(size) * 1.1;
        int r = (int)Math.ceil(radius);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx*dx + dy*dy + dz*dz > radius * radius) continue;
                    if (rng.nextDouble() > 0.65) continue;
                    int bx = cx+dx, by = cy+dy, bz = cz+dz;
                    if ((bx >> 4) != chunk.getPos().x) continue;
                    if ((bz >> 4) != chunk.getPos().z) continue;
                    if (by < LAYER_MIN_Y || by > LAYER_MAX_Y) continue;
                    pos.set(bx, by, bz);
                    BlockState ex = chunk.getBlockState(pos);
                    if (ex.is(Blocks.STONE)    || ex.is(Blocks.DEEPSLATE) || ex.is(Blocks.TUFF)
                            || ex.is(Blocks.GRANITE)  || ex.is(Blocks.DIORITE)   || ex.is(Blocks.ANDESITE)) {
                        chunk.setBlockState(pos, ore, false);
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Публичный интерфейс биомов
    // ══════════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    public interface BiomeResolver {
        ResourceLocation get(int wx, int wz);
    }
}