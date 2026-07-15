package org.example.aeroworld.worldgen.layer;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.IslandCache;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.noise.AeroNoise;
import org.example.aeroworld.worldgen.noise.IslandPlacer;
import org.example.aeroworld.config.Layer3Settings;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Layer 3 — High Sky Islands (Y 1000 to Y 1100).
 *
 * Форма: ШАРЫ и ЭЛЛИПСОИДЫ (случайно вытянутые по одной из горизонтальных осей
 * или по вертикали). Никаких конусов, никаких щупалец.
 *
 * CACHE: Свойства каждого острова (yBounds, radius, ellipsoidAxes) вычисляются
 *        один раз и хранятся в IslandCache. Список центров для чанка хранится
 *        в ChunkIslandCache.
 */
public class HighIslandGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int LAYER_MIN_Y = 1000;
    public static final int LAYER_MAX_Y = 1100;

    /** Идентификатор слоя для общего ChunkIslandCache. */
    public static final int LAYER_ID = 1;

    private static final double NOISE_DEFORM_DEFAULT = 6.0; // fallback, перекрывается settings

    private final int    maxHeight;
    private final double maxRadius;
    private final double minRadius;
    private final double noiseDeform;

    private static final int    GRID_CHUNKS  = 26;
    private static final double SPAWN_CHANCE = 0.10;

    private static final int MAX_HOLE_WARNINGS = 200;
    private static final net.minecraft.world.level.block.state.BlockState BS_STONE =
            Blocks.STONE.defaultBlockState();
    private static final AtomicLong holeWarnCount = new AtomicLong(0);

    // ── Шумы ─────────────────────────────────────────────────────────────────
    private final IslandPlacer placer;
    private final AeroNoise    heightVariance;
    private final AeroNoise    edgeNoise;
    private final AeroNoise    shapeNoise;

    /**
     * Минимальный радиус поиска ячеек, гарантирующий захват всех островов,
     * чей физический радиус может достигать текущего чанка.
     * Вычисляется из конфига: ceil(maxRadius / (gridChunks * 16)) + 1.
     */
    private final int searchRadius;

    // ── Кэши ─────────────────────────────────────────────────────────────────
    /**
     * Кэш свойств островов включая эллипсоидные оси.
     * Вычисление getEllipsoidAxes содержит несколько умножений и ветвление —
     * с кэшом выполняется один раз вместо 256 раз на чанк.
     */
    private final IslandCache islandCache = new IslandCache();
    private final ChunkIslandCache chunkCache;

    public HighIslandGenerator(long worldSeed, Layer3Settings cfg, ChunkIslandCache sharedChunkCache) {
        this.maxHeight      = cfg.maxHeight();
        this.maxRadius      = cfg.maxRadius();
        this.minRadius      = cfg.minRadius();
        this.noiseDeform    = cfg.noiseDeform();
        this.searchRadius   = Math.max(2, (int) Math.ceil(cfg.maxRadius() / (cfg.gridChunks() * 16.0)) + 1);
        this.chunkCache     = sharedChunkCache;
        this.placer         = new IslandPlacer(worldSeed ^ 0x10L, cfg.gridChunks(), cfg.spawnChance());
        this.heightVariance = new AeroNoise(worldSeed ^ 0x12L);
        this.edgeNoise      = new AeroNoise(worldSeed ^ 0x13L);
        this.shapeNoise     = new AeroNoise(worldSeed ^ 0x14L);
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

        double[] axes = computeEllipsoidAxes(cx, cz, radius, botY, topY);
        return new IslandData(cx, cz, botY, topY, radius, axes);
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
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        List<int[]> centres = chunkCache.get(LAYER_ID, chunkX, chunkZ,
                key -> placer.getIslandCentresForChunk(chunkX, chunkZ, searchRadius));

        if (centres.isEmpty()) return;

        // Предвычисляем все IslandData до цикла по блокам
        IslandData[] islandData = new IslandData[centres.size()];
        for (int i = 0; i < centres.size(); i++) {
            int[] c = centres.get(i);
            islandData[i] = getIslandData(c[0], c[1]);
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (IslandData d : islandData) {
            // ── Пункт J: заменяем деления на умножение на обратные величины ──
            // 6 делений на каждый из ~25 600 блоков острова → 3 умножения,
            // предвычисленных один раз. Деление на JVM в ~3–5× дороже умножения.
            double ax = d.ellipsoidAxes[0];
            double ay = d.ellipsoidAxes[1];
            double az = d.ellipsoidAxes[2];
            double invAx = 1.0 / ax;
            double invAy = 1.0 / ay;
            double invAz = 1.0 / az;
            int    cy    = d.centerY();

            int expectedSolid = 0;
            int placedSolid   = 0;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = baseX + lx;
                    int wz = baseZ + lz;

                    // ── Шум края зависит только от (wx, wz), не от wy ────────
                    // Прежде вычислялся внутри isSphereBlockSolid на каждый Y —
                    // 2 вызова fbm2D × (topY-bottomY+1) ≈ 200 лишних вычислений
                    // на XZ-колонку. Выносим сюда: вычисляется один раз на колонку.
                    double nx = edgeNoise.fbm2D(wx * 0.035,      wz * 0.035,      2, 2.0, 0.5) * noiseDeform;
                    double nz = edgeNoise.fbm2D(wx * 0.035 + 55, wz * 0.035 + 55, 2, 2.0, 0.5) * noiseDeform;

                    // XZ-компоненты эллипсоида постоянны для всей колонки
                    double dxN    = (wx - d.cx) + nx;
                    double dzN    = (wz - d.cz) + nz;
                    double dxNInv = dxN * invAx;
                    double dzNInv = dzN * invAz;
                    double xzSq   = dxNInv * dxNInv + dzNInv * dzNInv;

                    // Ранний XZ-reject: если только XZ-компонента уже > 1.0,
                    // ни один Y в этой колонке не будет внутри эллипсоида
                    if (xzSq > 1.0) continue;

                    for (int wy = d.bottomY; wy <= d.topY; wy++) {
                        double dyInv = (wy - cy) * invAy;
                        if (xzSq + dyInv * dyInv > 1.0) continue;

                        expectedSolid++;
                        pos.set(wx, wy, wz);
                        chunk.setBlockState(pos, BS_STONE, false);

                        if (chunk.getBlockState(pos).isAir()) {
                            logHoleWarning(chunkX, chunkZ, wx, wy, wz, d.cx, d.cz,
                                    "setBlockState→AIR: ChunkSection отсутствует или Y вне диапазона измерения");
                        } else {
                            placedSolid++;
                        }
                    }
                }
            }

            if (expectedSolid > 0 && placedSolid < expectedSolid) {
                int lost = expectedSolid - placedSolid;
                if (lost * 100 / expectedSolid >= 1) {
                    LOGGER.warn("[AeroWorld][L3-SPHERE] СТАТ chunk=[{},{}] остров@({},{}) " +
                                    "ожидалось={} поставлено={} ПОТЕРИ={}",
                            chunkX, chunkZ, d.cx, d.cz, expectedSolid, placedSolid, lost);
                }
            }
        }
    }

    public void releaseChunkCache(int chunkX, int chunkZ) {
        chunkCache.release(LAYER_ID, chunkX, chunkZ);
    }

    /** Доступ к IslandPlacer для TerrainColumnSampler. */
    public IslandPlacer getPlacer() { return placer; }

    /** Радиус поиска ячеек для этого слоя. Используется TerrainColumnSampler. */
    public int getSearchRadius() { return searchRadius; }

    // ── Форма шара / эллипсоида ───────────────────────────────────────────────

    /**
     * Точечная проверка принадлежности блока эллипсоиду.
     * Используется в TerrainColumnSampler (одиночные запросы вне горячего цикла).
     *
     * <p>Пункт J: деления заменены умножением на предвычисленные обратные величины.
     * invAx/invAy/invAz вычисляются здесь; для горячего цикла fillChunk они
     * вычисляются один раз снаружи (см. fillChunk).
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
        LOGGER.warn("[AeroWorld][L3-SPHERE] ДЫРА chunk=[{},{}] block=({},{},{}) island=({},{}) | {}",
                chunkX, chunkZ, wx, wy, wz, cx, cz, reason);
    }
    /**
     * Возвращает реальный Y верхней поверхности эллипсоидного острова
     * в точке (wx, wz) с учётом шумовой деформации края — для LOD.
     *
     * <p>Для эллипсоида верхняя граница в точке (wx, wz):
     * <pre>
     *   xzSq = ((wx-cx+nx)/ax)² + ((wz-cz+nz)/az)²
     *   topY  = cy + ay * sqrt(max(0, 1 - xzSq))
     * </pre>
     * Это O(1) — без итерации по Y.
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
     * Возвращает реальный Y нижней поверхности эллипсоида в точке (wx, wz).
     *
     * <pre>
     *   bottomY = cy - ay * sqrt(max(0, 1 - xzSq))
     * </pre>
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

}