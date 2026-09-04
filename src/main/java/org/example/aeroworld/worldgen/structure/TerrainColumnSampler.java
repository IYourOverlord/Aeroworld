package org.example.aeroworld.worldgen.structure;

import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1FlatGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
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

    // Глубина поиска опоры вниз от подошвы структуры (блоков).
    // УВЕЛИЧЕНО с 6 до 24: в Amplified-режиме 3D-пещеры вырезают полости
    // существенно глубже, чем плоский рельеф старого Layer1FlatGenerator
    // предполагал изначально. createStructures вызывается ДО applyCarvers,
    // поэтому detected surfaceHeight/topmostHeight ещё не отражают будущие
    // пещеры под зданием — на практике деревня, чей фундамент придётся на
    // потолок будущей пещеры, проваливается после карвинга. Расширенный скан
    // не устраняет 100% случаев (глубокие пещеры всё ещё возможны), но
    // отсеивает подавляющее большинство тонких перекрытий над кавернами.
    private static final int SUPPORT_SCAN_DEPTH = 24;

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
     * Реальный уровень мира (доступен только из failsafe-пути в
     * {@code applyBiomeDecoration}, где applyCarvers для стартового чанка
     * структуры уже отработал). Если задан — hasSolidBelow/isWaterCoveredAt
     * для Layer 1 читают ФАКТИЧЕСКИЕ блоки чанка вместо детерминированного
     * предсказания через layer1.surfaceHeight()/topmostHeight(). Это
     * устраняет принципиальный класс ложных accept: детерминированная
     * формула не знает о 3D-пещерах Amplified (они вычисляются отдельным
     * density-function пайплайном в applyCarvers, не воспроизводимым здесь
     * дёшево), поэтому "опора" под деревней могла считаться твёрдой, хотя
     * там уже вырезана пещера. Может быть {@code null} — тогда используется
     * старое детерминированное поведение (например, из createStructures,
     * где мир ещё не сгенерирован и блоки читать нельзя).
     */
    private final net.minecraft.world.level.WorldGenLevel realLevel;

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
                                ChunkIslandCache     sharedChunkCache,
                                StructureSupportValidator.Layer1HeightSampler heightSampler) {
        this(layer1, layer2, layer3, layer4, sharedChunkCache, null, heightSampler);
    }

    private final StructureSupportValidator.Layer1HeightSampler heightSampler;

    public TerrainColumnSampler(Layer1FlatGenerator  layer1,
                                LowerIslandGenerator layer2,
                                HighIslandGenerator  layer3,
                                UpperIslandGenerator layer4,
                                ChunkIslandCache     sharedChunkCache,
                                net.minecraft.world.level.WorldGenLevel realLevel,
                                StructureSupportValidator.Layer1HeightSampler heightSampler) {
        this.layer1            = layer1;
        this.layer2           = layer2;
        this.layer3           = layer3;
        this.layer4           = layer4;
        this.sharedChunkCache = sharedChunkCache;
        this.realLevel        = realLevel;
        this.heightSampler    = heightSampler;
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
     * Затоплена ли колонка (wx, wz) на уровне {@code atY}, то есть находится ли
     * {@code atY} строго между грунтом ({@link Layer1FlatGenerator#surfaceHeight})
     * и фактическим верхом воды/суши ({@link Layer1FlatGenerator#topmostHeight})?
     * Если да — значит на этой высоте стоит вода, а не воздух/земля, и наземная
     * структура (деревня, аванпост) не должна тут строиться, даже если ниже по
     * колонке есть твёрдое дно.
     *
     * <p>Используется только для категории SURFACE — WATER-структурам вода
     * наоборот необходима, ISLAND/UNDERGROUND её не касаются (Layer 1 water
     * есть только на самом Layer 1).
     */
    public boolean isWaterCoveredAt(int wx, int wz, int atY) {
        if (realLevel != null && isChunkGenerated(wx, wz)) {
            try {
                net.minecraft.world.level.block.state.BlockState state =
                        realLevel.getBlockState(new net.minecraft.core.BlockPos(wx, atY, wz));
                return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            } catch (RuntimeException e) {
                // Позиция вне окна WorldGenRegion — откатываемся ниже.
            }
        }
        int ground = heightSampler.getHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG);
        int top    = heightSampler.getHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG);
        return top > ground && atY > ground && atY <= top;
    }

    /**
     * {@code true}, если чанк, содержащий (wx, wz), уже сгенерирован
     * достаточно далеко (минимум FEATURES/carvers применены), чтобы можно
     * было безопасно читать его блоки через {@link #realLevel} без риска
     * триггернуть повторную генерацию или получить неполный рельеф.
     */
    private boolean isChunkGenerated(int wx, int wz) {
        if (realLevel == null) return false;
        try {
            net.minecraft.world.level.chunk.ChunkAccess ca = realLevel.getChunk(wx >> 4, wz >> 4,
                    net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, false);
            if (ca == null) return false;
            return ca.getPersistedStatus().isOrAfter(
                    net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES);
        } catch (RuntimeException e) {
            // WorldGenRegion ограничен окном чанков вокруг текущего центра —
            // запрос чанка за пределами этого окна может бросить исключение
            // вместо null. В таком случае считаем чанк недоступным для чтения
            // и откатываемся на детерминированное предсказание.
            return false;
        }
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

    /**
     * Определяет, какому слою РЕАЛЬНО принадлежит точка (wx, wz) — по факту
     * наличия острова в XZ-проекции, а не по диапазону Y структуры.
     *
     * <p><b>Зачем.</b> Раньше категория (SURFACE/ISLAND) определялась только
     * по {@code baseY} структуры ({@code isIslandLayerY}): если ванильный
     * heightmap в данной XZ-точке случайно выдавал Y, попадающий в диапазон
     * Layer 2/3/4 (даже с запасом LAYER_MARGIN), структура Layer 1 (деревня,
     * аванпост и т.п.) ошибочно классифицировалась как ISLAND и проходила
     * валидацию против рельефа острова, который в этой точке физически есть,
     * а не против того, для которого структура предназначалась. Из-за этого
     * структуры, ожидаемые на Layer 1, отстраивались на островах Layer 2 (и
     * потенциально 3/4).
     *
     * <p><b>Как чинит.</b> Используем ту же кэшированную логику XZ-покрытия,
     * что и {@link #computeIslandTopY} (Layer 2 → 3 → 4 → Layer 1) — без
     * дополнительных проходов по миру, все обращения идут через уже
     * прогретый {@link ChunkIslandCache} / {@code IslandCache} генераторов.
     * Возвращает номер слоя (1–4), под которым реально находится твердь
     * в этой XZ-точке, либо -1 если ни один слой не покрывает точку (пустота
     * между слоями/за пределами острова).
     */
    public int resolveActualLayer(int wx, int wz) {
        int chunkX = wx >> 4;
        int chunkZ = wz >> 4;

        // ── Layer 2 ───────────────────────────────────────────────────────────
        {
            LongArrayList centres = sharedChunkCache.get(
                    LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer2.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer2.getSearchRadius()));
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = layer2.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) {
                    return 2;
                }
            }
        }

        // ── Layer 3 ───────────────────────────────────────────────────────────
        {
            LongArrayList centres = sharedChunkCache.get(
                    HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer3.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer3.getSearchRadius()));
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = layer3.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double effR = (d.ellipsoidAxes != null)
                        ? Math.max(d.ellipsoidAxes[0], d.ellipsoidAxes[2])
                        : d.radius;
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= effR * effR) {
                    return 3;
                }
            }
        }

        // ── Layer 4 ───────────────────────────────────────────────────────────
        {
            LongArrayList centres = sharedChunkCache.get(
                    UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer4.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer4.getSearchRadius()));
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = layer4.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) {
                    return 4;
                }
            }
        }

        // ── Layer 1: реальная высота по колонке (горы/холмы) ───────────────────
        int surfY = heightSampler.getHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG);
        if (isSolidAt(wx, surfY, wz, 1)) {
            return 1;
        }

        return -1;
    }

    // ── Внутренние вычисления ─────────────────────────────────────────────────

    private boolean computeHasSolid(int wx, int wz, int fromY) {
        int layer = getLayerForY(fromY);
        int minY = fromY - SUPPORT_SCAN_DEPTH;

        // Для Layer 2–4: быстрая проверка пересечения диапазона [fromY - SUPPORT_SCAN_DEPTH, fromY]
        // с геометрией острова напрямую за один проход по списку островов без поблочного цикла.
        switch (layer) {
            case 2: return hasLayer2SolidInRange(wx, wz, minY, fromY);
            case 3: return hasLayer3SolidInRange(wx, wz, minY, fromY);
            case 4: return hasLayer4SolidInRange(wx, wz, minY, fromY);
            case 1:
            default:
                for (int y = fromY; y >= minY; y--) {
                    if (isSolidAt(wx, y, wz, 1)) return true;
                }
                return false;
        }
    }

    /**
     * Быстрая конусная аппроксимация Layer 2: проверяет, пересекает ли диапазон [minY, maxY]
     * тело острова в точке (wx, wz).
     */
    private boolean hasLayer2SolidInRange(int wx, int wz, int minY, int maxY) {
        if (maxY < LowerIslandGenerator.LAYER_MIN_Y || minY > LowerIslandGenerator.LAYER_MAX_Y) return false;
        int chunkX = wx >> 4, chunkZ = wz >> 4;
        LongArrayList centres = sharedChunkCache.get(
                LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer2.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer2.getSearchRadius()));
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = layer2.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
            if (minY > d.topY || maxY < d.bottomY) continue;
            double dx = wx - d.cx, dz = wz - d.cz;
            double distSq = dx * dx + dz * dz;
            if (distSq > d.radius * d.radius) continue;

            // Конусная аппроксимация формы острова: радиус сужается книзу.
            // Проверяем верхнюю доступную точку интервала (maxY в пределах острова).
            int testY = Math.min(maxY, d.topY);
            double t = (double) (testY - d.bottomY) / (double) Math.max(1, d.topY - d.bottomY);
            double coneR = d.radius * Math.max(0.1, t);
            if (distSq <= coneR * coneR) return true;
        }
        return false;
    }

    /**
     * Быстрая эллипсоидная аппроксимация Layer 3: проверяет диапазон [minY, maxY].
     */
    private boolean hasLayer3SolidInRange(int wx, int wz, int minY, int maxY) {
        if (maxY < HighIslandGenerator.LAYER_MIN_Y || minY > HighIslandGenerator.LAYER_MAX_Y) return false;
        int chunkX = wx >> 4, chunkZ = wz >> 4;
        LongArrayList centres = sharedChunkCache.get(
                HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer3.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer3.getSearchRadius()));
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = layer3.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
            if (minY > d.topY || maxY < d.bottomY) continue;
            double ax = (d.ellipsoidAxes != null) ? d.ellipsoidAxes[0] : d.radius;
            double ay = (d.ellipsoidAxes != null) ? d.ellipsoidAxes[1] : (d.topY - d.bottomY) * 0.5;
            double az = (d.ellipsoidAxes != null) ? d.ellipsoidAxes[2] : d.radius;
            double cy = (d.topY + d.bottomY) * 0.5;

            double dx = (wx - d.cx) / Math.max(1.0, ax);
            double dz = (wz - d.cz) / Math.max(1.0, az);
            double horiz = dx * dx + dz * dz;
            if (horiz > 1.0) continue;

            int testY = Math.max(minY, Math.min(maxY, (int) Math.round(cy)));
            double dy = (testY - cy) / Math.max(1.0, ay);
            if (horiz + dy * dy <= 1.0) return true;
        }
        return false;
    }

    /**
     * Быстрая купольная аппроксимация Layer 4: проверяет диапазон [minY, maxY].
     */
    private boolean hasLayer4SolidInRange(int wx, int wz, int minY, int maxY) {
        if (maxY < UpperIslandGenerator.LAYER_MIN_Y || minY > UpperIslandGenerator.LAYER_MAX_Y) return false;
        int chunkX = wx >> 4, chunkZ = wz >> 4;
        LongArrayList centres = sharedChunkCache.get(
                UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer4.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer4.getSearchRadius()));
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = layer4.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
            if (minY > d.topY || maxY < d.bottomY) continue;
            double dx = wx - d.cx, dz = wz - d.cz;
            if (dx * dx + dz * dz <= d.radius * d.radius) return true;
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
            LongArrayList centres = sharedChunkCache.get(
                    LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer2.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer2.getSearchRadius()));
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = layer2.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) {
                    return d.topY;
                }
            }
        }

        // ── Layer 3 ───────────────────────────────────────────────────────────
        {
            LongArrayList centres = sharedChunkCache.get(
                    HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer3.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer3.getSearchRadius()));
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = layer3.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
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
            LongArrayList centres = sharedChunkCache.get(
                    UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                    key -> layer4.getPlacer().getIslandCentresForChunk(
                            chunkX, chunkZ, layer4.getSearchRadius()));
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = layer4.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = wx - d.cx, dz = wz - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) {
                    return d.topY;
                }
            }
        }

        // ── Layer 1: реальная высота по колонке (горы/холмы), не константа ────
        int surfY = heightSampler.getHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG);
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
            // ИСПРАВЛЕНО (деревни в пещерах/под водой проходят валидацию):
            // если реальный уровень доступен и чанк уже прошёл FEATURES
            // (значит applyCarvers для него уже применил 3D-пещеры Amplified),
            // читаем ФАКТИЧЕСКИЙ блок вместо детерминированного предсказания
            // через surfaceHeight() — детерминированная формула в принципе
            // не может знать, вырезал ли carver пещеру именно в этой точке.
            case 1: {
                if (realLevel != null && isChunkGenerated(wx, wz)) {
                    try {
                        net.minecraft.world.level.block.state.BlockState state =
                                realLevel.getBlockState(new net.minecraft.core.BlockPos(wx, y, wz));
                        return !state.isAir() && !state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                                && y >= Layer1FlatGenerator.LAYER_MIN_Y;
                    } catch (RuntimeException e) {
                        // Позиция вне окна WorldGenRegion — откатываемся на
                        // детерминированное предсказание ниже.
                    }
                }
                return y <= heightSampler.getHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG) && y >= Layer1FlatGenerator.LAYER_MIN_Y;
            }
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
        LongArrayList centres = sharedChunkCache.get(
                LowerIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer2.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer2.getSearchRadius()));
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = layer2.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
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
        LongArrayList centres = sharedChunkCache.get(
                HighIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer3.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer3.getSearchRadius()));
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = layer3.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
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
        LongArrayList centres = sharedChunkCache.get(
                UpperIslandGenerator.LAYER_ID, chunkX, chunkZ,
                key -> layer4.getPlacer().getIslandCentresForChunk(
                        chunkX, chunkZ, layer4.getSearchRadius()));
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = layer4.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
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