package org.example.aeroworld.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.exampl.physical_structures.api.PhysicalStructures;
import org.exampl.physical_structures.api.PhysicalStructurePlacer.PlaceResult;
import org.exampl.physical_structures.api.provider.StructureSourceProviderRegistry;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.structure.PendingStructureData;
import org.example.aeroworld.structure.StructurePlacementHelper;
import org.example.aeroworld.structure.StructureSizeCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Тикает каждые {@value CHECK_INTERVAL} тиков в AeroWorld-измерении.
 *
 * <h3>Алгоритм спавна</h3>
 * <ol>
 *   <li>Сбрасываем очередь worldgen-потоков в SavedData.</li>
 *   <li>Для каждой pending-записи рядом с игроком:</li>
 *   <ol type="a">
 *     <li>Проверяем backoff — {@link PendingStructureData.Entry#isReadyToRetry}.</li>
 *     <li>Проверяем лимит попыток — {@link PendingStructureData.Entry#isExhausted}.</li>
 *     <li>Ищем реальную поверхность острова в живом мире — {@link #findRealSurface}.</li>
 *     <li>Читаем размер NBT из кеша — {@link StructureSizeCache#getSize}.</li>
 *     <li>Проверяем что все чанки зоны FULL — {@link StructurePlacementHelper#areChunksReady}.</li>
 *     <li>Проверяем что зона свободна — {@link StructurePlacementHelper#isSpaceClear}.</li>
 *     <li>Вызываем {@link PhysicalStructures#spawnStructureResult} и анализируем результат.</li>
 *   </ol>
 * </ol>
 *
 * <h3>Почему больше нет бесконечного retry</h3>
 * При любой неудаче запись обновляется через {@link PendingStructureData#replaceEntry},
 * увеличивая счётчик и сдвигая {@code nextRetryTick}. После {@value MAX_ATTEMPTS_LOG}-й
 * попытки запись удаляется навсегда.
 */
public final class ProximityTriggerHandler {

    // ── Константы ─────────────────────────────────────────────────────────────

    /** Как часто (тиков) проверять pending-список. */
    private static final int    CHECK_INTERVAL         = 20;      // 1 секунда

    /** XZ-расстояние (блоки) до игрока, при котором запись становится «активной». */
    private static final double TRIGGER_DISTANCE_XZ    = 96.0;
    private static final double TRIGGER_DISTANCE_XZ_SQ = TRIGGER_DISTANCE_XZ * TRIGGER_DISTANCE_XZ;

    /** Y-расстояние (блоки) до игрока. Layer 2 простирается на ~100 блоков — берём с запасом. */
    private static final double TRIGGER_DISTANCE_Y     = 128.0;

    /** Y-диапазон, в котором ищем поверхность острова Layer 2.
     *  Должен соответствовать LowerIslandGenerator.LAYER_MIN_Y..LAYER_MAX_Y (400..500).
     *  Небольшой запас (±10) на случай пограничных случаев carver'а/шума. */
    private static final int LAYER2_SURFACE_MIN = 390;
    private static final int LAYER2_SURFACE_MAX = 510;

    /** Y-диапазон, в котором ищем поверхность острова Layer 3 (High Islands). */
    private static final int LAYER3_SURFACE_MIN = 990;
    private static final int LAYER3_SURFACE_MAX = 1150;

    /** Для логирования — совпадает с Entry.MAX_ATTEMPTS, вынесено для читаемости. */
    private static final int MAX_ATTEMPTS_LOG = PendingStructureData.Entry.MAX_ATTEMPTS;

    // ── Event ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Работаем только в AeroWorld-измерении
        if (!level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false)) return;

        // 1. Перенести новые записи из worldgen-очереди в SavedData
        if (AeroWorld.structureScheduler != null) {
            AeroWorld.structureScheduler.flushToPersistence(level);
        }

        if (level.getGameTime() % CHECK_INTERVAL != 0) return;

        PendingStructureData data = PendingStructureData.getOrCreate(level);
        if (data.isEmpty()) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        long currentTick = level.getGameTime();

        // 2. Собрать записи, к которым достаточно близко стоит хотя бы один игрок
        List<PendingStructureData.Entry> nearby = new ArrayList<>();
        for (PendingStructureData.Entry entry : data.getAll()) {
            if (!entry.isReadyToRetry(currentTick)) continue; // backoff ещё не истёк

            BlockPos pos = entry.pos();
            for (ServerPlayer player : players) {
                double dxA = player.getX() - pos.getX();
                double dzA = player.getZ() - pos.getZ();
                double dyA = player.getY();
                boolean inXZ = dxA * dxA + dzA * dzA <= TRIGGER_DISTANCE_XZ_SQ;
                if (!inXZ) continue;
                // Layer 2 или Layer 3 — принимаем запись если игрок находится
                // в вертикальном окне любого из двух слоёв.
                boolean nearLayer2 = dyA >= LAYER2_SURFACE_MIN - TRIGGER_DISTANCE_Y
                        && dyA <= LAYER2_SURFACE_MAX + TRIGGER_DISTANCE_Y;
                boolean nearLayer3 = dyA >= LAYER3_SURFACE_MIN - TRIGGER_DISTANCE_Y
                        && dyA <= LAYER3_SURFACE_MAX + TRIGGER_DISTANCE_Y;
                if (nearLayer2 || nearLayer3) {
                    nearby.add(entry);
                    break;
                }
            }
        }

        // 3. Обработать каждую запись
        for (PendingStructureData.Entry entry : nearby) {
            tryPlace(level, entry, data, currentTick);
        }
    }

    // ── Принудительное массовое размещение (для прегенерации) ──────────────────

    /**
     * Немедленно обрабатывает <b>все</b> записи из {@link PendingStructureData},
     * игнорируя проверку дистанции до игрока и backoff между попытками.
     *
     * <h3>Зачем это нужно</h3>
     * <p>В обычном режиме структура физически попадает в чанк только тогда, когда
     * живой игрок оказывается в радиусе {@link #TRIGGER_DISTANCE_XZ} блоков
     * ({@link #onLevelTick}). При прегенерации через Chunky/C2ME игрок физически
     * не бывает рядом почти ни с одним из сгенерированных островов — поэтому
     * структуры остаются <b>незаписанными</b> в уже сохранённые чанки. Любой
     * сторонний инструмент, который читает эти чанки напрямую с диска
     * (например, импорт LOD-рендерера), увидит остров БЕЗ структуры — а когда
     * игрок впоследствии подлетит вживую, {@link #onLevelTick} допишет блоки
     * в уже импортированный чанк, и расхождение станет заметно визуально.</p>
     *
     * <h3>Когда вызывать</h3>
     * <p>Один раз, на сервере, сразу после того как Chunky/C2ME полностью
     * закончили прегенерацию региона — и <b>обязательно до</b> любого внешнего
     * импорта/снятия снимка с этих чанков. Рекомендуемая команда:
     * {@code /aeroworld forcePlacePending}.</p>
     *
     * <p>Так как Chunky обычно не держит все сгенерированные чанки
     * принудительно загруженными, метод сам подгружает (синхронно, на
     * server thread) окрестность каждой записи перед попыткой размещения.
     * Эта подгрузка — тяжёлая операция; не вызывать во время обычной игры.</p>
     *
     * @param level измерение AeroWorld
     * @return количество записей, для которых размещение завершилось успехом
     */
    public int forcePlaceAll(ServerLevel level) {
        // Перенести всё, что воркеры worldgen успели накопить, но ещё не сбросили
        if (AeroWorld.structureScheduler != null) {
            AeroWorld.structureScheduler.flushToPersistence(level);
        }

        PendingStructureData data = PendingStructureData.getOrCreate(level);
        long currentTick = level.getGameTime();

        // Копируем список — tryPlace() мутирует data.pending (remove/replaceEntry) по ходу обработки
        List<PendingStructureData.Entry> snapshot = new ArrayList<>(data.getAll());

        int successCount = 0;
        int total = snapshot.size();
        int processed = 0;
        for (PendingStructureData.Entry entry : snapshot) {
            processed++;
            if (entry.isExhausted()) {
                continue;
            }

            // Принудительно подгружаем окрестность острова, чтобы areChunksReady()/
            // findRealSurface() не отбрасывали запись только из-за того что чанк сейчас выгружен.
            // Радиус с запасом: даже крупные структуры обычно укладываются в 3x3 чанка.
            int centerCX = entry.pos().getX() >> 4;
            int centerCZ = entry.pos().getZ() >> 4;
            for (int dcx = -2; dcx <= 2; dcx++) {
                for (int dcz = -2; dcz <= 2; dcz++) {
                    level.getChunk(centerCX + dcx, centerCZ + dcz,
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                }
            }

            int before = data.getAll().size();
            tryPlace(level, entry, data, currentTick);
            // Грубая эвристика успеха: запись пропала из очереди и не была replace'нута на тот же id/pos с тем же attempts
            boolean stillPending = data.getAll().stream().anyMatch(e ->
                    e.id().equals(entry.id()) && e.pos().getX() == entry.pos().getX() && e.pos().getZ() == entry.pos().getZ());
            if (!stillPending) {
                successCount++;
            }

            if (processed % 200 == 0) {
                AeroWorld.LOGGER.info(
                        "[AeroWorld] forcePlaceAll: {}/{} обработано, {} успешно размещено.",
                        processed, total, successCount);
            }
        }

        AeroWorld.LOGGER.info(
                "[AeroWorld] forcePlaceAll: завершено. {}/{} структур размещено успешно.",
                successCount, total);
        return successCount;
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    private void tryPlace(ServerLevel level,
                          PendingStructureData.Entry entry,
                          PendingStructureData data,
                          long currentTick) {

        int x  = entry.pos().getX();
        int z  = entry.pos().getZ();
        ResourceLocation id = entry.id();

        // ── Лимит попыток ────────────────────────────────────────────────────
        if (entry.isExhausted()) {
            AeroWorld.LOGGER.error(
                    "[AeroWorld] Giving up on '{}' at x={} z={} after {} attempts. " +
                            "Check that the NBT file exists and the island surface is accessible.",
                    id, x, z, MAX_ATTEMPTS_LOG);
            data.remove(entry);
            return;
        }

        // ── 1. Реальная поверхность острова ─────────────────────────────────
        int surfaceY = findRealSurface(level, x, z);
        if (surfaceY < 0) {
            AeroWorld.LOGGER.debug(
                    "[AeroWorld] No surface found at x={} z={} for '{}' (attempt {}/{}).",
                    x, z, id, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
            data.replaceEntry(entry, entry.withNextRetry(currentTick));
            return;
        }

        BlockPos origin = new BlockPos(x, surfaceY + 1, z);

        // ── 2. Размер структуры ───────────────────────────────────────────────
        // excraft: структуры (снимки Sable sub-level'ов) не читаются как vanilla
        // StructureTemplate, поэтому StructureSizeCache для них не применим —
        // используем консервативный fallback-размер только для проверки чанков.
        boolean isExcraft = "excraft".equals(id.getNamespace());
        Vec3i structureSize = isExcraft ? null : StructureSizeCache.getSize(level, toNbtLocation(id));

        if (structureSize != null) {
            // ── 3. Все чанки зоны должны быть FULL ──────────────────────────
            if (!StructurePlacementHelper.areChunksReady(level, origin, structureSize)) {
                AeroWorld.LOGGER.debug(
                        "[AeroWorld] Chunks not FULL for '{}' at {} (attempt {}/{}).",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.replaceEntry(entry, entry.withNextRetry(currentTick));
                return;
            }

            // ── 4. Зона над поверхностью должна быть свободна ───────────────
            // Первые 2 попытки пропускаем: carver-pass мог ещё не восстановить острова.
            if (entry.attempts() >= 2
                    && !StructurePlacementHelper.isSpaceClear(level, origin, structureSize)) {
                AeroWorld.LOGGER.warn(
                        "[AeroWorld] Space occupied for '{}' at {} (attempt {}/{}). " +
                                "Another structure or terrain block is in the way.",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.replaceEntry(entry, entry.withNextRetry(currentTick));
                return;
            }
        } else {
            // Размер недоступен (excraft: или кеш не смог прочитать) — fallback:
            // используем консервативный размер и для проверки готовности чанков,
            // и для проверки занятости пространства.
            //
            // ИСПРАВЛЕНО: раньше isSpaceClear здесь не вызывался вовсе, из-за
            // чего excraft-структуры (например 'physical_structures:tank21')
            // безусловно ставились поверх origin, даже если там уже стоял
            // Vault/Trial Spawner, поставленный decoration-фазой islan'а
            // (см. IslandVaultTrialGenerator) — блюпринт перезаписывал его
            // своими блоками, и спавнер физически исчезал из мира, оставаясь
            // только в логах decoration ("placed N structure(s)").
            Vec3i fallbackSize = new Vec3i(16, 10, 16);
            if (!StructurePlacementHelper.areChunksReady(level, origin, fallbackSize)) {
                data.replaceEntry(entry, entry.withNextRetry(currentTick));
                return;
            }

            if (entry.attempts() >= 2
                    && !StructurePlacementHelper.isSpaceClear(level, origin, fallbackSize)) {
                AeroWorld.LOGGER.warn(
                        "[AeroWorld] Space occupied for '{}' at {} (attempt {}/{}). " +
                                "Another structure or terrain block (e.g. a vault/trial spawner) is in the way.",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.replaceEntry(entry, entry.withNextRetry(currentTick));
                return;
            }
        }

        // ── 5. Спавн ──────────────────────────────────────────────────────────
        if (isExcraft) {
            // excraft: id не идут через PhysicalStructureRegistry — размещение
            // делегируется Toolgun'у через StructureSourceProviderRegistry
            // (провайдер physical_structures:excraft_toolgun). Игрок здесь всегда
            // null (proximity-триггер работает без инициатора) — для .excraft-
            // блупринтов формата SubLevelFileStore (не Create-физических схем)
            // это штатный сценарий и команда Toolgun'а выполняется от консоли.
            boolean ok = StructureSourceProviderRegistry.place(level, origin, id, null);
            if (ok) {
                AeroWorld.LOGGER.info(
                        "[AeroWorld] Placed '{}' at {} (attempt {}/{}) — SUCCESS (excraft/Toolgun).",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.remove(entry);
            } else {
                AeroWorld.LOGGER.warn(
                        "[AeroWorld] excraft placement failed for '{}' at {} (attempt {}/{}). Will retry.",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.replaceEntry(entry, entry.withNextRetry(currentTick));
            }
            return;
        }

        // ── Обычные структуры через PhysicalStructures API ──────────────────
        ResourceLocation defId = toDefinitionId(id);
        PlaceResult result = PhysicalStructures.spawnStructureResult(level, origin, defId);

        switch (result) {
            case SUCCESS -> {
                AeroWorld.LOGGER.info(
                        "[AeroWorld] Placed '{}' at {} (attempt {}/{}) — SUCCESS.",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.remove(entry);
            }
            case UNKNOWN_ID -> {
                AeroWorld.LOGGER.error(
                        "[AeroWorld] Structure '{}' is not registered in PhysicalStructures. " +
                                "Add it via PhysicalStructures.registerStructure() or a JSON file. Removing entry.",
                        defId);
                data.remove(entry);
            }
            case LOAD_FAILED -> {
                AeroWorld.LOGGER.warn(
                        "[AeroWorld] LOAD_FAILED for '{}' at {} (attempt {}/{}). Will retry.",
                        id, origin, entry.attempts() + 1, MAX_ATTEMPTS_LOG);
                data.replaceEntry(entry, entry.withNextRetry(currentTick));
            }
        }
    }

    // ── Surface scan ──────────────────────────────────────────────────────────

    /**
     * Ищет верхний твёрдый блок с воздухом сверху в диапазонах Layer 2 и Layer 3.
     *
     * <p>Алгоритм:</p>
     * <ol>
     *   <li>Быстрый путь — heightmap {@code MOTION_BLOCKING_NO_LEAVES}.
     *       Если результат попадает в диапазон Layer 3 или Layer 2 — возвращаем сразу.</li>
     *   <li>Полный скан сверху вниз сначала по Layer 3, потом по Layer 2.
     *       Нужен когда heightmap бьёт по Layer 1 (Y≤50) или по воздуху выше острова.</li>
     * </ol>
     *
     * @return Y верхнего твёрдого блока, или {@code -1} если поверхность не найдена
     */
    private int findRealSurface(ServerLevel level, int x, int z) {
        // Быстрый путь: heightmap корректен если остров уже полностью сгенерирован
        int hmY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (hmY > LAYER3_SURFACE_MIN && hmY <= LAYER3_SURFACE_MAX) {
            return hmY - 1;
        }
        if (hmY > LAYER2_SURFACE_MIN && hmY <= LAYER2_SURFACE_MAX) {
            return hmY - 1;
        }

        // Полный скан Layer 3 (High Islands) сверху вниз
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = LAYER3_SURFACE_MAX; y >= LAYER3_SURFACE_MIN; y--) {
            p.set(x, y, z);
            if (!level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir()) {
                return y;
            }
        }

        // Полный скан Layer 2 (Lower Islands) сверху вниз
        for (int y = LAYER2_SURFACE_MAX; y >= LAYER2_SURFACE_MIN; y--) {
            p.set(x, y, z);
            if (!level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir()) {
                return y;
            }
        }

        return -1;
    }

    // ── ID helpers ────────────────────────────────────────────────────────────

    /**
     * Преобразует id структуры в путь к NBT-файлу для ResourceManager.
     *
     * <p>Пример: {@code physical_structures:tank_11}
     *          → {@code physical_structures:structures/tank_11.nbt}</p>
     *
     * <p>Если id уже заканчивается на {@code .nbt} — возвращаем как есть
     * (на случай если в PendingStructureData была сохранена старая запись с суффиксом).</p>
     */
    private static ResourceLocation toNbtLocation(ResourceLocation id) {
        String path = id.getPath();
        if (path.endsWith(".nbt")) {

            if (!path.contains("/")) {
                path = "structures/" + path;
            }
            return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
        }
        // Новый формат: physical_structures:tank_11
        return ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(), "structures/" + path + ".nbt");
    }

    /**
     * Преобразует id (с или без суффикса .nbt) в «чистый» id определения
     * PhysicalStructures (без суффикса и без пути structures/).
     *
     * <p>Пример: {@code physical_structures:tank_11.nbt}
     *          → {@code physical_structures:tank_11}</p>
     */
    private static ResourceLocation toDefinitionId(ResourceLocation id) {
        String path = id.getPath();
        // Убрать префикс пути если есть
        if (path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        // Убрать суффикс .nbt если есть
        if (path.endsWith(".nbt")) {
            path = path.substring(0, path.length() - 4);
        }
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }
}