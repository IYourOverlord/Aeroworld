package org.example.aeroworld.worldgen.structure;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
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
 *   <li><b>SURFACE</b> — проверить что ≥70% точек сетки имеют опору на Layer 1 (Y≤50).</li>
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
    private static final boolean LOG_ACCEPTED  = false; // включить для отладки
    private static final boolean LOG_REJECTED  = true;

    private final LowerIslandGenerator layer2;
    private final HighIslandGenerator  layer3;
    private final UpperIslandGenerator layer4;
    private final ChunkIslandCache     sharedChunkCache;

    public StructureSupportValidator(LowerIslandGenerator layer2,
                                     HighIslandGenerator  layer3,
                                     UpperIslandGenerator layer4,
                                     ChunkIslandCache     sharedChunkCache) {
        this.layer2            = layer2;
        this.layer3            = layer3;
        this.layer4            = layer4;
        this.sharedChunkCache  = sharedChunkCache;
    }

    // ── Главная точка входа ───────────────────────────────────────────────────

    /**
     * Валидирует размещение структуры.
     *
     * @param structureId id структуры (например, {@code minecraft:village})
     * @param start       {@link StructureStart} с BoundingBox
     * @return {@link ValidationResult} — итог с диагностикой
     */
    public ValidationResult validate(ResourceLocation structureId, StructureStart start) {
        if (!start.isValid()) {
            return ValidationResult.denied(structureId, StructureCategory.DENY,
                    start.getBoundingBox());
        }

        BoundingBox bounds  = start.getBoundingBox();
        int baseY           = bounds.minY();
        StructureCategory category = StructureCategoryResolver.resolveForY(structureId, baseY);

        // ── 1. Жёсткое отклонение ─────────────────────────────────────────────
        if (category == StructureCategory.DENY) {
            logRejection(structureId, bounds, "структура в deny-списке");
            return ValidationResult.denied(structureId, category, bounds);
        }

        // ── 2. Водные структуры — нет смысла в AeroWorld ──────────────────────
        if (category == StructureCategory.WATER) {
            logRejection(structureId, bounds, "водная структура, нет океана в AeroWorld");
            return ValidationResult.waterStructure(structureId, bounds);
        }

        // ── 3. Пустота между слоями — структура гарантированно в воздухе ──────
        if (StructureCategoryResolver.isVoidGapY(baseY)) {
            logRejection(structureId, bounds,
                    String.format("Y=%d в пустоте между слоями", baseY));
            return ValidationResult.voidGap(structureId, category, bounds);
        }

        // ── 4. Специфичная логика по категории ────────────────────────────────
        TerrainColumnSampler sampler = new TerrainColumnSampler(layer2, layer3, layer4, sharedChunkCache);

        ValidationResult result = switch (category) {
            case SURFACE      -> validateSurface(structureId, bounds, sampler);
            case ISLAND       -> validateIsland(structureId, bounds, sampler);
            case UNDERGROUND  -> validateUnderground(structureId, bounds, sampler);
            case SKY_FLOATING -> validateSkyFloating(structureId, bounds, sampler);
            default           -> validateSurface(structureId, bounds, sampler);
        };

        if (result.accepted && LOG_ACCEPTED) {
            LOGGER.debug("[AeroWorld][StructureVal] ПРИНЯТО {} @ {} ratio={} ({}/{})",
                    structureId,
                    formatBounds(bounds),
                    String.format("%.2f", result.supportRatio),
                    result.supportedSamples,
                    result.totalSamples);
        }
        if (!result.accepted && LOG_REJECTED) {
            LOGGER.warn("[AeroWorld][StructureVal] ОТКЛОНЕНО {} [{}] @ {} причина={} ratio={}/{} ({}% < {}%)",
                    structureId, category, formatBounds(bounds),
                    result.rejectionReason,
                    result.supportedSamples, result.totalSamples,
                    String.format("%.0f", result.supportRatio * 100),
                    String.format("%.0f", result.requiredRatio * 100));
            if (!result.failingSamples.isEmpty()) {
                LOGGER.warn("[AeroWorld][StructureVal]   первые несупортированные точки: {}",
                        result.failingSamples.stream()
                                .map(s -> "(" + s.x() + "," + s.z() + ")")
                                .toList());
            }
        }

        return result;
    }

    // ── Валидация SURFACE ─────────────────────────────────────────────────────

    /**
     * Проверяет наземные структуры (Layer 1, Y ≤ 50).
     * Требует твёрдую землю под подошвой структуры.
     */
    private ValidationResult validateSurface(ResourceLocation id, BoundingBox bounds,
                                             TerrainColumnSampler sampler) {
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

        // Используем Layer 1 — подземные структуры только там (Y ≤ 50)
        // Проверка: scanY должен быть внутри слоя 1
        if (scanY > 50) {
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
            LOGGER.warn("[AeroWorld][StructureVal] ОТКЛОНЕНО {} @ {} — {}",
                    id, formatBounds(bounds), reason);
        }
    }

    private static String formatBounds(BoundingBox b) {
        return String.format("[%d,%d,%d]->[%d,%d,%d]",
                b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }
}