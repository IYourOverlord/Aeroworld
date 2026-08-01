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
    // 48 + 180 = 228 в самых высоких точках, плюс запас неба до LAYER_MAX_Y.
    private static final int MAX_EXTRA_HEIGHT = 180;
    // Не даём пикам подходить ближе чем на 30 блоков к потолку слоя —
    // чтобы оставался запас неба над самой высокой горой.
    private static final int PEAK_SKY_BUFFER  = 30;
    // Амплитуда лёгкой холмистости на равнинах (там, где mountainMask≈0) —
    // равнины не идеально плоские, но и не похожи на горы.
    private static final int FLATLAND_BUMP    = 6;

    // ── Реки / озёра ──────────────────────────────────────────────────────────
    // Фиксированный уровень воды — чуть ниже базовой равнины (48), чтобы вода
    // естественно скапливалась в низинах, а не резала склоны гор.
    private static final int    WATER_LEVEL       = BASE_SURFACE_Y - 4; // 44
    private static final double RIVER_HALF_WIDTH  = 0.045; // ширина полосы |noise|<X — река
    private static final double LAKE_THRESHOLD    = 0.55;  // порог по шуму — озеро
    private static final int    RIVER_BED_DEPTH   = 3;
    private static final int    LAKE_BED_DEPTH    = 6;
    // Реки/озёра карвятся только в низинах — там, где горная маска почти не
    // подняла рельеф. Не резать русло сквозь склон горы.
    private static final int    WATER_MAX_LAND_Y  = BASE_SURFACE_Y + 12;
    // Ширина плавного перехода (в единицах шума) между сушей и водой —
    // формирует пологий пляж/склон дна вместо резкого вертикального среза.
    private static final double SHORE_BLEND       = 0.05;

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
        // Очень широкий масштаб (период ~4500 блоков) — горные хребты как
        // отдельные "острова" на карте, а не примесь повсюду.
        double maskRaw = heightNoise.fbm2D(wx * 0.00045, wz * 0.00045, 4, 2.0, 0.5);
        // smoothstep(0.20, 0.62): ниже 0.20 → 0 (чистые равнины),
        // выше 0.62 → 1 (полноценный хребет), плавный переход между ними.
        double mountainMask = smoothstep(0.20, 0.62, maskRaw);

        // ── Форма хребта (используется только внутри горных регионов) ───────
        double ridgeRaw = heightNoise.fbm2D(wx * 0.006 + 9000, wz * 0.006 + 9000, 4, 2.0, 0.5);
        double ridge     = Math.pow(Math.max(0.0, 1.0 - Math.abs(ridgeRaw)), 2.0);

        // ── Мелкая холмистость — везде, но с малой амплитудой ────────────────
        double hillsRaw = heightNoise.fbm2D(wx * 0.02 + 3000, wz * 0.02 + 3000, 3, 2.0, 0.5);
        double hills01  = Math.max(0.0, hillsRaw); // 0..1

        // Горная часть: полный размах MAX_EXTRA_HEIGHT, включена только
        // пропорционально mountainMask.
        int mountainExtra = (int) Math.round(mountainMask * ridge * MAX_EXTRA_HEIGHT);

        // Равнинная холмистость: скромные ±FLATLAND_BUMP блоков, гасится
        // внутри горных регионов (там рельеф и так задран хребтом).
        int flatExtra = (int) Math.round(hills01 * FLATLAND_BUMP * (1.0 - mountainMask));

        int h = BASE_SURFACE_Y + Math.max(0, mountainExtra) + flatExtra;

        // Запас неба над самым высоким пиком
        return Math.min(h, LAYER_MAX_Y - PEAK_SKY_BUFFER);
    }

    /** Классический smoothstep: 0 ниже edge0, 1 выше edge1, плавный переход между ними. */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
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
        if (landHeight > WATER_MAX_LAND_Y) {
            return new ColumnProfile(landHeight, -1); // горы/холмы — суша всегда
        }

        // ── Океан (проверяется первым — самый широкий водоём) ────────────────
        double oceanN = heightNoise.fbm2D(wx * 0.0009 + 90000, wz * 0.0009 + 90000, 5, 2.0, 0.5);
        // 0 на суше, 1 в открытом океане — плавный переход шириной SHORE_BLEND
        // формирует пологий пляж/склон дна вместо резкого среза.
        double oceanW = smoothstep(OCEAN_THRESHOLD + SHORE_BLEND, OCEAN_THRESHOLD - SHORE_BLEND, oceanN);
        if (oceanW > 0.0) {
            double depth01 = smoothstep(OCEAN_THRESHOLD, OCEAN_DEEP_AT, oceanN);
            int bedDepth = OCEAN_MIN_DEPTH
                    + (int) Math.round(depth01 * (OCEAN_MAX_DEPTH - OCEAN_MIN_DEPTH));
            int oceanFloorY = WATER_LEVEL - bedDepth;
            int blendedY = (int) Math.round(landHeight + (oceanFloorY - landHeight) * oceanW);
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