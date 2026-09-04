package org.example.aeroworld.worldgen.structure;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1FlatGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Полная валидация размещения структур в измерении AeroWorld.
 *
 * <h3>Проблема</h3>
 * Ванильный {@code ChunkGenerator.createStructures} размещает структуры
 * без учёта кастомного рельефа: деревни падают в пустоту между слоями,
 * шахты оказываются в воздухе на Y=300, аванпосты висят над Islands Layer 2.
 *
 * <h3>Как это работает</h3>
 * Перехватываем {@code AeroWorldChunkGenerator#createStructures} и для каждой
 * {@code StructureStart} запускаем валидацию <i>до</i> того, как структура
 * будет записана в чанк. Если валидация не пройдена — структура отбрасывается.
 *
 * <h3>Логика по категориям</h3>
 * <ul>
 *   <li><b>DENY</b> — отклонить сразу.</li>
 *   <li><b>WATER</b> — отклонить (нет океана в AeroWorld).</li>
 *   <li><b>VOID_GAP</b> — структура в пустоте между слоями → отклонить.</li>
 *   <li><b>SURFACE</b> — проверить что ≥70% точек сетки имеют опору на Layer 1 (Y ≤ LAYER_MAX_Y, с горами).</li>
 *   <li><b>ISLAND</b> — проверить что ≥65% точек имеют остров снизу (Layer 2–4).</li>
 *   <li><b>UNDERGROUND</b> — проверить что ≥80% точек внутри твёрдого рельефа.</li>
 *   <li><b>SKY_FLOATING</b> — минимум 1 остров в радиусе 96 блоков + нет коллизий.</li>
 * </ul>
 *
 * <h3>Потокобезопасность</h3>
 * {@link TerrainColumnSampler} создаётся заново для каждого вызова validate()
 * — нет общего состояния между вызовами.
 */
public final class StructureSupportValidator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Пороги поддержки ──────────────────────────────────────────────────────
    /** Мин. доля опорных точек для наземных структур (деревни, аванпосты) */
    private static final double SURFACE_SUPPORT_THRESHOLD  = 0.70;
    /** Мин. доля опорных точек для островных структур */
    private static final double ISLAND_SUPPORT_THRESHOLD   = 0.65;
    /** Мин. доля опорных точек для подземных структур */
    private static final double UNDERGROUND_THRESHOLD      = 0.80;

    // ── Параметры сетки сэмплов ───────────────────────────────────────────────
    /** Шаг сетки сэмплирования по XZ (блоков) */
    private static final int SAMPLE_STEP = TerrainColumnSampler.SAMPLE_GRID_STEP;
    /** Максимум сохраняемых failing-сэмплов для лога */
    private static final int MAX_FAILING_LOGGED = 8;

    // ── Логирование ───────────────────────────────────────────────────────────
    private static final boolean LOG_ACCEPTED  = true; // включено для диагностики: подземные/подводные деревни проходят молча
    private static final boolean LOG_REJECTED  = true;

    private final Layer1FlatGenerator  layer1;
    private final LowerIslandGenerator layer2;
    private final HighIslandGenerator  layer3;
    private final UpperIslandGenerator layer4;
    private final ChunkIslandCache     sharedChunkCache;

    private final java.util.Map<StructureStart, ValidationResult> validatedCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public StructureSupportValidator(Layer1FlatGenerator  layer1,
                                     LowerIslandGenerator layer2,
                                     HighIslandGenerator  layer3,
                                     UpperIslandGenerator layer4,
                                     ChunkIslandCache     sharedChunkCache) {
        this.layer1             = layer1;
        this.layer2            = layer2;
        this.layer3            = layer3;
        this.layer4            = layer4;
        this.sharedChunkCache  = sharedChunkCache;
    }

    // ── Главная точка входа ───────────────────────────────────────────────────

    public interface Layer1HeightSampler {
        int getHeight(int x, int z, net.minecraft.world.level.levelgen.Heightmap.Types type);
    }

    /**
     * Валидирует размещение структуры.
     *
     * @param structureId id структуры (например, {@code minecraft:village})
     * @param start       {@link StructureStart} с BoundingBox
     * @param heightSampler сэмплер высот Layer 1 (из vanillaGenerator)
     * @return {@link ValidationResult} — итог с диагностикой
     */
    public ValidationResult validate(ResourceLocation structureId, StructureStart start,
                                     Layer1HeightSampler heightSampler) {
        return validate(structureId, start, null, heightSampler);
    }

    /**
     * Перегрузка с доступом к реальному уровню мира.
     */
    public ValidationResult validate(ResourceLocation structureId, StructureStart start,
                                     net.minecraft.world.level.WorldGenLevel realLevel,
                                     Layer1HeightSampler heightSampler) {
        if (start == null || !start.isValid()) {
            return ValidationResult.denied(structureId, StructureCategory.DENY,
                    start != null ? start.getBoundingBox() : BoundingBox.infinite());
        }

        ValidationResult cached = validatedCache.get(start);
        if (cached != null) return cached;

        BoundingBox bounds  = start.getBoundingBox();
        int baseY           = bounds.minY();

        // Сэмплер создаём раньше, чем раньше — теперь он нужен уже на этапе
        // определения категории, а не только для проверки поддержки.
        TerrainColumnSampler sampler = new TerrainColumnSampler(layer1, layer2, layer3, layer4, sharedChunkCache, realLevel, heightSampler);

        // ── ИСПРАВЛЕНИЕ: категория по фактическому слою в XZ-точке, а не по
        //    сырому baseY. Раньше resolveForY(structureId, baseY) полагался
        //    только на диапазон Y, из-за чего структуры Layer 1 (деревни,
        //    аванпосты) при определённых heightmap-значениях ошибочно
        //    классифицировались как ISLAND и отстраивались на островах
        //    Layer 2 (и потенциально 3/4). См. javadoc
        //    StructureCategoryResolver.resolveForActualLayer(). ──────────────
        int cx = (bounds.minX() + bounds.maxX()) / 2;
        int cz = (bounds.minZ() + bounds.maxZ()) / 2;
        int actualLayer = sampler.resolveActualLayer(cx, cz);
        StructureCategory category = StructureCategoryResolver.resolveForActualLayer(structureId, actualLayer);

        // ── 1. Жёсткое отклонение ─────────────────────────────────────────────
        if (category == StructureCategory.DENY) {
            logRejection(structureId, bounds, "структура в deny-списке");
            return cacheAndReturn(start, ValidationResult.denied(structureId, category, bounds));
        }

        // ── 2. Водные структуры — Layer 1 имеет полноценные океаны (WATER_LEVEL,
        //    см. getSeaLevel()), поэтому больше не отклоняются безусловно.
        //    Требуем твёрдое дно/опору под подошвой структуры (тот же порог,
        //    что и для наземных SURFACE), только если фактический слой — Layer 1
        //    (actualLayer == 1) или не определён (actualLayer < 0, часто бывает
        //    прямо на воде, где сэмплер не видит тверди в толще над дном).
        if (category == StructureCategory.WATER) {
            if (actualLayer == 2 || actualLayer == 3 || actualLayer == 4) {
                logRejection(structureId, bounds, "водная структура попала на небесный остров");
                return cacheAndReturn(start, ValidationResult.waterStructure(structureId, bounds));
            }
            // Подводные структуры (ocean_monument) стоят на дне, а bounds.minY()
            // может быть на несколько блоков выше самого дна (толща воды над
            // основанием монумента) — SUPPORT_SCAN_DEPTH=6 в hasSolidBelow этого
            // не всегда достаёт. Сканируем от maxY бокса вниз, глубина скана там
            // покрывает всю высоту структуры + запас.
            return cacheAndReturn(start, sampleSupport(structureId, StructureCategory.WATER, bounds, sampler,
                    bounds.maxY(), SURFACE_SUPPORT_THRESHOLD));
        }

        // ── 3. Пустота между слоями — структура гарантированно в воздухе ──────
        //    actualLayer == -1 уже покрывает большинство случаев пустоты, но
        //    оставляем и старую проверку по Y как дополнительную страховку
        //    (например, если структура сама по себе большая и её центр по XZ
        //    зацепил остров, а minY всё равно в пустоте).
        if (actualLayer < 0 && StructureCategoryResolver.isVoidGapY(baseY)) {
            logRejection(structureId, bounds,
                    String.format("Y=%d в пустоте между слоями (фактический слой не найден)", baseY));
            return cacheAndReturn(start, ValidationResult.voidGap(structureId, category, bounds));
        }

        // ── 4. Специфичная логика по категории ────────────────────────────────
        ValidationResult result = switch (category) {
            case SURFACE      -> validateSurface(structureId, bounds, sampler);
            case ISLAND       -> validateIsland(structureId, bounds, sampler);
            case UNDERGROUND  -> validateUnderground(structureId, bounds, sampler);
            case SKY_FLOATING -> validateSkyFloating(structureId, bounds, sampler);
            default           -> validateSurface(structureId, bounds, sampler);
        };

        if (result.accepted && LOG_ACCEPTED) {
        }
        if (!result.accepted && LOG_REJECTED) {
            if (!result.failingSamples.isEmpty()) {
            }
        }

        return cacheAndReturn(start, result);
    }

    private ValidationResult cacheAndReturn(StructureStart start, ValidationResult result) {
        if (validatedCache.size() > 4096) {
            validatedCache.clear();
        }
        validatedCache.put(start, result);
        return result;
    }

    // ── Валидация SURFACE ─────────────────────────────────────────────────────

    /**
     * Проверяет наземные структуры (Layer 1, Y ≤ Layer1FlatGenerator.LAYER_MAX_Y).
     * Требует твёрдую землю под подошвой структуры.
     */
    private ValidationResult validateSurface(ResourceLocation id, BoundingBox bounds,
                                             TerrainColumnSampler sampler) {
        // ИСПРАВЛЕНО (деревни/аванпосты стоят на воде): sampleSupport сама по
        // себе проверяет только твёрдость грунта (hasSolidBelow → дно), но не
        // видит воду НАД этим грунтом на уровне подошвы структуры. Структура,
        // чей minY пришёлся на уровень озера/океана (typical для village
        // jigsaw-старта на WORLD_SURFACE_WG, который включает воду), проходила
        // проверку — дно под водой твёрдое. Дополнительно считаем точки,
        // залитые водой на bounds.minY(), как "несупортированные" — тем самым
        // деревня, чей фундамент оказался на глади воды, теперь отклоняется.
        int waterCovered = 0;
        int total        = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += TerrainColumnSampler.SAMPLE_GRID_STEP) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += TerrainColumnSampler.SAMPLE_GRID_STEP) {
                total++;
                if (sampler.isWaterCoveredAt(x, z, bounds.minY())) waterCovered++;
            }
        }
        if (total > 0 && (double) waterCovered / total > (1.0 - SURFACE_SUPPORT_THRESHOLD)) {
            logRejection(id, bounds,
                    String.format("наземная структура на воде: %d/%d точек залито", waterCovered, total));
            return ValidationResult.insufficientSupport(id, StructureCategory.SURFACE, bounds,
                    total - waterCovered, total,
                    (double) (total - waterCovered) / total, SURFACE_SUPPORT_THRESHOLD, List.of());
        }

        return sampleSupport(id, StructureCategory.SURFACE, bounds, sampler,
                bounds.minY(), SURFACE_SUPPORT_THRESHOLD);
    }

    // ── Валидация ISLAND ──────────────────────────────────────────────────────

    /**
     * Проверяет структуры на небесных островах (Layer 2–4).
     * Требует остров под подошвой структуры.
     */
    private ValidationResult validateIsland(ResourceLocation id, BoundingBox bounds,
                                            TerrainColumnSampler sampler) {
        return sampleSupport(id, StructureCategory.ISLAND, bounds, sampler,
                bounds.minY(), ISLAND_SUPPORT_THRESHOLD);
    }

    // ── Валидация UNDERGROUND ─────────────────────────────────────────────────

    /**
     * Проверяет подземные структуры.
     * Требует что структура находится внутри твёрдого рельефа, а не в пустоте.
     * Проверяем и верх (minY) и середину структуры — чтобы шахта не висела в воздухе.
     */
    private ValidationResult validateUnderground(ResourceLocation id, BoundingBox bounds,
                                                 TerrainColumnSampler sampler) {
        // Для подземных структур сканируем от середины по высоте
        int scanY = (bounds.minY() + bounds.maxY()) / 2;

        // Используем Layer 1 — подземные структуры только там
        // Проверка: scanY должен быть внутри слоя 1
        if (scanY > Layer1FlatGenerator.LAYER_MAX_Y) {
            logRejection(id, bounds,
                    String.format("подземная структура выше Layer 1 (Y=%d)", scanY));
            return ValidationResult.insufficientSupport(id, StructureCategory.UNDERGROUND,
                    bounds, 0, 1, 0, UNDERGROUND_THRESHOLD, List.of());
        }

        return sampleSupport(id, StructureCategory.UNDERGROUND, bounds, sampler,
                scanY, UNDERGROUND_THRESHOLD);
    }

    // ── Валидация SKY_FLOATING ────────────────────────────────────────────────

    /**
     * Проверяет парящие в воздухе структуры.
     * Принимает если:
     *   1. В радиусе 96 блоков есть хотя бы 1 остров (структура "принадлежит" миру)
     *   2. Нет коллизии с рельефом (зазор ≥ 8 блоков)
     */
    private ValidationResult validateSkyFloating(ResourceLocation id, BoundingBox bounds,
                                                 TerrainColumnSampler sampler) {
        int cx = (bounds.minX() + bounds.maxX()) / 2;
        int cz = (bounds.minZ() + bounds.maxZ()) / 2;

        int nearbyIslands = sampler.countNearbyIslands(cx, cz);
        boolean hasNearby = nearbyIslands >= 1;

        // Подсчёт коллизий по сетке подошвы
        int collisions  = 0;
        int total       = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += SAMPLE_STEP) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += SAMPLE_STEP) {
                total++;
                if (sampler.hasCollision(x, z, bounds.minY())) collisions++;
            }
        }
        boolean noColl = collisions == 0;

        boolean accepted  = hasNearby && noColl;
        int supported     = total - collisions;
        double ratio      = total > 0 ? (double) supported / total : 1.0;

        return ValidationResult.skyFloating(id, bounds, accepted,
                nearbyIslands, collisions, supported, total, ratio);
    }

    // ── Общий механизм сэмплирования ─────────────────────────────────────────

    /**
     * Сэмплирует сетку точек подошвы структуры и считает долю поддержанных.
     *
     * @param scanFromY Y, от которого сканировать вниз
     * @param threshold минимальная доля для принятия
     */
    private ValidationResult sampleSupport(ResourceLocation id,
                                           StructureCategory category,
                                           BoundingBox bounds,
                                           TerrainColumnSampler sampler,
                                           int scanFromY,
                                           double threshold) {
        int supported = 0;
        int total     = 0;
        List<SupportSample> failing = new ArrayList<>();

        for (int x = bounds.minX(); x <= bounds.maxX(); x += SAMPLE_STEP) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += SAMPLE_STEP) {
                total++;
                if (sampler.hasSolidBelow(x, z, scanFromY)) {
                    supported++;
                } else if (failing.size() < MAX_FAILING_LOGGED) {
                    failing.add(new SupportSample(x, z));
                }
            }
        }

        if (total == 0) {
            // BoundingBox меньше шага сетки — проверяем центр
            int cx = (bounds.minX() + bounds.maxX()) / 2;
            int cz = (bounds.minZ() + bounds.maxZ()) / 2;
            total = 1;
            if (sampler.hasSolidBelow(cx, cz, scanFromY)) {
                supported = 1;
            } else {
                failing.add(new SupportSample(cx, cz));
            }
        }

        double ratio = (double) supported / total;
        boolean ok   = ratio >= threshold;

        if (ok) {
            return ValidationResult.accepted(id, category, bounds,
                    supported, total, ratio, threshold);
        } else {
            return ValidationResult.insufficientSupport(id, category, bounds,
                    supported, total, ratio, threshold, failing);
        }
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private static void logRejection(ResourceLocation id, BoundingBox bounds, String reason) {
        if (LOG_REJECTED) {
        }
    }

    private static String formatBounds(BoundingBox b) {
        return String.format("[%d,%d,%d]->[%d,%d,%d]",
                b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }
}