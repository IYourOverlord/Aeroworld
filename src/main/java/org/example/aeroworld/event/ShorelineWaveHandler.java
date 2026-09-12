package org.example.aeroworld.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.example.aeroworld.AeroWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Симуляция наката и возврата волны на берег.
 *
 * Схема:
 *
 *   МОРЕ
 *     ↓
 *   [1] → [2] → [3] → [4] → [5]
 *                              ↓
 *                         HOLD_TICKS
 *                              ↓
 *   [1] ← [2] ← [3] ← [4] ← [5]
 *     ↓
 *   МОРЕ
 *
 * Каждая волна полностью независима.
 */
public final class ShorelineWaveHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ShorelineWaveHandler.class);

    // ========================================================================
    // НАСТРОЙКИ
    // ========================================================================

    /**
     * Размер ячейки при поиске стартовых точек.
     */
    private static final int CELL = 4;

    /**
     * Радиус поиска волн вокруг игрока.
     */
    private static final int SEARCH_RADIUS_CELLS = 10;

    /**
     * Как часто проверять появление новых волн.
     *
     * 10 тиков = примерно 0.5 секунды.
     */
    private static final int TICK_INTERVAL = 10;

    /**
     * Цикл генерации волн для одной стартовой ячейки.
     */
    private static final int CYCLE_TICKS = 100;

    /**
     * Максимальная глубина распространения волны.
     */
    private static final int WAVE_DEPTH = 5;

    /**
     * Задержка между кольцами.
     *
     * 5 тиков = 0.25 секунды.
     */
    private static final int STEP_DELAY_TICKS = 5;

    /**
     * Сколько тиков волна стоит на максимальной глубине
     * перед началом возврата.
     */
    private static final int HOLD_TICKS = 10;

    /**
     * Ограничение по высоте.
     */
    private static final int MIN_Y_FILTER = -10;
    private static final int MAX_Y_FILTER = 90;

    // ========================================================================
    // ВОДА
    // ========================================================================

    /**
     * Вода волны.
     *
     * LEVEL=0 — source-блок.
     *
     * ВАЖНО:
     * LEVEL=7 (текучая вода) не годится — у неё нет соседнего
     * source/flow, поддерживающего уровень, поэтому ванильный
     * fluid tick обнуляет её в AIR почти сразу после установки
     * (ещё до наступления HOLD_TICKS/removalTime). Наш Java-код
     * при этом ни разу не видит "исчезновение" как ошибку, т.к.
     * removeWaveBlock() просто находит уже отсутствующую воду
     * и снимает владение без вопросов — отсюда и мигание кольцами
     * вместо накопления слоёв.
     *
     * Source-блок стабилен и не тикает сам по себе, поэтому стоит
     * ровно до вызова removeWaveBlock() по расписанию волны.
     */
    private static final BlockState BS_WATER_THIN =
            Blocks.WATER.defaultBlockState()
                    .setValue(LiquidBlock.LEVEL, 0);

    /**
     * Флаги установки блока.
     *
     * UPDATE_CLIENTS (2) — обязателен, иначе клиент не увидит блок.
     * Без UPDATE_NEIGHBORS (1): не пересчитываем окружающую
     * жидкостную физику при установке, чтобы не провоцировать
     * немедленный fluid tick соседних клеток волны.
     */
    private static final int PLACE_FLAGS = 2;

    // ========================================================================
    // ВНУТРЕННИЕ ОБЪЕКТЫ
    // ========================================================================

    /**
     * ID следующей волны.
     */
    private long nextWaveId = 1;

    /**
     * Один блок волны.
     *
     * placementTime:
     *   время появления.
     *
     * removalTime:
     *   время удаления при возврате.
     */
    private record WaveBlock(
            BlockPos pos,
            long placementTime,
            long removalTime
    ) {
    }

    /**
     * Полностью сформированная волна.
     *
     * rings[0] — первое кольцо от моря.
     * rings[1] — второе.
     * ...
     * rings[last] — самое дальнее.
     */
    private record Wave(
            long id,
            ServerLevel level,
            List<List<WaveBlock>> rings,
            long lastPlacementTime,
            long endTime,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int y
    ) {
    }

    /**
     * Активные волны.
     */
    private final Deque<Wave> activeWaves = new ArrayDeque<>();

    /**
     * Владельцы установленных водных блоков.
     *
     * Важно:
     * карта теперь разделена по ServerLevel.
     *
     * Поэтому одинаковый BlockPos в разных измерениях
     * больше не конфликтует.
     */
    private final Map<ServerLevel, Map<BlockPos, Long>> ownedWaterBlocks =
            new HashMap<>();

    // ========================================================================
    // TICK
    // ========================================================================

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // Работаем только в AeroWorld.
        if (!level.dimensionTypeRegistration()
                .unwrapKey()
                .map(key ->
                        key.location()
                                .getNamespace()
                                .equals(AeroWorld.MOD_ID))
                .orElse(false)) {
            return;
        }

        long gameTime = level.getGameTime();

        // Сначала обрабатываем существующие волны.
        processWaves(level, gameTime);

        // Затем ищем новые.
        if (gameTime % TICK_INTERVAL != 0) {
            return;
        }

        for (ServerPlayer player : level.players()) {

            spawnNear(
                    level,
                    player.blockPosition(),
                    gameTime
            );
        }
    }

    // ========================================================================
    // ОБРАБОТКА ВОЛН
    // ========================================================================

    private void processWaves(
            ServerLevel level,
            long gameTime
    ) {

        if (activeWaves.isEmpty()) {
            return;
        }

        List<Wave> finishedWaves = new ArrayList<>();

        for (Wave wave : activeWaves) {

            if (wave.level() != level) {
                continue;
            }

            processSingleWave(
                    wave,
                    level,
                    gameTime
            );

            /*
             * ВАЖНО:
             *
             * Используем <= вместо проверки точного тика.
             *
             * Если Minecraft пропустил один игровой тик,
             * волна всё равно продолжит движение.
             */
            if (gameTime >= wave.endTime()) {
                finishedWaves.add(wave);
            }
        }

        for (Wave wave : finishedWaves) {

            activeWaves.remove(wave);

            spongeCleanup(wave, level);

            Map<BlockPos, Long> ownership =
                    ownedWaterBlocks.get(wave.level());

            if (ownership != null) {

                ownership.entrySet().removeIf(
                        entry -> entry.getValue() == wave.id()
                );

                if (ownership.isEmpty()) {
                    ownedWaterBlocks.remove(wave.level());
                }
            }
        }
    }

    /**
     * Губко-подобная зачистка.
     *
     * Снимает любую воду в bounding box волны, включая source-блоки,
     * самопроизвольно расплодившиеся через ванильную infinite-water
     * механику (два соседних source рождают новый source между собой
     * при randomTick) — такие блоки не входят ни в rings, ни в
     * ownedWaterBlocks, поэтому removeWaveBlock() их не видит.
     *
     * Настоящее "море" находится за пределами bounding box (он строится
     * строго по позициям над сушей, куда волна докатилась), поэтому
     * зачистка не трогает исходный водоём.
     */
    private void spongeCleanup(
            Wave wave,
            ServerLevel level
    ) {

        BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

        for (int x = wave.minX(); x <= wave.maxX(); x++) {

            for (int z = wave.minZ(); z <= wave.maxZ(); z++) {

                pos.set(x, wave.y(), z);

                if (!level.hasChunkAt(pos)) {
                    continue;
                }

                if (level.getBlockState(pos).is(Blocks.WATER)) {

                    level.setBlock(
                            pos,
                            Blocks.AIR.defaultBlockState(),
                            PLACE_FLAGS
                    );
                }
            }
        }
    }

    /**
     * Обработка одной волны.
     */
    private void processSingleWave(
            Wave wave,
            ServerLevel level,
            long gameTime
    ) {

        List<List<WaveBlock>> rings = wave.rings();

        // ====================================================================
        // НАКАТ
        // ====================================================================

        /*
         * Идём от моря к берегу.
         */
        for (List<WaveBlock> ring : rings) {

            for (WaveBlock block : ring) {

                /*
                 * <= делает механику устойчивой к пропуску тика.
                 */
                if (block.placementTime() > gameTime) {
                    continue;
                }

                /*
                 * Проверяем, не был ли блок уже обработан.
                 */
                if (isOwnedByWave(
                        level,
                        block.pos(),
                        wave.id()
                )) {
                    continue;
                }

                placeWaveBlock(
                        wave,
                        block,
                        level
                );
            }
        }

        // ====================================================================
        // ВОЗВРАТ
        // ====================================================================

        /*
         * Жёсткая гарантия:
         *
         * ни один блок не снимается, пока накат волны
         * не завершён полностью (последнее кольцо доставлено).
         *
         * Это защищает от преждевременного исчезновения слоёв
         * даже если individual removalTime по какой-то причине
         * наступил раньше срока.
         */
        if (gameTime < wave.lastPlacementTime()) {
            return;
        }

        /*
         * Идём ОТ САМОГО ДАЛЬНЕГО кольца к морю.
         *
         * Это и создаёт визуальное движение воды назад.
         */
        for (int i = rings.size() - 1;
             i >= 0;
             i--) {

            List<WaveBlock> ring = rings.get(i);

            for (WaveBlock block : ring) {

                /*
                 * Если время возврата ещё не пришло —
                 * оставляем воду.
                 */
                if (block.removalTime() > gameTime) {
                    continue;
                }

                removeWaveBlock(
                        wave,
                        block,
                        level
                );
            }
        }

        /*
         * Побочная зачистка от самотиражированной воды.
         *
         * После lastPlacementTime вся площадь волны фиксирована
         * (новых колец больше не появится), поэтому безопасно
         * снимать любую "ничью" воду в bounding box — она либо
         * наша (тогда удалится по расписанию removeWaveBlock выше),
         * либо результат ванильной infinite-water генерации.
         *
         * Выполняется каждый тик фазы возврата, чтобы лишние
         * блоки не оставались видимыми лужами вплоть до endTime.
         */
        sweepUnownedWater(wave, level);
    }

    /**
     * Снимает воду в bounding box волны, не принадлежащую
     * ни одной активной волне.
     *
     * См. spongeCleanup() — та же причина (infinite-water),
     * но вызывается на каждом тике возврата, а не только
     * в момент завершения волны.
     */
    private void sweepUnownedWater(
            Wave wave,
            ServerLevel level
    ) {

        Map<BlockPos, Long> ownership =
                ownedWaterBlocks.get(level);

        BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

        for (int x = wave.minX(); x <= wave.maxX(); x++) {

            for (int z = wave.minZ(); z <= wave.maxZ(); z++) {

                pos.set(x, wave.y(), z);

                if (ownership != null
                        && ownership.containsKey(pos)) {
                    continue;
                }

                if (!level.hasChunkAt(pos)) {
                    continue;
                }

                if (level.getBlockState(pos).is(Blocks.WATER)) {

                    level.setBlock(
                            pos,
                            Blocks.AIR.defaultBlockState(),
                            PLACE_FLAGS
                    );
                }
            }
        }
    }

    // ========================================================================
    // УСТАНОВКА ВОДЫ
    // ========================================================================

    private void placeWaveBlock(
            Wave wave,
            WaveBlock block,
            ServerLevel level
    ) {

        BlockPos pos = block.pos();

        if (!level.hasChunkAt(pos)) {
            return;
        }

        /*
         * Не перехватываем блок, уже принадлежащий другой активной волне.
         */
        Map<BlockPos, Long> existingOwnership =
                ownedWaterBlocks.get(level);

        if (existingOwnership != null) {

            Long existingOwner =
                    existingOwnership.get(pos);

            if (existingOwner != null
                    && existingOwner != wave.id()) {
                return;
            }
        }

        /*
         * Ставим только в воздух.
         *
         * Это сохранено из твоей рабочей логики.
         */
        if (!level.getBlockState(pos).isAir()) {
            return;
        }

        level.setBlock(
                pos,
                BS_WATER_THIN,
                PLACE_FLAGS
        );

        /*
         * Проверяем, что блок действительно появился.
         */
        if (!level.getBlockState(pos).is(Blocks.WATER)) {
            return;
        }

        /*
         * Регистрируем владельца только после фактической установки.
         */
        ownedWaterBlocks
                .computeIfAbsent(
                        level,
                        ignored -> new HashMap<>()
                )
                .put(
                        pos.immutable(),
                        wave.id()
                );
    }

    // ========================================================================
    // УДАЛЕНИЕ ВОДЫ
    // ========================================================================

    private void removeWaveBlock(
            Wave wave,
            WaveBlock block,
            ServerLevel level
    ) {

        BlockPos pos = block.pos();

        Map<BlockPos, Long> ownership =
                ownedWaterBlocks.get(level);

        if (ownership == null) {
            return;
        }

        Long owner = ownership.get(pos);

        /*
         * Эта вода уже принадлежит другой волне
         * или вообще не была поставлена этой волной.
         */
        if (owner == null || owner != wave.id()) {
            return;
        }

        if (!level.hasChunkAt(pos)) {
            return;
        }

        BlockState current =
                level.getBlockState(pos);

        /*
         * Не уничтожаем посторонний блок.
         */
        if (!current.is(Blocks.WATER)) {

            ownership.remove(pos);

            return;
        }

        /*
         * Удаляем воду.
         */
        level.setBlock(
                pos,
                Blocks.AIR.defaultBlockState(),
                PLACE_FLAGS
        );

        ownership.remove(pos);
    }

    // ========================================================================
    // ПРОВЕРКА ВЛАДЕЛЬЦА
    // ========================================================================

    private boolean isOwnedByWave(
            ServerLevel level,
            BlockPos pos,
            long waveId
    ) {

        Map<BlockPos, Long> ownership =
                ownedWaterBlocks.get(level);

        if (ownership == null) {
            return false;
        }

        Long owner = ownership.get(pos);

        if (owner == null || owner != waveId) {
            return false;
        }

        /*
         * Карта владения могла устареть: блок мог исчезнуть
         * не через removeWaveBlock() (сосед снёс воду, чанк
         * перезагрузился, взрыв и т.д.). Если это так — считаем
         * позицию свободной, чтобы НАКАТ переставил блок заново,
         * а не молчаливо решил, что она уже занята.
         */
        if (!level.getBlockState(pos).is(Blocks.WATER)) {

            ownership.remove(pos);

            return false;
        }

        return true;
    }

    // ========================================================================
    // ПОИСК НОВЫХ ВОЛН
    // ========================================================================

    private void spawnNear(
            ServerLevel level,
            BlockPos center,
            long gameTime
    ) {

        long seed = level.getSeed();

        int baseCellX =
                Math.floorDiv(
                        center.getX(),
                        CELL
                );

        int baseCellZ =
                Math.floorDiv(
                        center.getZ(),
                        CELL
                );

        for (int dcx = -SEARCH_RADIUS_CELLS;
             dcx <= SEARCH_RADIUS_CELLS;
             dcx++) {

            for (int dcz = -SEARCH_RADIUS_CELLS;
                 dcz <= SEARCH_RADIUS_CELLS;
                 dcz++) {

                int cellX =
                        baseCellX + dcx;

                int cellZ =
                        baseCellZ + dcz;

                long hash =
                        cellHash(
                                cellX,
                                cellZ,
                                seed
                        );

                long phaseOffset =
                        Long.remainderUnsigned(
                                hash >>> 32,
                                CYCLE_TICKS
                        );

                long phase =
                        Math.floorMod(
                                gameTime + phaseOffset,
                                (long) CYCLE_TICKS
                        );

                if (phase >= TICK_INTERVAL) {
                    continue;
                }

                int ox =
                        (int) Long.remainderUnsigned(
                                hash,
                                CELL
                        );

                int oz =
                        (int) Long.remainderUnsigned(
                                hash >>> 16,
                                CELL
                        );

                int wx =
                        cellX * CELL + ox;

                int wz =
                        cellZ * CELL + oz;

                BlockPos probe =
                        new BlockPos(
                                wx,
                                64,
                                wz
                        );

                if (!level.hasChunkAt(probe)) {
                    continue;
                }

                int topY =
                        level.getHeight(
                                Heightmap.Types.WORLD_SURFACE,
                                wx,
                                wz
                        ) - 1;

                if (topY < MIN_Y_FILTER
                        || topY > MAX_Y_FILTER) {
                    continue;
                }

                BlockPos waterPos =
                        new BlockPos(
                                wx,
                                topY,
                                wz
                        );

                /*
                 * Источник должен быть водой.
                 */
                if (!level.getBlockState(waterPos)
                        .is(Blocks.WATER)) {
                    continue;
                }

                BlockPos aboveWater =
                        waterPos.above();

                /*
                 * Над водой должен быть воздух.
                 */
                if (!level.getBlockState(aboveWater)
                        .isAir()) {
                    continue;
                }

                /*
                 * Рядом должна быть суша.
                 */
                if (!hasDryNeighbor(
                        level,
                        waterPos
                )) {
                    continue;
                }

                /*
                 * Не запускаем новую волну,
                 * если в этой точке уже идёт волна.
                 */
                if (hasNearbyActiveWave(
                        level,
                        aboveWater
                )) {
                    continue;
                }

                Wave wave =
                        createWave(
                                level,
                                aboveWater,
                                waterPos.getY(),
                                gameTime
                        );

                if (wave != null) {
                    activeWaves.addLast(wave);
                }
            }
        }
    }

    // ========================================================================
    // ЗАЩИТА ОТ НАЛОЖЕНИЯ ОДНОЙ И ТОЙ ЖЕ ВОЛНЫ
    // ========================================================================

    private boolean hasNearbyActiveWave(
            ServerLevel level,
            BlockPos start
    ) {

        /*
         * Проверяем только существующие волны этого измерения.
         *
         * Радиус небольшой, поэтому это не создаёт серьёзной нагрузки.
         */
        for (Wave wave : activeWaves) {

            if (wave.level() != level) {
                continue;
            }

            for (List<WaveBlock> ring : wave.rings()) {

                for (WaveBlock block : ring) {

                    BlockPos pos = block.pos();

                    if (Math.abs(pos.getX() - start.getX()) <= WAVE_DEPTH
                            && Math.abs(pos.getZ() - start.getZ()) <= WAVE_DEPTH) {

                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ========================================================================
    // СОЗДАНИЕ ВОЛНЫ
    // ========================================================================

    private Wave createWave(
            ServerLevel level,
            BlockPos startAbove,
            int shoreY,
            long gameTime
    ) {

        Set<Long> visited =
                new HashSet<>();

        visited.add(
                packXZ(
                        startAbove.getX(),
                        startAbove.getZ()
                )
        );

        List<BlockPos> current =
                new ArrayList<>();

        current.add(
                startAbove.immutable()
        );

        List<List<BlockPos>> rawRings =
                new ArrayList<>();

        // ====================================================================
        // BFS
        // ====================================================================

        for (int depth = 1;
             depth <= WAVE_DEPTH
                     && !current.isEmpty();
             depth++) {

            List<BlockPos> next =
                    new ArrayList<>();

            List<BlockPos> ring =
                    new ArrayList<>();

            for (BlockPos currentPos : current) {

                /*
                 * Только 4 направления.
                 */
                for (int dx = -1;
                     dx <= 1;
                     dx++) {

                    for (int dz = -1;
                         dz <= 1;
                         dz++) {

                        if (dx == 0 && dz == 0) {
                            continue;
                        }

                        if (dx != 0 && dz != 0) {
                            continue;
                        }

                        int nx =
                                currentPos.getX() + dx;

                        int nz =
                                currentPos.getZ() + dz;

                        long key =
                                packXZ(
                                        nx,
                                        nz
                                );

                        if (!visited.add(key)) {
                            continue;
                        }

                        int topY =
                                level.getHeight(
                                        Heightmap.Types.WORLD_SURFACE,
                                        nx,
                                        nz
                                ) - 1;

                        if (topY < MIN_Y_FILTER
                                || topY > MAX_Y_FILTER) {
                            continue;
                        }

                        /*
                         * Не перепрыгиваем через резкий перепад высоты.
                         */
                        if (Math.abs(
                                topY - shoreY
                        ) > 1) {
                            continue;
                        }

                        BlockPos ground =
                                new BlockPos(
                                        nx,
                                        topY,
                                        nz
                                );

                        /*
                         * Только сухая поверхность.
                         */
                        if (!isDryGround(
                                level,
                                ground
                        )) {
                            continue;
                        }

                        BlockPos waterBlock =
                                ground.above()
                                        .immutable();

                        ring.add(waterBlock);

                        next.add(waterBlock);
                    }
                }
            }

            if (!ring.isEmpty()) {
                rawRings.add(ring);
            }

            current = next;
        }

        if (rawRings.isEmpty()) {
            return null;
        }

        long waveId =
                nextWaveId++;

        List<List<WaveBlock>> rings =
                new ArrayList<>();

        // ====================================================================
        // РАСПИСАНИЕ ВОЛНЫ
        // ====================================================================

        /*
         * Последнее кольцо появляется в:
         *
         * gameTime + ringsCount * STEP_DELAY_TICKS
         *
         * После него ждём HOLD_TICKS.
         */
        long lastPlacementTime =
                gameTime
                        + (long) rawRings.size()
                        * STEP_DELAY_TICKS;

        /*
         * Затем начинаем возврат.
         *
         * Сначала исчезает самое дальнее кольцо.
         */
        long firstRemovalTime =
                lastPlacementTime
                        + HOLD_TICKS;

        for (int i = 0;
             i < rawRings.size();
             i++) {

            /*
             * Накат:
             *
             * кольцо 0
             * кольцо 1
             * кольцо 2
             * ...
             */
            long placementTime =
                    gameTime
                            + (long) (i + 1)
                            * STEP_DELAY_TICKS;

            /*
             * Возврат:
             *
             * последнее кольцо
             * предпоследнее
             * ...
             * первое
             */
            long removalTime =
                    firstRemovalTime
                            + (long)
                            (rawRings.size() - 1 - i)
                            * STEP_DELAY_TICKS;

            List<WaveBlock> ring =
                    new ArrayList<>();

            for (BlockPos pos :
                    rawRings.get(i)) {

                ring.add(
                        new WaveBlock(
                                pos.immutable(),
                                placementTime,
                                removalTime
                        )
                );
            }

            rings.add(ring);
        }

        long endTime =
                firstRemovalTime
                        + (long)
                        (rawRings.size() - 1)
                        * STEP_DELAY_TICKS;

        /*
         * Bounding box волны — нужен для губко-подобной зачистки
         * при завершении (см. spongeCleanup()).
         *
         * Ванильная вода с LEVEL=0 (source) самотиражируется:
         * два соседних source-блока рождают новый source между ними
         * через randomTick, независимо от нашего кода. Эти лишние
         * блоки никогда не попадают в rings/ownedWaterBlocks, поэтому
         * обычное removeWaveBlock() их не видит. Зачистка по всей
         * площади волны решает это гарантированно.
         */
        int boxMinX = Integer.MAX_VALUE;
        int boxMaxX = Integer.MIN_VALUE;
        int boxMinZ = Integer.MAX_VALUE;
        int boxMaxZ = Integer.MIN_VALUE;

        for (List<BlockPos> ring : rawRings) {

            for (BlockPos pos : ring) {

                boxMinX = Math.min(boxMinX, pos.getX());
                boxMaxX = Math.max(boxMaxX, pos.getX());
                boxMinZ = Math.min(boxMinZ, pos.getZ());
                boxMaxZ = Math.max(boxMaxZ, pos.getZ());
            }
        }

        LOGGER.info(
                "[ShorelineWave] wave {} created at {}: rings={}, "
                        + "gameTime={}, lastPlacementTime={}, "
                        + "firstRemovalTime={}, endTime={}",
                waveId,
                startAbove,
                rawRings.size(),
                gameTime,
                lastPlacementTime,
                firstRemovalTime,
                endTime
        );

        return new Wave(
                waveId,
                level,
                rings,
                lastPlacementTime,
                endTime,
                boxMinX,
                boxMaxX,
                boxMinZ,
                boxMaxZ,
                startAbove.getY()
        );
    }

    // ========================================================================
    // ПРОВЕРКА БЕРЕГА
    // ========================================================================

    private boolean hasDryNeighbor(
            ServerLevel level,
            BlockPos waterPos
    ) {

        BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

        for (int dx = -1;
             dx <= 1;
             dx++) {

            for (int dz = -1;
                 dz <= 1;
                 dz++) {

                if (dx == 0 && dz == 0) {
                    continue;
                }

                pos.set(
                        waterPos.getX() + dx,
                        waterPos.getY(),
                        waterPos.getZ() + dz
                );

                if (isDryGround(
                        level,
                        pos
                )) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isDryGround(
            ServerLevel level,
            BlockPos pos
    ) {

        BlockState state =
                level.getBlockState(pos);

        return (
                state.is(Blocks.SAND)
                        || state.is(Blocks.GRASS_BLOCK)
        )
                && level.getBlockState(
                pos.above()
        ).isAir();
    }

    // ========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ========================================================================

    private static long packXZ(
            int x,
            int z
    ) {

        return ((long) x << 32)
                ^ (z & 0xFFFFFFFFL);
    }

    private static long cellHash(
            int cellX,
            int cellZ,
            long seed
    ) {

        long h = seed;

        h = h
                * 6364136223846793005L
                + cellX
                * 1442695040888963407L
                + 0x51ED270B4A2C1D3FL;

        h = h
                * 6364136223846793005L
                + cellZ
                * 1442695040888963407L
                + 0x2A9D3B7C6E1F0854L;

        h ^= h >>> 33;

        h *= 0xFF51AFD7ED558CCDL;

        h ^= h >>> 33;

        return h;
    }
}