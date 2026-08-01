package org.example.aeroworld.worldgen.structure;

import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1FlatGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверяет наличие твёрдой основы под заданной XZ-позицией на заданном Y.
 *
 * <h3>Почему нельзя читать мир напрямую</h3>
 * Валидация вызывается из {@code createStructures}, когда чанки ещё не
 * сгенерированы (находятся в статусе STRUCTURE_STARTS или EMPTY).
 * Читать блоки из незагруженного чанка нельзя — это вызовет загрузку
 * чанка или вернёт VOID.
 *
 * <h3>Решение</h3>
 * Используем те же генераторы слоёв, которые строят рельеф, и спрашиваем:
 * «Будет ли здесь твёрдый блок на Y=baseY-1?» — это детерминировано и
 * не зависит от состояния мира.
 *
 * <h3>Оптимизация (пункты M, N, O, P)</h3>
 * Прежде: {@code computeIslandTopY} итерировался по всем Y от LAYER_MAX_Y до
 * LAYER_MIN_Y (до 331 итераций), на каждом шаге вызывая {@code isSolidAt},
 * который внутри дёргал {@code getIslandCentresForChunk} напрямую через
 * {@link org.example.aeroworld.worldgen.noise.IslandPlacer} — без кэша.
 * Для Layer 2 searchRadius=8: (2×8+1)²=289 hash-вычислений на каждый вызов.
 * Итого на одну XZ-точку: 331 × 289 ≈ <b>95 659 hash-вычислений</b>.
 *
 * <p>Теперь:
 * <ol>
 *   <li>Список центров получается через {@link ChunkIslandCache} — одно
 *       вычисление на (layerId, chunkX, chunkZ), остальные — мгновенные хиты.</li>
 *   <li>{@link IslandData} (bottomY, topY, radius) берётся из {@code IslandCache}
 *       генераторов — тоже только хиты после первого обращения.</li>
 *   <li>{@code computeIslandTopY} не итерируется по Y вообще: проверяет
 *       XZ-покрытие точки каждым островом-кандидатом и возвращает {@code topY}
 *       первого совпадения. O(n) по числу ближайших островов, а не O(height×n).</li>
 * </ol>
 *
 * <p>Кэш колонок (supportCache, topYCache) живёт в пределах одного вызова
 * валидации (один {@code StructureStart}) — не занимает постоянную память.
 */
public final class TerrainColumnSampler {

    // Глубина поиска опоры вниз от подошвы структуры (блоков)
    private static final int SUPPORT_SCAN_DEPTH = 6;

    // Шаг сетки сэмплов по XZ (блоков)
    public static final int SAMPLE_GRID_STEP = 8;

    // Радиус поиска ближайших островов (для SKY_FLOATING)
    private static final int SKY_NEARBY_RADIUS = 96;
    private static final int SKY_NEARBY_STEP   = 16;

    // Минимальный зазор для SKY_FLOATING (структура не должна касаться рельефа)
    private static final int SKY_MIN_CLEARANCE = 8;

    private final Layer1FlatGenerator  layer1;
    private final LowerIslandGenerator layer2;
    private final HighIslandGenerator  layer3;
    private final UpperIslandGenerator layer4;

    /**
     * Общий ChunkIslandCache — те же кэшированные списки центров, что
     * используются при fillChunk. В фазе createStructures они уже могут
     * быть заполнены (если чанк в процессе генерации) или вычислятся здесь
     * и станут доступны fillChunk позже — без повторных hash-вычислений.
     */
    private final ChunkIslandCache sharedChunkCache;

    // Кэш: key = packKey(x,z,fromY) → есть ли твёрдая поверхность на [y-DEPTH, y]
    private final Map<Long, Boolean> supportCache = new HashMap<>();
    // Кэш: key = packKey(x,z,0) → Y верхней поверхности острова (или -1)
    private final Map<Long, Integer> topYCache    = new HashMap<>();

    public TerrainColumnSampler(Layer1FlatGenerator  layer1,
                                LowerIslandGenerator layer2,
                                HighIslandGenerator  layer3,
                                UpperIslandGenerator layer4,
                                ChunkIslandCache     sharedChunkCache) {
        this.layer1            = layer1;
        this.layer2           = layer2;
        this.layer3           = layer3;
        this.layer4           = layer4;
        this.sharedChunkCache = sharedChunkCache;
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Есть ли твёрдый блок в колонке (wx, wz) в диапазоне [fromY - depth, fromY]?
     * Используется для проверки опоры структур Layer 2–4.
     */
    public boolean hasSolidBelow(int wx, int wz, int fromY) {
        long key = packKey(wx, wz, fromY);
        return supportCache.computeIfAbsent(key, k -> computeHasSolid(wx, wz, fromY));
    }

    /**
     * Возвращает Y верхней поверхности острова в колонке (wx, wz), или -1 если
     * острова нет. Используется для проверки коллизий SKY_FLOATING структур.
     */
    public int getIslandTopY(int wx, int wz) {
        long key = packKey(wx, wz, 0);
        return topYCache.computeIfAbsent(key, k -> computeIslandTopY(wx, wz));
    }

    /**
     * Есть ли хотя бы один остров в радиусе {@link #SKY_NEARBY_RADIUS} блоков
     * от центра (cx, cz)? Используется для SKY_FLOATING валидации.
     */
    public int countNearbyIslands(int cx, int cz) {
        int count = 0;
        for (int x = cx - SKY_NEARBY_RADIUS; x <= cx + SKY_NEARBY_RADIUS; x += SKY_NEARBY_STEP) {
            for (int z = cz - SKY_NEARBY_RADIUS; z <= cz + SKY_NEARBY_RADIUS; z += SKY_NEARBY_STEP) {
                if (getIslandTopY(x, z) >= 0) count++;
            }
        }
        return count;
    }

    /**
     * Есть ли коллизия структуры (minY) с рельефом в точке (wx, wz)?
     * Коллизия — остров слишком близко сверху (зазор < SKY_MIN_CLEARANCE).
     */
    public boolean hasCollision(int wx, int wz, int structureMinY) {
        int topY = getIslandTopY(wx, wz);
        if (topY < 0) return false;
        return structureMinY - topY < SKY_MIN_CLEARANCE;
    }

    // ── Внутренние вычисления ─────────────────────────────────────────────────

    private boolean computeHasSolid(int wx, int wz, int fromY) {
        int layer = getLayerForY(fromY);
        for (int dy = 0; dy <= SUPPORT_SCAN_DEPTH; dy++) {
            int y = fromY - dy;
            if (isSolidAt(wx, y, wz, layer)) return true;
        }
        return false;
    }

    /**
     * Возвращает topY острова, который покрывает точку (wx, wz), или -1.
     *
     * <p><b>Было:</b> до 331 итераций по Y × 289 hash-вычислений на каждую =
     * ~95 000 операций на точку.
     *
     * <p><b>Стало:</b> один вызов {@code getIslandCentresForChunk} через
     * {@link ChunkIslandCache} (мгновенный хит после первого вычисления) +
     * для каждого острова-кандидата — одно обращение к {@link IslandData}
     * (тоже хит) + простая арифметика. O(n) по числу островов.
     */
    private int computeIslandTopY(int wx, int wz) {
        int chunkX = wx >> 4;
        int chunkZ = wz >> 4;

        // ── Layer 2 ───────────────────────────────────────────────────────────
        {
            List<int[]> centres = sharedChunkCache.get(
                    LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer2.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer2.getSearchRadius()));
            for (int[] c : centres) {
                IslandData d = layer2.getIslandData(c[0], c[1]);
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) {
                    return d.topY;
                }
            }
        }

        // ── Layer 3 ───────────────────────────────────────────────────────────
        {
            List<int[]> centres = sharedChunkCache.get(
                    HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer3.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer3.getSearchRadius()));
            for (int[] c : centres) {
                IslandData d = layer3.getIslandData(c[0], c[1]);
                // Layer 3 — эллипсоид; для грубой XZ-проверки достаточно
                // максимальной горизонтальной полуоси (ax или az).
                double effR = (d.ellipsoidAxes != null)
                        ? Math.max(d.ellipsoidAxes[0], d.ellipsoidAxes[2])
                        : d.radius;
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= effR * effR) {
                    return d.topY;
                }
            }
        }

        // ── Layer 4 ───────────────────────────────────────────────────────────
        {
            List<int[]> centres = sharedChunkCache.get(
                    UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer4.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer4.getSearchRadius()));
            for (int[] c : centres) {
                IslandData d = layer4.getIslandData(c[0], c[1]);
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) {
                    return d.topY;
                }
            }
        }

        // ── Layer 1: реальная высота по колонке (горы/холмы), не константа ────
        int surfY = layer1.surfaceHeight(wx, wz);
        if (isSolidAt(wx, surfY, wz, 1)) {
            return surfY;
        }

        return -1;
    }

    /**
     * Детерминированная проверка — будет ли блок твёрдым в данной позиции
     * после генерации?
     *
     * <p>Для Layer 1 используется напрямую (фиксированная Y-граница).
     * Для Layer 2–4 вызывается только из {@code computeHasSolid} при поиске
     * опоры ({@code hasSolidBelow}), где нужна точная проверка конкретного Y —
     * там тоже используем кэш через {@link ChunkIslandCache} + {@link IslandData}.
     */
    private boolean isSolidAt(int wx, int y, int wz, int layer) {
        switch (layer) {
            case 1: return y <= layer1.surfaceHeight(wx, wz) && y >= Layer1FlatGenerator.LAYER_MIN_Y;
            case 2: return isLayer2Solid(wx, y, wz);
            case 3: return isLayer3Solid(wx, y, wz);
            case 4: return isLayer4Solid(wx, y, wz);
            default: return false;
        }
    }

    /**
     * Проверяет, твёрд ли блок Layer 2 в точке (wx, y, wz).
     *
     * <p><b>Было:</b> прямой вызов {@code placer.getIslandCentresForChunk} — 289
     * hash-вычислений каждый раз, плюс два отдельных вызова {@code getIslandYBounds}
     * и {@code getIslandRadius} (два обращения к islandCache вместо одного).
     *
     * <p><b>Стало:</b> список центров — через {@link ChunkIslandCache} (хит),
     * данные острова — через {@link LowerIslandGenerator#getIslandData} (хит).
     * Одно обращение к IslandData вместо двух.
     */
    private boolean isLayer2Solid(int wx, int y, int wz) {
        if (y < LowerIslandGenerator.LAYER_MIN_Y || y > LowerIslandGenerator.LAYER_MAX_Y) return false;
        int chunkX = wx >> 4, chunkZ = wz >> 4;
        List<int[]> centres = sharedChunkCache.get(
                LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer2.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer2.getSearchRadius()));
        for (int[] c : centres) {
            IslandData d = layer2.getIslandData(c[0], c[1]);
            if (y < d.bottomY || y > d.topY) continue;
            double dx = wx - d.cx, dz = wz - d.cz;
            if (dx * dx + dz * dz <= d.radius * d.radius) return true;
        }
        return false;
    }

    /**
     * Проверяет, твёрд ли блок Layer 3 в точке (wx, y, wz).
     *
     * <p>Для эллипсоида при grub-проверке используем максимальную горизонтальную
     * полуось (ax или az) — это консервативная оценка (может дать false positive,
     * но никогда false negative). Для точной валидации опоры структур этого достаточно.
     */
    private boolean isLayer3Solid(int wx, int y, int wz) {
        if (y < HighIslandGenerator.LAYER_MIN_Y || y > HighIslandGenerator.LAYER_MAX_Y) return false;
        int chunkX = wx >> 4, chunkZ = wz >> 4;
        List<int[]> centres = sharedChunkCache.get(
                HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer3.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer3.getSearchRadius()));
        for (int[] c : centres) {
            IslandData d = layer3.getIslandData(c[0], c[1]);
            if (y < d.bottomY || y > d.topY) continue;
            double effR = (d.ellipsoidAxes != null)
                    ? Math.max(d.ellipsoidAxes[0], d.ellipsoidAxes[2])
                    : d.radius;
            double dx = wx - d.cx, dz = wz - d.cz;
            if (dx * dx + dz * dz <= effR * effR) return true;
        }
        return false;
    }

    /**
     * Проверяет, твёрд ли блок Layer 4 в точке (wx, y, wz).
     *
     * <p>Щупальца (tentacleData) при грубой проверке опоры структуры не учитываются —
     * они занимают небольшой объём ниже шапки и не являются надёжной опорой.
     * Проверяем только по горизонтальному радиусу шапки.
     */
    private boolean isLayer4Solid(int wx, int y, int wz) {
        if (y < UpperIslandGenerator.LAYER_MIN_Y || y > UpperIslandGenerator.LAYER_MAX_Y) return false;
        int chunkX = wx >> 4, chunkZ = wz >> 4;
        List<int[]> centres = sharedChunkCache.get(
                UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer4.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer4.getSearchRadius()));
        for (int[] c : centres) {
            IslandData d = layer4.getIslandData(c[0], c[1]);
            if (y < d.bottomY || y > d.topY) continue;
            double dx = wx - d.cx, dz = wz - d.cz;
            if (dx * dx + dz * dz <= d.radius * d.radius) return true;
        }
        return false;
    }

    private static int getLayerForY(int y) {
        if (y <= Layer1FlatGenerator.LAYER_MAX_Y) return 1;
        if (y <= 420)  return 2;
        if (y <= 1120) return 3;
        return 4;
    }

    private static long packKey(int x, int z, int y) {
        // x: 21 бит, z: 21 бит, y: 22 бит — вполне хватает для диапазона -64..2031
        return ((long)(x & 0x1FFFFF) << 43)
                | ((long)(z & 0x1FFFFF) << 22)
                | (y & 0x3FFFFF);
    }
}
