package org.example.aeroworld.worldgen.structure;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1FlatGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
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
    /**
     * Максимально допустимое отклонение реальной высоты рельефа
     * (heightSampler) от {@code bounds.minY()} структуры SURFACE в любой
     * точке сетки подошвы. {@code hasSolidBelow} сканирует твёрдость в
     * фиксированном окне [bounds.minY()-24, bounds.minY()], одинаковом для
     * всей структуры — на резком кастомном рельефе (горы, хребты) это
     * позволяет структуре набрать 70% "опорных" точек даже когда реальная
     * земля в части точек на 15-30 блоков выше или ниже подошвы, то есть
     * структура физически висит в воздухе или наполовину закопана. Проверка
     * разброса высоты — быстрая (один проход по уже вычисляемой сетке,
     * heightSampler кэширован в Layer1ColumnCache) и всегда выполняется до
     * дорогого sampleSupport, отсекая явно неровные случаи заранее.
     */
    private static final int SURFACE_MAX_HEIGHT_DEVIATION   = 6;

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

        // ── 2. Водные структуры — разрешаем только в океаническом Layer 1. ───
        if (category == StructureCategory.WATER) {
            if (actualLayer == 2 || actualLayer == 3 || actualLayer == 4) {
                logRejection(structureId, bounds, "водная структура попала на небесный остров");
                return cacheAndReturn(start, ValidationResult.waterStructure(structureId, bounds));
            }
            // Vanilla water structures already validate their biome and fluid volume while
            // creating the start. Their bounding boxes may span far above the ocean floor,
            // so a shallow support scan rejects valid monuments, ruins, and shipwrecks in
            // the custom deep-ocean profile. At this phase only prevent cross-layer starts.
            return cacheAndReturn(start, ValidationResult.accepted(structureId, StructureCategory.WATER,
                    bounds, 1, 1, 1.0, 1.0));
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

        // ── Проверка разброса рельефа под подошвой ────────────────────────────
        // Дешёвая (переиспользует heightSampler, уже кэшированный Layer1ColumnCache),
        // выполняется ДО дорогого sampleSupport и отсекает структуры, чья
        // жёсткая подошва (bounds.minY()) не совпадает с реальной высотой земли
        // хотя бы в одной точке сетки сильнее допустимого — именно это даёт
        // парящие/полузакопанные деревни и порталы на резком кастомном рельефе.
        List<SupportSample> unevenSamples = new ArrayList<>();
        int uneven = 0, heightTotal = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += SAMPLE_STEP) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += SAMPLE_STEP) {
                heightTotal++;
                int groundY = sampler.groundHeightAt(x, z);
                if (Math.abs(groundY - bounds.minY()) > SURFACE_MAX_HEIGHT_DEVIATION) {
                    uneven++;
                    if (unevenSamples.size() < MAX_FAILING_LOGGED) unevenSamples.add(new SupportSample(x, z));
                }
            }
        }
        if (heightTotal > 0 && (double) (heightTotal - uneven) / heightTotal < SURFACE_SUPPORT_THRESHOLD) {
            logRejection(id, bounds,
                    String.format("рельеф под подошвой слишком неровный: %d/%d точек вне допуска ±%d",
                            uneven, heightTotal, SURFACE_MAX_HEIGHT_DEVIATION));
            return ValidationResult.insufficientSupport(id, StructureCategory.SURFACE, bounds,
                    heightTotal - uneven, heightTotal,
                    (double) (heightTotal - uneven) / heightTotal, SURFACE_SUPPORT_THRESHOLD, unevenSamples);
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

        // Ancient City получает собственную ступенчатую островную платформу-опору в пещере
        if (id.getPath().contains("ancient_city")) {
            return ValidationResult.accepted(id, StructureCategory.UNDERGROUND, bounds, 1, 1, 1.0, UNDERGROUND_THRESHOLD);
        }

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

    // ── Пост-размещение: физическая зачистка уже построенных структур ─────────

    /**
     * Зачищает уже физически размещённые структуры, не прошедшие валидацию.
     *
     * <h3>Почему это нужно</h3>
     * {@code createStructures} инвалидирует {@link StructureStart} через
     * {@code StructureManager.setStartForStructure(..., INVALID_START, chunk)},
     * но эта запись живёт в {@code ChunkAccess}, полученном на статусе
     * STRUCTURE_STARTS/STRUCTURE_REFERENCES. Реальная постройка piece'ов
     * (jigsaw-куски деревень, части ruined_portal) происходит позже, на шаге
     * FEATURES, внутри {@code super.applyBiomeDecoration}, которое читает
     * старт заново из своего собственного {@code WorldGenRegion}/{@code ChunkAccess}
     * — инвалидация с предыдущего статуса на практике до него не долетает,
     * и структура строится как есть, несмотря на отклонение. Единственный
     * надёжный способ — проверить структуру ПОСЛЕ того как ваниль её уже
     * физически построила, и стереть то, что не прошло проверку.
     *
     * <h3>Как это работает</h3>
     * Вызывается из {@code applyBiomeDecoration} сразу после
     * {@code super.applyBiomeDecoration} (структуры уже в блоках чанка).
     * Для каждого {@code StructureStart}, пересекающего текущий чанк,
     * повторно валидируем через {@link #validate} (теперь с {@code realLevel},
     * что также включает более точную {@code isSolidAt}-проверку по факту
     * блоков вместо детерминированного предсказания). Если отклонена —
     * заменяем все блоки структуры, попадающие ТОЛЬКО в текущий чанк
     * (в пределах {@code BoundingBox} структуры), на воздух/воду в
     * зависимости от того, что было в колонке до структуры на Layer 1.
     *
     * <h3>Стоимость</h3>
     * Выполняется один раз на чанк, только если в чанке вообще есть старты
     * ({@code getAllStarts()} — тот же дешёвый вызов, что уже используется
     * в {@code createStructures}). Валидация пересчитывается заново (Java-объект
     * {@code StructureStart} на шаге FEATURES обычно другой инстанс, чем на
     * STRUCTURE_STARTS, поэтому {@code validatedCache} по ключу объекта не
     * даёт хита) — но сам расчёт дешёвый (см. {@code TerrainColumnSampler}
     * javadoc про O(n) вместо O(height×n)). Стирание блоков — только для
     * структур, реально отклонённых (редкий случай), не более чем
     * {@code BoundingBox} структуры пересечённый с текущим чанком (16×16
     * колонок максимум).
     */
    public void postPlacementCleanup(net.minecraft.world.level.WorldGenLevel region,
                                     net.minecraft.world.level.chunk.ChunkAccess chunk,
                                     net.minecraft.core.RegistryAccess registryAccess,
                                     Layer1HeightSampler heightSampler) {
        java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, StructureStart> allStarts =
                chunk.getAllStarts();
        if (allStarts.isEmpty()) return;

        net.minecraft.world.level.ChunkPos chunkPos = chunk.getPos();
        BoundingBox chunkBox = new BoundingBox(
                chunkPos.getMinBlockX(), region.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(), region.getMinBuildHeight() + region.getHeight() - 1, chunkPos.getMaxBlockZ());

        allStarts.forEach((structure, start) -> {
            if (start == null || start == StructureStart.INVALID_START || !start.isValid()) return;

            ResourceLocation structureId = registryAccess
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getKey(structure);
            if (structureId == null) return;

            ValidationResult result = validate(structureId, start, region, heightSampler);
            if (result.accepted) return;

            logRejection(structureId, start.getBoundingBox(),
                    "структура уже построена, но отклонена постфактум — зачистка блоков в чанке");
            eraseStructureInChunk(region, start, chunkBox, heightSampler);
        });
    }

    /**
     * Заменяет блоки всех {@code StructurePiece} структуры, пересекающие
     * {@code chunkBox}, на воздух (или воду ниже уровня моря на Layer 1).
     * Ограничено пересечением с текущим чанком — при повторных вызовах для
     * соседних чанков той же структуры остальная часть зачистится там же.
     */
    private void eraseStructureInChunk(net.minecraft.world.level.WorldGenLevel region,
                                       StructureStart start, BoundingBox chunkBox,
                                       Layer1HeightSampler heightSampler) {
        for (var piece : start.getPieces()) {
            BoundingBox pieceBox = piece.getBoundingBox();
            int minX = Math.max(pieceBox.minX(), chunkBox.minX());
            int maxX = Math.min(pieceBox.maxX(), chunkBox.maxX());
            int minY = Math.max(pieceBox.minY(), chunkBox.minY());
            int maxY = Math.min(pieceBox.maxY(), chunkBox.maxY());
            int minZ = Math.max(pieceBox.minZ(), chunkBox.minZ());
            int maxZ = Math.min(pieceBox.maxZ(), chunkBox.maxZ());
            if (minX > maxX || minY > maxY || minZ > maxZ) continue;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int groundY = heightSampler.getHeight(x, z,
                            net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG);
                    for (int y = minY; y <= maxY; y++) {
                        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                        net.minecraft.world.level.block.state.BlockState replacement =
                                (y <= groundY && y <= Layer1TerrainGenerator.SEA_LEVEL)
                                        ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                                        : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                        region.setBlock(pos, replacement, 2);
                    }
                }
            }
        }
    }



    private static void logRejection(ResourceLocation id, BoundingBox bounds, String reason) {
        if (LOG_REJECTED) {
        }
    }

    private static String formatBounds(BoundingBox b) {
        return String.format("[%d,%d,%d]->[%d,%d,%d]",
                b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }
}