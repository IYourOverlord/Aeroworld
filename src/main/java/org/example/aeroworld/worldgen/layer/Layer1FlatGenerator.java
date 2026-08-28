package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
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
    // Инфраструктурные константы, не относящиеся к региональной системе
    // архетипов хребтов (та убрана этапом 1) — вода/пещеры/туннели/блоки/
    // сиды шума. ВОССТАНОВЛЕНО: коммит, вводивший noise router (см. ниже),
    // по ошибке удалил этот блок целиком вместе с региональной системой,
    // из-за чего файл переставал компилироваться (WATER_LEVEL, FLOOR_BASE_Y,
    // MTUN_*, BS_*, heightNoise и т.д. использовались, но нигде не были
    // объявлены). Восстановлено по последней компилируемой версии (до
    // удаления архетипов) — региональные константы (CELL_SIZE, RIDGE_*,
    // RING_*, ALPINE/PLATEAU/TERRACE/SHATTERED_CHANCE, старый CANYON_*)
    // сюда осознанно НЕ возвращены, они остаются упразднены согласно ТЗ п.1.4.
    // ══════════════════════════════════════════════════════════════════════════

    // ── Реки / озёра ──────────────────────────────────────────────────────────
    // Фиксированный уровень воды — чуть ниже базовой равнины (48), чтобы вода
    // естественно скапливалась в низинах, а не резала склоны гор.
    public static final int WATER_LEVEL = BASE_SURFACE_Y - 4; // 44 (public: нужен AeroWorldChunkGenerator.getSeaLevel())
    private static final double RIVER_HALF_WIDTH  = 0.075; // ширина полосы |noise|<X — река
    private static final double LAKE_THRESHOLD    = 0.55;  // порог по шуму — озеро
    private static final int    RIVER_BED_DEPTH   = 3;
    private static final int    LAKE_BED_DEPTH    = 6;
    // Реки/озёра карвятся только в низинах — там, где рельеф почти не поднят.
    // Не резать русло сквозь склон горы.
    private static final int    WATER_MAX_LAND_Y  = BASE_SURFACE_Y + 12;
    // Ширина плавного перехода (в единицах шума) между сушей и водой —
    // формирует пологий пляж/склон дна вместо резкого вертикального среза.
    private static final double SHORE_BLEND       = 0.14;

    // ── Ступенчатый пляжный карниз ───────────────────────────────────────────
    private static final double BEACH_EDGE_WIDTH   = 1.0;  // блоков — первая линия, вровень с водой
    private static final double BEACH_LEDGE_WIDTH  = 6.0;  // блоков — карниз на 1 блок ниже первой линии
    private static final double BEACH_LEDGE_BLEND  = 3.0;  // блоков дальше — минимум ширины перехода
    // Дополнительная ширина зоны перехода (в блоках) на каждый блок высоты,
    // которую нужно набрать от карниза до полного рельефа — иначе у подножия
    // высокой горы переход в 3 блока превращался в вертикальную "стену".
    private static final double BEACH_LEDGE_HEIGHT_RATIO = 0.4;
    // Высота первой линии — вровень с водой (соприкасается с ней).
    private static final int    BEACH_EDGE_Y       = WATER_LEVEL;
    // Высота карниза — на 1 блок НИЖЕ первой линии, то есть уже под водой.
    private static final int    BEACH_LEDGE_Y      = WATER_LEVEL - 1;

    // ── Полая песчаная кромка ────────────────────────────────────────────────
    private static final int    SHORE_HOLLOW_DEPTH = 6;  // блоков полости под линией кромки

    // ── Океаны ────────────────────────────────────────────────────────────────
    private static final double OCEAN_THRESHOLD  = -0.10; // ниже — океан
    private static final double OCEAN_DEEP_AT    = -0.60; // тут уже максимальная глубина
    private static final int    OCEAN_MIN_DEPTH  = 8;      // глубина у "берега"
    private static final int    OCEAN_MAX_DEPTH  = 24;     // глубина в открытом океане
    // Толщина grass/dirt (или sand/sandstone, terracotta) под поверхностью.
    private static final int SURFACE_SKIN     = 3;

    private static final int DEEPSLATE_TOP = 0;

    // ══════════════════════════════════════════════════════════════════════════
    // Горные туннели/арки ("noodle"-пещеры внутри тела горы, выше базовой
    // подземной пещерной системы). Два независимых 3D-шума n1,n2, туннель
    // там, где sqrt(n1²+n2²) < порог.
    // ══════════════════════════════════════════════════════════════════════════
    private static final int    MTUN_MIN_GROUND_Y  = BASE_SURFACE_Y + 65; // только настоящие горы
    private static final int    MTUN_LOW_MARGIN    = 22;  // отступ вверх от потолка базовой пещеры
    private static final int    MTUN_HIGH_MARGIN   = 24;  // отступ вниз от поверхности/пика
    private static final double MTUN_FREQ_XZ       = 0.017; // частота по X/Z
    private static final double MTUN_FREQ_Y_MULT   = 0.55;  // туннель более пологий/горизонтальный
    private static final double MTUN_THRESHOLD     = 0.42;  // порог sqrt(n1²+n2²) — ширина прохода
    private static final double MTUN_WATER_SHORE_WOBBLE = 2.5; // лёгкая рябь берега (блоков)

    // ── Параметры пещеры ──────────────────────────────────────────────────────
    private static final int FLOOR_BASE_Y   = -14;   // базовый Y верхнего края пола
    private static final int CEIL_BASE_Y    = 36;    // базовый Y нижнего края потолка
    private static final int FLOOR_VAR      = 5;     // амплитуда холмов пола (блоков)
    private static final int CEIL_VAR       = 5;     // амплитуда холмов потолка (блоков)
    // Единый ФИКСИРОВАННЫЙ мировой Y для уровня воды в горных туннелях —
    // вода всегда горизонтальна, не повторяет купол горы.
    // Раньше считался независимо от WATER_LEVEL (CEIL_BASE_Y+CEIL_VAR+
    // MTUN_LOW_MARGIN+14 ≈ 77) — вода внутри горы зависала на своей
    // отметке, не совпадающей с мировым уровнем океана (44), что выглядело
    // как "вода висит в воздухе" на срезе горы. Приравнено к WATER_LEVEL —
    // один и тот же мировой уровень воды везде.
    private static final int    MTUN_WATER_LEVEL_Y = WATER_LEVEL;
    private static final int STALAGMITE_MAX = 14;    // макс. высота сталагмита
    private static final int STALACTITE_MAX = 14;    // макс. длина сталактита

    // ── Параметры колонн ──────────────────────────────────────────────────────
    private static final int    COLUMN_GRID_SIZE  = 30;   // шаг сетки (блоков)
    private static final double COLUMN_CHANCE     = 0.55; // вероятность колонны в ячейке
    private static final int    COLUMN_BASE_MIN   = 8;    // мин. радиус основания
    private static final int    COLUMN_BASE_MAX   = 18;   // макс. радиус основания
    private static final double COLUMN_WAIST_FRAC = 0.22; // доля от base-радиуса (талия)
    private static final int    COLUMN_RADIUS_MAX = COLUMN_BASE_MAX;

    // ── Кэшированные BlockState ──────────────────────────────────────────────
    private static final BlockState BS_AIR             = Blocks.AIR            .defaultBlockState();
    private static final BlockState BS_BEDROCK         = Blocks.BEDROCK        .defaultBlockState();
    private static final BlockState BS_STONE           = Blocks.STONE          .defaultBlockState();
    private static final BlockState BS_DEEPSLATE       = Blocks.DEEPSLATE      .defaultBlockState();
    private static final BlockState BS_GRANITE         = Blocks.GRANITE        .defaultBlockState();
    private static final BlockState BS_DIORITE         = Blocks.DIORITE        .defaultBlockState();
    private static final BlockState BS_ANDESITE        = Blocks.ANDESITE       .defaultBlockState();
    private static final BlockState BS_TUFF            = Blocks.TUFF           .defaultBlockState();
    private static final BlockState BS_GRAVEL          = Blocks.GRAVEL         .defaultBlockState();
    private static final BlockState BS_DIRT            = Blocks.DIRT           .defaultBlockState();
    private static final BlockState BS_SANDSTONE       = Blocks.SANDSTONE      .defaultBlockState();
    private static final BlockState BS_SAND            = Blocks.SAND           .defaultBlockState();
    private static final BlockState BS_WATER           = Blocks.WATER          .defaultBlockState();
    private static final BlockState BS_TERRACOTTA      = Blocks.TERRACOTTA     .defaultBlockState();
    private static final BlockState BS_COAL_ORE        = Blocks.COAL_ORE       .defaultBlockState();
    private static final BlockState BS_IRON_ORE        = Blocks.IRON_ORE       .defaultBlockState();
    private static final BlockState BS_DEEPSLATE_IRON  = Blocks.DEEPSLATE_IRON_ORE .defaultBlockState();
    private static final BlockState BS_COPPER_ORE      = Blocks.COPPER_ORE     .defaultBlockState();
    private static final BlockState BS_DEEPSLATE_COPPER= Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
    private static final BlockState BS_GOLD_ORE        = Blocks.GOLD_ORE       .defaultBlockState();
    private static final BlockState BS_DEEPSLATE_GOLD  = Blocks.DEEPSLATE_GOLD_ORE .defaultBlockState();
    private static final BlockState BS_REDSTONE_ORE    = Blocks.REDSTONE_ORE   .defaultBlockState();
    private static final BlockState BS_DEEPSLATE_RED   = Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();

    // ── Seed-константы ────────────────────────────────────────────────────────
    private static final long SEED_STONE = 0xAA01L;
    private static final long SEED_FLOOR = 0xF100_BEEF_1234L;
    private static final long SEED_CEIL  = 0xC100_DEAD_5678L;
    private static final long SEED_STAG  = 0x5714_6717_9999L;
    private static final long SEED_STAC  = 0x4C71_1234_ABCDL;
    private static final long SEED_ORE   = 0xAA02L;
    private static final long SEED_HEIGHT = 0x4E1687_71ADL;
    private static final long SEED_MTUN_A = 0x7A0D_1E11_A001L;
    private static final long SEED_MTUN_B = 0x7A0D_1E11_B002L;
    private static final long SEED_MTUN_W = 0x7A0D_1E11_C003L;
    // ── Этап 2: сиды для riverEligibility/каньона (см. ниже) ─────────────────
    private static final long SEED_CANYON = 0x9B2E_C0DE_CA07L;

    // ── Шумовые генераторы ────────────────────────────────────────────────────
    private final AeroNoise stoneVariance;
    private final AeroNoise oreNoise;
    private final AeroNoise floorNoise;
    private final AeroNoise ceilNoise;
    private final AeroNoise stalagNoise;
    private final AeroNoise stalacNoise;
    private final AeroNoise heightNoise;
    private final AeroNoise mtunNoiseA;
    private final AeroNoise mtunNoiseB;
    private final AeroNoise mtunWaterNoise;
    private final AeroNoise canyonNoise;

    private final long seed;

    public Layer1FlatGenerator(long worldSeed) {
        this.seed     = worldSeed;
        stoneVariance = new AeroNoise(worldSeed ^ SEED_STONE);
        oreNoise      = new AeroNoise(worldSeed ^ SEED_ORE);
        floorNoise    = new AeroNoise(worldSeed ^ SEED_FLOOR);
        ceilNoise     = new AeroNoise(worldSeed ^ SEED_CEIL);
        stalagNoise   = new AeroNoise(worldSeed ^ SEED_STAG);
        stalacNoise   = new AeroNoise(worldSeed ^ SEED_STAC);
        heightNoise   = new AeroNoise(worldSeed ^ SEED_HEIGHT);
        mtunNoiseA    = new AeroNoise(worldSeed ^ SEED_MTUN_A);
        mtunNoiseB    = new AeroNoise(worldSeed ^ SEED_MTUN_B);
        mtunWaterNoise= new AeroNoise(worldSeed ^ SEED_MTUN_W);
        canyonNoise   = new AeroNoise(worldSeed ^ SEED_CANYON);
    }


    // ══════════════════════════════════════════════════════════════════════════
    // Ванильный рельеф/вода (замена самописного noise router'а высоты и
    // водной маски — см. NEXT_SESSION_PROMPT.md, "заменить кастомную водную
    // маску AeroWorld на ванильную генерацию воды/биомов", Вариант B).
    //
    // РЕШЕНИЕ (принято пользователем): вместо синхронизации ДВУХ независимых
    // систем (самописная высота/вода + ванильный биом) — высота, вода И
    // биом Layer 1 теперь читаются из ОДНОГО источника: настоящего
    // NoiseBasedChunkGenerator (тот же ноут-раутер overworld, что уже
    // используется как vanillaGenerator в AeroWorldChunkGenerator для
    // Climate.Sampler/applyCarvers/spawnOriginalMobs). Расхождение между
    // "где стоит вода" и "какой там биом" структурно невозможно, т.к. оба
    // вопроса задаются одному и тому же генератору для одной и той же
    // колонки (wx, wz).
    //
    // Цена решения (осознанно принята пользователем): самописные горы Layer 1
    // (высота до Y=300, свои сплайны continentalness/erosion/PV/weirdness,
    // каньоны, реки с riverEligibility и т.д.) — упразднены полностью.
    // Рельеф теперь обычный ванильный overworld (высоты примерно до
    // Y≈150-190, sea level=63). Пещеры/горные туннели/колонны/сталагмиты
    // (FLOOR_*, CEIL_*, MTUN_*, COLUMN_*) НЕ затронуты — они читают только
    // итоговый groundY из ColumnProfile, а не внутреннее устройство этого
    // блока, поэтому продолжают работать поверх ванильной высоты без правок.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ванильный генератор рельефа (NoiseBasedChunkGenerator, overworld noise
     * settings) — единственный источник истины для высоты/воды Layer 1.
     * Прокидывается через {@link #setVanillaSource} из
     * {@code AeroWorldChunkGenerator.init(RandomState)}, т.к. на момент
     * конструктора {@code Layer1FlatGenerator} у нас есть только seed, а
     * {@code RandomState}/{@code NoiseBasedChunkGenerator} появляются позже
     * (см. {@code AeroWorldChunkGenerator.vanillaGenerator}, уже существующее
     * поле — используем именно его, не создаём второй экземпляр).
     *
     * <p>Может быть {@code null} в узком окне между конструктором
     * {@code Layer1FlatGenerator} и первым вызовом {@code setVanillaSource}
     * (например, если что-то дёрнет columnProfile до init()) — в этом случае
     * {@link #columnProfile} отдаёт безопасный сухопутный fallback на
     * {@code BASE_SURFACE_Y}, а не падает с NPE.
     */
    private volatile NoiseBasedChunkGenerator vanillaSource;
    private volatile RandomState vanillaRandomState;

    /** Итог расчёта колонки: где дно (твёрдая порода) и есть ли сверху вода. */
    public static final class ColumnProfile {
        /** Верхний твёрдый блок (дно реки/озера/океана, если waterY != -1; иначе сама поверхность). */
        public final int groundY;
        /** Y поверхности воды, или -1 если это суша. */
        public final int waterY;
        /**
         * true — колонка попадает в узкую прибрежную полосу с полой полостью
         * под тонкой линией песка (см. SHORE_HOLLOW_DEPTH в fillChunk). Это
         * была специфика самописного пляжного профиля applyBeachFlat —
         * ванильный рельеф такого явления не знает, поэтому всегда false.
         * fillChunk корректно обрабатывает isShoreEdge=false как обычный
         * монолитный берег (ветка hollowShore просто никогда не срабатывает).
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
     * Диапазон Y для запроса {@code getBaseColumn} — должен покрывать весь
     * реальный диапазон ванильного overworld (-64..320), а не только
     * LAYER_MIN_Y..LAYER_MAX_Y слоя 1: даже если мы читаем только нижнюю
     * часть колонки, NoiseBasedChunkGenerator.getBaseColumn/iterateNoiseColumn
     * ожидает диапазон, согласованный с его собственными noise settings
     * (clampToHeightAccessor), иначе смещает сетку интерполяции.
     */
    private static final LevelHeightAccessor VANILLA_COLUMN_HEIGHT = new LevelHeightAccessor() {
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight()         { return 384; }
    };

    /**
     * Вызывается из {@code AeroWorldChunkGenerator.init(RandomState)} при
     * каждой (пере)инициализации seed/мира — дешёвый no-op при повторном
     * вызове с тем же {@code RandomState} (см. fast-path в вызывающем коде).
     */
    public void setVanillaSource(NoiseBasedChunkGenerator vanillaGenerator, RandomState randomState) {
        this.vanillaSource      = vanillaGenerator;
        this.vanillaRandomState = randomState;
    }

    /**
     * Всегда false — кольцевые кальдеры (RidgeArchetype.RING) упразднены
     * вместе со всей региональной системой архетипов задолго до перехода на
     * ванильный рельеф. Метод оставлен как совместимая заглушка, т.к.
     * вызывается извне из {@link org.example.aeroworld.worldgen.biome.AeroBiomeSource}
     * — убирать сигнатуру без правки вызывающего кода нельзя.
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

    /**
     * Кэш последнего вычисленного {@link ColumnProfile} по (wx,wz) — простой
     * однослотовый кэш (не карта), т.к. {@code columnProfile}/{@code
     * surfaceHeight}/{@code topmostHeight} для ОДНОЙ и той же колонки часто
     * вызываются подряд несколько раз за один проход (fillChunk,
     * applyLayer1Surface, MountainForestScatter, TerrainColumnSampler) —
     * getBaseColumn у ванильного генератора не бесплатен (строит целый
     * NoiseChunk на колонку, см. NoiseBasedChunkGenerator.iterateNoiseColumn),
     * поэтому важно не пересчитывать его по несколько раз подряд для одной
     * и той же точки. НЕ потокобезопасен по конструкции (см. ниже) —
     * генерация одного чанка (все вызовы columnProfile из fillChunk и
     * applyLayer1Surface для этого чанка) всегда идёт в одном потоке
     * генератора чанков, гонка возможна только МЕЖДУ разными чанками
     * (разными потоками) — в этом случае кэш просто чаще промахивается
     * (each thread has its own Layer1FlatGenerator instance? — нет, общий).
     * Чтобы не гнаться за микрооптимизацией ценой корректности при
     * многопоточной генерации (C2ME и т.п.), кэш держим per-thread через
     * ThreadLocal, а не как обычное volatile-поле экземпляра.
     */
    private final ThreadLocal<ColumnProfile> lastProfileCache = new ThreadLocal<>();
    private final ThreadLocal<Long> lastProfileKey = new ThreadLocal<>();

    private static long profileKey(int wx, int wz) {
        return (((long) wx) << 32) ^ (wz & 0xFFFFFFFFL);
    }

    /**
     * Полный профиль колонки (wx, wz): высота дна + есть ли вода сверху —
     * теперь напрямую из ванильного {@code NoiseBasedChunkGenerator}
     * (см. блок-комментарий выше). Один вызов {@code getBaseColumn} даёт
     * весь вертикальный столбец сразу; сканируем его СВЕРХУ ВНИЗ:
     * первый небо-непустой блок — если это жидкость (вода), запоминаем как
     * {@code waterY} и продолжаем вниз до первого НЕ-жидкого/непрозрачного
     * блока — это {@code groundY} (твёрдое дно). Если самый верхний
     * непустой блок сразу твёрдый (не вода) — колонка сухая,
     * {@code waterY = -1}.
     *
     * <p>{@code isShoreEdge} (полая песчаная кромка пляжа, см. javadoc
     * {@link ColumnProfile}) больше не имеет смысла в терминах ванильного
     * рельефа (это была специфика самописного пляжного профиля
     * applyBeachFlat) — всегда {@code false}; {@code fillChunk} корректно
     * обрабатывает {@code isShoreEdge=false} как обычный монолитный берег.
     */
    public ColumnProfile columnProfile(int wx, int wz) {
        long key = profileKey(wx, wz);
        Long cachedKey = lastProfileKey.get();
        if (cachedKey != null && cachedKey == key) {
            ColumnProfile cached = lastProfileCache.get();
            if (cached != null) return cached;
        }

        ColumnProfile result = computeColumnProfileVanilla(wx, wz);
        lastProfileKey.set(key);
        lastProfileCache.set(result);
        return result;
    }

    private ColumnProfile computeColumnProfileVanilla(int wx, int wz) {
        NoiseBasedChunkGenerator gen = vanillaSource;
        RandomState random = vanillaRandomState;
        if (gen == null || random == null) {
            // Узкое окно до первого setVanillaSource() — безопасный
            // сухопутный fallback вместо NPE (см. javadoc vanillaSource).
            return new ColumnProfile(BASE_SURFACE_Y, -1);
        }

        NoiseColumn column = gen.getBaseColumn(wx, wz, VANILLA_COLUMN_HEIGHT, random);

        int minY = VANILLA_COLUMN_HEIGHT.getMinBuildHeight();
        int maxY = minY + VANILLA_COLUMN_HEIGHT.getHeight() - 1;

        // Сканируем СВЕРХУ ВНИЗ: первый непустой блок — либо вода (тогда
        // это waterY, продолжаем искать дно ниже), либо сразу твёрдая
        // порода (тогда это groundY, воды нет).
        int waterY  = -1;
        int groundY = LAYER_MIN_Y; // safety fallback, если колонка целиком воздух

        for (int y = maxY; y >= minY; y--) {
            BlockState state = column.getBlock(y);
            if (state.isAir()) continue;

            if (!state.getFluidState().isEmpty() && waterY == -1) {
                // Верхний блок жидкости — фиксируем как поверхность воды и
                // продолжаем сканировать вниз в поисках твёрдого дна.
                waterY = y;
                continue;
            }
            if (state.getFluidState().isEmpty()) {
                // Первый по-настоящему твёрдый (не жидкий, не воздух) блок —
                // это дно.
                groundY = y;
                break;
            }
            // Ещё жидкость ниже уже найденной поверхности воды (толща воды) —
            // просто продолжаем спуск, дно ещё не найдено.
        }

        return new ColumnProfile(groundY, waterY);
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

        // lowY раньше был ЖЁСТКО CEIL_BASE_Y+CEIL_VAR+MTUN_LOW_MARGIN (≈63) —
        // выше уровня воды туннелей (MTUN_WATER_LEVEL_Y = WATER_LEVEL = 44).
        // Раз вода всегда была НАД этим полом, а не под ним, полоса туннеля
        // никогда физически не могла достать до уровня воды: цикл ниже
        // (y <= waterY) не выполнялся ни разу, и озеро внутри горы либо
        // "висело" на собственной старой отметке (77, никак не связанной с
        // морем), либо (после приравнивания к WATER_LEVEL) исчезало вовсе.
        // Берём МЕНЬШЕЕ из двух — обычный запас от потолка базовой пещеры
        // ИЛИ отметку чуть ниже WATER_LEVEL — чтобы полоса туннеля всегда
        // захватывала уровень моря, и вода внутри горы физически совпадала
        // с мировым уровнем океана.
        int lowY  = Math.min(CEIL_BASE_Y + CEIL_VAR + MTUN_LOW_MARGIN, WATER_LEVEL - 8);
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