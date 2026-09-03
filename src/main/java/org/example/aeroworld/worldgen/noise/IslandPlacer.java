package org.example.aeroworld.worldgen.noise;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.example.aeroworld.worldgen.cache.ChunkKey;

/**
 * IslandPlacer — deterministic, seed-based island placement grid.
 *
 * Each layer defines a grid cell size (in chunks). For each cell, we check
 * whether an island spawns there, and if so, where its center is within the cell.
 * This ensures minimum spacing between islands and 100% deterministic generation.
 *
 * <p>Координаты центров упакованы в {@code long} через {@link ChunkKey#of(int,int)}:
 * старшие 32 бита — blockX, младшие 32 — blockZ. Нулевые аллокации на горячем пути.</p>
 *
 * <p><b>Архипелаги (Layer 2):</b> {@link #ARCHIPELAGO_CHANCE} ячеек, где вообще
 * спавнится остров, вместо одиночного острова превращаются в архипелаг: один
 * центральный остров (тот же центр ячейки) + {@link #SATELLITE_MIN}–{@link #SATELLITE_MAX}
 * спутников вдвое меньшего размера вокруг него. Определение "это архипелаг?" и
 * "это спутник?" детерминировано по координатам центра ячейки/острова —
 * {@link #isArchipelagoCentre} и {@link #getSatellitesForCentre} можно вызывать
 * из любого места без побочных эффектов и без хранения состояния.</p>
 */
public class IslandPlacer {

    /** Sentinel: ячейка пуста (остров не спавнится). */
    public static final long NO_ISLAND = Long.MIN_VALUE;

    /** Доля островных ячеек, которые становятся архипелагом вместо одиночного острова. */
    public static final double ARCHIPELAGO_CHANCE = 0.25;

    /** Минимум/максимум спутников вокруг центра архипелага. */
    public static final int SATELLITE_MIN = 5;
    public static final int SATELLITE_MAX = 6;

    /** Во сколько раз уменьшен радиус/высота центра архипелага относительно обычного острова. */
    public static final double ARCHIPELAGO_SCALE = 0.5;

    /**
     * Во сколько раз уменьшен радиус/высота спутника относительно центра архипелага
     * (а не относительно обычного острова). Итоговый масштаб спутника от обычного
     * острова — {@code ARCHIPELAGO_SCALE * SATELLITE_SCALE} = 0.15.
     */
    public static final double SATELLITE_SCALE = 0.3;

    /**
     * Расстояние от центра архипелага до спутника, в блоках (кольцо вокруг центра).
     * Оба значения должны быть меньше {@code Layer2Settings.bridgeMaxRange} (по
     * умолчанию 80) — иначе гарантированный мост спутник↔центр может не попасть
     * в AABB-фильтр раннего отброса чанков в {@code LowerIslandGenerator.fillChunk}.
     */
    private static final double SATELLITE_DIST_MIN = 40.0;
    private static final double SATELLITE_DIST_MAX = 70.0;

    private final long worldSeed;
    private final int  gridSizeChunks;   // minimum spacing in chunks between island centres
    private final double spawnChance;    // 0.0 – 1.0, probability an island spawns in a cell
    private final boolean archipelagosEnabled;
    private final double maxRadiusEstimate; // worst-case остров-радиус этого слоя, для anti-overlap сдвига

    /** Минимальный гарантированный зазор между краями (не центрами) соседних островов, блоков. */
    private static final double MIN_ISLAND_GAP = 24.0;

    /**
     * @param worldSeed       the world seed
     * @param gridSizeChunks  cell size in chunks; islands are at most 1 per cell
     * @param spawnChance     probability [0,1] that a cell actually has an island
     */
    public IslandPlacer(long worldSeed, int gridSizeChunks, double spawnChance) {
        this(worldSeed, gridSizeChunks, spawnChance, false, 0.0);
    }

    /**
     * @param worldSeed           the world seed
     * @param gridSizeChunks      cell size in chunks; islands are at most 1 per cell
     * @param spawnChance         probability [0,1] that a cell actually has an island
     * @param archipelagosEnabled если true — часть островных ячеек ({@link #ARCHIPELAGO_CHANCE})
     *                            превращаются в архипелаг (центр + спутники). Используется
     *                            только Layer 2 — Layer 3/4 создают {@code IslandPlacer} без
     *                            этого флага (по умолчанию false), чтобы не получить лишние
     *                            точки-спутники, о которых их собственная генерация формы не знает.
     */
    public IslandPlacer(long worldSeed, int gridSizeChunks, double spawnChance, boolean archipelagosEnabled) {
        this(worldSeed, gridSizeChunks, spawnChance, archipelagosEnabled, 0.0);
    }

    /**
     * @param worldSeed           the world seed
     * @param gridSizeChunks      cell size in chunks; islands are at most 1 per cell
     * @param spawnChance         probability [0,1] that a cell actually has an island
     * @param archipelagosEnabled см. {@link #IslandPlacer(long, int, double, boolean)}
     * @param maxRadiusEstimate   worst-case радиус острова этого слоя (обычно {@code cfg.maxRadius()}).
     *                            Используется ТОЛЬКО для anti-overlap отталкивания центров соседних
     *                            занятых ячеек друг от друга (см. {@link #getCentreForCell}) — точный
     *                            радиус конкретного острова вычисляется позже отдельным шумом в
     *                            {@code LowerIslandGenerator.computeIslandData} и здесь неизвестен,
     *                            поэтому берём консервативную верхнюю оценку. {@code 0.0} отключает
     *                            отталкивание (используется старыми вызывающими без anti-overlap).
     */
    public IslandPlacer(long worldSeed, int gridSizeChunks, double spawnChance, boolean archipelagosEnabled,
                        double maxRadiusEstimate) {
        this.worldSeed           = worldSeed;
        this.gridSizeChunks      = gridSizeChunks;
        this.spawnChance         = spawnChance;
        this.archipelagosEnabled = archipelagosEnabled;
        this.maxRadiusEstimate   = maxRadiusEstimate;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all island centres (packed blockX/blockZ as {@code long} via
     * {@link ChunkKey#of}) that are relevant for the chunk at (chunkX, chunkZ).
     * Includes islands from neighbouring cells whose radius could reach into this chunk.
     *
     * <p>Для архипелажных ячеек включает центр архипелага и всех его спутников
     * (см. {@link #getSatellitesForCentre}) — вызывающему коду не нужно знать
     * об архипелагах, он просто получает больше "островов" на ту же ячейку.</p>
     *
     * <p>Zero heap allocations on the hot path: {@link LongArrayList} is a primitive
     * {@code long[]} wrapper from fastutil (already on classpath via Minecraft).</p>
     *
     * @param chunkX      chunk coordinate X
     * @param chunkZ      chunk coordinate Z
     * @param searchRadius extra cell radius to check for large islands (usually 1–2)
     */
    public LongArrayList getIslandCentresForChunk(int chunkX, int chunkZ, int searchRadius) {
        LongArrayList result = new LongArrayList();

        int cellX = Math.floorDiv(chunkX, gridSizeChunks);
        int cellZ = Math.floorDiv(chunkZ, gridSizeChunks);

        for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
            for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
                long centre = getCentreForCell(cellX + dcx, cellZ + dcz);
                if (centre == NO_ISLAND) continue;
                result.add(centre);

                if (isArchipelagoCentre(centre)) {
                    long[] satellites = getSatellitesForCentre(centre);
                    for (long sat : satellites) result.add(sat);
                }
            }
        }
        return result;
    }

    /**
     * Returns the island centre packed as {@code long} ({@link ChunkKey#of(int,int)})
     * for the given grid cell, or {@link #NO_ISLAND} if no island spawns there.
     *
     * <p><b>Anti-overlap отталкивание:</b> если {@code maxRadiusEstimate > 0}, после
     * вычисления "сырой" (raw) позиции центра в ячейке она сдвигается прочь от
     * сырых позиций всех непосредственно соседних занятых ячеек (в кольце 3×3),
     * если расстояние между ними меньше {@code maxRadiusEstimate*2 + MIN_ISLAND_GAP}
     * (консервативная оценка: оба острова — worst-case максимального радиуса
     * слоя). Раньше сетка гарантировала только {@code margin} чанков от края
     * ЯЧЕЙКИ, что НЕ гарантирует зазор между самими островами — два соседних
     * острова у общей границы ячеек с радиусами, близкими к {@code maxRadius},
     * визуально наплывали друг на друга (особенно заметно на архипелагах
     * {@code dense_archipelago}, где {@code grid_chunks=10}, {@code max_radius=60}).</p>
     *
     * <p>Отталкивание НЕ рекурсивно: соседи берутся по их СЫРЫМ (нескорректированным)
     * позициям — иначе потребовался бы неограниченный каскад пересчётов (сосед
     * отталкивается от своего соседа, который отталкивается от текущей ячейки, и
     * так далее). Раз каждая соседняя ячейка тоже применяет то же самое
     * отталкивание СИММЕТРИЧНО (та же формула, тот же порог) относительно СВОИХ
     * соседей — включая текущую, — на практике зазор соблюдается с обеих сторон:
     * после однократного взаимного сдвига оба острова уже не ближе половины
     * требуемого зазора друг к другу, а обычно дальше, т.к. сдвиг применяется в
     * направлении "прочь от всех соседей сразу" (векторная сумма), а не только
     * от одного конкретного.</p>
     */
    public long getCentreForCell(int cellX, int cellZ) {
        long raw = rawCentreForCell(cellX, cellZ);
        if (raw == NO_ISLAND) return NO_ISLAND;
        if (maxRadiusEstimate <= 0.0) return raw;

        int rawX = ChunkKey.x(raw);
        int rawZ = ChunkKey.z(raw);

        double minCentreDist = maxRadiusEstimate * 2.0 + MIN_ISLAND_GAP;

        double pushX = 0.0, pushZ = 0.0;
        boolean anyNeighbour = false;

        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                if (dcx == 0 && dcz == 0) continue;
                long neighbourRaw = rawCentreForCell(cellX + dcx, cellZ + dcz);
                if (neighbourRaw == NO_ISLAND) continue;

                int nx = ChunkKey.x(neighbourRaw);
                int nz = ChunkKey.z(neighbourRaw);

                // Если сосед — центр архипелага, его реально занятая область — не
                // точка, а диск радиусом до SATELLITE_DIST_MAX (там, где сидят
                // спутники), плюс их собственный worst-case радиус. Без этого
                // обычный остров соседней ячейки отталкивался бы только от точки
                // ЦЕНТРА архипелага и продолжал бы наплывать на его спутников —
                // именно баг со скриншота (обычные острова перекрывают спутников).
                double neighbourEffectiveRadius = maxRadiusEstimate;
                if (archipelagosEnabled && isArchipelagoCentre(neighbourRaw)) {
                    neighbourEffectiveRadius = SATELLITE_DIST_MAX
                            + maxRadiusEstimate * ARCHIPELAGO_SCALE * SATELLITE_SCALE;
                }
                double requiredDist = maxRadiusEstimate + neighbourEffectiveRadius + MIN_ISLAND_GAP;

                double dx = rawX - nx;
                double dz = rawZ - nz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist >= requiredDist || dist < 1e-6) continue;

                // Вклад тем сильнее, чем ближе к порогу нарушен зазор; нормализованное направление "прочь".
                double weight = (requiredDist - dist) / requiredDist;
                pushX += (dx / dist) * weight;
                pushZ += (dz / dist) * weight;
                anyNeighbour = true;
            }
        }

        if (!anyNeighbour) return raw;

        double pushLen = Math.sqrt(pushX * pushX + pushZ * pushZ);
        if (pushLen < 1e-6) return raw; // силы взаимно погасились (симметричный случай) — оставляем как есть

        // Сдвигаем на величину, достаточную для устранения худшего нарушения, но не
        // выходя за пределы своей ячейки (иначе легко залезть в ЕЩЁ одну чужую).
        int cellMinX = cellX * gridSizeChunks * 16;
        int cellMinZ = cellZ * gridSizeChunks * 16;
        int cellMaxX = cellMinX + gridSizeChunks * 16 - 1;
        int cellMaxZ = cellMinZ + gridSizeChunks * 16 - 1;

        double maxShift = (gridSizeChunks * 16) * 0.5; // не более половины ячейки за один проход
        double shiftX = (pushX / pushLen) * Math.min(maxShift, (maxRadiusEstimate * 2.0 + MIN_ISLAND_GAP) * 0.5);
        double shiftZ = (pushZ / pushLen) * Math.min(maxShift, (maxRadiusEstimate * 2.0 + MIN_ISLAND_GAP) * 0.5);

        int newX = clamp((int) Math.round(rawX + shiftX), cellMinX, cellMaxX);
        int newZ = clamp((int) Math.round(rawZ + shiftZ), cellMinZ, cellMaxZ);

        return ChunkKey.of(newX, newZ);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Нескорректированная (до anti-overlap отталкивания) позиция центра ячейки — детерминирована только по хэшу. */
    private long rawCentreForCell(int cellX, int cellZ) {
        long hash = hash(cellX, cellZ);

        // Spawn chance check
        double chance = ((hash >>> 1) & 0xFFFFFFL) / (double) 0xFFFFFFL;
        if (chance > spawnChance) return NO_ISLAND;

        // Place centre randomly within the cell, but not right at edges
        int margin  = 2; // chunks from cell edge
        int range   = gridSizeChunks - margin * 2;
        if (range <= 0) range = 1;

        int offsetChunksX = margin + (int)(((hash >> 24) & 0xFFL) % range);
        int offsetChunksZ = margin + (int)(((hash >> 32) & 0xFFL) % range);

        int blockX = (cellX * gridSizeChunks + offsetChunksX) * 16 + 8;
        int blockZ = (cellZ * gridSizeChunks + offsetChunksZ) * 16 + 8;

        return ChunkKey.of(blockX, blockZ);
    }

    // ── Архипелаги ────────────────────────────────────────────────────────────

    /**
     * Является ли остров с данным (упакованным) центром центром архипелага.
     * Детерминировано по координатам центра — не зависит от порядка вызовов.
     * Спутники сами по себе архипелагом НЕ являются (isArchipelagoCentre(satellite) == false),
     * поэтому рекурсии в getIslandCentresForChunk не возникает.
     */
    public boolean isArchipelagoCentre(long packedCentre) {
        if (!archipelagosEnabled) return false;
        int cx = ChunkKey.x(packedCentre);
        int cz = ChunkKey.z(packedCentre);
        long h = archipelagoHash(cx, cz);
        double roll = ((h >>> 1) & 0xFFFFFFL) / (double) 0xFFFFFFL;
        return roll < ARCHIPELAGO_CHANCE;
    }

    /**
     * Детерминированно определяет, является ли остров с данным центром спутником
     * архипелага с центром в {@code archipelagoCentreCx/Cz}. Используется вызывающим
     * кодом (например, для мостов), которому нужно узнать принадлежность конкретной
     * пары точек, а не сгенерировать список заново.
     */
    public boolean isSameArchipelago(int cx, int cz, int archipelagoCentreCx, int archipelagoCentreCz) {
        long centre = ChunkKey.of(archipelagoCentreCx, archipelagoCentreCz);
        if (!isArchipelagoCentre(centre)) return false;
        for (long sat : getSatellitesForCentre(centre)) {
            if (ChunkKey.x(sat) == cx && ChunkKey.z(sat) == cz) return true;
        }
        return false;
    }

    /**
     * Возвращает упакованные координаты 5–6 спутников архипелага вокруг центра
     * {@code packedCentre}. Детерминировано и без побочных эффектов — при каждом
     * вызове с тем же центром возвращает тот же набор точек (порядок и координаты).
     *
     * <p>Спутники размещаются по кольцу вокруг центра со случайным (но
     * детерминированным) угловым распределением и расстоянием в диапазоне
     * [{@link #SATELLITE_DIST_MIN}, {@link #SATELLITE_DIST_MAX}].</p>
     */
    public long[] getSatellitesForCentre(long packedCentre) {
        int centreX = ChunkKey.x(packedCentre);
        int centreZ = ChunkKey.z(packedCentre);

        long baseHash = archipelagoHash(centreX, centreZ) ^ 0x5A7E177EL;
        int count = SATELLITE_MIN + (int) ((baseHash >>> 40) % (SATELLITE_MAX - SATELLITE_MIN + 1));

        long[] result = new long[count];
        double angleStep = (Math.PI * 2.0) / count;

        for (int i = 0; i < count; i++) {
            long h = satelliteHash(centreX, centreZ, i);

            // Угол: базовое равномерное распределение + джиттер, чтобы спутники
            // не стояли идеально ровным кольцом.
            double jitter = (((h >>> 8) & 0xFFFFL) / (double) 0xFFFFL - 0.5) * angleStep * 0.6;
            double angle = angleStep * i + jitter;

            double distRoll = ((h >>> 24) & 0xFFFFFFL) / (double) 0xFFFFFFL;
            double dist = SATELLITE_DIST_MIN + distRoll * (SATELLITE_DIST_MAX - SATELLITE_DIST_MIN);

            int satX = centreX + (int) Math.round(Math.cos(angle) * dist);
            int satZ = centreZ + (int) Math.round(Math.sin(angle) * dist);

            result[i] = ChunkKey.of(satX, satZ);
        }
        return result;
    }

    /**
     * Возвращает упакованные координаты центра архипелага, спутником которого
     * является остров {@code (cx, cz)}, или {@link #NO_ISLAND} если это не спутник
     * (обычный остров или центр архипелага сам по себе).
     *
     * <p>Перебирает соседние ячейки сетки в радиусе {@code searchRadius} (то же
     * значение, что используется в {@code getIslandCentresForChunk}), поскольку
     * центр архипелага для спутника может лежать в соседней ячейке.</p>
     */
    public long findArchipelagoCentreFor(int cx, int cz, int searchRadius) {
        int cellX = Math.floorDiv(cx, gridSizeChunks * 16);
        int cellZ = Math.floorDiv(cz, gridSizeChunks * 16);

        for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
            for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
                long centre = getCentreForCell(cellX + dcx, cellZ + dcz);
                if (centre == NO_ISLAND || !isArchipelagoCentre(centre)) continue;
                for (long sat : getSatellitesForCentre(centre)) {
                    if (ChunkKey.x(sat) == cx && ChunkKey.z(sat) == cz) return centre;
                }
            }
        }
        return NO_ISLAND;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Размер ячейки сетки в чанках. Нужен внешнему коду (напр. debug-командам) для спирального поиска. */
    public int gridSizeChunks() { return gridSizeChunks; }

    /** Fast integer hash combining cell coords and world seed. */
    private long hash(int cellX, int cellZ) {
        long h = worldSeed
                ^ ((long) cellX * 341873128712L)
                ^ ((long) cellZ * 132897987541L);
        h = h ^ (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h = h ^ (h >>> 27);
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** Отдельная хэш-соль для решения "архипелаг ли эта ячейка" — не пересекается с spawnChance-хэшем. */
    private long archipelagoHash(int cx, int cz) {
        long h = worldSeed
                ^ ((long) cx * 668265263L)
                ^ ((long) cz * 2246822519L)
                ^ 0xA5C7112EA1L;
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    /** Хэш для конкретного спутника (индекс i) вокруг центра (centreX, centreZ). */
    private long satelliteHash(int centreX, int centreZ, int i) {
        long h = worldSeed
                ^ ((long) centreX * 341873128712L)
                ^ ((long) centreZ * 132897987541L)
                ^ ((long) i * 2654435761L)
                ^ 0x9E3779B97F4A7C15L;
        h = h ^ (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h = h ^ (h >>> 27);
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}