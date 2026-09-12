package org.example.aeroworld.worldgen.layer;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.cache.BodyType;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandCache;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.noise.AeroNoise;
import org.example.aeroworld.worldgen.noise.IslandPlacer;
import org.example.aeroworld.worldgen.util.ChunkWriter;
import org.example.aeroworld.worldgen.util.ChunkAccessWriter;
import org.example.aeroworld.config.Layer3Settings;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Layer 3 — High Sky Islands (Y 1000 to Y 1100).
 *
 * Два типа небесных тел (50/50, детерминированно по центру острова):
 * <ul>
 *   <li>{@link BodyType#METEORITE} — полый эллипсоид со стенкой толщиной
 *       {@code wallThickness}. Нижняя полусфера монолитная (несквозные
 *       кратеры, дно ≥ {@code craterMinBottomThickness}), верхняя полусфера
 *       пробита 2–4 воронками (шум кратера {@code > craterThreshold}).</li>
 *   <li>{@link BodyType#PLANET} — сплошная сфера + 2–3 концентрических
 *       горизонтальных кольца из разреженных микро-астероидов 3×3×3.</li>
 * </ul>
 *
 * CACHE: Свойства каждого острова (bounds, оси, тип тела, толщина стенки,
 *        радиусы колец) вычисляются один раз и хранятся в IslandCache.
 *        Список центров для чанка хранится в ChunkIslandCache.
 *
 * PERFORMANCE: Заполнение метеорита — аналитический интервальный скан по Y
 *        (никаких поблочных 3D-вызовов шума): для каждой XZ-колонки границы
 *        внешней оболочки и внутренней полости находятся через квадратный
 *        корень (O(1)), полость пропускается целиком без итерации и без
 *        записи блоков. Заполнение колец использует детерминированную
 *        квантованную сетку ячеек — без перебора списка астероидов.
 */
public class HighIslandGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int LAYER_MIN_Y = 1000;
    public static final int LAYER_MAX_Y = 1100;

    /** Идентификатор слоя для общего ChunkIslandCache. */
    public static final int LAYER_ID = 1;

    private final long   worldSeed;
    private final int    maxHeight;
    private final double maxRadius;
    private final double minRadius;
    private final double noiseDeform;

    private final double wallThicknessMin;
    private final double wallThicknessMax;
    private final double craterThreshold;
    private final int    craterMinBottomThickness;

    private final double ringInnerFactorMin, ringInnerFactorMax;
    private final double ringMidFactorMin,   ringMidFactorMax;
    private final double ringOuterFactorMin, ringOuterFactorMax;
    private final int    ringCellSize;
    private final int    ringSparsityMask;

    private static final int    GRID_CHUNKS  = 26;
    private static final double SPAWN_CHANCE = 0.10;

    private static final int MAX_HOLE_WARNINGS = 200;
    private static final BlockState BS_DEEPSLATE      = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BS_BASALT         = Blocks.BASALT.defaultBlockState();
    private static final BlockState BS_OBSIDIAN       = Blocks.OBSIDIAN.defaultBlockState();
    private static final BlockState BS_MAGMA          = Blocks.MAGMA_BLOCK.defaultBlockState();
    private static final BlockState BS_GOLD_ORE       = Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
    private static final BlockState BS_IRON_ORE       = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();

    private static final BlockState BS_TERRACOTTA     = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState BS_STONE          = Blocks.STONE.defaultBlockState();
    private static final BlockState BS_TUFF           = Blocks.TUFF.defaultBlockState();
    private static final BlockState BS_CALCITE        = Blocks.CALCITE.defaultBlockState();

    private static final BlockState BS_ASTEROID       = Blocks.COBBLED_DEEPSLATE.defaultBlockState();

    private static final AtomicLong holeWarnCount = new AtomicLong(0);

    // ── Шумы ─────────────────────────────────────────────────────────────────
    private final IslandPlacer placer;
    private final AeroNoise    heightVariance;
    private final AeroNoise    edgeNoise;
    private final AeroNoise    shapeNoise;
    private final AeroNoise    craterNoise;
    private final AeroNoise    wallMaterialNoise;
    private final AeroNoise    planetLayerNoise;

    /**
     * Минимальный радиус поиска ячеек, гарантирующий захват всех островов,
     * чей физический радиус может достигать текущего чанка. Учитывает
     * внешний радиус колец планеты (до {@code ringOuterFactorMax * radius}),
     * т.к. кольца — часть тела острова для целей поиска центров.
     */
    private final int searchRadius;

    // ── Кэши ─────────────────────────────────────────────────────────────────
    private final IslandCache islandCache = new IslandCache();
    private final ChunkIslandCache chunkCache;

    public HighIslandGenerator(long worldSeed, Layer3Settings cfg, ChunkIslandCache sharedChunkCache) {
        this.worldSeed      = worldSeed;
        this.maxHeight      = cfg.maxHeight();
        this.maxRadius      = cfg.maxRadius();
        this.minRadius      = cfg.minRadius();
        this.noiseDeform    = cfg.noiseDeform();

        this.wallThicknessMin = cfg.wallThicknessMin();
        this.wallThicknessMax = cfg.wallThicknessMax();
        this.craterThreshold  = cfg.craterThreshold();
        this.craterMinBottomThickness = cfg.craterMinBottomThickness();

        this.ringInnerFactorMin = cfg.ringInnerFactorMin();
        this.ringInnerFactorMax = cfg.ringInnerFactorMax();
        this.ringMidFactorMin   = cfg.ringMidFactorMin();
        this.ringMidFactorMax   = cfg.ringMidFactorMax();
        this.ringOuterFactorMin = cfg.ringOuterFactorMin();
        this.ringOuterFactorMax = cfg.ringOuterFactorMax();
        this.ringCellSize       = cfg.ringCellSize();
        this.ringSparsityMask   = cfg.ringSparsityMask();

        // searchRadius должен покрывать худший случай: планета с maxRadius и
        // внешним кольцом на ringOuterFactorMax — иначе крайние астероиды
        // потеряются на границе чанка (остров "не найден" для дальних чанков).
        double worstCaseExtent = cfg.maxRadius() * cfg.ringOuterFactorMax();
        this.searchRadius   = Math.max(2, (int) Math.ceil(worstCaseExtent / (cfg.gridChunks() * 16.0)) + 1);
        this.chunkCache     = sharedChunkCache;
        this.placer         = new IslandPlacer(worldSeed ^ 0x10L, cfg.gridChunks(), cfg.spawnChance(), false, cfg.maxRadius());
        this.heightVariance = new AeroNoise(worldSeed ^ 0x12L);
        this.edgeNoise      = new AeroNoise(worldSeed ^ 0x13L);
        this.shapeNoise     = new AeroNoise(worldSeed ^ 0x14L);
        this.craterNoise    = new AeroNoise(worldSeed ^ 0x15L);
        this.wallMaterialNoise = new AeroNoise(worldSeed ^ 0x16L);
        this.planetLayerNoise  = new AeroNoise(worldSeed ^ 0x17L);
    }

    public HighIslandGenerator(long worldSeed) {
        this(worldSeed, Layer3Settings.DEFAULT, new ChunkIslandCache());
    }

    // ── Получение данных острова через кэш ───────────────────────────────────

    /** Публичный доступ к кэшированным данным острова. Используется TerrainColumnSampler. */
    public IslandData getIslandData(int cx, int cz) {
        return islandCache.get(cx, cz, key -> computeIslandData(cx, cz));
    }

    private IslandData computeIslandData(int cx, int cz) {
        double v      = (heightVariance.noise2D(cx * 0.003, cz * 0.003) + 1.0) * 0.5;
        int bandRange = LAYER_MAX_Y - LAYER_MIN_Y - maxHeight;
        int botY      = LAYER_MIN_Y + (int)(v * bandRange);
        int islandH   = 20 + (int)((heightVariance.noise2D(cx * 0.008, cz * 0.008) + 1.0)
                * 0.5 * (maxHeight - 20));
        int topY      = Math.min(botY + islandH, LAYER_MAX_Y);

        double vr     = (heightVariance.noise2D(cx * 0.013, cz * 0.013) + 1.0) * 0.5;
        double radius = minRadius + vr * (maxRadius - minRadius);

        long packed = ChunkKey.of(cx, cz);
        // ТЗ п.2: BodyType = ((seed ^ ChunkKey.of(cx, cz)) & 1) == 0 ? METEORITE : PLANET
        BodyType bodyType = ((worldSeed ^ packed) & 1L) == 0L ? BodyType.METEORITE : BodyType.PLANET;

        double[] axes = computeEllipsoidAxes(cx, cz, radius, botY, topY);

        if (bodyType == BodyType.METEORITE) {
            double wt = wallThicknessMin + hashUnit(cx, cz, 0x9E3779B1L) * (wallThicknessMax - wallThicknessMin);
            // Полость не может съесть стенку целиком по любой оси — минимум 3 блока в толще.
            wt = Math.min(wt, Math.min(axes[0], Math.min(axes[1], axes[2])) - 3.0);
            wt = Math.max(wt, 2.0);
            double[] inner = { axes[0] - wt, axes[1] - wt, axes[2] - wt };
            return new IslandData(cx, cz, botY, topY, radius, axes, bodyType, wt, inner, null);
        } else {
            // Планета: ядро — слабо деформированная сфера (ay ~ ax ~ az), кольца
            // считаются от радиуса ядра (ось X эллипсоида как базовый радиус).
            double planetR = axes[0];
            double[] rings = computeRingRadii(cx, cz, planetR);
            return new IslandData(cx, cz, botY, topY, radius,
                    new double[]{planetR, planetR, planetR}, bodyType, 0.0, null, rings);
        }
    }

    private double[] computeRingRadii(int cx, int cz, double planetR) {
        double f1 = ringInnerFactorMin + hashUnit(cx, cz, 0xA24BAED4L) * (ringInnerFactorMax - ringInnerFactorMin);
        double f2 = ringMidFactorMin   + hashUnit(cx, cz, 0x9F6ABC13L) * (ringMidFactorMax - ringMidFactorMin);
        double f3 = ringOuterFactorMin + hashUnit(cx, cz, 0x1B873593L) * (ringOuterFactorMax - ringOuterFactorMin);

        // Зазоры Кассини: толщина каждого кольца — 12% от его центрального радиуса.
        double r1c = planetR * f1, r2c = planetR * f2, r3c = planetR * f3;
        double w1 = r1c * 0.12, w2 = r2c * 0.12, w3 = r3c * 0.12;

        return new double[]{
                r1c - w1, r1c + w1,
                r2c - w2, r2c + w2,
                r3c - w3, r3c + w3
        };
    }

    /** Детерминированный хэш [0..1) по центру острова и соли — независим от heightVariance/edgeNoise. */
    private long baseHash(int cx, int cz, long salt) {
        long h = (long) cx * 0xC2B2AE3D27D4EB4FL ^ (long) cz * 0x9E3779B97F4A7C15L ^ salt;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    private double hashUnit(int cx, int cz, long salt) {
        long h = baseHash(cx, cz, salt);
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private double[] computeEllipsoidAxes(int cx, int cz, double radius, int botY, int topY) {
        long h = (long) cx * 741873128L ^ (long) cz * 432897987L;
        h ^= (h >>> 27);
        h *= 0xBF58476D1CE4E5B9L;
        int variant = (int)(Math.abs(h) % 5);

        double halfH = (topY - botY) * 0.5;
        double ax, ay, az;

        switch (variant) {
            case 1:  ax = radius * 1.5;  ay = halfH;        az = radius * 0.75; break;
            case 2:  ax = radius * 0.75; ay = halfH;        az = radius * 1.5;  break;
            case 3:  ax = radius * 1.3;  ay = halfH * 0.5;  az = radius * 1.3;  break;
            case 4:  ax = radius * 0.7;  ay = halfH * 1.4;  az = radius * 0.7;  break;
            default: ax = radius;         ay = halfH;        az = radius;
        }
        return new double[]{ax, ay, az};
    }

    // ── Публичные методы (обратная совместимость) ─────────────────────────────

    public int[] getIslandYBounds(int cx, int cz) {
        IslandData d = getIslandData(cx, cz);
        return new int[]{d.bottomY, d.topY};
    }

    public double getIslandRadius(int cx, int cz) {
        return getIslandData(cx, cz).radius;
    }

    // ── Заполнение чанка ──────────────────────────────────────────────────────

    public void fillChunk(ChunkAccess chunk, int chunkX, int chunkZ) {
        fillChunk(new ChunkAccessWriter(chunk), chunkX, chunkZ);
    }

    public void fillChunk(ChunkWriter chunk, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        LongArrayList centres = chunkCache.get(LAYER_ID, chunkX, chunkZ,
                key -> placer.getIslandCentresForChunk(chunkX, chunkZ, searchRadius));

        if (centres.isEmpty()) return;

        IslandData[] islandData = new IslandData[centres.size()];
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            islandData[i] = getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
        }

        for (IslandData d : islandData) {
            if (d.bodyType == BodyType.PLANET) {
                fillPlanet(chunk, d, baseX, baseZ);
            } else {
                fillMeteorite(chunk, d, baseX, baseZ);
            }
        }
    }

    // ── Тип A: Полый метеорит ────────────────────────────────────────────────

    /**
     * Аналитический интервальный скан (ТЗ раздел 3.А). Для каждой XZ-колонки:
     * 1. Ранний XZ-reject по внешней оболочке (O(1)).
     * 2. Аналитические Y-границы внешней оболочки через sqrt.
     * 3. Если колонка пересекает внутреннюю полость — аналитические Y-границы
     *    полости; заполняются только [Ymin_out..Ymin_in] и [Ymax_in..Ymax_out],
     *    интервал полости пропускается целиком без цикла и без записи блоков.
     * 4. Нижняя полусфера (Y <= cy): несквозные кратеры (дно >= minBottomThickness).
     *    Верхняя полусфера (Y > cy): сквозные воронки, если craterNoise > threshold.
     */
    private void fillMeteorite(ChunkWriter chunk, IslandData d, int baseX, int baseZ) {
        double ax = d.ellipsoidAxes[0], ay = d.ellipsoidAxes[1], az = d.ellipsoidAxes[2];
        double rx = d.innerAxes[0],     ry = d.innerAxes[1],     rz = d.innerAxes[2];

        double marginX = ax + noiseDeform;
        double marginZ = az + noiseDeform;
        if (d.cx + marginX < baseX || d.cx - marginX > baseX + 15) return;
        if (d.cz + marginZ < baseZ || d.cz - marginZ > baseZ + 15) return;

        int minLx = Math.max(0, (int) Math.floor(d.cx - marginX) - baseX);
        int maxLx = Math.min(15, (int) Math.ceil(d.cx + marginX) - baseX);
        int minLz = Math.max(0, (int) Math.floor(d.cz - marginZ) - baseZ);
        int maxLz = Math.min(15, (int) Math.ceil(d.cz + marginZ) - baseZ);
        if (minLx > maxLx || minLz > maxLz) return;

        double invAx = 1.0 / ax, invAz = 1.0 / az;
        double invRx = 1.0 / rx, invRz = 1.0 / rz;
        int cy = d.centerY();

        for (int lx = minLx; lx <= maxLx; lx++) {
            int wx = baseX + lx;
            for (int lz = minLz; lz <= maxLz; lz++) {
                int wz = baseZ + lz;

                double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
                double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

                double dxN = (wx - d.cx) + nx;
                double dzN = (wz - d.cz) + nz;

                // ── Внешняя оболочка: xzSqOuter ────────────────────────────
                double dxOut = dxN * invAx, dzOut = dzN * invAz;
                double xzSqOuter = dxOut * dxOut + dzOut * dzOut;
                if (xzSqOuter > 1.0) continue;

                double maxDyOut = ay * Math.sqrt(1.0 - xzSqOuter);
                int yMinOut = Math.max(d.bottomY, (int) Math.ceil(cy - maxDyOut));
                int yMaxOut = Math.min(d.topY,    (int) Math.floor(cy + maxDyOut));
                if (yMinOut > yMaxOut) continue;

                // ── Внутренняя полость: xzSqInner ──────────────────────────
                double dxIn = dxN * invRx, dzIn = dzN * invRz;
                double xzSqInner = dxIn * dxIn + dzIn * dzIn;

                int yMinIn, yMaxIn;
                boolean hasCavity = xzSqInner < 1.0;
                if (hasCavity) {
                    double maxDyIn = ry * Math.sqrt(1.0 - xzSqInner);
                    yMinIn = (int) Math.ceil(cy - maxDyIn);
                    yMaxIn = (int) Math.floor(cy + maxDyIn);
                } else {
                    // Колонка не достигает полости по XZ — сплошной столб от yMinOut до yMaxOut.
                    yMinIn = yMaxOut + 1;
                    yMaxIn = yMinOut - 1;
                }

                // ── Дно (нижняя полусфера, монолит с несквозными кратерами) ──
                // Кратер вырезает только верхний слой дна (ближе к полости),
                // никогда не трогая нижние craterMinBottomThickness блоков от
                // внешней поверхности — дно физически не может быть пробито
                // насквозь. carveFromY — первый Y (снизу), с которого кратер
                // разрешён; ниже него дно всегда монолитно.
                int floorTop = Math.min(yMaxOut, hasCavity ? (yMinIn - 1) : yMaxOut);
                floorTop = Math.min(floorTop, cy); // дно не заходит выше центра
                int carveFromY = yMinOut + craterMinBottomThickness;
                boolean floorCraterHit = craterNoise.fbm2D(wx * 0.02, wz * 0.02, 3, 2.0, 0.5) > craterThreshold;
                for (int wy = yMinOut; wy <= floorTop; wy++) {
                    boolean craterCarve = floorCraterHit && wy >= carveFromY;
                    if (craterCarve) continue;
                    chunk.setBlockState(wx, wy, wz, wallMaterial(wx, wy, wz, cy));
                }

                // ── Свод (верхняя полусфера, со сквозными воронками) ─────────
                int ceilBottom = Math.max(yMinOut, hasCavity ? (yMaxIn + 1) : yMinOut);
                ceilBottom = Math.max(ceilBottom, cy + 1);
                boolean crater = craterNoise.fbm2D(wx * 0.02, wz * 0.02, 3, 2.0, 0.5) > craterThreshold;
                if (!crater) {
                    for (int wy = ceilBottom; wy <= yMaxOut; wy++) {
                        chunk.setBlockState(wx, wy, wz, wallMaterial(wx, wy, wz, cy));
                    }
                }
                // crater == true → вся колонка свода пропускается (сквозная воронка).

                // Полость [yMinIn..yMaxIn] (если hasCavity) не заполняется —
                // O(1) пропуск, ни одной записи блока.
            }
        }
    }

    /** Материал стенки метеорита: глубинный сланец с вкраплениями базальта/обсидиана/магмы/руд. */
    private BlockState wallMaterial(int wx, int wy, int wz, int cy) {
        double m = wallMaterialNoise.noise3D(wx * 0.08, wy * 0.08, wz * 0.08);
        if (m > 0.75) return BS_OBSIDIAN;
        if (m > 0.55) return BS_MAGMA;
        if (m > 0.35) return BS_BASALT;
        if (m > 0.28) return BS_GOLD_ORE;
        if (m > 0.20) return BS_IRON_ORE;
        return BS_DEEPSLATE;
    }

    // ── Тип B: Сферическая планета с кольцами ───────────────────────────────

    private void fillPlanet(ChunkWriter chunk, IslandData d, int baseX, int baseZ) {
        double planetR = d.ellipsoidAxes[0];
        double ringOuterMax = d.ringRadii[5]; // внешняя граница третьего кольца
        double margin = Math.max(planetR, ringOuterMax) + noiseDeform;

        if (d.cx + margin < baseX || d.cx - margin > baseX + 15) return;
        if (d.cz + margin < baseZ || d.cz - margin > baseZ + 15) return;

        int minLx = Math.max(0, (int) Math.floor(d.cx - margin) - baseX);
        int maxLx = Math.min(15, (int) Math.ceil(d.cx + margin) - baseX);
        int minLz = Math.max(0, (int) Math.floor(d.cz - margin) - baseZ);
        int maxLz = Math.min(15, (int) Math.ceil(d.cz + margin) - baseZ);
        if (minLx > maxLx || minLz > maxLz) return;

        int cy = d.centerY();
        double invR = 1.0 / planetR;

        for (int lx = minLx; lx <= maxLx; lx++) {
            int wx = baseX + lx;
            for (int lz = minLz; lz <= maxLz; lz++) {
                int wz = baseZ + lz;

                double dist = Math.sqrt((double)(wx - d.cx) * (wx - d.cx) + (double)(wz - d.cz) * (wz - d.cz));

                // ── Ядро планеты: сплошная слабо деформированная сфера ──────
                double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
                double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;
                double dxN = ((wx - d.cx) + nx) * invR;
                double dzN = ((wz - d.cz) + nz) * invR;
                double xzSq = dxN * dxN + dzN * dzN;

                if (xzSq <= 1.0) {
                    double maxDy = planetR * Math.sqrt(1.0 - xzSq);
                    int yMin = Math.max(d.bottomY, (int) Math.ceil(cy - maxDy));
                    int yMax = Math.min(d.topY,    (int) Math.floor(cy + maxDy));
                    for (int wy = yMin; wy <= yMax; wy++) {
                        chunk.setBlockState(wx, wy, wz, planetMaterial(wx, wy, wz, cy, planetR));
                    }
                }

                // ── Кольца: горизонтальный диапазон, вне сферы ядра ─────────
                if (dist >= d.ringRadii[0] && dist <= d.ringRadii[5]) {
                    fillRingColumn(chunk, d, wx, wz, dist, cy);
                }
            }
        }
    }

    /** Слоистый материал ядра планеты (терракота/камень/туф/кальцит по глубине от поверхности). */
    private BlockState planetMaterial(int wx, int wy, int wz, int cy, double planetR) {
        double layerN = planetLayerNoise.noise3D(wx * 0.05, wy * 0.05, wz * 0.05);
        double band = (layerN + 1.0) * 0.5; // 0..1
        if (band > 0.75) return BS_TERRACOTTA;
        if (band > 0.5)  return BS_CALCITE;
        if (band > 0.25) return BS_TUFF;
        return BS_STONE;
    }

    /**
     * Заполняет одну XZ-колонку в диапазоне колец (ТЗ раздел 3.Б).
     * Пространство квантуется в ячейки {@code ringCellSize x ringCellSize};
     * каждой ячейке детерминированно назначается 0 или 1 астероид через
     * LCG-хэш ({@code cellHash & sparsityMask}). Блок ставится, только если
     * (wx, wz) лежит в пределах радиуса конкретного астероида — без перебора
     * списка астероидов и без хранения состояния.
     */
    private void fillRingColumn(ChunkWriter chunk, IslandData d, int wx, int wz, double dist, int cy) {
        // Определяем, в каком именно кольце лежит dist (с зазорами Кассини между ними).
        boolean inRing1 = dist >= d.ringRadii[0] && dist <= d.ringRadii[1];
        boolean inRing2 = dist >= d.ringRadii[2] && dist <= d.ringRadii[3];
        boolean inRing3 = dist >= d.ringRadii[4] && dist <= d.ringRadii[5];
        if (!inRing1 && !inRing2 && !inRing3) return;

        int cellX = Math.floorDiv(wx, ringCellSize);
        int cellZ = Math.floorDiv(wz, ringCellSize);

        // Проверяем ячейку кандидата и соседние (астероид в соседней ячейке
        // может физически пересекать эту колонку, если его центр у границы).
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                int ccx = cellX + dcx;
                int ccz = cellZ + dcz;
                long cellHash = ringCellHash(ccx, ccz, d.cx, d.cz);
                if ((cellHash & ringSparsityMask) != 0) continue; // ячейка пустая

                // Центр астероида — детерминированное смещение внутри ячейки.
                int cellBaseX = ccx * ringCellSize;
                int cellBaseZ = ccz * ringCellSize;
                int offX = (int) ((cellHash >>> 8) % ringCellSize);
                int offZ = (int) ((cellHash >>> 16) % ringCellSize);
                int ax = cellBaseX + offX;
                int az = cellBaseZ + offZ;

                double distFromCentre = Math.sqrt((double)(ax - d.cx) * (ax - d.cx) + (double)(az - d.cz) * (az - d.cz));
                if (!(distFromCentre >= d.ringRadii[0] && distFromCentre <= d.ringRadii[5])) continue;
                // Астероид должен лежать строго в одном из трёх колец (не в зазоре Кассини).
                boolean asteroidInRing =
                        (distFromCentre >= d.ringRadii[0] && distFromCentre <= d.ringRadii[1]) ||
                                (distFromCentre >= d.ringRadii[2] && distFromCentre <= d.ringRadii[3]) ||
                                (distFromCentre >= d.ringRadii[4] && distFromCentre <= d.ringRadii[5]);
                if (!asteroidInRing) continue;

                int ay = cy + (int) ((cellHash >>> 24) % 3) - 1; // ay ∈ [cy-1, cy+1]

                double shapeN = shapeNoise.noise3D(ax * 0.3, ay * 0.3, az * 0.3);
                double maxDist = 1.5 + shapeN * 0.4;

                double ddx = wx - ax, ddz = wz - az;
                double horizDistSq = ddx * ddx + ddz * ddz;
                if (horizDistSq > maxDist * maxDist) continue;

                // Вертикальный экстент астероида — до 1 блока в каждую сторону от ay,
                // с той же формой (3x3x3 максимум).
                for (int wy = ay - 1; wy <= ay + 1; wy++) {
                    double ddy = wy - ay;
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= maxDist * maxDist) {
                        chunk.setBlockState(wx, wy, wz, BS_ASTEROID);
                    }
                }
            }
        }
    }

    /** LCG-хэш ячейки кольца, детерминированный по (ccx, ccz) и центру острова. */
    private long ringCellHash(int ccx, int ccz, int islandCx, int islandCz) {
        long h = (long) ccx * 0x27D4EB2FL ^ (long) ccz * 0x165667B1L
                ^ (long) islandCx * 0x9E3779B1L ^ (long) islandCz * 0x85EBCA6BL;
        h ^= (h >>> 15);
        h *= 0x2545F4914F6CDD1DL;
        h ^= (h >>> 13);
        return h;
    }

    public void releaseChunkCache(int chunkX, int chunkZ) {
        chunkCache.release(LAYER_ID, chunkX, chunkZ);
    }

    /** Доступ к IslandPlacer для TerrainColumnSampler. */
    public IslandPlacer getPlacer() { return placer; }

    /** Радиус поиска ячеек для этого слоя. Используется TerrainColumnSampler. */
    public int getSearchRadius() { return searchRadius; }

    // ── Форма шара / эллипсоида (ядро; для LOD/вспомогательных запросов) ──────

    /**
     * Точечная проверка принадлежности блока телу острова (сплошному ядру
     * планеты или стенке метеорита — без учёта колец/полости, приближённо).
     * Используется в TerrainColumnSampler (одиночные запросы вне горячего цикла).
     */
    boolean isSphereBlockSolid(int wx, int wy, int wz,
                               IslandData d,
                               double ax, double ay, double az) {
        if (wy < d.bottomY || wy > d.topY) return false;

        double invAx = 1.0 / ax;
        double invAy = 1.0 / ay;
        double invAz = 1.0 / az;

        double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
        double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

        double dxInv = ((wx - d.cx) + nx) * invAx;
        double dyInv = ((wy - d.centerY()))   * invAy;
        double dzInv = ((wz - d.cz) + nz) * invAz;

        return dxInv * dxInv + dyInv * dyInv + dzInv * dzInv <= 1.0;
    }

    // ── Диагностика ───────────────────────────────────────────────────────────

    private static void logHoleWarning(int chunkX, int chunkZ,
                                       int wx, int wy, int wz,
                                       int cx, int cz, String reason) {
        if (holeWarnCount.incrementAndGet() > MAX_HOLE_WARNINGS) return;
    }

    /**
     * Возвращает нормализованное XZ-расстояние ({@code xzSq}) точки (wx, wz)
     * от центра тела острова (внешняя оболочка эллипсоида/сферы ядра), с
     * учётом той же шумовой деформации края, что использует {@link #fillChunk}.
     *
     * <p>Используется генератором Vault/Trial Spawner Layer 3, чтобы искать
     * точку постановки строго внутри тела острова.
     */
    public double computeXZSq(int wx, int wz, IslandData d) {
        double ax = d.ellipsoidAxes[0];
        double az = d.ellipsoidAxes[2];

        double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
        double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

        double dxN = ((wx - d.cx) + nx) / ax;
        double dzN = ((wz - d.cz) + nz) / az;
        return dxN * dxN + dzN * dzN;
    }

    /**
     * Возвращает реальный Y верхней поверхности внешней оболочки острова
     * в точке (wx, wz) с учётом шумовой деформации края — для LOD.
     * Для метеорита это внешний свод (не полость); для планеты — сфера ядра.
     *
     * @return topY острова в этой колонке, или {@code d.bottomY - 1} если вне острова
     */
    public int getEllipsoidTopY(int wx, int wz, IslandData d) {
        if (d.ellipsoidAxes == null) return d.topY;

        double ax = d.ellipsoidAxes[0];
        double ay = d.ellipsoidAxes[1];
        double az = d.ellipsoidAxes[2];

        double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
        double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

        double dxN  = ((wx - d.cx) + nx) / ax;
        double dzN  = ((wz - d.cz) + nz) / az;
        double xzSq = dxN * dxN + dzN * dzN;
        if (xzSq > 1.0) return d.bottomY - 1; // вне эллипсоида

        int cy   = d.centerY();
        int topY = (int) Math.floor(cy + ay * Math.sqrt(1.0 - xzSq));
        return Math.min(topY, d.topY);
    }

    /**
     * Возвращает реальный Y нижней поверхности внешней оболочки острова
     * в точке (wx, wz).
     *
     * @return bottomY острова в этой колонке, или {@code d.topY + 1} если вне острова
     */
    public int getEllipsoidBottomY(int wx, int wz, IslandData d) {
        if (d.ellipsoidAxes == null) return d.bottomY;

        double ax = d.ellipsoidAxes[0];
        double ay = d.ellipsoidAxes[1];
        double az = d.ellipsoidAxes[2];

        double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
        double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

        double dxN  = ((wx - d.cx) + nx) / ax;
        double dzN  = ((wz - d.cz) + nz) / az;
        double xzSq = dxN * dxN + dzN * dzN;
        if (xzSq > 1.0) return d.topY + 1;

        int cy      = d.centerY();
        int bottomY = (int) Math.ceil(cy - ay * Math.sqrt(1.0 - xzSq));
        return Math.max(bottomY, d.bottomY);
    }

    /**
     * Верхняя граница внутренней полости метеорита в точке (wx, wz).
     * Только для {@link BodyType#METEORITE}; для планет/других тел не вызывать.
     *
     * @return Y верхней границы полости, или {@code d.bottomY - 1}, если
     *         колонка не пересекает полость по XZ.
     */
    public int getMeteoriteCavityTopY(int wx, int wz, IslandData d) {
        double rx = d.innerAxes[0], ry = d.innerAxes[1], rz = d.innerAxes[2];

        double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
        double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

        double dxN = ((wx - d.cx) + nx) / rx;
        double dzN = ((wz - d.cz) + nz) / rz;
        double xzSq = dxN * dxN + dzN * dzN;
        if (xzSq > 1.0) return d.bottomY - 1;

        int cy = d.centerY();
        return (int) Math.floor(cy + ry * Math.sqrt(1.0 - xzSq));
    }

    /**
     * Нижняя граница внутренней полости метеорита в точке (wx, wz) — она же
     * пол полости, куда ставятся Trial Vault/Spawner (ТЗ раздел 4).
     * Только для {@link BodyType#METEORITE}.
     *
     * @return Y нижней границы полости (пол), или {@code d.topY + 1}, если
     *         колонка не пересекает полость по XZ.
     */
    public int getMeteoriteCavityBottomY(int wx, int wz, IslandData d) {
        double rx = d.innerAxes[0], ry = d.innerAxes[1], rz = d.innerAxes[2];

        double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
        double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

        double dxN = ((wx - d.cx) + nx) / rx;
        double dzN = ((wz - d.cz) + nz) / rz;
        double xzSq = dxN * dxN + dzN * dzN;
        if (xzSq > 1.0) return d.topY + 1;

        int cy = d.centerY();
        return (int) Math.ceil(cy - ry * Math.sqrt(1.0 - xzSq));
    }

}