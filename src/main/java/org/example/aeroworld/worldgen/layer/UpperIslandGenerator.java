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
import org.example.aeroworld.config.Layer4Settings;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Layer 4 — Upper Sky Islands (Y 1900 to Y 2031).
 *
 * Форма: МЕДУЗА / КАЛЬМАР.
 *   - Верхняя часть: куполообразная шапка.
 *   - Нижняя часть: 10 щупалец, изгибающихся в разные стороны.
 *
 * CACHE: Все свойства острова (yBounds, radius, tentacleData) вычисляются
 *        один раз и хранятся в IslandCache. Особенно выгодно для buildTentacles:
 *        10 щупалец × несколько noise2D/fbm2D вызовов каждое =
 *        ~50 шумовых вычислений — теперь только единожды на остров.
 */
public class UpperIslandGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int LAYER_MIN_Y = 1900;
    public static final int LAYER_MAX_Y = 2031;

    /** Идентификатор слоя для общего ChunkIslandCache. */
    public static final int LAYER_ID = 2;

    private final int    minHeight;
    private final int    maxHeight;
    private final double minRadius;
    private final double maxRadius;
    private final int    tentacleCount;
    private final int    tentacleMinLen;
    private final int    tentacleMaxLen;
    private final double tentacleBend;

    private static final double CAP_NOISE_DEF  = 8.0;
    private static final double TENTACLE_BASE_R = 5.0;
    private static final double TENTACLE_TIP_R  = 1.5;

    /**
     * Золотое сечение φ = (√5 − 1) / 2 ≈ 0.6180.
     * Угол Золотого сечения в радианах = 2π × (1 − φ) ≈ 2.399 рад ≈ 137.5°.
     * При закрутке SPIRAL_IN/OUT каждое следующее щупальце смещается
     * на этот угол — природный паттерн подсолнуха / раковины.
     */
    private static final double GOLDEN_ANGLE = 2.0 * Math.PI * (1.0 - (Math.sqrt(5.0) - 1.0) / 2.0);


    private static final int SPIRAL_STRAIGHT = 0; // прямые
    private static final int SPIRAL_IN       = 1; // закрутка внутрь
    private static final int SPIRAL_OUT      = 2; // закрутка наружу


    private static final int MAX_HOLE_WARNINGS = 200;
    private static final net.minecraft.world.level.block.state.BlockState BS_STONE =
            Blocks.STONE.defaultBlockState();
    private static final AtomicLong holeWarnCount = new AtomicLong(0);

    // ── Шумы ─────────────────────────────────────────────────────────────────
    private final IslandPlacer placer;
    private final AeroNoise    heightVariance;
    private final AeroNoise    capEdgeNoise;
    private final AeroNoise    tentacleNoise;

    /**
     * Минимальный радиус поиска ячеек, гарантирующий захват всех островов,
     * чей физический радиус может достигать текущего чанка.
     * Вычисляется из конфига: ceil(maxRadius / (gridChunks * 16)) + 1.
     */
    private final int searchRadius;

    // ── Кэши ─────────────────────────────────────────────────────────────────
    /**
     * Кэш свойств островов включая параметры всех щупалец.
     * buildTentacles содержит ~60 вызовов noise2D/fbm2D — экономия максимальна.
     */
    private final IslandCache islandCache = new IslandCache();
    private final ChunkIslandCache chunkCache;

    public UpperIslandGenerator(long worldSeed, Layer4Settings cfg, ChunkIslandCache sharedChunkCache) {
        this.minHeight      = cfg.minHeight();
        this.maxHeight      = cfg.maxHeight();
        this.minRadius      = cfg.minRadius();
        this.maxRadius      = cfg.maxRadius();
        this.tentacleCount  = cfg.tentacleCount();
        this.tentacleMinLen = cfg.tentacleMinLen();
        this.tentacleMaxLen = cfg.tentacleMaxLen();
        this.tentacleBend   = cfg.tentacleBend();
        this.searchRadius   = Math.max(2, (int) Math.ceil(cfg.maxRadius() / (cfg.gridChunks() * 16.0)) + 1);
        this.chunkCache     = sharedChunkCache;
        this.placer         = new IslandPlacer(worldSeed ^ 0x20L, cfg.gridChunks(), cfg.spawnChance());
        this.heightVariance = new AeroNoise(worldSeed ^ 0x22L);
        this.capEdgeNoise   = new AeroNoise(worldSeed ^ 0x23L);
        this.tentacleNoise  = new AeroNoise(worldSeed ^ 0x24L);
    }

    public UpperIslandGenerator(long worldSeed) {
        this(worldSeed, Layer4Settings.DEFAULT, new ChunkIslandCache());
    }

    // ── Получение данных острова через кэш ───────────────────────────────────

    /** Публичный доступ к кэшированным данным острова. Используется TerrainColumnSampler. */
    public IslandData getIslandData(int cx, int cz) {
        return islandCache.get(cx, cz, key -> computeIslandData(cx, cz));
    }

    private IslandData computeIslandData(int cx, int cz) {
        // Границы
        double v      = (heightVariance.noise2D(cx * 0.004, cz * 0.004) + 1.0) * 0.5;
        int bandRange = LAYER_MAX_Y - LAYER_MIN_Y - maxHeight;
        int botY      = LAYER_MIN_Y + (int)(v * bandRange);
        int islandH   = minHeight + (int)((heightVariance.noise2D(cx * 0.009, cz * 0.009) + 1.0)
                * 0.5 * (maxHeight - minHeight));
        int topY      = Math.min(botY + islandH, LAYER_MAX_Y);

        // Радиус
        double vr     = (heightVariance.noise2D(cx * 0.015, cz * 0.015) + 1.0) * 0.5;
        double radius = minRadius + vr * (maxRadius - minRadius);

        // Щупальца — самая дорогая часть, кэшируем обязательно
        int capBaseY        = botY + (topY - botY) / 3;
        double[][] tentacles = buildTentacles(cx, cz, capBaseY);

        return new IslandData(cx, cz, botY, topY, radius, tentacles);
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
            if (d.radius < minRadius) continue;
            if (d.height() < minHeight) continue;

            int capBaseY     = d.bottomY + d.height() / 3;
            int tentacleFloor = Math.max(LAYER_MIN_Y - tentacleMaxLen, -64);

            int expectedSolid = 0;
            int placedSolid   = 0;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = baseX + lx;
                    int wz = baseZ + lz;

                    // Precompute XZ noise once per column — shared by all Y-levels.
                    CapXZCache      capXZ  = precomputeCapXZ(wx, wz);
                    TentacleXZCache tentXZ = precomputeTentacleXZ(wx, wz);

                    // ── Шапка ─────────────────────────────────────────────────
                    for (int wy = capBaseY; wy <= d.topY; wy++) {
                        if (!isCapSolid(wx, wy, wz, d.cx, d.cz, capBaseY, d.topY, d.radius, capXZ)) continue;
                        expectedSolid++;
                        pos.set(wx, wy, wz);
                        chunk.setBlockState(pos, BS_STONE, false);
                        if (chunk.getBlockState(pos).isAir()) {
                            logHole(chunkX, chunkZ, wx, wy, wz, d.cx, d.cz, "шапка→AIR");
                        } else {
                            placedSolid++;
                        }
                    }

                    // ── Щупальца — данные уже в IslandData, повторных вычислений нет ──
                    for (int wy = tentacleFloor; wy < capBaseY; wy++) {
                        if (!isTentacleSolid(wx, wy, wz, capBaseY, tentacleFloor,
                                d.tentacleData, tentXZ)) continue;
                        expectedSolid++;
                        pos.set(wx, wy, wz);
                        chunk.setBlockState(pos, BS_STONE, false);
                        if (chunk.getBlockState(pos).isAir()) {
                            logHole(chunkX, chunkZ, wx, wy, wz, d.cx, d.cz, "щупальце→AIR");
                        } else {
                            placedSolid++;
                        }
                    }
                }
            }

            if (expectedSolid > 0 && placedSolid < expectedSolid) {
                int lost = expectedSolid - placedSolid;
                if (lost * 100 / expectedSolid >= 1) {
                    LOGGER.warn("[AeroWorld][L4-JELLY] СТАТ chunk=[{},{}] остров@({},{}) " +
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

    // ── Форма шапки
    /** XZ-deformation for cap: precomputed once per column. */
    private static final class CapXZCache {
        final double nx, nz; // already multiplied by CAP_NOISE_DEF
        CapXZCache(double nx, double nz) { this.nx = nx; this.nz = nz; }
    }

    private CapXZCache precomputeCapXZ(int wx, int wz) {
        double nx = capEdgeNoise.fbm2D(wx * 0.04, wz * 0.04, 2, 2.0, 0.5) * CAP_NOISE_DEF;
        double nz = capEdgeNoise.fbm2D(wx * 0.04 + 99, wz * 0.04 + 99, 2, 2.0, 0.5) * CAP_NOISE_DEF;
        return new CapXZCache(nx, nz);
    }

    private boolean isCapSolid(int wx, int wy, int wz,
                               int cx, int cz,
                               int capBaseY, int topY,
                               double radius,
                               CapXZCache capXZ) {
        if (wy < capBaseY || wy > topY) return false;

        double t      = (double)(wy - capBaseY) / Math.max(1, topY - capBaseY);
        // capR² = radius² * (1 - t²) * bulge² — без sqrt
        double base   = Math.max(0, 1.0 - t * t);           // (1-t²)
        double bulge  = t < 0.25 ? (1.0 + (0.25 - t) * 0.6) : 1.0; // выпуклость
        double capRSq = radius * radius * base * bulge * bulge; // capR² без sqrt

        double dx = (wx - cx) + capXZ.nx;
        double dz = (wz - cz) + capXZ.nz;
        return dx * dx + dz * dz <= capRSq; // no sqrt anywhere
    }

    // ── Щупальца


    private static final class TentacleXZCache {
        final double unitNx, unitNz; // fbm2D output without amplitude factor
        TentacleXZCache(double unitNx, double unitNz) { this.unitNx = unitNx; this.unitNz = unitNz; }
    }

    private TentacleXZCache precomputeTentacleXZ(int wx, int wz) {
        double unitNx = tentacleNoise.fbm2D(wx * 0.08, wz * 0.08, 2, 2.0, 0.5);
        double unitNz = tentacleNoise.fbm2D(wx * 0.08 + 33, wz * 0.08 + 33, 2, 2.0, 0.5);
        return new TentacleXZCache(unitNx, unitNz);
    }


    private double[][] buildTentacles(int cx, int cz, int capBaseY) {
        // Тип закрутки — детерминированный хэш от координат центра.
        // Три ветки: каждый третий остров получает свой тип.
        long typeHash = (long) cx * 374761393L ^ (long) cz * 668265263L;
        typeHash ^= (typeHash >>> 13);
        typeHash *= 0x9E3779B97F4A7C15L;
        int spiralType = (int) Math.abs(typeHash % 3); // 0, 1 или 2

        double[][] result = new double[tentacleCount][];
        for (int i = 0; i < tentacleCount; i++) {
            double angleBase = tentacleNoise.noise2D(cx * 0.01 + i * 37, cz * 0.01 + i * 53);
            double angle     = (i * (2.0 * Math.PI / tentacleCount)) + angleBase * 0.5;

            double rootOffset = tentacleNoise.noise2D(cx * 0.02 + i, cz * 0.02 + i + 100);
            double rootR      = 8.0 + rootOffset * 5.0;

            double rootX = cx + Math.cos(angle) * rootR;
            double rootZ = cz + Math.sin(angle) * rootR;

            double bendAngle = angle + tentacleNoise.noise2D(
                    cx * 0.03 + i * 17, cz * 0.03 + i * 23) * 0.8;
            double dirX = Math.cos(bendAngle);
            double dirZ = Math.sin(bendAngle);

            double lenFrac = (tentacleNoise.noise2D(cx * 0.05 + i * 7, cz * 0.05 + i * 11) + 1.0) * 0.5;
            double length  = tentacleMinLen + lenFrac * (tentacleMaxLen - tentacleMinLen);


            double spiralStrength = 0.6 + (tentacleNoise.noise2D(
                    cx * 0.07 + i * 41, cz * 0.07 + i * 59) + 1.0) * 0.4;

            result[i] = new double[]{
                    rootX, rootZ,           // [0,1] корень щупальца (XZ)
                    dirX,  dirZ,            // [2,3] направление изгиба
                    length,                 // [4]   длина
                    TENTACLE_BASE_R,        // [5]   базовый радиус
                    spiralType,             // [6]   тип закрутки (0/1/2)
                    spiralStrength          // [7]   сила закрутки
            };
        }
        return result;
    }

    private boolean isTentacleSolid(int wx, int wy, int wz,
                                    int capBaseY, int botY,
                                    double[][] tentacles,
                                    TentacleXZCache tentXZ) {
        if (wy >= capBaseY || wy < botY) return false;
        for (double[] t : tentacles) {
            double rootX         = t[0], rootZ         = t[1];
            double dirX          = t[2], dirZ          = t[3];
            double length        = t[4], baseR         = t[5];
            int    spiralType    = (int) t[6];
            double spiralStrength= t[7];

            double s = (capBaseY - wy) / length;
            if (s < 0 || s > 1.0) continue;


            double centerX = rootX + dirX * tentacleBend * s;
            double centerZ = rootZ + dirZ * tentacleBend * s;


            if (spiralType != SPIRAL_STRAIGHT) {
                double spiralAngle = GOLDEN_ANGLE * s * spiralStrength * 4.0;

                if (spiralType == SPIRAL_IN) spiralAngle = -spiralAngle;
                // SPIRAL_OUT: положительный угол — закрутка наружу

                // Перпендикуляр к dirX/dirZ для поперечного смещения
                double perpX = -dirZ;
                double perpZ =  dirX;
                double spiralDx = perpX * Math.sin(spiralAngle) * tentacleBend * s;
                double spiralDz = perpZ * Math.sin(spiralAngle) * tentacleBend * s;
                centerX += spiralDx;
                centerZ += spiralDz;
            }

            double tentR    = baseR + (TENTACLE_TIP_R - baseR) * s;
            double noiseAmp = tentR * 0.4;

            double dx = (wx - centerX) + tentXZ.unitNx * noiseAmp;
            double dz = (wz - centerZ) + tentXZ.unitNz * noiseAmp;

            if (dx * dx + dz * dz <= tentR * tentR) return true;
        }
        return false;
    }

    private static void logHole(int chunkX, int chunkZ,
                                int wx, int wy, int wz,
                                int cx, int cz, String reason) {
        if (holeWarnCount.incrementAndGet() > MAX_HOLE_WARNINGS) return;
        LOGGER.warn("[AeroWorld][L4-JELLY] ДЫРА chunk=[{},{}] block=({},{},{}) island=({},{}) | {}",
                chunkX, chunkZ, wx, wy, wz, cx, cz, reason);
    }

    public int getCapTopY(int wx, int wz, IslandData d) {
        int capBaseY = d.bottomY + d.height() / 3;

        // Быстрый XZ-reject: проверяем хотя бы один Y (верхушку)
        CapXZCache capXZ = precomputeCapXZ(wx, wz);
        if (!isCapSolid(wx, d.topY, wz, d.cx, d.cz, capBaseY, d.topY, d.radius, capXZ)) {
            return d.bottomY - 1;
        }

        // Бинарный поиск: наибольший Y где isCapSolid = true
        int lo = capBaseY, hi = d.topY;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (isCapSolid(wx, mid, wz, d.cx, d.cz, capBaseY, d.topY, d.radius, capXZ)) lo = mid;
            else hi = mid - 1;
        }
        // Убеждаемся что Y+1 — воздух
        if (isCapSolid(wx, lo + 1, wz, d.cx, d.cz, capBaseY, d.topY, d.radius, capXZ)) {
            return d.bottomY - 1;
        }
        return lo;
    }


    public int getCapBottomY(int wx, int wz, IslandData d) {
        int capBaseY = d.bottomY + d.height() / 3;
        CapXZCache capXZ = precomputeCapXZ(wx, wz);

        if (!isCapSolid(wx, capBaseY, wz, d.cx, d.cz, capBaseY, d.topY, d.radius, capXZ)) {
            return d.topY + 1;
        }

        int lo = capBaseY, hi = d.topY;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (isCapSolid(wx, mid, wz, d.cx, d.cz, capBaseY, d.topY, d.radius, capXZ)) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }


}