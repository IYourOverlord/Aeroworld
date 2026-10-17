package org.example.aeroworld.worldgen.layer;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.biome.AeroBiomeSource;
import org.example.aeroworld.worldgen.cache.Layer1ColumnCache;
import org.example.aeroworld.worldgen.noise.AeroNoise;
import org.example.aeroworld.worldgen.util.ChunkWriter;

import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Layer1TerrainGenerator — кастомная генерация рельефа и поверхности для Layer 1 (-64..300).
 * Заменяет ванильный NoiseBasedChunkGenerator для рельефа, сохраняя детерминированность от seed.
 */
public class Layer1TerrainGenerator {

    public static final int SEA_LEVEL = 0;
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 300;
    public static final int BEDROCK_LAYERS = 5;

    private static final BlockState BS_BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState BS_DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BS_STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BS_WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState BS_GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BS_DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState BS_SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState BS_SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState BS_RED_SAND = Blocks.RED_SAND.defaultBlockState();
    private static final BlockState BS_TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState BS_GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState BS_SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState BS_ICE = Blocks.ICE.defaultBlockState();
    private static final BlockState BS_PODZOL = Blocks.PODZOL.defaultBlockState();
    private static final BlockState BS_CALCITE = Blocks.CALCITE.defaultBlockState();
    private static final BlockState BS_BASALT = Blocks.BASALT.defaultBlockState();
    private static final BlockState BS_BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();
    private static final BlockState BS_TUFF = Blocks.TUFF.defaultBlockState();
    private static final BlockState BS_ANDESITE = Blocks.ANDESITE.defaultBlockState();

    // Цветная терракота для elevation bands бэдлендов
    private static final BlockState BS_ORANGE_TERRACOTTA = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_YELLOW_TERRACOTTA = Blocks.YELLOW_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_BROWN_TERRACOTTA = Blocks.BROWN_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_WHITE_TERRACOTTA = Blocks.WHITE_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_LIGHT_GRAY_TERRACOTTA = Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_RED_TERRACOTTA = Blocks.RED_TERRACOTTA.defaultBlockState();

    /** Глубина замены поверхности для бэдлендов (вместо стандартных 3 блоков). */
    private static final int BADLANDS_DEPTH = 15;

    /**
     * Детерминированный паттерн цветных полос терракоты по Y (32 элемента, зацикленный).
     * Приближение к ванильному бэдлендсу без шумовой деформации.
     */
    private static final BlockState[] BADLANDS_BANDS = {
            BS_TERRACOTTA,            BS_TERRACOTTA,
            BS_ORANGE_TERRACOTTA,     BS_ORANGE_TERRACOTTA,
            BS_TERRACOTTA,            BS_YELLOW_TERRACOTTA,
            BS_YELLOW_TERRACOTTA,     BS_TERRACOTTA,
            BS_LIGHT_GRAY_TERRACOTTA, BS_TERRACOTTA,
            BS_WHITE_TERRACOTTA,      BS_WHITE_TERRACOTTA,
            BS_BROWN_TERRACOTTA,      BS_BROWN_TERRACOTTA,
            BS_TERRACOTTA,            BS_RED_TERRACOTTA,
            BS_TERRACOTTA,            BS_ORANGE_TERRACOTTA,
            BS_TERRACOTTA,            BS_TERRACOTTA,
            BS_ORANGE_TERRACOTTA,     BS_TERRACOTTA,
            BS_YELLOW_TERRACOTTA,     BS_TERRACOTTA,
            BS_LIGHT_GRAY_TERRACOTTA, BS_TERRACOTTA,
            BS_WHITE_TERRACOTTA,      BS_TERRACOTTA,
            BS_BROWN_TERRACOTTA,      BS_TERRACOTTA,
            BS_RED_TERRACOTTA,        BS_TERRACOTTA,
    };

    /**
     * Паттерн горных прожилок по Y для STONY/KARST биомов (16 элементов, зацикленный).
     */
    private static final BlockState[] STONY_BANDS = {
            BS_STONE,    BS_STONE,    BS_STONE,    BS_CALCITE,
            BS_STONE,    BS_STONE,    BS_TUFF,     BS_STONE,
            BS_CALCITE,  BS_STONE,    BS_STONE,    BS_STONE,
            BS_TUFF,     BS_STONE,    BS_STONE,    BS_STONE,
    };

    /**
     * Паттерн полос плато {@code ashen_plateau} по Y (16 элементов, зацикленный): stone / tuff / andesite / basalt.
     */
    private static final BlockState[] STRATA_BANDS = {
            BS_STONE,    BS_STONE,    BS_ANDESITE, BS_TUFF,
            BS_STONE,    BS_BASALT,   BS_STONE,    BS_ANDESITE,
            BS_ANDESITE, BS_STONE,    BS_TUFF,     BS_STONE,
            BS_BASALT,   BS_STONE,    BS_ANDESITE, BS_STONE,
    };

    /** Возвращает блок цветной терракоты по Y-координатному паттерну бэдлендов. */
    public static BlockState getBadlandsBand(int y) {
        return BADLANDS_BANDS[(y & 0x7FFF_FFFF) % BADLANDS_BANDS.length];
    }

    /** Возвращает блок горных прожилок по Y-координатному паттерну. */
    public static BlockState getStoneskyBand(int y) {
        return STONY_BANDS[(y & 0x7FFF_FFFF) % STONY_BANDS.length];
    }

    /** Возвращает блок полос плато по Y-координатному паттерну (stone / tuff / andesite / basalt). */
    public static BlockState getStrataBand(int y) {
        return STRATA_BANDS[(y & 0x7FFF_FFFF) % STRATA_BANDS.length];
    }

    private static final BlockState BS_SEAGRASS = Blocks.SEAGRASS.defaultBlockState();
    private static final BlockState BS_KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();
    private static final BlockState BS_KELP = Blocks.KELP.defaultBlockState();

    private static final BlockState[] BS_CORAL_BLOCKS = {
            Blocks.TUBE_CORAL_BLOCK.defaultBlockState(),
            Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(),
            Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState(),
            Blocks.FIRE_CORAL_BLOCK.defaultBlockState(),
            Blocks.HORN_CORAL_BLOCK.defaultBlockState()
    };

    private static final BlockState[] BS_CORALS = {
            Blocks.TUBE_CORAL.defaultBlockState(),
            Blocks.BRAIN_CORAL.defaultBlockState(),
            Blocks.BUBBLE_CORAL.defaultBlockState(),
            Blocks.FIRE_CORAL.defaultBlockState(),
            Blocks.HORN_CORAL.defaultBlockState()
    };

    private static final BlockState[] BS_CORAL_FANS = {
            Blocks.TUBE_CORAL_FAN.defaultBlockState(),
            Blocks.BRAIN_CORAL_FAN.defaultBlockState(),
            Blocks.BUBBLE_CORAL_FAN.defaultBlockState(),
            Blocks.FIRE_CORAL_FAN.defaultBlockState(),
            Blocks.HORN_CORAL_FAN.defaultBlockState()
    };

    /** Плоское дно глубокого озера (там ставится океанический монумент). */
    public static final int DEEP_LAKE_FLOOR_Y = -50;
    /** Высота, выше которой рельеф считается «горным» и сжимается в долине реки (верх обычной равнины). */
    private static final double VALLEY_BASE_Y = 8.0;
    /** Доля горного рельефа, остающаяся у самого русла (0 = ровно до VALLEY_BASE_Y). */
    private static final double VALLEY_MIN_ALLOW = 0.15;
    /** Сила глубокого озера, выше которой колонка получает биом {@code deep_lake}. */
    private static final double DEEP_LAKE_BIOME_THRESHOLD = 0.5;
    /** Глубокое озеро считается настоящим, если дно реально не выше этой отметки (на горах чаша гасится). */
    private static final int DEEP_LAKE_MIN_DEPTH = 15;

    public static final int CAVE_BOTTOM_Y = -50;
    public static final int CAVE_TOP_Y = -25;
    private static final double CAVE_MID_Y = (CAVE_TOP_Y + CAVE_BOTTOM_Y) / 2.0; // -37.5

    // ── Lush-полость внутри гор ───────────────────────────────────────────────────────────────────────────
    /** Нет полости в колонке (влезает в short, чтобы лежать в {@link Layer1ColumnCache} без пересчёта). */
    public static final int LUSH_NONE = Short.MIN_VALUE;
    /**
     * Гора: полость есть, только если поверхность самой колонки И её четырёх соседей по сторонам не ниже этой
     * отметки. Холмы и равнины (до ~35) остаются сплошными.
     */
    public static final int LUSH_MIN_SURFACE = 36;
    /** Толщина верхней корки горы: верхний блок поверхности плюс один подповерхностный. */
    public static final int LUSH_CRUST = 2;
    /** Нижняя отметка пола полости; пол лежит на {@code LUSH_FLOOR_BASE .. LUSH_FLOOR_BASE + LUSH_FLOOR_RANGE - 1}. */
    public static final int LUSH_FLOOR_BASE = 12;
    /** Число ступеней террас (шаг ступени 1 блок: по ним можно ходить). */
    public static final int LUSH_FLOOR_RANGE = 9;
    /** Уровень воды в бассейнах: пол ниже него затоплен до этой отметки включительно. */
    public static final int LUSH_WATER_Y = 15;
    /** Максимальная длина лианы. */
    private static final int LUSH_VINE_MAX = 9;

    private static final BlockState BS_MOSS_BLOCK = Blocks.MOSS_BLOCK.defaultBlockState();
    private static final BlockState BS_CLAY = Blocks.CLAY.defaultBlockState();
    private static final BlockState BS_MOSS_CARPET = Blocks.MOSS_CARPET.defaultBlockState();
    private static final BlockState BS_SHORT_GRASS = Blocks.SHORT_GRASS.defaultBlockState();
    private static final BlockState BS_TALL_GRASS_LOWER = Blocks.TALL_GRASS.defaultBlockState();
    private static final BlockState BS_TALL_GRASS_UPPER = Blocks.TALL_GRASS.defaultBlockState()
            .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
    private static final BlockState BS_AZALEA = Blocks.AZALEA.defaultBlockState();
    private static final BlockState BS_FLOWERING_AZALEA = Blocks.FLOWERING_AZALEA.defaultBlockState();
    private static final BlockState BS_LILY_PAD = Blocks.LILY_PAD.defaultBlockState();
    /** Светящийся лишайник на полу (грань DOWN: крепится к блоку под ним), свет 7. */
    private static final BlockState BS_GLOW_LICHEN_FLOOR = Blocks.GLOW_LICHEN.defaultBlockState()
            .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
    /** Тело лианы с ягодами (светятся). */
    public static final BlockState BS_VINE_PLANT_BERRIES = Blocks.CAVE_VINES_PLANT.defaultBlockState()
            .setValue(BlockStateProperties.BERRIES, true);
    private static final BlockState BS_VINE_PLANT_BARE = Blocks.CAVE_VINES_PLANT.defaultBlockState()
            .setValue(BlockStateProperties.BERRIES, false);
    /** Кончик лианы с ягодами; возраст 25 останавливает дальнейший рост. */
    private static final BlockState BS_VINE_HEAD_BERRIES = Blocks.CAVE_VINES.defaultBlockState()
            .setValue(BlockStateProperties.BERRIES, true)
            .setValue(BlockStateProperties.AGE_25, 25);

    // ── Сенот (провал с озером) в биоме jungle ───────────────────────────────────────────────────────────
    /** Размер ячейки сетки: в каждой не больше одного сенота. */
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CENOTE_CELL = 128;
    /** Отступ центра от края ячейки (больше радиуса + волны + запаса), чтобы шахта не вышла за ячейку. */
    private static final int CENOTE_MARGIN = 13;
    /** Доля ячеек джунглей, в которых есть сенот. */
    private static final double CENOTE_CHANCE = 0.5;
    private static final int CENOTE_RADIUS_MIN = 6;
    private static final int CENOTE_RADIUS_RANGE = 4;
    /** Уровень воды: на столько ниже самой низкой точки кольца вокруг шахты. */
    private static final int CENOTE_DROP = 8;
    /** Кольцо вокруг шахты: самая низкая точка не ниже CENOTE_MIN_RIM, перепад не больше CENOTE_MAX_SPREAD (холмистые джунгли). */
    private static final int CENOTE_MIN_RIM = 6;
    private static final int CENOTE_MAX_SPREAD = 14;
    /** Радиус (от радиуса шахты) каменного кольца вокруг неё, где lush-полость горы не вырезается. */
    private static final int CENOTE_PILLAR = 5;
    private static final int CENOTE_CACHE_LIMIT = 4096;
    /** Максимальная длина лианы на стене шахты - 3 (длина 3..11). */
    private static final int CENOTE_VINE_SPAN = 9;
    /** Доли колонок дна под морской огурец и морскую траву, доля воздушных блоков стены под светящийся лишайник. */
    private static final double CENOTE_PICKLE_CHANCE = 0.08;
    private static final double CENOTE_SEAGRASS_CHANCE = 0.30;
    private static final double CENOTE_LICHEN_CHANCE = 0.12;
    private static final BlockState BS_SEA_PICKLE = Blocks.SEA_PICKLE.defaultBlockState();
    private static final BlockState BS_VINE = Blocks.VINE.defaultBlockState();

    private final long seed;
    private final AeroNoise continentNoise;
    private final AeroNoise erosionNoise;
    private final AeroNoise heightNoise;
    private final AeroNoise detailNoise;
    private final AeroNoise caveCeilingNoise;
    private final AeroNoise caveFloorNoise;
    private final AeroNoise caveLushNoise;
    private final AeroNoise lushFloorNoise;
    private final AeroNoise lushVineNoise;
    private final AeroNoise cenoteWobble;
    private final AeroNoise cenoteTempNoise;
    private final AeroNoise cenoteHumidNoise;
    private final ConcurrentHashMap<Long, AeroCenote> cenotes = new ConcurrentHashMap<>();
    private final AeroNoise oceanVegNoise;
    private final AeroNoise coralNoise;
    private final AeroNoise ridgeNoise;
    private final AeroNoise riverNoise;
    private final AeroNoise plateauNoise;
    private final AeroRiverNetwork rivers;

    public Layer1TerrainGenerator(long seed) {
        this.seed = seed;
        this.continentNoise   = new AeroNoise(seed ^ 0x1A2B3C4DL);
        this.erosionNoise     = new AeroNoise(seed ^ 0x5E6F7A8BL);
        this.heightNoise      = new AeroNoise(seed ^ 0x9C0D1E2FL);
        this.detailNoise      = new AeroNoise(seed ^ 0x33445566L);
        this.caveCeilingNoise = new AeroNoise(seed ^ 0xBBCCDDEEL);
        this.caveFloorNoise   = new AeroNoise(seed ^ 0x13579BDFL);
        this.caveLushNoise    = new AeroNoise(seed ^ 0x2468ACE0L);
        this.lushFloorNoise   = new AeroNoise(seed ^ 0x6D2B79F5L);
        this.lushVineNoise    = new AeroNoise(seed ^ 0x3C6EF372L);
        this.cenoteWobble     = new AeroNoise(seed ^ 0x2F6B1A93L);
        // Реальные чанки читают источник биомов из поля ChunkGenerator (сид по умолчанию), см. BIOME_SYNC.
        long biomeSeed = AeroWorldChunkGenerator.BIOME_SYNC ? seed : AeroBiomeSource.DEFAULT_SEED;
        this.cenoteTempNoise  = new AeroNoise(biomeSeed ^ AeroBiomeSource.TEMP_SALT);
        this.cenoteHumidNoise = new AeroNoise(biomeSeed ^ AeroBiomeSource.HUMID_SALT);
        this.oceanVegNoise    = new AeroNoise(seed ^ 0x44556677L);
        this.coralNoise       = new AeroNoise(seed ^ 0x8899AABBL);
        this.ridgeNoise       = new AeroNoise(seed ^ 0x71D63A4BL);
        this.riverNoise       = new AeroNoise(seed ^ 0x4E9B17C3L);
        this.plateauNoise     = new AeroNoise(seed ^ 0x6A3F52D1L);
        this.rivers           = new AeroRiverNetwork(seed, this::getContinentality);
    }

    public long getSeed() {
        return seed;
    }

    private final ThreadLocal<Layer1ColumnCache> columnCache =
            ThreadLocal.withInitial(Layer1ColumnCache::new);

    public Layer1ColumnCache getColumnCache(int chunkX, int chunkZ) {
        Layer1ColumnCache cache = columnCache.get();
        cache.initForChunk(chunkX, chunkZ, this);
        return cache;
    }

    public int computeCaveTop(int wx, int wz) {
        double ceilingOffset = caveCeilingNoise.noise2D(wx * 0.03, wz * 0.03) * 2.0;
        return (int) Math.round(CAVE_TOP_Y + ceilingOffset);
    }

    public int computeCaveBottom(int wx, int wz) {
        double floorHills = caveFloorNoise.fbm2D(wx * 0.015, wz * 0.015, 4, 2.0, 0.5) * 14.0;
        double floorDetail = caveFloorNoise.noise2D(wx * 0.08, wz * 0.08) * 3.0;
        int bottom = (int) Math.round(CAVE_BOTTOM_Y + floorHills + floorDetail);
        return Math.min(bottom, (int) Math.floor(CAVE_MID_Y - 3));
    }

    /**
     * Сенот ячейки, содержащей колонку, или {@link AeroCenote#ABSENT}. Чистая функция seed и координат (рельеф, биом),
     * поэтому реальные чанки и LOD видят один и тот же провал.
     * <p>ponytail: кэш ячеек сбрасывается целиком при переполнении ({@link #CENOTE_CACHE_LIMIT}); апгрейд - LRU.
     */
    public AeroCenote cenoteAt(int wx, int wz) {
        int gx = Math.floorDiv(wx, CENOTE_CELL);
        int gz = Math.floorDiv(wz, CENOTE_CELL);
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        AeroCenote c = cenotes.get(key);
        if (c == null) {
            if (cenotes.size() > CENOTE_CACHE_LIMIT) cenotes.clear();
            c = computeCenote(gx, gz);
            cenotes.put(key, c);
        }
        return c;
    }

    /** Дно озера сенота в колонке или {@link AeroCenote#NONE}, если колонка вне шахты. */
    public int cenoteFloorY(int wx, int wz) {
        AeroCenote c = cenoteAt(wx, wz);
        if (c.radius() == 0) return AeroCenote.NONE;
        int dx = wx - c.cx(), dz = wz - c.cz();
        int reach = c.radius() + 2;
        if (dx * dx + dz * dz > reach * reach) return AeroCenote.NONE;
        return c.floorY(wx, wz, cenoteWobble.noise2D(wx * 0.2, wz * 0.2) * 1.2);
    }

    /**
     * Колонка в шахте сенота или в каменном кольце вокруг неё: lush-полость горы здесь не вырезается, иначе вода озера
     * граничила бы с воздухом полости. Джунгли лежат и на горах (поверхность >= LUSH_MIN_SURFACE), поэтому сенот
     * там стоит каменным столбом внутри полости.
     */
    public boolean cenoteBuffer(int wx, int wz) {
        AeroCenote c = cenoteAt(wx, wz);
        if (c.radius() == 0) return false;
        int dx = wx - c.cx(), dz = wz - c.cz();
        int reach = c.radius() + CENOTE_PILLAR;
        return dx * dx + dz * dz <= reach * reach;
    }

    private AeroCenote computeCenote(int gx, int gz) {
        long h = lushHash(gx, gz, 0x0CE07E01L);
        if (((h >>> 11) & 0xFFFF) / 65536.0 >= CENOTE_CHANCE) return AeroCenote.ABSENT;
        long h2 = lushHash(gz, gx, 0x5A17B0C3L);
        int span = CENOTE_CELL - 2 * CENOTE_MARGIN;
        int cx = gx * CENOTE_CELL + CENOTE_MARGIN + (int) (((h >>> 27) & 0xFFFF) % span);
        int cz = gz * CENOTE_CELL + CENOTE_MARGIN + (int) (((h2 >>> 27) & 0xFFFF) % span);
        int r = CENOTE_RADIUS_MIN + (int) (((h2 >>> 43) & 0xFFFF) % CENOTE_RADIUS_RANGE);
        if (!isJungle(cx, cz)) return AeroCenote.ABSENT;

        int ring = r + 3;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int y = getHeight(cx + (int) Math.round(Math.cos(a) * ring), cz + (int) Math.round(Math.sin(a) * ring));
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        if (min < CENOTE_MIN_RIM || max - min > CENOTE_MAX_SPREAD) {
            LOGGER.info("[Cenote] cell ({},{}) rejected: candidate ({},{}) r={} rimMin={} rimMax={} spread={} (need rimMin>={} spread<={})",
                    gx, gz, cx, cz, r, min, max, max - min, CENOTE_MIN_RIM, CENOTE_MAX_SPREAD);
            return AeroCenote.ABSENT;
        }
        LOGGER.info("[Cenote] cell ({},{}) accepted: centre ({},{}) r={} waterY={} rimMin={} rimMax={}",
                gx, gz, cx, cz, r, min - CENOTE_DROP, min, max);
        return new AeroCenote(cx, cz, r, min - CENOTE_DROP);
    }

    /**
     * Биом джунглей в точке по той же карте, что у реальных чанков и LOD: без {@code BIOME_SYNC} это поле
     * {@code ChunkGenerator} (сид по умолчанию, реальными из рельефа остаются только слои {@code biomeOverlay}), с
     * {@code BIOME_SYNC} источник настроен сидом мира и Layer1, поэтому берём всё реальное (overlay = null).
     * Формула общая с источником биомов ({@link AeroBiomeSource#resolveFieldBiomeAt}).
     */
    private boolean isJungle(int wx, int wz) {
        double qx = (wx >> 2) << 2;
        double qz = (wz >> 2) << 2;
        double temp = AeroBiomeSource.temperature(cenoteTempNoise, qx, qz);
        double humid = AeroBiomeSource.humidity(cenoteHumidNoise, qx, qz);
        var overlay = AeroWorldChunkGenerator.BIOME_SYNC ? null : AeroBiomeSource.OVERLAY;
        return "jungle".equals(AeroBiomeSource.resolveFieldBiomeAt(this, overlay, qx, qz, temp, humid));
    }

    /**
     * Верхний воздушный блок lush-полости, если все пять точек (колонка и четыре соседа по сторонам) не ниже
     * {@link #LUSH_MIN_SURFACE}; иначе {@link #LUSH_NONE}. Принимает минимум поверхности по этим пяти точкам.
     * Минимум по соседям гарантирует, что воздух полости никогда не касается наружного воздуха (корка не
     * прорывается на крутых склонах): воздух ниже {@code min - LUSH_CRUST - 1}, под ним слой мха.
     */
    public static int lushAirTopFromMin(int minSurface) {
        return minSurface >= LUSH_MIN_SURFACE ? minSurface - LUSH_CRUST - 1 : LUSH_NONE;
    }

    /**
     * Верх полости в колонке без кэша (LOD, getBaseColumn). Соседей считает только для колонок, уже прошедших
     * порог горы, и бросает счёт при первом же соседе ниже порога.
     */
    public int lushAirTop(int wx, int wz, int surfaceY) {
        if (surfaceY < LUSH_MIN_SURFACE || cenoteBuffer(wx, wz)) return LUSH_NONE;
        int m = surfaceY;
        m = Math.min(m, getHeight(wx - 1, wz));
        if (m < LUSH_MIN_SURFACE) return LUSH_NONE;
        m = Math.min(m, getHeight(wx + 1, wz));
        if (m < LUSH_MIN_SURFACE) return LUSH_NONE;
        m = Math.min(m, getHeight(wx, wz - 1));
        if (m < LUSH_MIN_SURFACE) return LUSH_NONE;
        m = Math.min(m, getHeight(wx, wz + 1));
        return lushAirTopFromMin(m);
    }

    /** Верхний блок пола полости (террасы шагом 1 блок на {@code LUSH_FLOOR_BASE..+LUSH_FLOOR_RANGE-1}). */
    public int lushFloorY(int wx, int wz) {
        double n = lushFloorNoise.fbm2D(wx * 0.022, wz * 0.022, 3, 2.0, 0.5);
        double t = Math.max(0.0, Math.min(0.999, 0.5 + n * 1.1));
        return LUSH_FLOOR_BASE + (int) (t * LUSH_FLOOR_RANGE);
    }

    /** Пол затоплен: сверху глина и вода до {@link #LUSH_WATER_Y}. */
    public static boolean lushIsWet(int floorY) {
        return floorY < LUSH_WATER_Y;
    }

    /**
     * Длина лианы под мхом (0 = нет лианы). Детерминирована от seed и колонки, поэтому реальные чанки и LOD
     * получают одно и то же. Лианы растут пятнами (низкочастотный шум задаёт густоту), длина 1..9, не короче
     * свободного места над водой или полом.
     */
    public int lushVineLength(int wx, int wz, int airTop, int floorY) {
        int room = airTop - Math.max(floorY, LUSH_WATER_Y) - 1;
        if (room <= 0) return 0;
        double density = 0.28 + 0.42 * Math.max(0.0, Math.min(1.0, 0.5 + lushVineNoise.noise2D(wx * 0.06, wz * 0.06)));
        long h = lushHash(wx, wz, 0x51ED270BL);
        if (((h >>> 11) & 0xFFFF) / 65536.0 >= density) return 0;
        int len = 1 + (int) (((h >>> 29) & 0xFFFF) % LUSH_VINE_MAX);
        return Math.min(len, room);
    }

    private long lushHash(int wx, int wz, long salt) {
        long h = seed ^ salt;
        h ^= wx * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
        h ^= wz * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return h;
    }

    /**
     * Проверяет, находится ли точка внутри полости гигантской пещеры (не под водой).
     */
    public boolean isCaveAir(int wx, int y, int wz, int surfaceY) {
        if (surfaceY < SEA_LEVEL) return false;
        return y >= computeCaveBottom(wx, wz) && y <= computeCaveTop(wx, wz);
    }

    /**
     * Континентальность: от -1.0 до +1.0.
     * < -0.15 = океан, -0.15..0.0 = побережье/пляж, > 0.0 = суша.
     */
    public double getContinentality(double wx, double wz) {
        return continentNoise.fbm2D(wx * 0.0008, wz * 0.0008, 4, 2.0, 0.5);
    }

    /**
     * Эрозия: от -1.0 до +1.0.
     * < -0.3 = горы/пики, -0.3..0.2 = холмы, > 0.2 = равнины/плато.
     */
    public double getErosion(double wx, double wz) {
        return erosionNoise.fbm2D(wx * 0.0015, wz * 0.0015, 3, 2.0, 0.5);
    }

    /**
     * Вычисляет высоту поверхности (твёрдого грунта) в координатах (wx, wz).
     * Стандартная суша: в диапазоне Y от 2 до 18..20.
     * Океаны: от -50 до -1.
     * Горы (низкая эрозия): могут подниматься выше Y=20 (до 60..120+).
     */
    public int getHeight(int wx, int wz) {
        return height(wx, wz, true);
    }

    /** Высота без мостов плато: дно каньона под мостом (нужно для пустоты под ним). */
    public int getHeightNoBridge(int wx, int wz) {
        return height(wx, wz, false);
    }

    private int height(int wx, int wz, boolean bridges) {
        double continentality = getContinentality(wx, wz);
        double erosion = getErosion(wx, wz);
        double macro = heightNoise.fbm2D(wx * 0.003, wz * 0.003, 5, 2.0, 0.5);
        double detail = detailNoise.noise2D(wx * 0.02, wz * 0.02);

        double deepOcean = -47.0 + macro * 3.0 + detail * 0.75;
        double shelf = -8.0 + macro * 2.5 + detail;
        double beach = 2.0 + macro * 1.5 + detail;

        double continentalRise = smoothstep(0.0, 0.70, continentality) * 11.0;
        double mountainness = smoothstep(0.0, 0.55, -erosion);

        // Ridged multifractal: each octave is folded (1 - |noise|) and squared before
        // summing, so instead of one smooth low-frequency bump we get many separate,
        // steep-sided peaks and ridgelines with real valleys between them, at a scale
        // comparable to vanilla mountains (roughly 80-160 blocks peak-to-peak).
        double ridged = ridgedMultifractal(heightNoise, wx * 0.010, wz * 0.010, 6, 2.05, 0.55);
        double ridgedSharp = ridged * ridged * ridged; // narrows summits, widens the base

        // High-frequency talus/rock texture, confined to slopes that are already
        // mountainous, so plains and hills stay smooth.
        double talus = ridgedMultifractal(detailNoise, wx * 0.045, wz * 0.045, 4, 2.1, 0.5)
                * mountainness * ridged;

        double land = 3.0 + continentalRise + mountainness * 130.0 * ridgedSharp
                + macro * 4.0 + detail * 1.5 + talus * 10.0;

        // Rare continent-scale ridge systems. The ridge field is sampled in world
        // coordinates and blended by continentality, so it cannot create chunk seams.
        double ridge = getRidgeStrength(wx, wz);
        double ridgeLandMask = smoothstep(0.04, 0.22, continentality);
        land += ridgeLandMask * ridge * (92.0 + 34.0 * ridgedSharp);

        double height = lerp(deepOcean, shelf, smoothstep(-0.42, -0.16, continentality));
        height = lerp(height, beach, smoothstep(-0.20, -0.02, continentality));
        height = lerp(height, land, smoothstep(-0.06, 0.10, continentality));

        // Долины рек: рельеф над базовой равниной ограничен расстоянием до русла (AeroRiverNetwork.Sample.valley:
        // 0 у русла, 1 за пределами долины). Горы отступают от реки, долина образуется сама. Идёт до плато и до
        // вырезания русла: ниже подножия lowland уже не гасит реку, и она доходит дальше в предгорья.
        AeroRiverNetwork.Sample net = rivers.sample(wx, wz, continentality);
        if (net.valley() < 1.0) {
            double relief = height - VALLEY_BASE_Y;
            if (relief > 0.0) {
                height = VALLEY_BASE_Y + relief * (VALLEY_MIN_ALLOW + (1.0 - VALLEY_MIN_ALLOW) * net.valley());
            }
        }

        // Плато ashen_plateau: кайма из плит (подъём + террасы шагом 6) и каньон в центре. До рек: на плато высота > 50,
        // lowland гасит реки сам. Маска берётся из getPlateauMask, той же функции, что выбирает биом.
        if (PlateauTerrace.gate(continentality, ridge) > 0.0) {
            double pn = plateauNoise.fbm2D(wx * PlateauTerrace.NOISE_FREQ, wz * PlateauTerrace.NOISE_FREQ, 3, 2.0, 0.5);
            height = PlateauTerrace.column(plateauNoise, height, wx, wz,
                    PlateauTerrace.mask(pn, continentality, ridge), bridges);
        }

        // Реки и озёра: сеть стекает к океану и озёрам (AeroRiverNetwork), русла и чаши уходят под уровень моря,
        // воду доливает обычная заливка. В горах и на высокой суше реки гасятся: они кончаются у подножия, а не
        // прорезают каньоны. Маска побережья не нужна: min() никогда не поднимает дно океана.
        double lowland = 1.0 - smoothstep(20.0, 50.0, height);
        double riverBed = SEA_LEVEL - (5.0 + riverNoise.noise2D(wx * 0.018, wz * 0.018) * 0.5);
        height = lerp(height, Math.min(height, riverBed), net.river() * lowland);
        // Глубокие озёра (доля сток-озёр, AeroRiverNetwork.DEEP_LAKE_SHARE): к обычной чаше -4..-12 добавляется
        // плавная чаша deep^1.5 с плоским дном на DEEP_LAKE_FLOOR_Y (deep = 1 внутри 0.55 радиуса озера).
        double lakeDepth = 4.0 + 8.0 * net.lake()
                + (SEA_LEVEL - DEEP_LAKE_FLOOR_Y - 12.0) * Math.pow(net.deep(), 1.5);
        double lakeBed = SEA_LEVEL - lakeDepth;
        height = lerp(height, Math.min(height, lakeBed), net.lake() * lowland);

        int finalHeight = (int) Math.round(height);
        return Math.max(MIN_Y + BEDROCK_LAYERS + 1, Math.min(220, finalHeight));
    }

    /**
     * Глубокое озеро в колонке: единая маска для биома {@code deep_lake} и монумента (иначе они разъедутся).
     * Сила чаши {@code > 0.5} и дно реально глубокое (чаша не погашена горами).
     */
    public boolean isDeepLake(int wx, int wz) {
        AeroRiverNetwork.Sample net = rivers.sample(wx, wz, getContinentality(wx, wz));
        if (net.deep() <= DEEP_LAKE_BIOME_THRESHOLD) return false;
        return getHeight(wx, wz) <= SEA_LEVEL - DEEP_LAKE_MIN_DEPTH;
    }

    /**
     * Центр глубокого озера в чанке: {@code {x, z}} или {@code null}. Центр отдаётся, только если дно там
     * действительно на {@link #DEEP_LAKE_FLOOR_Y} (плоская чаша), иначе монументу не на чем стоять.
     */
    public int[] getDeepLakeCentre(int chunkX, int chunkZ) {
        int[] c = rivers.deepLakeCentre(chunkX, chunkZ);
        if (c == null) return null;
        return getHeight(c[0], c[1]) <= DEEP_LAKE_FLOOR_Y + 1 ? c : null;
    }

    /**
     * Пустота под естественным мостом плато в колонке: {@code {floorY, topY}} (воздух от floorY + 1 до topY включительно,
     * floorY это дно каньона) или {@code null}. Дорогой {@link #getHeightNoBridge} зовётся только на пересечении
     * каньона с полосой моста.
     */
    public int[] plateauBridgeVoid(int wx, int wz, int surfaceY) {
        double bridge = PlateauTerrace.bridgeAt(plateauNoise, wx, wz);
        if (bridge < PlateauTerrace.BRIDGE_VOID_MIN
                || Math.abs(PlateauTerrace.canyonNoise(plateauNoise, wx, wz)) >= PlateauTerrace.CANYON_W1) return null;
        int floorY = getHeightNoBridge(wx, wz);
        return PlateauTerrace.hasBridgeVoid(bridge, floorY, surfaceY)
                ? new int[] {floorY, surfaceY - PlateauTerrace.DECK} : null;
    }

    /**
     * Маска плато 0..1: редкие пятна по {@code plateauNoise}, гаснет у берега и на хребтах.
     * Единственный источник маски для {@link #getHeight} и {@code AeroBiomeSource}.
     */
    public double getPlateauMask(int wx, int wz) {
        return getPlateauMask(wx, wz, getContinentality(wx, wz), getRidgeStrength(wx, wz));
    }

    /**
     * Колонка на плато по рельефу (маска + суша + вне хребтов), без учёта температуры. Реальные чанки берут биом из
     * экземпляра-поля {@code ChunkGenerator}, где Layer1 не применён и плато всегда 0, поэтому поверхность плато
     * ({@link SurfaceType#STRATA}) выбирается по той же маске, что поднимает высоту, а не по биому чанка.
     */
    public boolean isPlateau(int wx, int wz) {
        double c = getContinentality(wx, wz);
        double r = getRidgeStrength(wx, wz);
        return PlateauTerrace.isPlateauBiome(getPlateauMask(wx, wz, c, r), c, r, 0.0);
    }

    /** Поверхность плато: то же, что даёт биом {@code ashen_plateau}. */
    public static final BiomeSurfaceInfo PLATEAU_SURFACE = classifyPath("ashen_plateau");

    /** То же, что {@link #getPlateauMask(int, int)}, но с уже посчитанными континентальностью и хребтом. */
    public double getPlateauMask(int wx, int wz, double continentality, double ridge) {
        if (PlateauTerrace.gate(continentality, ridge) <= 0.0) return 0.0;
        double n = plateauNoise.fbm2D(wx * PlateauTerrace.NOISE_FREQ, wz * PlateauTerrace.NOISE_FREQ, 3, 2.0, 0.5);
        return PlateauTerrace.mask(n, continentality, ridge);
    }

    /** Returns the continuous ridge contribution used by terrain and biome selection. */
    public double getRidgeStrength(int wx, int wz) {
        double field = 1.0 - Math.abs(ridgeNoise.fbm2D(wx * 0.00125, wz * 0.00125, 4, 2.0, 0.5));
        return smoothstep(0.72, 0.90, field);
    }

    /**
     * Ridged multifractal noise: each octave is folded to a crease (1 - |noise|)
     * and squared before accumulating, producing many separate sharp ridges/peaks
     * with real valleys between them instead of a single smooth low-frequency bump.
     * Returns a value in 0..1.
     */
    private static double ridgedMultifractal(AeroNoise noise, double x, double z, int octaves, double lacunarity, double gain) {
        double sum = 0.0, amplitude = 0.5, frequency = 1.0, max = 0.0;
        for (int i = 0; i < octaves; i++) {
            double n = 1.0 - Math.abs(noise.noise2D(x * frequency, z * frequency));
            n *= n;
            sum += n * amplitude;
            max += amplitude;
            frequency *= lacunarity;
            amplitude *= gain;
        }
        return sum / max;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    /**
     * Верхняя граница (включая воду) для WORLD_SURFACE_WG.
     */
    public int getTopmostHeight(int wx, int wz) {
        int solid = getHeight(wx, wz);
        return Math.max(solid, SEA_LEVEL);
    }

    /**
     * Заполняет твёрдую толщу Layer 1 камнем/глубинным сланцем, бедроком и водой до sea_level.
     */
    public void fillTerrain(ChunkWriter writer, int chunkX, int chunkZ) {
        Layer1ColumnCache cache = getColumnCache(chunkX, chunkZ);
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                int surfaceY = cache.surfaceY[idx];
                boolean hasCave = surfaceY >= SEA_LEVEL;
                int caveTop = cache.caveTop[idx];
                int caveBottom = cache.caveBottom[idx];
                int lushTop = cache.lushAirTop[idx];
                int lushFloor = cache.lushFloor[idx];
                int cenoteFloor = cache.cenoteFloor[idx];

                // Бедрок на дне (0..4 слоя от MIN_Y)
                for (int y = MIN_Y; y < MIN_Y + BEDROCK_LAYERS; y++) {
                    if (y == MIN_Y) {
                        writer.setBlockState(wx, y, wz, BS_BEDROCK);
                    } else {
                        // Плавный переход бедрока
                        double r = detailNoise.noise2D(wx * 0.5, wz * 0.5 + y * 13.0);
                        writer.setBlockState(wx, y, wz, (r > 0.0) ? BS_BEDROCK : BS_DEEPSLATE);
                    }
                }

                // Каменная толща: deepslate ниже Y=0, stone выше Y=0
                for (int y = MIN_Y + BEDROCK_LAYERS; y <= surfaceY; y++) {
                    // Проверяем полость гигантской пещеры:
                    // если это суша и точка попадает в пещеру, то блок не ставим (оставляем воздух)
                    if (hasCave && y >= caveBottom && y <= caveTop) {
                        continue;
                    }
                    // Lush-полость в горе: воздух от пола до верхнего воздушного блока (мох/вода/декор ставит decorateLushCavity)
                    if (lushTop != LUSH_NONE && y > lushFloor && y <= lushTop) {
                        continue;
                    }

                    // Сенот: шахта до дна озера (воду, дно и лианы ставит decorateCenote)
                    if (cenoteFloor != AeroCenote.NONE && y > cenoteFloor) {
                        continue;
                    }

                    if (y < -8) {
                        writer.setBlockState(wx, y, wz, BS_DEEPSLATE);
                    } else if (y <= 0) {
                        double r = detailNoise.noise2D(wx * 0.1, wz * 0.1 + y * 0.2);
                        writer.setBlockState(wx, y, wz, (r > 0.0) ? BS_DEEPSLATE : BS_STONE);
                    } else {
                        writer.setBlockState(wx, y, wz, BS_STONE);
                    }
                }

                // Вода до SEA_LEVEL
                if (surfaceY < SEA_LEVEL) {
                    for (int y = surfaceY + 1; y <= SEA_LEVEL; y++) {
                        writer.setBlockState(wx, y, wz, BS_WATER);
                    }
                }
            }
        }

        decorateCaveCeiling(writer, chunkX, chunkZ, cache);
        decorateCaveFloor(writer, chunkX, chunkZ, cache);
        decorateLushCavity(writer, chunkX, chunkZ, cache);
        decorateCenote(writer, chunkX, chunkZ, cache);
    }

    /**
     * Оформляет сенот: глина на дне, вода до {@code waterY}, на стенах шахты лианы (vine) и светящийся лишайник,
     * на дне морская трава и морские огурцы. Дно и вода совпадают с LOD-моделью {@code AeroColumnModel}; растения и
     * лишайник есть только в реальных чанках.
     */
    private void decorateCenote(ChunkWriter writer, int chunkX, int chunkZ, Layer1ColumnCache cache) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        int cenoteColumns = 0, vineColumns = 0;

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = (lx << 4) | lz;
                int floorY = cache.cenoteFloor[idx];
                if (floorY == AeroCenote.NONE) continue;
                cenoteColumns++;
                int waterY = cache.cenoteWater[idx];

                writer.setBlockState(wx, floorY, wz, BS_CLAY);
                for (int y = floorY + 1; y <= waterY; y++) {
                    writer.setBlockState(wx, y, wz, BS_WATER);
                }

                // Дно: морская трава и морские огурцы на глине (глина всегда под водой: waterY >= floorY + 2).
                double fr = ((lushHash(wx, wz, 0x5EA64A55L) >>> 11) & 0xFFFF) / 65536.0;
                if (fr < CENOTE_PICKLE_CHANCE) {
                    writer.setBlockState(wx, floorY + 1, wz, BS_SEA_PICKLE.setValue(SeaPickleBlock.PICKLES,
                            1 + (int) (((lushHash(wx, wz, 0x71C41E5L) >>> 29) & 0xFFFF) % 4)));
                } else if (fr < CENOTE_PICKLE_CHANCE + CENOTE_SEAGRASS_CHANCE) {
                    writer.setBlockState(wx, floorY + 1, wz, BS_SEAGRASS);
                }

                // Стены: соседние колонки вне шахты. Лиана и лишайник цепляются к стороне соседнего твёрдого блока.
                boolean[] wall = new boolean[4];
                int[] wallTop = new int[4];
                int top = Integer.MIN_VALUE;
                for (int i = 0; i < 4; i++) {
                    int nx = wx + dirs[i].getStepX(), nz = wz + dirs[i].getStepZ();
                    if (cenoteFloorY(nx, nz) != AeroCenote.NONE) continue;
                    int lnx = lx + dirs[i].getStepX(), lnz = lz + dirs[i].getStepZ();
                    wall[i] = true;
                    wallTop[i] = (lnx >= 0 && lnx < 16 && lnz >= 0 && lnz < 16) ? cache.surfaceY[(lnx << 4) | lnz] : getHeight(nx, nz);
                    top = Math.max(top, wallTop[i]);
                }
                if (top == Integer.MIN_VALUE) continue;

                long vh = lushHash(wx, wz, 0x7E11C0DEL);
                int vineBottom = top + 1; // выше этого Y лианы нет
                if (((vh >>> 11) & 0xFFFF) / 65536.0 < 0.8) {
                    vineColumns++;
                    int len = 3 + (int) (((vh >>> 29) & 0xFFFF) % CENOTE_VINE_SPAN);
                    vineBottom = Math.max(top - len + 1, waterY + 1);
                    for (int y = top; y >= vineBottom; y--) {
                        BlockState vine = BS_VINE;
                        for (int i = 0; i < 4; i++) {
                            if (wall[i] && y <= wallTop[i]) vine = vine.setValue(vineFace(dirs[i]), true);
                        }
                        writer.setBlockState(wx, y, wz, vine);
                    }
                }
                // Светящийся лишайник в оставшихся воздушных блоках стены (над водой, не поверх лиан).
                for (int y = Math.min(top, vineBottom - 1); y > waterY; y--) {
                    double lr = ((lushHash(wx, wz * 1024 + y, 0x61077E11L) >>> 11) & 0xFFFF) / 65536.0;
                    if (lr >= CENOTE_LICHEN_CHANCE) continue;
                    BlockState lichen = Blocks.GLOW_LICHEN.defaultBlockState();
                    for (int i = 0; i < 4; i++) {
                        if (wall[i] && y <= wallTop[i]) lichen = lichen.setValue(MultifaceBlock.getFaceProperty(dirs[i]), true);
                    }
                    writer.setBlockState(wx, y, wz, lichen);
                }
            }
        }
        if (cenoteColumns > 0) {
            AeroCenote c = cenoteAt(startX, startZ);
            LOGGER.info("[Cenote] chunk ({},{}) decorated: {} shaft columns, {} vine columns (cell centre ({},{}) r={} waterY={})",
                    chunkX, chunkZ, cenoteColumns, vineColumns, c.cx(), c.cz(), c.radius(), c.waterY());
        }
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty vineFace(Direction d) {
        return switch (d) {
            case NORTH -> VineBlock.NORTH;
            case EAST -> VineBlock.EAST;
            case SOUTH -> VineBlock.SOUTH;
            default -> VineBlock.WEST;
        };
    }

    /**
     * Оформляет lush-полость внутри гор (прототип Lush Caves): мох на своде с лианами со светящимися ягодами,
     * пол террасами (мох на сухих, глина и вода в бассейнах), на сухих террасах трава, мховый ковёр, азалии и светящийся лишайник (вместо пустого воздуха),
     * на воде кувшинки. Геометрия (потолок, пол, вода, длина лиан) берётся из тех же функций, что у LOD-модели
     * {@code AeroColumnModel}; трава, ковёр, азалии и кувшинки есть только в реальных чанках (в LOD их нет).
     */
    private void decorateLushCavity(ChunkWriter writer, int chunkX, int chunkZ, Layer1ColumnCache cache) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                int airTop = cache.lushAirTop[idx];
                if (airTop == LUSH_NONE) continue;
                int floorY = cache.lushFloor[idx];

                // Свод: сплошной слой мха сразу под коркой.
                writer.setBlockState(wx, airTop + 1, wz, BS_MOSS_BLOCK);

                // Пол и вода.
                boolean wet = lushIsWet(floorY);
                if (wet) {
                    writer.setBlockState(wx, floorY, wz, BS_CLAY);
                    writer.setBlockState(wx, floorY - 1, wz, BS_CLAY);
                    for (int y = floorY + 1; y <= LUSH_WATER_Y; y++) {
                        writer.setBlockState(wx, y, wz, BS_WATER);
                    }
                } else {
                    writer.setBlockState(wx, floorY, wz, BS_MOSS_BLOCK);
                }

                // Лианы: тело с ягодами по жребию, кончик всегда с ягодами.
                int vineLen = lushVineLength(wx, wz, airTop, floorY);
                if (vineLen > 0) {
                    long vh = lushHash(wx, wz, 0x7F4A7C15L);
                    for (int i = 0; i < vineLen - 1; i++) {
                        boolean berry = ((vh >>> i) & 3L) == 0L; // ~25% сегментов тела
                        writer.setBlockState(wx, airTop - i, wz, berry ? BS_VINE_PLANT_BERRIES : BS_VINE_PLANT_BARE);
                    }
                    writer.setBlockState(wx, airTop - (vineLen - 1), wz, BS_VINE_HEAD_BERRIES);
                }

                // Растительность на полу (только реальные чанки).
                double roll = (lushHash(wx, wz, 0x2545F491L) >>> 16 & 0xFFFF) / 65536.0;
                if (wet) {
                    if (roll < 0.05) writer.setBlockState(wx, LUSH_WATER_Y + 1, wz, BS_LILY_PAD);
                } else if (airTop - floorY >= 3) {
                    int py = floorY + 1;
                    if (roll < 0.18) {
                        writer.setBlockState(wx, py, wz, BS_SHORT_GRASS);
                    } else if (roll < 0.26) {
                        writer.setBlockState(wx, py, wz, BS_TALL_GRASS_LOWER);
                        writer.setBlockState(wx, py + 1, wz, BS_TALL_GRASS_UPPER);
                    } else if (roll < 0.36) {
                        writer.setBlockState(wx, py, wz, BS_MOSS_CARPET);
                    } else if (roll < 0.385) {
                        writer.setBlockState(wx, py, wz, BS_AZALEA);
                    } else if (roll < 0.40) {
                        writer.setBlockState(wx, py, wz, BS_FLOWERING_AZALEA);
                    } else if (roll < 0.75) {
                        writer.setBlockState(wx, py, wz, BS_GLOW_LICHEN_FLOOR);
                    }
                }
            }
        }
    }

    /**
     * Декорирует свод гигантской пещеры под стилистику "пышных пещер":
     * мох на потолочном камне, светящийся лишайник и свисающие пещерные лианы
     * (с шансом светящихся ягод на конце) в полости под потолком.
     */
    private void decorateCaveCeiling(ChunkWriter writer, int chunkX, int chunkZ, Layer1ColumnCache cache) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        BlockState mossBlock   = Blocks.MOSS_BLOCK.defaultBlockState();
        BlockState glowLichen  = Blocks.GLOW_LICHEN.defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        BlockState vineBerries = Blocks.CAVE_VINES.defaultBlockState()
                .setValue(BlockStateProperties.BERRIES, true);
        BlockState vineNoBerry = Blocks.CAVE_VINES.defaultBlockState()
                .setValue(BlockStateProperties.BERRIES, false);

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                int surfaceY = cache.surfaceY[idx];
                if (surfaceY < SEA_LEVEL) continue; // пещера только на суше

                int top = cache.caveTop[idx];
                int bottom = cache.caveBottom[idx];
                if (top < bottom) continue;

                int ceilingBlockY = top + 1;
                if (ceilingBlockY > surfaceY) continue;

                double mossNoise = caveLushNoise.fbm2D(wx * 0.05, wz * 0.05, 2, 2.0, 0.5);
                if (mossNoise <= 0.05) continue; // пятнами, не сплошным ковром

                writer.setBlockState(wx, ceilingBlockY, wz, mossBlock);

                double lichenRoll = caveLushNoise.noise2D(wx * 0.11 + 100.0, wz * 0.11);
                if (lichenRoll > -0.2) {
                    writer.setBlockState(wx, top, wz, glowLichen);
                }

                double vineRoll = caveLushNoise.noise2D(wx * 0.17 + 500.0, wz * 0.17 + 500.0);
                if (vineRoll > 0.55) {
                    int vineLen = 1 + ((int) (Math.abs(vineRoll) * 100.0) % 4); // 1..4 сегмента
                    for (int i = 0; i < vineLen; i++) {
                        int vy = top - 1 - i;
                        if (vy < bottom) break;
                        boolean isLast = i == vineLen - 1;
                        writer.setBlockState(wx, vy, wz, isLast ? vineBerries : vineNoBerry);
                    }
                }
            }
        }
    }

    /**
     * Декорирует пол гигантской пещеры светящимся лишайником (грань UP) поверх блока пола.
     */
    private void decorateCaveFloor(ChunkWriter writer, int chunkX, int chunkZ, Layer1ColumnCache cache) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        BlockState glowLichenUp = Blocks.GLOW_LICHEN.defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                int surfaceY = cache.surfaceY[idx];
                if (surfaceY < SEA_LEVEL) continue; // пещера только на суше

                int bottom = cache.caveBottom[idx];
                int top = cache.caveTop[idx];
                if (bottom > top) continue;
                if (bottom - 1 < MIN_Y + BEDROCK_LAYERS) continue;

                double lichenRoll = caveLushNoise.noise2D(wx * 0.11 + 900.0, wz * 0.11 + 900.0);
                if (lichenRoll > -0.2) {
                    writer.setBlockState(wx, bottom, wz, glowLichenUp);
                }
            }
        }
    }

    /** Верхний блок поверхности и подповерхностный слой (3 блока под ним). */
    public record SurfaceBlocks(BlockState top, BlockState under) {}

    /**
     * Единая логика выбора материала поверхности колонки Layer 1. Используется и реальной
     * генерацией ({@link #buildSurface}), и LOD-моделью {@code AeroColumnModel}.
     * Для бэдлендов {@code under} берётся из elevation-band паттерна по {@code surfaceY}.
     */
    public static SurfaceBlocks surfaceBlocks(BiomeSurfaceInfo info, int surfaceY) {
        BlockState topBlock = BS_GRASS;
        BlockState underBlock = BS_DIRT;

        if (surfaceY < SEA_LEVEL) {
            if (surfaceY <= SEA_LEVEL - 8) {
                topBlock = BS_GRAVEL;
                underBlock = BS_GRAVEL;
            } else {
                topBlock = BS_SAND;
                underBlock = BS_SANDSTONE;
            }
        } else if (info.type() != SurfaceType.DEFAULT) {
            if (info.type() == SurfaceType.COLD) {
                if (surfaceY >= 60) {
                    topBlock = BS_SNOW_BLOCK;
                    underBlock = BS_STONE;
                }
            } else if (info.type() == SurfaceType.BADLANDS) {
                topBlock = BS_RED_SAND;
                underBlock = getBadlandsBand(surfaceY - 1);
            } else if (info.type() == SurfaceType.STONY || info.type() == SurfaceType.KARST) {
                topBlock = info.type().top;
                underBlock = getStoneskyBand(surfaceY - 1);
            } else if (info.type() == SurfaceType.STRATA) {
                // Без снега на Y>=60: верх андезит, под ним полосы по Y.
                topBlock = info.type().top;
                underBlock = getStrataBand(surfaceY - 1);
            } else {
                topBlock = info.type().top;
                underBlock = info.type().under;
            }
        } else if (surfaceY >= 60) {
            topBlock = BS_SNOW_BLOCK;
            underBlock = BS_STONE;
        }
        return new SurfaceBlocks(topBlock, underBlock);
    }

    public enum SurfaceType {
        DEFAULT(BS_GRASS, BS_DIRT),
        DESERT(BS_SAND, BS_SANDSTONE),
        BADLANDS(BS_RED_SAND, BS_TERRACOTTA),
        BEACH(BS_SAND, BS_SANDSTONE),
        STONY(BS_STONE, BS_STONE),
        COLD(null, null),
        VOLCANIC(BS_BASALT, BS_BLACKSTONE),
        KARST(BS_CALCITE, BS_STONE),
        PODZOL(BS_PODZOL, BS_DIRT),
        STRATA(BS_ANDESITE, BS_STONE);

        final BlockState top;
        final BlockState under;

        SurfaceType(BlockState top, BlockState under) {
            this.top = top;
            this.under = under;
        }
    }

    public record BiomeSurfaceInfo(SurfaceType type, boolean isCold, boolean isWarmOcean, boolean isFrozenOcean) {}

    private final ConcurrentHashMap<Holder<Biome>, BiomeSurfaceInfo> surfaceInfoCache =
            new ConcurrentHashMap<>(64);

    private BiomeSurfaceInfo getSurfaceInfo(Holder<Biome> holder) {
        return surfaceInfoCache.computeIfAbsent(holder, this::classifyBiome);
    }

    private BiomeSurfaceInfo classifyBiome(Holder<Biome> holder) {
        String path = holder.unwrapKey().map(k -> k.location().getPath()).orElse("plains");
        return classifyPath(path);
    }

    private static final ConcurrentHashMap<String, BiomeSurfaceInfo> PATH_INFO_CACHE =
            new ConcurrentHashMap<>(64);

    /** Классификация поверхности по имени биома ("aeroworld:desert" или "desert") — для LOD-генератора. */
    public static BiomeSurfaceInfo surfaceInfoForBiomeName(String biomeName) {
        if (biomeName == null) {
            return PATH_INFO_CACHE.computeIfAbsent("plains", Layer1TerrainGenerator::classifyPath);
        }
        int colon = biomeName.indexOf(':');
        String path = colon >= 0 ? biomeName.substring(colon + 1) : biomeName;
        return PATH_INFO_CACHE.computeIfAbsent(path, Layer1TerrainGenerator::classifyPath);
    }

    private static BiomeSurfaceInfo classifyPath(String path) {
        boolean warm = path.contains("warm");
        boolean frozen = path.contains("frozen");
        boolean isCold = path.contains("snowy") || frozen;

        SurfaceType type;
        if (path.contains("desert")) {
            type = SurfaceType.DESERT;
        } else if (path.contains("badlands")) {
            type = SurfaceType.BADLANDS;
        } else if (path.contains("beach")) {
            type = SurfaceType.BEACH;
        } else if (path.contains("stony")) {
            type = SurfaceType.STONY;
        } else if (isCold) {
            type = SurfaceType.COLD;
        } else if (path.contains("ashen_plateau")) {
            type = SurfaceType.STRATA;
        } else if (path.contains("volcanic")) {
            type = SurfaceType.VOLCANIC;
        } else if (path.contains("karst")) {
            type = SurfaceType.KARST;
        } else if (path.contains("heather") || path.contains("old_growth") || path.contains("taiga")) {
            type = SurfaceType.PODZOL;
        } else {
            type = SurfaceType.DEFAULT;
        }

        return new BiomeSurfaceInfo(type, isCold, warm, frozen);
    }

    /**
     * Возвращает высоту kelp-стебля для LOD-столбца (0 = нет kelp, >0 = высота от дна).
     * Воспроизводит логику {@link #placeUnderwaterFeatures} детерминированно.
     * Seagrass возвращается как высота 1 при отсутствии kelp.
     */
    public int sampleKelpHeight(int wx, int wz, int surfaceY, boolean isFrozenOcean) {
        int waterDepth = SEA_LEVEL - surfaceY;
        if (waterDepth < 2) return 0;

        double veg = oceanVegNoise.fbm2D(wx * 0.03, wz * 0.03, 3, 2.0, 0.5);
        if (veg > 0.35 && waterDepth >= 6 && !isFrozenOcean) {
            int kelpHeight = 3 + (int) (detailNoise.noise2D(wx * 0.1, wz * 0.1) * 8.0);
            return Math.max(2, Math.min(kelpHeight, waterDepth - 2));
        }
        if (veg > 0.10 && surfaceY + 1 < SEA_LEVEL) {
            return 1; // seagrass
        }
        return 0;
    }

    /**
     * Накладывает слой поверхности в зависимости от биома (песок, трава, снег, терракота и т.д.).
     */
    public void buildSurface(ChunkAccess chunk, BiFunction<Integer, Integer, Holder<Biome>> biomeGetter) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        Layer1ColumnCache cache = getColumnCache(chunkX, chunkZ);

        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                // Сенот: дно и стены уже оформлены в fillTerrain, поверхностный слой здесь не нужен
                if (cache.cenoteFloor[idx] != AeroCenote.NONE) continue;

                int surfaceY = cache.surfaceY[idx];
                Holder<Biome> biomeHolder = biomeGetter.apply(wx, wz);
                boolean plateau = isPlateau(wx, wz);
                BiomeSurfaceInfo info = plateau ? PLATEAU_SURFACE : getSurfaceInfo(biomeHolder);

                boolean underWater = surfaceY < SEA_LEVEL;

                SurfaceBlocks surfaceBlocks = surfaceBlocks(info, surfaceY);
                BlockState topBlock = surfaceBlocks.top();

                // Глубина замены зависит от типа поверхности
                boolean hasBands = info.type() == SurfaceType.BADLANDS;
                boolean hasStoneskyBands = info.type() == SurfaceType.STONY || info.type() == SurfaceType.KARST;
                boolean hasStrataBands = info.type() == SurfaceType.STRATA;
                int depth = hasBands ? BADLANDS_DEPTH : ((hasStoneskyBands || hasStrataBands) ? 8 : 3);
                // Гора с полостью: корка только LUSH_CRUST блоков (верх + один под ним), глубже слои не пишем,
                // иначе они забьют мох и воздух полости.
                if (cache.lushAirTop[idx] != LUSH_NONE) depth = Math.min(depth, LUSH_CRUST - 1);

                pos.set(wx, surfaceY, wz);
                chunk.setBlockState(pos, topBlock, false);

                for (int d = 1; d <= depth; d++) {
                    int y = surfaceY - d;
                    if (y > MIN_Y + BEDROCK_LAYERS) {
                        pos.set(wx, y, wz);
                        BlockState bandBlock = hasBands ? getBadlandsBand(y)
                                : hasStoneskyBands ? getStoneskyBand(y)
                                : hasStrataBands ? getStrataBand(y)
                                : surfaceBlocks.under();
                        chunk.setBlockState(pos, bandBlock, false);
                    }
                }

                // Мост плато: плита остаётся сверху, под ней вырезается канал (после слоёв, иначе они забьют пустоту).
                if (plateau) {
                    int[] bridgeVoid = plateauBridgeVoid(wx, wz, surfaceY);
                    if (bridgeVoid != null) {
                        for (int y = bridgeVoid[0] + 1; y <= bridgeVoid[1]; y++) {
                            pos.set(wx, y, wz);
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                        }
                        pos.set(wx, bridgeVoid[0], wz);
                        chunk.setBlockState(pos, surfaceBlocks(info, bridgeVoid[0]).top(), false);
                    }
                }

                // Лёд на поверхности воды для frozen-биомов
                if (underWater && info.isFrozenOcean()) {
                    pos.set(wx, SEA_LEVEL, wz);
                    chunk.setBlockState(pos, BS_ICE, false);
                }

                // Подводная растительность и коралловые рифы
                if (underWater && surfaceY < SEA_LEVEL - 1) {
                    placeUnderwaterFeatures(chunk, wx, surfaceY, wz, info.isWarmOcean, info.isFrozenOcean);
                }
            }
        }
    }

    private void placeUnderwaterFeatures(ChunkAccess chunk, int wx, int surfaceY, int wz, boolean isWarmOcean, boolean isFrozenOcean) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int waterDepth = SEA_LEVEL - surfaceY;

        // 1. Коралловые рифы (в теплых океанах, warm_ocean или lukwarm_ocean, на глубине от 4 до 35 блоков)
        if (isWarmOcean && waterDepth >= 4) {
            double cNoise = coralNoise.fbm2D(wx * 0.05, wz * 0.05, 3, 2.0, 0.5);
            if (cNoise > 0.40) {
                int coralTypeIdx = Math.abs((int) (coralNoise.noise2D(wx * 0.2, wz * 0.2) * 10)) % BS_CORAL_BLOCKS.length;
                int reefHeight = 1 + (int) ((cNoise - 0.40) * 10.0);
                reefHeight = Math.min(reefHeight, waterDepth - 2);

                for (int h = 1; h <= reefHeight; h++) {
                    pos.set(wx, surfaceY + h, wz);
                    chunk.setBlockState(pos, BS_CORAL_BLOCKS[coralTypeIdx], false);
                }

                int topY = surfaceY + reefHeight + 1;
                if (topY < SEA_LEVEL) {
                    pos.set(wx, topY, wz);
                    BlockState decor = (cNoise > 0.55) ? BS_CORALS[coralTypeIdx] : BS_CORAL_FANS[coralTypeIdx];
                    chunk.setBlockState(pos, decor, false);
                }
                return;
            }
        }

        // 2. Ламинарии (Kelp) — растут высокими стеблями на глубинах от 6 блоков
        double veg = oceanVegNoise.fbm2D(wx * 0.03, wz * 0.03, 3, 2.0, 0.5);
        if (veg > 0.35 && waterDepth >= 6 && !isFrozenOcean) {
            int kelpHeight = 3 + (int) (detailNoise.noise2D(wx * 0.1, wz * 0.1) * 8.0);
            kelpHeight = Math.max(2, Math.min(kelpHeight, waterDepth - 2));

            for (int k = 1; k < kelpHeight; k++) {
                pos.set(wx, surfaceY + k, wz);
                chunk.setBlockState(pos, BS_KELP_PLANT, false);
            }
            pos.set(wx, surfaceY + kelpHeight, wz);
            chunk.setBlockState(pos, BS_KELP, false);
            return;
        }

        // 3. Морская трава / водоросли (Seagrass) — на умеренных и мелких глубинах
        if (veg > 0.10 && surfaceY + 1 < SEA_LEVEL) {
            pos.set(wx, surfaceY + 1, wz);
            chunk.setBlockState(pos, BS_SEAGRASS, false);
        }
    }
}