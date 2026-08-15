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
    // Насколько высоко шум может поднять рельеф над базовой высотой равнин.
    // Было 130 — при таком размахе даже "полная" гора выглядела как высокий
    // холм, а не гора, и визуально сливалась с предгорьями. Подняли до 190,
    // чтобы настоящие пики/плато были ощутимо выше окружающих холмов —
    // сейчас основная жалоба именно на отсутствие выраженных гор.
    private static final int MAX_EXTRA_HEIGHT = 190;
    // Не даём пикам подходить ближе чем на 30 блоков к потолку слоя —
    // чтобы оставался запас неба над самой высокой горой.
    private static final int PEAK_SKY_BUFFER  = 30;
    // Амплитуда лёгкой холмистости на равнинах (там, где mountainMask≈0) —
    // равнины не идеально плоские, но и не похожи на горы.
    // Было 6 — почти незаметно с высоты полёта, подняли до 14.
    private static final int FLATLAND_BUMP    = 14;

    // ── Региональная структура хребтов (Terralith-подобная драма + WWOO-подобная
    //    мягкость переходов) ──────────────────────────────────────────────────
    // Мир поделён на крупные регионы CELL_SIZE×CELL_SIZE блоков. Каждый регион
    // детерминированно (по хэшу от координат региона + seed) получает СВОЙ
    // стиль хребта: либо параллельные гряды под случайным углом (через них
    // хочется наводить мосты), либо кольцевой массив-кальдера (внутри — тихая
    // низина под лес/вишнёвую рощу), плюс шанс на гигантское ущелье, режущее
    // хребет насквозь. Сама ГРАНИЦА "гора/не гора" по-прежнему берётся из
    // mountainMask (широкий fbm) — она отвечает за мягкость переходов;
    // региональная система отвечает только за ФОРМУУ хребта внутри горной зоны.
    private static final double CELL_SIZE         = 640.0; // размер региона (блоков)
    private static final double RIDGE_SPACING     = 260.0; // расстояние между параллельными гребнями (было 340 — с возросшим MAX_EXTRA_HEIGHT давало слишком пологие, "холмистые" гряды вместо гор)
    private static final double RIDGE_WARP_AMPL   = 55.0;  // "волнистость" линии гребня (блоков)
    private static final double RING_WIDTH        = 45.0;  // толщина кольцевого хребта
    private static final double RING_RADIUS_MIN   = 90.0;
    private static final double RING_RADIUS_MAX   = 210.0;
    // Доли архетипов ниже пересчитаны пропорционально при добавлении
    // SHATTERED, чтобы все 5 "особых" архетипов остались сопоставимы друг
    // с другом (было 0.28/0.24/0.20/0.20 без SHATTERED, сумма 0.92 — почти
    // не оставляло места PARALLEL). Теперь сумма специальных = 0.80,
    // PARALLEL = 0.20 — заметная, не исчезающая доля обычных гряд.
    private static final double RING_CHANCE       = 0.195; // доля горных регионов — кольцевые кальдеры
    private static final double ALPINE_CHANCE     = 0.167; // доля горных регионов — альпийские пики (Terralith-style)
    private static final double PLATEAU_CHANCE    = 0.139; // доля горных регионов — плато со крутыми бортами
    private static final double TERRACE_CHANCE    = 0.139; // доля горных регионов — ступенчатый склон
    private static final double SHATTERED_CHANCE  = 0.160; // доля горных регионов — разбитые скальные гряды (хаотичные обломки)
    // Остаток (1 - RING - ALPINE - PLATEAU - TERRACE - SHATTERED = 0.20) — параллельные гряды.
    private static final double CANYON_CHANCE     = 0.35;  // доля горных регионов — с гигантским ущельем
    private static final double CANYON_HALF_WIDTH = 26.0;  // блоков от центра ущелья до края
    private static final int    CANYON_FLOOR      = BASE_SURFACE_Y + 6; // дно ущелья — безопасно выше пещеры

    // ── Реки / озёра ──────────────────────────────────────────────────────────
    // Фиксированный уровень воды — чуть ниже базовой равнины (48), чтобы вода
    // естественно скапливалась в низинах, а не резала склоны гор.
    private static final int    WATER_LEVEL       = BASE_SURFACE_Y - 4; // 44
    private static final double RIVER_HALF_WIDTH  = 0.075; // ширина полосы |noise|<X — река (было 0.045, толще на ~3 блока с каждой стороны)
    private static final double LAKE_THRESHOLD    = 0.55;  // порог по шуму — озеро
    private static final int    RIVER_BED_DEPTH   = 3;
    private static final int    LAKE_BED_DEPTH    = 6;
    // Реки/озёра карвятся только в низинах — там, где горная маска почти не
    // подняла рельеф. Не резать русло сквозь склон горы.
    private static final int    WATER_MAX_LAND_Y  = BASE_SURFACE_Y + 12;
    // Ширина плавного перехода (в единицах шума) между сушей и водой —
    // формирует пологий пляж/склон дна вместо резкого вертикального среза.
    // Было 0.05 — на небольших островах переход укладывался в 1-3 блока и
    // выглядел как обрыв. Расширили втрое.
    private static final double SHORE_BLEND       = 0.14;

    // ── Ступенчатый пляжный карниз ───────────────────────────────────────────
    // Раньше высота суши у самой кромки воды бралась прямо из landHeight
    // (с обычной микрохолмистостью FLATLAND_BUMP) и просто линейно
    // сводилась к уровню дна океана через oceanW/SHORE_BLEND. У холмистого
    // landHeight рядом с водой уже сами по себе есть перепады высоты в
    // 1-3 блока — на песке они читаются как ступеньки прямо на пляже.
    //
    // Промежуточные версии держали весь карниз ровно на WATER_LEVEL — но
    // это визуально читается как береговая линия, просто отодвинутая на
    // несколько блоков вглубь суши (тот же уровень воды, просто дальше),
    // а не как настоящее понижение рельефа. Нужен трёхступенчатый профиль:
    //   1) BEACH_EDGE_WIDTH  (1 блок)  — вровень с водой, касается воды.
    //   2) BEACH_LEDGE_WIDTH (6 блоков) — на 1 блок НИЖЕ, чем п.1 —
    //      то есть уже под уровнем воды на 1 блок. Здесь вода, вытесняющая
    //      песок, может растекаться по нижнему слою, не упираясь в стену.
    //   3) BEACH_LEDGE_BLEND (плавный подъём) — от уровня п.2 обратно к
    //      обычной высоте суши.
    private static final double BEACH_EDGE_WIDTH   = 1.0;  // блоков — первая линия, вровень с водой
    private static final double BEACH_LEDGE_WIDTH  = 6.0;  // блоков — карниз на 1 блок ниже первой линии
    private static final double BEACH_LEDGE_BLEND  = 3.0;  // блоков дальше — переход от карниза к обычному рельефу (минимум для адаптивной ширины, см. BEACH_LEDGE_HEIGHT_RATIO)
    // Дополнительная ширина зоны перехода (в блоках) на каждый блок высоты,
    // которую нужно набрать от карниза до полного рельефа. При blendedY,
    // близком к уровню воды (равнина/пляж), heightToClimb мало и адаптивная
    // ширина не превышает BEACH_LEDGE_BLEND — поведение как раньше. У
    // подножия высокой горы (heightToClimb в сотни блоков) ширина
    // растягивается пропорционально, чтобы угол склона не превращался в
    // отвесную стену. 0.4 ≈ уклон около 68° — всё ещё круто (это подножие
    // горы, не пологий берег), но уже физически проходимый склон, а не
    // вертикальный обрыв.
    private static final double BEACH_LEDGE_HEIGHT_RATIO = 0.4;
    // Высота первой линии — вровень с водой (соприкасается с ней).
    private static final int    BEACH_EDGE_Y       = WATER_LEVEL;
    // Высота карниза — на 1 блок НИЖЕ первой линии, то есть уже под водой.
    private static final int    BEACH_LEDGE_Y      = WATER_LEVEL - 1;

    // ── Океаны ────────────────────────────────────────────────────────────────
    // Отдельный, гораздо более широкий шум (масштаб материков), чем
    // реки/озёра. Ниже порога — открытый океан; глубина растёт по мере
    // удаления от условного "берега" (порога), а не одинакова везде.
    private static final double OCEAN_THRESHOLD  = -0.10; // ниже — океан
    private static final double OCEAN_DEEP_AT    = -0.60; // тут уже максимальная глубина
    private static final int    OCEAN_MIN_DEPTH  = 8;      // глубина у "берега"
    private static final int    OCEAN_MAX_DEPTH  = 24;     // глубина в открытом океане
    // Толщина grass/dirt (или sand/sandstone, terracotta) под поверхностью,
    // которую buildSurface красит поверх (2 верхних блока) — как раньше.
    private static final int SURFACE_SKIN     = 3;

    private static final int DEEPSLATE_TOP = 0;

    // ── Параметры пещеры ──────────────────────────────────────────────────────
    private static final int FLOOR_BASE_Y   = -14;   // базовый Y верхнего края пола
    private static final int CEIL_BASE_Y    = 36;    // базовый Y нижнего края потолка
    private static final int FLOOR_VAR      = 5;     // амплитуда холмов пола (блоков)
    private static final int CEIL_VAR       = 5;     // амплитуда холмов потолка (блоков)
    private static final int STALAGMITE_MAX = 14;    // макс. высота сталагмита
    private static final int STALACTITE_MAX = 14;    // макс. длина сталактита

    // ── Параметры колонн ──────────────────────────────────────────────────────
    // Колонны — форма ПЕСОЧНЫХ ЧАСОВ:
    //   у пола и потолка — широкие (BASE_RADIUS блоков),
    //   в середине — узкие (WAIST_RADIUS блоков).
    // BASE_RADIUS — радиус основания (у пола/потолка)
    // WAIST_RADIUS — радиус талии (в середине)
    private static final int    COLUMN_GRID_SIZE  = 30;   // шаг сетки (блоков)
    private static final double COLUMN_CHANCE     = 0.55; // вероятность колонны в ячейке
    private static final int    COLUMN_BASE_MIN   = 8;    // мин. радиус основания
    private static final int    COLUMN_BASE_MAX   = 18;   // макс. радиус основания
    // Талия = BASE * WAIST_FRACTION (0.18–0.30 → 2–4 блока при base=8..18)
    private static final double COLUMN_WAIST_FRAC = 0.22; // доля от base-радиуса
    // Для совместимости со старым кодом (reach-расчёт)
    private static final int    COLUMN_RADIUS_MAX = COLUMN_BASE_MAX;

    // ── Кэшированные BlockState (пункт S) ────────────────────────────────────
    // defaultBlockState() возвращает singleton, но каждый вызов проходит
    // через виртуальный метод. При 150+ вызовах на чанк в горячих путях
    // (resolveBlock, deepslateBlock, stoneBlock, fillChunk, generateOres)
    // замена на прямое чтение поля даёт заметное ускорение.
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

    // ── Шумовые генераторы ────────────────────────────────────────────────────
    private final AeroNoise stoneVariance;
    private final AeroNoise oreNoise;
    private final AeroNoise floorNoise;
    private final AeroNoise ceilNoise;
    private final AeroNoise stalagNoise;
    private final AeroNoise stalacNoise;
    private final AeroNoise heightNoise;

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
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Региональные параметры хребтов (детерминированный хэш от координат региона)
    // ══════════════════════════════════════════════════════════════════════════

    private static final class RegionParams {
        final double angle;        // ориентация параллельных гряд, радианы
        final RidgeArchetype archetype; // какой архетип рельефа у этого региона
        final double ringCx, ringCz, ringRadius;
        final boolean hasCanyon;
        final double canyonAngle, canyonOffset;
        final boolean cherryGrove; // внутри кольца: вишнёвая роща вместо обычного леса
        // ── Alpine: несколько отдельных пиков внутри региона (координаты
        //    центров + индивидуальный "рост" каждого — не все пики одной
        //    высоты, как в реальных горных цепях). ────────────────────────
        final double[] peakCx, peakCz, peakHeight;
        // ── Plateau: положение и радиус плоской платформы + случайный
        //    "изгрыз" контура (не идеальный круг). ────────────────────────
        final double plateauCx, plateauCz, plateauRadius, plateauEdgeSeed;
        // ── Terrace: положение/радиус ступенчатой горы + сид неровности
        //    контура (тот же приём, что у Plateau, отдельный сид, чтобы
        //    контуры двух архетипов не совпадали при случайном соседстве
        //    региональных ячеек одинакового типа). ─────────────────────────
        final double terraceCx, terraceCz, terraceRadius, terraceEdgeSeed;
        // ── Shattered: несколько угловатых обломков внутри региона —
        //    координаты центров + индивидуальный радиус и "сид" углова-
        //    тости каждого (не все обломки одной формы). ──────────────────
        final double[] shatterCx, shatterCz, shatterRadius, shatterJagSeed;

        RegionParams(double angle, RidgeArchetype archetype, double ringCx, double ringCz, double ringRadius,
                     boolean hasCanyon, double canyonAngle, double canyonOffset, boolean cherryGrove,
                     double[] peakCx, double[] peakCz, double[] peakHeight,
                     double plateauCx, double plateauCz, double plateauRadius, double plateauEdgeSeed,
                     double terraceCx, double terraceCz, double terraceRadius, double terraceEdgeSeed,
                     double[] shatterCx, double[] shatterCz, double[] shatterRadius, double[] shatterJagSeed) {
            this.angle = angle;
            this.archetype = archetype;
            this.ringCx = ringCx;
            this.ringCz = ringCz;
            this.ringRadius = ringRadius;
            this.hasCanyon = hasCanyon;
            this.canyonAngle = canyonAngle;
            this.canyonOffset = canyonOffset;
            this.cherryGrove = cherryGrove;
            this.peakCx = peakCx;
            this.peakCz = peakCz;
            this.peakHeight = peakHeight;
            this.plateauCx = plateauCx;
            this.plateauCz = plateauCz;
            this.plateauRadius = plateauRadius;
            this.plateauEdgeSeed = plateauEdgeSeed;
            this.terraceCx = terraceCx;
            this.terraceCz = terraceCz;
            this.terraceRadius = terraceRadius;
            this.terraceEdgeSeed = terraceEdgeSeed;
            this.shatterCx = shatterCx;
            this.shatterCz = shatterCz;
            this.shatterRadius = shatterRadius;
            this.shatterJagSeed = shatterJagSeed;
        }

        /** Совместимость со старым кодом, который проверял "кольцо или гряды". */
        boolean isRing() { return archetype == RidgeArchetype.RING; }
    }

    /**
     * Архетип рельефа региона — аналог того, как Terralith переключает
     * СТИЛЬ горы по биому (Alpine Highlands, Rocky Shrubland, Amethyst
     * Rock и т.д.), а не использует один и тот же профиль хребта везде.
     * Каждый архетип имеет свою форму профиля в {@link #sampleRidge}.
     */
    private enum RidgeArchetype {
        PARALLEL,   // параллельные гряды (существующее поведение)
        RING,       // кольцевая кальдера (существующее поведение)
        ALPINE,     // несколько острых изрезанных пиков — Terralith "Alpine Highlands"
        PLATEAU,    // плоская приподнятая платформа с крутыми бортами — Terralith "Rocky Shrubland" / mesa-плато
        TERRACE,    // ступенчатый склон — несколько плоских "полок" вместо одного гладкого подъёма
        SHATTERED   // разбитые скальные гряды — хаотичная россыпь угловатых обломков/останцов разной высоты
    }

    private static final int ALPINE_PEAK_COUNT = 3; // пиков на регион
    private static final double ALPINE_PEAK_SPREAD = 0.30; // доля CELL_SIZE — как далеко от центра региона могут быть пики
    // Было 70-130 — при регионе 640 блоков это лишь ~20% площади реально
    // закрывалось пиками, и на фоне общей волнистости они не читались как
    // отдельные "иглы". Расширили радиус влияния — пики стали крупнее и
    // заметнее, но всё ещё локальны (не сливаются в сплошной хребет).
    private static final double ALPINE_PEAK_RADIUS_MIN = 110.0;  // блоков — минимальный "радиус влияния" пика
    private static final double ALPINE_PEAK_RADIUS_MAX = 190.0;
    // >1 = острее вершина, чем стандартный купол. Подняли с 1.9 — раньше
    // пик визуально был почти неотличим от обычного холма той же высоты,
    // острота проявлялась только в самых верхних 10-15% профиля.
    private static final double ALPINE_SHARPNESS = 2.6;
    // Множитель, который поднимает alpine-пики ЗАМЕТНО выше, чем обычный
    // ridge/plateau того же региона — иначе при одинаковом MAX_EXTRA_HEIGHT
    // все архетипы выходят на одну и ту же максимальную высоту, и разница
    // между "холмом" и "горой" остаётся только в форме силуэта, а не в
    // масштабе, из-за чего пик легко потерять на фоне соседних холмов.
    private static final double ALPINE_HEIGHT_BOOST = 1.22;

    private static final double PLATEAU_RADIUS_MIN = 130.0;
    private static final double PLATEAU_RADIUS_MAX = 240.0;
    // Было 34 — при MAX_EXTRA_HEIGHT=190 и полной высоте плато набор высоты
    // на этой ширине борта означал уклон порядка 190/34 ≈ 5.6 блока вверх на
    // 1 блок вбок, то есть практически отвесная стена. Именно такой уклон
    // на воксельной сетке даёт "рифлёный" вид: каждый шаг высоты в 1 блок
    // соответствует лишь доле блока по горизонтали, поэтому грань каждой
    // ступени становится видимой горизонтальной полосой травы/камня.
    // Расширили почти вдвое — тот же диапазон высоты теперь растягивается
    // на больше блоков по горизонтали, уклон становится физически более
    // пологим, а не только "математически гладким".
    private static final double PLATEAU_EDGE_WIDTH = 62.0;   // ширина "борта" от полной высоты до 0
    private static final double PLATEAU_EDGE_ROUGH_AMPL = 18.0; // неровность контура плато (блоков)
    private static final double PLATEAU_TOP_FLAT = 0.85; // доля высоты, где верх уже почти не растёт (плоская "крыша")

    // ── Terrace: ступенчатый склон ────────────────────────────────────────
    // Контур региона — тот же приём, что у PLATEAU (круглая область с
    // неровным краем через тот же edgeNoise-подход), но профиль ВНУТРИ
    // контура не гладкий купол/плато, а серия плоских "полок" — гора
    // поднимается уступами, а не одним непрерывным склоном.
    private static final double TERRACE_RADIUS_MIN = 140.0;
    private static final double TERRACE_RADIUS_MAX = 230.0;
    private static final double TERRACE_EDGE_ROUGH_AMPL = 16.0; // неровность внешнего контура (блоков)
    // Сколько ступеней у горы (от подножия до вершины). Каждая ступень —
    // плоская "полка" ridge01, разделённая коротким крутым подъёмом до
    // следующей полки — визуально как террасное земледелие / рисовые
    // террасы, только в масштабе горы.
    private static final int    TERRACE_STEP_COUNT = 5;
    // Доля [0,1] каждой ступени, которая остаётся ПЛОСКОЙ (полка) — остаток
    // идёт на подъём к следующей ступени. 0.7 = полка держится 70% пути,
    // подъём к следующей полке занимает оставшиеся 30% — короткий и
    // заметный, но не отвесный (сглажен smoothstep, как и все остальные
    // архетипы, чтобы не давать ровно вертикальных стен).
    private static final double TERRACE_STEP_FLAT_FRAC = 0.7;
    // Небольшая волнистость контура каждой отдельной ступени по азимуту —
    // без неё все уступы читаются как идеальные концентрические окружности,
    // что для рукотворных террас нормально, но для природной горы выглядит
    // слишком искусственно. Амплитуда в единицах "доли ступени" (не блоков).
    private static final double TERRACE_STEP_WOBBLE = 0.10;

    // ── Shattered: разбитые скальные гряды (хаотичные обломки) ────────────
    // Россыпь угловатых останцов-обломков в регионе — как canyon-стиль
    // Badlands (резкие грани, никакой округлости), но крупнее и выше самого
    // ущелья: это не прорезь в рельефе, а сам рельеф из "разбитых" скальных
    // глыб разной высоты. Похоже по структуре на ALPINE (несколько локальных
    // пиков, берём максимум по всем), но профиль каждого останца не гладкий
    // купол — расстояние до центра ИСКАЖАЕТСЯ угловатым шумом ДО того, как
    // проходит через smoothstep, из-за чего контур каждого обломка становится
    // зубчатым/угловатым вместо круглого, а верх — плоский скол, а не пик.
    private static final int    SHATTERED_COUNT = 6; // обломков на регион (больше, чем ALPINE_PEAK_COUNT — это россыпь, а не отдельные пики)
    private static final double SHATTERED_SPREAD = 0.34; // доля CELL_SIZE — разброс обломков вокруг центра региона
    private static final double SHATTERED_RADIUS_MIN = 55.0;  // блоков — минимальный радиус отдельного обломка
    private static final double SHATTERED_RADIUS_MAX = 105.0;
    // Амплитуда угловатого искажения контура, в блоках. Применяется к
    // расстоянию до центра обломка ДО smoothstep — высокочастотный шум даёт
    // рваный, "сколотый" край вместо гладкой окружности alpine-пика.
    private static final double SHATTERED_JAGGED_AMPL = 22.0;
    // Доля верхней части профиля (после smoothstep), которая срезается в
    // плоский "скол" — обломок выглядит как отбитая глыба с плоской гранью
    // сверху, а не как купол или острый пик. Меньше, чем PLATEAU_TOP_FLAT —
    // скол должен читаться резче, площадка меньше.
    private static final double SHATTERED_TOP_FLAT = 0.90;
    // Множитель высоты — обломки должны быть заметно выше обычного каньона
    // (CANYON_FLOOR у самого подножия), это отдельный высокий скальный
    // рельеф, а не прорезь. Сопоставимо с ALPINE_HEIGHT_BOOST.
    private static final double SHATTERED_HEIGHT_BOOST = 1.15;



    /** Детерминированный 64-битный хэш от координат региона + мировой seed. */
    private long cellHash(int cellX, int cellZ) {
        long h = seed;
        h = h * 6364136223846793005L + cellX * 1442695040888963407L + 0x9E3779B97F4A7C15L;
        h = h * 6364136223846793005L + cellZ * 1442695040888963407L + 0x85EBCA6B_C2B2AE35L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return h;
    }

    /** Псевдослучайное число 0..1 из хэша региона + "соль" (разные значения из одного региона). */
    private static double hashDouble(long regionHash, int salt) {
        long v = regionHash * 1000003L + salt * 0xC2B2AE3D27D4EB4FL;
        v ^= (v >>> 29);
        v *= 0xBF58476D1CE4E5B9L;
        v ^= (v >>> 32);
        return ((v >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private RegionParams regionParams(int cellX, int cellZ) {
        long h = cellHash(cellX, cellZ);
        double angle = hashDouble(h, 1) * Math.PI;

        // ── Выбор архетипа рельефа региона ───────────────────────────────
        // Раньше был единственный бинарный выбор isRing (RING_CHANCE) /
        // parallel. Теперь это 4-way выбор по накопленным вероятностям —
        // RING_CHANCE и оставшаяся часть делятся между PARALLEL/ALPINE/
        // PLATEAU. ALPINE_CHANCE и PLATEAU_CHANCE держим достаточно
        // высокими, чтобы новые архетипы реально встречались, а не
        // терялись как редкость.
        double archRoll = hashDouble(h, 2);
        RidgeArchetype archetype;
        if (archRoll < RING_CHANCE) {
            archetype = RidgeArchetype.RING;
        } else if (archRoll < RING_CHANCE + ALPINE_CHANCE) {
            archetype = RidgeArchetype.ALPINE;
        } else if (archRoll < RING_CHANCE + ALPINE_CHANCE + PLATEAU_CHANCE) {
            archetype = RidgeArchetype.PLATEAU;
        } else if (archRoll < RING_CHANCE + ALPINE_CHANCE + PLATEAU_CHANCE + TERRACE_CHANCE) {
            archetype = RidgeArchetype.TERRACE;
        } else if (archRoll < RING_CHANCE + ALPINE_CHANCE + PLATEAU_CHANCE + TERRACE_CHANCE + SHATTERED_CHANCE) {
            archetype = RidgeArchetype.SHATTERED;
        } else {
            archetype = RidgeArchetype.PARALLEL;
        }

        double ringCx = (cellX + 0.25 + hashDouble(h, 3) * 0.5) * CELL_SIZE;
        double ringCz = (cellZ + 0.25 + hashDouble(h, 4) * 0.5) * CELL_SIZE;
        double ringRadius = RING_RADIUS_MIN + hashDouble(h, 5) * (RING_RADIUS_MAX - RING_RADIUS_MIN);
        boolean hasCanyon = hashDouble(h, 6) < CANYON_CHANCE;
        double canyonAngle = hashDouble(h, 7) * Math.PI;
        double canyonOffset = (hashDouble(h, 8) - 0.5) * CELL_SIZE * 0.5;
        boolean cherryGrove = hashDouble(h, 9) < 0.5;

        // ── Alpine: 3 пика, разбросанных вокруг центра региона ───────────
        double regionCx = (cellX + 0.5) * CELL_SIZE;
        double regionCz = (cellZ + 0.5) * CELL_SIZE;
        double[] peakCx = new double[ALPINE_PEAK_COUNT];
        double[] peakCz = new double[ALPINE_PEAK_COUNT];
        double[] peakHeight = new double[ALPINE_PEAK_COUNT];
        for (int i = 0; i < ALPINE_PEAK_COUNT; i++) {
            int salt = 20 + i * 3;
            double ox = (hashDouble(h, salt) - 0.5) * 2.0 * ALPINE_PEAK_SPREAD * CELL_SIZE;
            double oz = (hashDouble(h, salt + 1) - 0.5) * 2.0 * ALPINE_PEAK_SPREAD * CELL_SIZE;
            peakCx[i] = regionCx + ox;
            peakCz[i] = regionCz + oz;
            // Высота пика 0.55..1.0 от полного размаха — не все пики цепи
            // одинаковой высоты, как в реальном горном массиве.
            peakHeight[i] = 0.55 + 0.45 * hashDouble(h, salt + 2);
        }

        // ── Plateau: одна платформа в центре региона ──────────────────────
        double plateauCx = regionCx + (hashDouble(h, 40) - 0.5) * 0.3 * CELL_SIZE;
        double plateauCz = regionCz + (hashDouble(h, 41) - 0.5) * 0.3 * CELL_SIZE;
        double plateauRadius = PLATEAU_RADIUS_MIN + hashDouble(h, 42) * (PLATEAU_RADIUS_MAX - PLATEAU_RADIUS_MIN);
        double plateauEdgeSeed = hashDouble(h, 43) * 1000.0;

        // ── Terrace: одна ступенчатая гора в центре региона ────────────────
        // Соли 50-53 — отдельный диапазон от plateau (40-43), чтобы позиция
        // и радиус двух архетипов не коррелировали между собой при общем h.
        double terraceCx = regionCx + (hashDouble(h, 50) - 0.5) * 0.3 * CELL_SIZE;
        double terraceCz = regionCz + (hashDouble(h, 51) - 0.5) * 0.3 * CELL_SIZE;
        double terraceRadius = TERRACE_RADIUS_MIN + hashDouble(h, 52) * (TERRACE_RADIUS_MAX - TERRACE_RADIUS_MIN);
        double terraceEdgeSeed = hashDouble(h, 53) * 1000.0;

        // ── Shattered: россыпь угловатых обломков вокруг центра региона ────
        // Соли 60+ — отдельный диапазон от alpine (20-28), plateau (40-43) и
        // terrace (50-53), чтобы позиции обломков не коррелировали с ними.
        double[] shatterCx      = new double[SHATTERED_COUNT];
        double[] shatterCz      = new double[SHATTERED_COUNT];
        double[] shatterRadius  = new double[SHATTERED_COUNT];
        double[] shatterJagSeed = new double[SHATTERED_COUNT];
        for (int i = 0; i < SHATTERED_COUNT; i++) {
            int salt = 60 + i * 4;
            double ox = (hashDouble(h, salt) - 0.5) * 2.0 * SHATTERED_SPREAD * CELL_SIZE;
            double oz = (hashDouble(h, salt + 1) - 0.5) * 2.0 * SHATTERED_SPREAD * CELL_SIZE;
            shatterCx[i]      = regionCx + ox;
            shatterCz[i]      = regionCz + oz;
            shatterRadius[i]  = SHATTERED_RADIUS_MIN
                    + hashDouble(h, salt + 2) * (SHATTERED_RADIUS_MAX - SHATTERED_RADIUS_MIN);
            shatterJagSeed[i] = hashDouble(h, salt + 3) * 1000.0;
        }

        return new RegionParams(angle, archetype, ringCx, ringCz, ringRadius,
                hasCanyon, canyonAngle, canyonOffset, cherryGrove,
                peakCx, peakCz, peakHeight,
                plateauCx, plateauCz, plateauRadius, plateauEdgeSeed,
                terraceCx, terraceCz, terraceRadius, terraceEdgeSeed,
                shatterCx, shatterCz, shatterRadius, shatterJagSeed);
    }

    /**
     * true, если (wx, wz) находится ВНУТРИ низины кольцевой кальдеры (не на
     * самом хребте, а в защищённой долине внутри него). Используется
     * {@code AeroBiomeSource}, чтобы гарантированно поставить туда лес или
     * вишнёвую рощу вместо того, что выпадет по обычной климатической таблице.
     *
     * <p>Повторяет ту же формулу (включая warp), что и {@link #computeLandHeight},
     * чтобы граница биома совпадала с фактической границей низины в рельефе.
     */
    public boolean isInsideRingValley(int wx, int wz) {
        int cellX = (int) Math.floor(wx / CELL_SIZE);
        int cellZ = (int) Math.floor(wz / CELL_SIZE);
        RegionParams p = regionParams(cellX, cellZ);
        if (!p.isRing()) return false;

        double warp = heightNoise.fbm2D(wx * 0.003 + 41000, wz * 0.003 + 41000, 3, 2.0, 0.5)
                * RIDGE_WARP_AMPL;
        double dx = wx - p.ringCx + warp;
        double dz = wz - p.ringCz + warp;
        double dist = Math.sqrt(dx * dx + dz * dz);
        return dist < p.ringRadius - RING_WIDTH;
    }

    /**
     * Какой биом должен быть внутри кольцевой долины в этой точке — "forest"
     * или "cherry_grove". Вызывать только после {@link #isInsideRingValley}
     * вернувшего true (иначе результат не имеет смысла — точка не в кольце).
     * Один и тот же выбор для всей долины целиком (не покольонный микс).
     */
    public String ringValleyBiome(int wx, int wz) {
        int cellX = (int) Math.floor(wx / CELL_SIZE);
        int cellZ = (int) Math.floor(wz / CELL_SIZE);
        return regionParams(cellX, cellZ).cherryGrove ? "cherry_grove" : "forest";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Высота поверхности (горы/холмы) — многооктавный шум
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Высота поверхности (верхний твёрдый блок) в колонке (wx, wz).
     *
     * <p>Раньше это была константа {@code SURFACE_Y=48} — плоский мир.
     * Теперь — настоящая шумовая heightmap, ближе по духу к ванильной:
     *
     * <ol>
     *   <li><b>mountainMask</b> — очень широкий (континентальный) шум,
     *       пропущенный через smoothstep-порог. У большей части карты
     *       он равен 0 (равнины/холмы), и только в отдельных РЕГИОНАХ
     *       плавно поднимается к 1 (горный хребет). Это ключевое отличие
     *       от первой версии: раньше хребты примешивались почти ВЕЗДЕ,
     *       из-за чего весь мир выглядел одинаково зубчатым и скрывал
     *       разницу между биомами.</li>
     *   <li><b>ridge</b> — форма самого хребта ({@code 1-|noise|}), но
     *       используется только там, где mountainMask > 0.</li>
     *   <li><b>hills</b> — мелкая холмистость, применяется ВЕЗДЕ (и на
     *       равнинах тоже) — небольшая амплитуда, чтобы равнины не были
     *       идеально плоским столом, но горами не выглядели.</li>
     * </ol>
     *
     * <p>Значение только поднимается над базовым уровнем равнин
     * ({@link #BASE_SURFACE_Y}) — вниз не проседает, чтобы не залезать
     * в пещерную систему (потолок пещеры максимум ~{@code CEIL_BASE_Y+CEIL_VAR}).
     *
     * <p>ВАЖНО: этот метод — источник истины для высоты поверхности.
     * {@code AeroWorldChunkGenerator} (getBaseHeight/getBaseColumn/
     * applyLayer1Surface) и {@code TerrainColumnSampler} обязаны вызывать
     * именно его, а не полагаться на старую константу SURFACE_Y — иначе
     * height-map запросы, покраска поверхности и валидация структур разъедутся
     * с фактическим рельефом, который рисует {@link #fillChunk}.
     */
    private int computeLandHeight(int wx, int wz) {
        // ── Маска горных регионов ────────────────────────────────────────────
        // ВАЖНО: тут было 4 октавы шума — высокочастотные октавы добавляют
        // мелкую рябь ПОВЕРХ широкой формы, и из-за неё порог smoothstep
        // мог пересекаться очень быстро в пространстве (за 20-40 блоков),
        // даже когда общая структура шума широкая. Математически переход
        // гладкий (непрерывная производная), но ФИЗИЧЕСКИ узкий — именно
        // это и выглядело как отвесная стена на стыке горы/равнины.
        // Убрали октавы (осталось 2 — только базовая широкая форма) и сильно
        // расширили сам диапазон smoothstep, чтобы переход растягивался на
        // многие сотни блоков, а не десятки.
        double maskRaw = heightNoise.fbm2D(wx * 0.00035, wz * 0.00035, 2, 2.0, 0.5);
        // Доп. страховка: усредняем маску с несколькими точками вокруг
        // (дешёвый box-blur, всего 2 октавы на каждый сэмпл) — это ЖЁСТКО
        // ограничивает, насколько быстро маска может измениться в пространстве,
        // независимо от того, что делает сам шум локально.
        double blurR = 40.0;
        double maskN = heightNoise.fbm2D(wx * 0.00035, (wz + blurR) * 0.00035, 2, 2.0, 0.5);
        double maskS = heightNoise.fbm2D(wx * 0.00035, (wz - blurR) * 0.00035, 2, 2.0, 0.5);
        double maskE = heightNoise.fbm2D((wx + blurR) * 0.00035, wz * 0.00035, 2, 2.0, 0.5);
        double maskW = heightNoise.fbm2D((wx - blurR) * 0.00035, wz * 0.00035, 2, 2.0, 0.5);
        double maskBlurred = (maskRaw + maskN + maskS + maskE + maskW) / 5.0;
        // Было smoothstep(-0.45, 0.65) — диапазон шириной 1.1 при типичном
        // размахе 2-октавного fbm (примерно ±0.7) означает, что значение 1.0
        // достигается только в крайне редком хвосте распределения шума.
        // Практически ВСЯ карта попадала в промежуточную зону маски (между
        // 0.1 и 0.9) — то есть "настоящей" полной высоты гора почти нигде
        // не набирала, и весь ландшафт выглядел как один сплошной перекат
        // из невысоких холмов, а не как чередование равнин и гор.
        // Сузили диапазон почти вдвое — теперь маска увереннее доходит до
        // 1.0 на значимой части каждого горного региона, при этом ширина
        // перехода (0.6 единиц шума) всё ещё растягивается на многие сотни
        // блоков благодаря низкой частоте шума (0.00035) — резких стен на
        // границе не будет, просто "плато" полной высоты станет шире.
        double mountainMask = smoothstep(-0.20, 0.40, maskBlurred);

        // ── "Волнистость" линии гребня — ДВА масштаба искажения вместо одного:
        //    крупный (плавные извивы всей линии) + мелкий (рваность, ломает
        //    ощущение "идеальной параболы"). Складываются вместе.
        double warpBig = heightNoise.fbm2D(wx * 0.0012 + 41000, wz * 0.0012 + 41000, 3, 2.0, 0.5)
                * (RIDGE_WARP_AMPL * 1.6);
        double warpSmall = heightNoise.fbm2D(wx * 0.008 + 61000, wz * 0.008 + 61000, 3, 2.0, 0.5)
                * (RIDGE_WARP_AMPL * 0.35);
        double warp = warpBig + warpSmall;

        // ── Форма гребня — билинейный бленд 4 СОСЕДНИХ региональных ячеек
        //    (а не жёсткий выбор одной ближайшей). Раньше на границе двух
        //    ячеек стиль хребта (угол/тип) менялся МГНОВЕННО — визуально это
        //    и был тот самый "обрезанный" перепад высот. Теперь между углами
        //    ячейки идёт плавный smoothstep-переход по обеим осям.
        double cellFx = frac(wx / CELL_SIZE);
        double cellFz = frac(wz / CELL_SIZE);
        double fadeX = smoothstep(0.0, 1.0, cellFx);
        double fadeZ = smoothstep(0.0, 1.0, cellFz);

        int cx0 = (int) Math.floor(wx / CELL_SIZE);
        int cz0 = (int) Math.floor(wz / CELL_SIZE);
        int cx1 = cx0 + 1;
        int cz1 = cz0 + 1;

        RidgeSample s00 = sampleRidge(cx0, cz0, wx, wz, warp);
        RidgeSample s10 = sampleRidge(cx1, cz0, wx, wz, warp);
        RidgeSample s01 = sampleRidge(cx0, cz1, wx, wz, warp);
        RidgeSample s11 = sampleRidge(cx1, cz1, wx, wz, warp);

        double ridgeTop    = lerp(s00.ridge, s10.ridge, fadeX);
        double ridgeBottom = lerp(s01.ridge, s11.ridge, fadeX);
        double ridge = lerp(ridgeTop, ridgeBottom, fadeZ);

        double ringTop    = lerp(s00.insideRing ? 1.0 : 0.0, s10.insideRing ? 1.0 : 0.0, fadeX);
        double ringBottom = lerp(s01.insideRing ? 1.0 : 0.0, s11.insideRing ? 1.0 : 0.0, fadeX);
        double insideRingFactor = lerp(ringTop, ringBottom, fadeZ); // 0..1

        // ── Неровность гребня вдоль его длины — отдельный шум, ломает
        //    идеально гладкую симметричную "параболу". Часть гребня выше
        //    и острее, часть — размыта и почти сходит на нет, как в природе.
        //
        // Раньше: 2-октавный шум напрямую зажимался в [0,1] через
        // max(0, roughRaw) и линейно умножал ridge. max(0, x) на самом шуме
        // даёт те же изломы, что мы только что убрали из формы гребня —
        // там, где roughRaw пересекает ноль, множитель имеет угловую точку,
        // и она проявляется как внезапный "уступ" высоты вдоль хребта.
        // Плюс всего 2 октавы означают, что эта неровность меняется почти
        // на том же пространственном масштабе, что и сам гребень — из-за
        // этого весь профиль "дрожит" крупными скачками, а не мелкой рябью.
        //
        // Теперь: 4 октавы (более естественный, самоподобный эрозионный
        // узор) и smoothstep вместо max(0, ...) — убирает угловую точку на
        // нуле. Диапазон сужен до 0.65..1.0: горы стали чуть "мясистее"
        // (меньше регионов, где хребет почти обнуляется), а сам множитель
        // теперь плавно проезжает через весь диапазон без излома.
        double roughRaw = heightNoise.fbm2D(wx * 0.006 + 81000, wz * 0.006 + 81000, 4, 2.0, 0.5);
        double roughFactor = 0.65 + 0.35 * smoothstep(-1.0, 1.0, roughRaw); // 0.65..1.0
        ridge *= roughFactor;

        // Внутри кольцевой низины гор почти нет (пропорционально степени
        // "внутренности" — тоже плавно, без резкой границы).
        double localMountainMask = mountainMask * (1.0 - insideRingFactor * 0.88);

        // ── Мелкая холмистость — везде, но с малой амплитудой ────────────────
        double hillsRaw = heightNoise.fbm2D(wx * 0.02 + 3000, wz * 0.02 + 3000, 3, 2.0, 0.5);
        double hills01  = Math.max(0.0, hillsRaw); // 0..1

        // Ближайшая региональная ячейка — нужна и для alpine-буста высоты
        // (ниже), и для каньона (дальше по методу). Вычисляем один раз здесь.
        int nearCellX = (int) Math.round(wx / CELL_SIZE);
        int nearCellZ = (int) Math.round(wz / CELL_SIZE);
        RegionParams nearest = regionParams(nearCellX, nearCellZ);

        // Горная часть: полный размах MAX_EXTRA_HEIGHT, включена только
        // пропорционально localMountainMask.
        //
        // ridge уже смягчён (smoothstep-профиль вместо pow(cos)), но
        // произведение localMountainMask * ridge всё ещё может расти почти
        // линейно с высотой на небольшом числе блоков там, где ridge уже
        // близок к 1, а mountainMask только начинает подниматься от 0 —
        // визуально это тоже читалось как резкий "трамплин" у подножия.
        // Дополнительный smoothstep по итоговому произведению растягивает
        // именно нижнюю и верхнюю часть кривой набора высоты, оставляя
        // середину почти как было — то есть общая высота гор не меняется,
        // меняется только "разгон" в начале подъёма и "торможение" у пика.
        double combined = localMountainMask * ridge;
        double combinedSmoothed = smoothstep(0.0, 1.0, combined);
        // Alpine- и Shattered-регионы намеренно поднимаются выше остальных
        // архетипов той же mountainMask — иначе пик/обломок той же высоты,
        // что окружающие холмы, визуально в них тонет. Буст применяется к
        // БЛИЖАЙШЕЙ ячейке, а не к билинейно смешанной, поэтому на границе
        // с соседним архетипом он тоже плавно спадает вместе с
        // localMountainMask/ridge — резкого скачка высоты на стыке
        // регионов не возникает.
        double heightBoost = switch (nearest.archetype) {
            case ALPINE -> ALPINE_HEIGHT_BOOST;
            case SHATTERED -> SHATTERED_HEIGHT_BOOST;
            default -> 1.0;
        };
        double rawMountainExtra = combinedSmoothed * MAX_EXTRA_HEIGHT * heightBoost;

        // ── Мягкое ограничение высоты у потолка слоя ─────────────────────────
        // Раньше итоговая высота h просто клэмпилась через Math.min(h, ceiling)
        // в конце метода. При heightBoost > 1.0 (ALPINE) rawMountainExtra
        // регулярно превышал ceiling - BASE_SURFACE_Y, и Math.min срезал
        // вершину ГОРИЗОНТАЛЬНОЙ плоскостью — визуально "срубленные" горы
        // с плоской крышей вместо острого пика (см. баг-репорт со скриншотом).
        //
        // Вместо этого сжимаем rawMountainExtra ДО округления в int, пока он
        // ещё непрерывная величина: в зоне [softZoneStart, maxAllowedExtra]
        // рост высоты плавно гасится к maxAllowedExtra через smoothstep,
        // так что кривая сама выполаживается в купол/пик, а не обрубается.
        // Вне горячей зоны (обычные ridge и низкие альпийские пики) поведение
        // не меняется вообще.
        double maxAllowedExtra = (LAYER_MAX_Y - PEAK_SKY_BUFFER) - BASE_SURFACE_Y;
        double softZoneStart   = maxAllowedExtra - 40.0; // блоков до потолка, где начинаем гасить рост
        double clampedMountainExtra;
        if (rawMountainExtra <= softZoneStart) {
            clampedMountainExtra = rawMountainExtra;
        } else {
            double t = Math.min(1.0, (rawMountainExtra - softZoneStart) / (maxAllowedExtra - softZoneStart));
            double eased = 1.0 - (1.0 - t) * (1.0 - t); // ease-out quad — быстрое торможение к потолку
            clampedMountainExtra = softZoneStart + (maxAllowedExtra - softZoneStart) * eased;
        }
        int mountainExtra = (int) Math.round(clampedMountainExtra);

        // Равнинная холмистость: скромные ±FLATLAND_BUMP блоков, гасится
        // внутри горных регионов (там рельеф и так задран хребтом).
        int flatExtra = (int) Math.round(hills01 * FLATLAND_BUMP * (1.0 - localMountainMask));

        int h = BASE_SURFACE_Y + Math.max(0, mountainExtra) + flatExtra;

        // ── Гигантское ущелье, режущее хребет насквозь ───────────────────────
        // Только в регионах с hasCanyon, и только там, где реально есть гора
        // (mountainMask масштабирует эффект — на равнине каньон невидим).
        // Каньон не блендится между ячейками (это единичная резкая структура,
        // не форма гребня) — берём параметры БЛИЖАЙШЕЙ ячейки (round, не floor).
        if (nearest.hasCanyon && mountainMask > 0.05) {
            double cu = wx * Math.cos(nearest.canyonAngle) + wz * Math.sin(nearest.canyonAngle)
                    - nearest.canyonOffset + warp;
            double canyonDist = Math.abs(cu);
            if (canyonDist < CANYON_HALF_WIDTH) {
                double t = 1.0 - canyonDist / CANYON_HALF_WIDTH; // 1 в центре, 0 на краях
                double carve = Math.pow(t, 1.4) * mountainMask;
                h = (int) Math.round(h - (h - CANYON_FLOOR) * carve);
            }
        }

        // Запас неба над самым высоким пиком
        return Math.min(h, LAYER_MAX_Y - PEAK_SKY_BUFFER);
    }

    /** Классический smoothstep: 0 ниже edge0, 1 выше edge1, плавный переход между ними. */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Результат расчёта формы гребня ОДНОЙ региональной ячейки в точке (wx, wz). */
    private record RidgeSample(double ridge, boolean insideRing) {}

    /**
     * Форма гребня, КАК ЕСЛИ БЫ вся карта использовала стиль ячейки
     * (cellX, cellZ) — используется как один из 4 углов билинейного бленда
     * в {@link #computeLandHeight}, а не как самостоятельное значение.
     */
    private RidgeSample sampleRidge(int cellX, int cellZ, int wx, int wz, double warp) {
        RegionParams p = regionParams(cellX, cellZ);
        switch (p.archetype) {
            case RING -> {
                // ── Кольцевая кальдера: хребет по окружности радиуса ringRadius,
                //    внутри — низина (место под лес/вишнёвую рощу).
                // Профиль поперёк хребта раньше строился как pow(linear, 1.1) —
                // почти треугольный скат с острым переломом на вершине. Заменили
                // на smoothstep: тот же общий "холм" по форме, но производная
                // непрерывно уходит к нулю у подножия И на пике, поэтому склон
                // выкатывается наружу вместо излома, а вершина — купол, а не угол.
                double dx = wx - p.ringCx + warp;
                double dz = wz - p.ringCz + warp;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double raw = Math.max(0.0, 1.0 - Math.abs(dist - p.ringRadius) / RING_WIDTH);
                double ridge = smoothstep(0.0, 1.0, raw);
                boolean insideRing = dist < p.ringRadius - RING_WIDTH;
                return new RidgeSample(ridge, insideRing);
            }
            case ALPINE -> {
                return sampleAlpine(p, wx, wz, warp);
            }
            case PLATEAU -> {
                return samplePlateau(p, wx, wz, warp);
            }
            case TERRACE -> {
                return sampleTerrace(p, wx, wz, warp);
            }
            case SHATTERED -> {
                return sampleShattered(p, wx, wz, warp);
            }
            default -> {
                // ── Параллельные гряды: проекция координат на ось, перпендикулярную
                //    направлению хребтов (angle), даёт периодическую волну —
                //    гребень/долина/гребень/долина, все параллельны друг другу.
                //
                // Раньше: pow(max(0, cos(u)), 1.15). У max(0, cos) есть слом
                // первой производной ровно там, где cos пересекает ноль (это
                // и есть подножие горы) — визуально резкий "обрыв" на границе
                // хребет/долина. pow() близко к 1 почти не меняет форму пика,
                // только слегка заостряет верхушку.
                //
                // Теперь: сначала растягиваем band из [-1,1] в [0,1], затем
                // прогоняем через smoothstep вместо pow. smoothstep(0,1,t) имеет
                // нулевую производную на ОБОИХ концах (t=0 и t=1) — то есть и
                // подножие, и вершина выходят на горизонталь плавно. Получается
                // покатый, куполообразный профиль вместо пилы. Небольшая
                // остаточная pow(...,1.06) сохраняет чуть более острый гребень
                // на самой верхушке, не возвращая резкости у подножия.
                double u = wx * Math.cos(p.angle) + wz * Math.sin(p.angle) + warp;
                double band01 = 0.5 + 0.5 * Math.cos(u * (2.0 * Math.PI / RIDGE_SPACING));
                double ridge = smoothstep(0.0, 1.0, band01);
                ridge = Math.pow(ridge, 1.06);
                return new RidgeSample(ridge, false);
            }
        }
    }

    /**
     * Alpine-архетип (Terralith "Alpine Highlands"): несколько отдельных
     * острых пиков в регионе вместо одного протяжённого хребта. Каждый пик —
     * купол с более крутым профилем (ALPINE_SHARPNESS > 1), пики
     * перекрываются по принципу "берём максимум", как горная цепь с
     * несколькими вершинами разной высоты, а не одна гладкая гряда.
     *
     * <p>В отличие от parallel/ring, тут нет периодической волны — каждый
     * пик локален (влияние падает до 0 за пределами своего радиуса), что и
     * даёт характерный "россыпь вершин" силуэт вместо сплошного хребта.
     */
    private RidgeSample sampleAlpine(RegionParams p, int wx, int wz, double warp) {
        double best = 0.0;
        for (int i = 0; i < p.peakCx.length; i++) {
            double dx = wx - p.peakCx[i] + warp;
            double dz = wz - p.peakCz[i] + warp;
            double dist = Math.sqrt(dx * dx + dz * dz);
            // Радиус влияния варьируется по пику (используем peakHeight как
            // вторичный источник вариации — выше пик, чуть шире его подошва).
            double radius = ALPINE_PEAK_RADIUS_MIN
                    + (ALPINE_PEAK_RADIUS_MAX - ALPINE_PEAK_RADIUS_MIN) * p.peakHeight[i];
            double t = Math.max(0.0, 1.0 - dist / radius); // 1 в центре пика, 0 на радиусе
            double dome = smoothstep(0.0, 1.0, t);
            // Заостряем вершину сильнее, чем у стандартного купола — альпийские
            // пики в Terralith именно острые сверху при том, что подножие
            // всё равно плавно уходит в ноль (smoothstep выше уже это дал).
            dome = Math.pow(dome, ALPINE_SHARPNESS);
            double contribution = dome * p.peakHeight[i];
            best = Math.max(best, contribution);
        }
        // Между пиками добавляем тонкую "перемычку" — иначе несколько
        // соседних пиков выглядят как полностью изолированные острова
        // высоты с провалом до нуля между ними, что в реальных горных
        // цепях бывает редко. Небольшой шум держит дно седловины немного
        // выше нуля, но заметно ниже самих пиков.
        double saddleNoise = 0.12 + 0.06 * Math.max(0.0,
                heightNoise.fbm2D(wx * 0.01 + 91000, wz * 0.01 + 91000, 3, 2.0, 0.5));
        double ridge = Math.max(best, best > 0.02 ? saddleNoise * (best > 0.5 ? 1.0 : best * 2.0) : 0.0);
        return new RidgeSample(Math.min(1.0, ridge), false);
    }

    /**
     * Plateau-архетип (Terralith "Rocky Shrubland" / mesa-подобные плато):
     * плоская, приподнятая над округой платформа со крутыми, но не
     * вертикальными бортами. В отличие от alpine (острый купол) и ridge
     * (волна), тут верхняя часть профиля намеренно СЖИМАЕТСЯ к плоскому
     * "потолку" — {@link #PLATEAU_TOP_FLAT} — вместо непрерывного роста к
     * единственной точке-пику.
     */
    private RidgeSample samplePlateau(RegionParams p, int wx, int wz, double warp) {
        // Неровный контур платформы — без этого шума граница была бы
        // идеальным кругом, что в природном рельефе смотрится искусственно.
        double edgeNoise = heightNoise.fbm2D(
                wx * 0.01 + p.plateauEdgeSeed, wz * 0.01 + p.plateauEdgeSeed, 3, 2.0, 0.5)
                * PLATEAU_EDGE_ROUGH_AMPL;

        double dx = wx - p.plateauCx + warp;
        double dz = wz - p.plateauCz + warp;
        double dist = Math.sqrt(dx * dx + dz * dz) - edgeNoise;

        // Профиль по дистанции: 1.0 внутри (dist much less than radius),
        // плавный спад к 0 в полосе шириной PLATEAU_EDGE_WIDTH у границы.
        double raw = 1.0 - smoothstep(p.plateauRadius - PLATEAU_EDGE_WIDTH, p.plateauRadius, dist);
        raw = Math.max(0.0, Math.min(1.0, raw));

        // "Сплющиваем" верх: всё, что выше PLATEAU_TOP_FLAT от полной высоты,
        // подтягиваем к 1.0 быстрее, чем растёт линейно — получаем плоскую
        // "крышу" вместо купола, при этом борт остаётся плавным (тот же
        // smoothstep, что и у остальных архетипов, не резкий обрыв).
        double ridge;
        if (raw > PLATEAU_TOP_FLAT) {
            double topT = (raw - PLATEAU_TOP_FLAT) / (1.0 - PLATEAU_TOP_FLAT);
            ridge = PLATEAU_TOP_FLAT + (1.0 - PLATEAU_TOP_FLAT) * smoothstep(0.0, 1.0, topT);
        } else {
            ridge = raw;
        }
        return new RidgeSample(ridge, false);
    }

    /**
     * Terrace-архетип: ступенчатый склон — гора поднимается несколькими
     * плоскими "полками" вместо одного гладкого купола/плато. Контур
     * региона считается тем же способом, что у {@link #samplePlateau}
     * (круглая область + неровный край через шум), но профиль ВНУТРИ
     * контура квантуется в {@link #TERRACE_STEP_COUNT} ступеней.
     *
     * <p>Каждая ступень — это plateau в миниатюре: {@link #TERRACE_STEP_FLAT_FRAC}
     * доли ступени рельеф стоит на месте (полка), остаток — smoothstep-подъём
     * к следующей полке. Использование smoothstep (а не резкого шага) для
     * подъёма между полками — намеренное решение: мы уже один раз чинили
     * ровно такую же проблему (вертикальные стены-обрывы, см. историю правок
     * {@link #applyBeachFlat} и клэмпа высоты пика) и не хотим повторить её
     * внутри нового архетипа. Подъём между полками короткий и заметный
     * (это и даёт "ступенчатый" силуэт), но не бесконечно резкий по Y.
     */
    private RidgeSample sampleTerrace(RegionParams p, int wx, int wz, double warp) {
        // Контур — идентичный приём с PLATEAU (свой edgeSeed, чтобы контуры
        // двух архетипов не совпадали пространственно при соседстве ячеек).
        double edgeNoise = heightNoise.fbm2D(
                wx * 0.01 + p.terraceEdgeSeed, wz * 0.01 + p.terraceEdgeSeed, 3, 2.0, 0.5)
                * TERRACE_EDGE_ROUGH_AMPL;

        double dx = wx - p.terraceCx + warp;
        double dz = wz - p.terraceCz + warp;
        double dist = Math.sqrt(dx * dx + dz * dz) - edgeNoise;

        // raw01: 1.0 в центре горы, плавно уходит к 0.0 за пределами
        // terraceRadius — тот же гладкий "сырой" купол, что служит базой
        // и для plateau, ДО ступенчатого квантования.
        double raw = 1.0 - smoothstep(p.terraceRadius * 0.35, p.terraceRadius, dist);
        raw = Math.max(0.0, Math.min(1.0, raw));

        // ── Небольшая волнистость границ ступеней по азимуту ──────────────
        // Без неё все уступы — идеальные концентрические окружности вокруг
        // terraceCx/terraceCz, что для природной горы выглядит слишком
        // геометрично. Шум зависит от угла+расстояния (не только от XZ
        // напрямую), чтобы волнистость вращалась вместе с контуром, а не
        // создавала отдельный, несвязанный с горой узор.
        double wobble = heightNoise.fbm2D(wx * 0.02 + 71000, wz * 0.02 + 71000, 2, 2.0, 0.5)
                * TERRACE_STEP_WOBBLE;

        // ── Квантование в ступени ──────────────────────────────────────────
        // raw ∈ [0,1] делится на TERRACE_STEP_COUNT равных "слотов". Внутри
        // каждого слота: первые TERRACE_STEP_FLAT_FRAC доли — плоская полка
        // (высота = нижняя граница слота), остаток — smoothstep-подъём к
        // верхней границе слота (= нижняя граница следующего).
        double scaled = Math.max(0.0, Math.min(0.999999, raw + wobble)) * TERRACE_STEP_COUNT;
        int    stepIdx    = (int) Math.floor(scaled);
        double stepLocalT = scaled - stepIdx; // 0..1 внутри своей ступени

        double stepBase = stepIdx / (double) TERRACE_STEP_COUNT;
        double stepTop  = (stepIdx + 1) / (double) TERRACE_STEP_COUNT;

        double ridge;
        if (stepLocalT <= TERRACE_STEP_FLAT_FRAC) {
            // На полке — высота фиксирована на нижней границе ступени.
            ridge = stepBase;
        } else {
            // Подъём к следующей полке — smoothstep, не резкий шаг.
            double climbT = (stepLocalT - TERRACE_STEP_FLAT_FRAC) / (1.0 - TERRACE_STEP_FLAT_FRAC);
            ridge = stepBase + (stepTop - stepBase) * smoothstep(0.0, 1.0, climbT);
        }
        return new RidgeSample(ridge, false);
    }

    /**
     * Shattered-архетип: разбитые скальные гряды — хаотичная россыпь
     * угловатых обломков/останцов, каждый заметно выше обычного каньона
     * (см. {@link #CANYON_FLOOR}, который лишь прорезает рельеф у подножия,
     * а не формирует его). Структурно похож на {@link #sampleAlpine}
     * (несколько локальных центров, берём максимум по всем — "россыпь", а
     * не единый хребет), но с двумя ключевыми отличиями:
     *
     * <ol>
     *   <li>Расстояние до центра каждого обломка ИСКАЖАЕТСЯ высокочастотным
     *       шумом ДО того, как проходит через smoothstep — контур обломка
     *       получается рваным/угловатым, а не гладкой окружностью купола.</li>
     *   <li>Верх профиля среза́н в плоский "скол" (аналогично PLATEAU_TOP_FLAT,
     *       но резче и площадка меньше) — обломок читается как отбитая
     *       глыба с плоской гранью сверху, а не как пик или холм.</li>
     * </ol>
     *
     * <p>Как и у остальных архетипов, сам переход "внутри обломка / снаружи"
     * идёт через smoothstep (не резкий шаг) — угловатость здесь исключительно
     * в ФОРМЕ контура (шум искажает расстояние), а не в резкости границы по
     * высоте, поэтому подножие каждого обломка всё ещё плавно уходит в 0 и
     * не создаёт вертикальных стен.
     */
    private RidgeSample sampleShattered(RegionParams p, int wx, int wz, double warp) {
        double best = 0.0;
        for (int i = 0; i < p.shatterCx.length; i++) {
            double dx = wx - p.shatterCx[i] + warp;
            double dz = wz - p.shatterCz[i] + warp;
            double dist = Math.sqrt(dx * dx + dz * dz);

            // Угловатое искажение расстояния — высокочастотный шум, отдельный
            // сид на каждый обломок (shatterJagSeed), чтобы соседние обломки
            // не выглядели одинаково "сколотыми". Искажаем САМО расстояние
            // (а не итоговую высоту) — так угловатость видна именно в форме
            // контура, а не в поверхности как рябь.
            double jag = heightNoise.fbm2D(
                    wx * 0.035 + p.shatterJagSeed[i], wz * 0.035 + p.shatterJagSeed[i], 3, 2.0, 0.5)
                    * SHATTERED_JAGGED_AMPL;
            double jaggedDist = dist - jag;

            double radius = p.shatterRadius[i];
            double t = Math.max(0.0, 1.0 - jaggedDist / radius); // 1 в центре, 0 на радиусе
            double raw = smoothstep(0.0, 1.0, t);

            // Плоский скол сверху — та же техника, что у PLATEAU_TOP_FLAT,
            // но с меньшей площадкой (SHATTERED_TOP_FLAT ближе к 1.0) —
            // визуально резче обрубленная вершина, а не широкое плато.
            double dome;
            if (raw > SHATTERED_TOP_FLAT) {
                double topT = (raw - SHATTERED_TOP_FLAT) / (1.0 - SHATTERED_TOP_FLAT);
                dome = SHATTERED_TOP_FLAT + (1.0 - SHATTERED_TOP_FLAT) * smoothstep(0.0, 1.0, topT);
            } else {
                dome = raw;
            }

            best = Math.max(best, dome);
        }
        return new RidgeSample(Math.min(1.0, best), false);
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

    /** Итог расчёта колонки: где дно (твёрдая порода) и есть ли сверху вода. */
    public static final class ColumnProfile {
        /** Верхний твёрдый блок (дно реки/озера, если waterY != -1; иначе сама поверхность). */
        public final int groundY;
        /** Y поверхности воды, или -1 если это суша. */
        public final int waterY;

        ColumnProfile(int groundY, int waterY) {
            this.groundY = groundY;
            this.waterY  = waterY;
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
    private int applyBeachFlat(int blendedY, double weight, double gradX, double gradZ) {
        double gradPerBlock = Math.sqrt(gradX * gradX + gradZ * gradZ);
        if (weight <= 0.0 || gradPerBlock <= 1e-6) return blendedY;

        // Расстояние вглубь суши от точки, где weight пересекает "линию
        // уреза" — см. подробное объяснение в javadoc выше.
        double distanceBlocks = weight / gradPerBlock;

        if (distanceBlocks <= BEACH_EDGE_WIDTH) {
            // Первая линия — вровень с водой, касается её напрямую.
            return Math.min(blendedY, BEACH_EDGE_Y);
        }

        double ledgeEnd = BEACH_EDGE_WIDTH + BEACH_LEDGE_WIDTH;
        if (distanceBlocks <= ledgeEnd) {
            // Карниз — жёстко на 1 блок ниже первой линии, никакого
            // смешивания с исходной blendedY. Это и даёт видимое
            // понижение рельефа сразу после береговой кромки, а не просто
            // отодвинутую вглубь линию воды.
            return Math.min(blendedY, BEACH_LEDGE_Y);
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
        if (blendDist >= adaptiveBlend) return blendedY; // уже обычный рельеф

        // Плавный подъём от карниза к обычной высоте суши.
        double riseT = smoothstep(0.0, adaptiveBlend, blendDist);
        int blended = (int) Math.round(lerp(BEACH_LEDGE_Y, blendedY, riseT));
        return Math.min(blendedY, blended);
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
            blendedY = applyBeachFlat(blendedY, oceanW, oceanGradX, oceanGradZ);
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
            blendedY = applyBeachFlat(blendedY, riverW, riverGradX, riverGradZ);
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
            blendedY = applyBeachFlat(blendedY, lakeW, lakeGradX, lakeGradZ);
            if (blendedY < WATER_LEVEL) {
                return new ColumnProfile(Math.min(blendedY, WATER_LEVEL - 1), WATER_LEVEL);
            }
            landHeight = blendedY;
        }

        return new ColumnProfile(landHeight, -1);
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
                int dirtMinY = groundY - SURFACE_SKIN;

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
                for (int y = LAYER_MIN_Y + 1; y < dirtMinY; y++) {
                    pos.set(wx, y, wz);
                    BlockState rock = isWaterCol
                            ? (y < DEEPSLATE_TOP ? BS_DEEPSLATE : BS_STONE)
                            : resolveBlock(wx, y, wz, fY, cY, stgY, stcY,
                                    inColumnBase, colTSq, colBaseRv);
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
                int dirtLoopTop = isWaterCol ? groundY + 1 : groundY - 1;
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



    // ══════════════════════════════════════════════════════════════════════════
    // Определение блока для позиции (wx, y, wz)
    // ══════════════════════════════════════════════════════════════════════════

    private BlockState resolveBlock(int wx, int y, int wz,
                                    int fY, int cY, int stgTopY, int stcBotY,
                                    boolean inColumnBase, double colTSq, int colBaseR) {
        // Ниже пола → сплошная порода
        if (y <= fY) {
            return y < DEEPSLATE_TOP ? deepslateBlock(wx, y, wz) : stoneBlock(wx, y, wz);
        }
        // Выше потолка → сплошная порода
        if (y >= cY) {
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