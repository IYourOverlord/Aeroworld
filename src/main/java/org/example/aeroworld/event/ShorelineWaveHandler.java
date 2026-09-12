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
 *   [кольцо 0, LEVEL=0] → [кольцо 1, LEVEL=1] → ... → [кольцо 7, LEVEL=7]
 *   (только полукольцо в сторону суши, назад в море BFS не растёт)
 *                                                              ↓
 *                                                         HOLD_TICKS
 *                                                              ↓
 *   [кольцо 7 исчезает] → [кольцо 6 исчезает] → ... → [кольцо 0 исчезает]
 *                                                              ↓
 *                                                            МОРЕ
 *
 * Вместо того чтобы полагаться на ванильную жидкостную физику
 * (fluid tick), которая распространяется непредсказуемо, волна
 * генерируется вручную: BFS-кольца от точки берега, где кольцу N
 * присваивается LiquidBlock.LEVEL = min(N, 7) — это в точности
 * имитирует то, как выглядела бы естественно растёкшаяся вода от
 * source-блока (уровень падает на 1 с каждым блоком удаления).
 *
 * BFS растёт только в направлении суши (полукольцо), а не во все
 * стороны — иначе часть воды бесполезно расползается обратно
 * в открытое море.
 *
 * Кольца появляются последовательно (имитация наката), а исчезают
 * тоже последовательно, но в обратном порядке — сначала самое
 * дальнее (последним появившееся) кольцо, и так до кольца 0
 * (имитация возврата волны в море).
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
     * Максимальная глубина распространения волны в кольцах.
     *
     * Ровно 7 — это максимальный уровень (LiquidBlock.LEVEL) обычной
     * текучей воды в ваниле, дальше вода физически не растекается.
     * Кольцо i получает LEVEL = min(i, 7), поэтому i=0..7 даёт
     * ровно диапазон уровней от source (0) до предельного flow (7).
     */
    private static final int WAVE_DEPTH = 7;

    /**
     * Задержка между появлением соседних колец.
     *
     * Имитирует скорость натурального растекания воды —
     * следующее кольцо появляется чуть позже предыдущего.
     */
    private static final int STEP_DELAY_TICKS = 3;

    /**
     * Задержка между исчезновением соседних колец при возврате.
     *
     * Может отличаться от STEP_DELAY_TICKS, чтобы настроить
     * скорость наката и скорость отката независимо.
     */
    private static final int RETREAT_STEP_DELAY_TICKS = 3;

    /**
     * Сколько тиков волна стоит полностью растёкшейся
     * перед тем, как начать исчезать.
     */
    private static final int HOLD_TICKS = 40;

    /**
     * Ограничение по высоте.
     */
    private static final int MIN_Y_FILTER = -10;
    private static final int MAX_Y_FILTER = 90;

    // ========================================================================
    // ВОДА
    // ========================================================================

    /**
     * Флаги установки блока.
     *
     * UPDATE_CLIENTS (2) — обязателен, иначе клиент не увидит блок.
     * Без UPDATE_NEIGHBORS (1): мы САМИ вручную расставляем все
     * уровни жидкости кольцами, поэтому ванильный fluid tick нам
     * не нужен и даже вреден — он может начать самостоятельно
     * пересчитывать наши уровни или порождать infinite-water между
     * соседними source-блоками. Полностью исключаем ванильную
     * жидкостную физику из процесса.
     */
    private static final int PLACE_FLAGS = 2;

    /**
     * Флаги удаления — тоже без UPDATE_NEIGHBORS, чтобы не
     * спровоцировать соседей пересчитать себя в момент зачистки.
     */
    private static final int REMOVE_FLAGS = 2;

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
     * level — уровень жидкости (0 = source, ..., 7 = предельный flow),
     * присвоенный по номеру кольца, в котором этот блок находится.
     *
     * placementTime — момент появления (кольца появляются
     * последовательно, имитируя растекание).
     *
     * removalTime — момент исчезновения ЭТОГО конкретного блока.
     * Кольца отматываются назад: дальнее кольцо (последним
     * появившееся) исчезает первым, кольцо 0 — последним.
     */
    private record WaveBlock(
            BlockPos pos,
            int level,
            long placementTime,
            long removalTime
    ) {
    }

    /**
     * Полностью сформированная волна.
     *
     * blocks — все блоки всех колец с их индивидуальным уровнем
     * жидкости, временем появления и временем исчезновения.
     *
     * endTime — момент, когда волна считается полностью завершённой
     * (после исчезновения последнего, самого первого кольца).
     */
    private record Wave(
            long id,
            ServerLevel level,
            List<WaveBlock> blocks,
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
     * Разделена по ServerLevel, чтобы одинаковый BlockPos
     * в разных измерениях не конфликтовал.
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
     * Финальная зачистка bounding box волны при завершении —
     * страховка на случай, если покольцевое удаление почему-то
     * не убрало какой-то блок.
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
                            REMOVE_FLAGS
                    );
                }
            }
        }
    }

    /**
     * Обработка одной волны: каждый блок появляется и исчезает
     * в свой собственный момент времени (см. WaveBlock).
     *
     * Накат: кольцо 0 → кольцо 1 → ... → кольцо 7 (появление).
     * Возврат: кольцо 7 → кольцо 6 → ... → кольцо 0 (исчезновение),
     * то есть строго в обратном порядке относительно появления.
     */
    private void processSingleWave(
            Wave wave,
            ServerLevel level,
            long gameTime
    ) {

        for (WaveBlock block : wave.blocks()) {

            /*
             * ВОЗВРАТ приоритетнее НАКАТА: если для блока уже
             * наступило время исчезновения — снимаем его, даже
             * если по каким-то причинам он не был вовремя
             * поставлен на накате.
             *
             * <= вместо == делает механику устойчивой к пропуску
             * тика.
             */
            if (block.removalTime() <= gameTime) {

                removeWaveBlock(wave, block, level);

                continue;
            }

            if (block.placementTime() > gameTime) {
                continue;
            }

            if (isOwnedByWave(level, block.pos(), wave.id())) {
                continue;
            }

            placeWaveBlock(wave, block, level);
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
         */
        if (!level.getBlockState(pos).isAir()) {
            return;
        }

        BlockState waterState =
                Blocks.WATER.defaultBlockState()
                        .setValue(
                                LiquidBlock.LEVEL,
                                block.level()
                        );

        level.setBlock(
                pos,
                waterState,
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

        level.setBlock(
                pos,
                Blocks.AIR.defaultBlockState(),
                REMOVE_FLAGS
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
         * не через наш код (сосед снёс воду, чанк перезагрузился,
         * взрыв и т.д.). Если это так — считаем позицию свободной,
         * чтобы НАКАТ переставил блок заново.
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
                 * Рядом должна быть суша — и нам нужно конкретное
                 * направление, чтобы построить полукольцо именно
                 * туда, а не во все стороны.
                 */
                ShoreDirection shoreDirection =
                        findShoreDirection(
                                level,
                                waterPos
                        );

                if (shoreDirection == null) {
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
                                shoreDirection,
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

        for (Wave wave : activeWaves) {

            if (wave.level() != level) {
                continue;
            }

            for (WaveBlock block : wave.blocks()) {

                BlockPos pos = block.pos();

                if (Math.abs(pos.getX() - start.getX()) <= WAVE_DEPTH
                        && Math.abs(pos.getZ() - start.getZ()) <= WAVE_DEPTH) {

                    return true;
                }
            }
        }

        return false;
    }

    // ========================================================================
    // СОЗДАНИЕ ВОЛНЫ
    // ========================================================================

    /**
     * Направление к суше как (dx, dz) — не обязательно единичный
     * вектор по каждой оси, но хотя бы одна из компонент ненулевая.
     */
    private record ShoreDirection(int dx, int dz) {
    }

    /**
     * Создаёт волну через ручной BFS-полукольца от точки берега,
     * растущий только в направлении суши.
     *
     * Кольцо 0 (сама startAbove) получает LEVEL=0 (source). Каждое
     * следующее кольцо получает LEVEL на 1 больше (до потолка
     * WAVE_DEPTH=7). BFS отбрасывает любую соседнюю клетку, чей
     * сдвиг от стартовой точки имеет отрицательное скалярное
     * произведение с shoreDirection — то есть клетки "позади"
     * стартовой точки (в сторону открытого моря) в кольца не
     * попадают. Получается полукольцо, растущее строго в сторону
     * берега, а не полное кольцо во все стороны.
     *
     * Каждый блок получает свой removalTime: кольца исчезают в
     * обратном порядке относительно появления — сначала кольцо 7
     * (самое дальнее/последнее появившееся), в конце — кольцо 0.
     */
    private Wave createWave(
            ServerLevel level,
            BlockPos startAbove,
            int shoreY,
            ShoreDirection shoreDirection,
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

        // Кольцо 0 — сама стартовая точка (всегда над водой, LEVEL=0).
        rawRings.add(
                List.of(startAbove.immutable())
        );

        int startX = startAbove.getX();
        int startZ = startAbove.getZ();

        for (int depth = 1;
             depth <= WAVE_DEPTH
                     && !current.isEmpty();
             depth++) {

            List<BlockPos> next =
                    new ArrayList<>();

            List<BlockPos> ring =
                    new ArrayList<>();

            for (BlockPos currentPos : current) {

                for (int dx = -1; dx <= 1; dx++) {

                    for (int dz = -1; dz <= 1; dz++) {

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

                        /*
                         * Полукольцо: отбрасываем точки, ушедшие
                         * "назад" относительно направления к суше.
                         * Скалярное произведение вектора от старта
                         * до кандидата с shoreDirection должно быть
                         * неотрицательным — иначе это движение
                         * обратно в открытое море.
                         */
                        int offsetX = nx - startX;
                        int offsetZ = nz - startZ;

                        int dot =
                                offsetX * shoreDirection.dx()
                                        + offsetZ * shoreDirection.dz();

                        if (dot < 0) {
                            continue;
                        }

                        long key =
                                packXZ(nx, nz);

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
                        if (Math.abs(topY - shoreY) > 1) {
                            continue;
                        }

                        BlockPos ground =
                                new BlockPos(nx, topY, nz);

                        /*
                         * Клетка годится, если под ней либо вода
                         * (продолжение моря в сторону берега —
                         * например, диагональный подступ), либо
                         * сухая суша (сам берег).
                         */
                        boolean isWaterGround =
                                level.getBlockState(ground)
                                        .is(Blocks.WATER);

                        boolean isDry =
                                isDryGround(level, ground);

                        if (!isWaterGround && !isDry) {
                            continue;
                        }

                        BlockPos waterBlock =
                                ground.above().immutable();

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

        if (rawRings.size() <= 1) {
            return null;
        }

        long waveId =
                nextWaveId++;

        int ringCount = rawRings.size();

        /*
         * Появление: кольцо i в gameTime + i * STEP_DELAY_TICKS.
         */
        long lastPlacementTime =
                gameTime
                        + (long) (ringCount - 1) * STEP_DELAY_TICKS;

        /*
         * Начало возврата — после удержания на пике.
         */
        long retreatStartTime =
                lastPlacementTime + HOLD_TICKS;

        /*
         * Возврат идёт в обратном порядке: кольцо (ringCount-1)
         * исчезает первым (в retreatStartTime), кольцо 0 —
         * последним.
         */
        List<WaveBlock> blocks =
                new ArrayList<>();

        int boxMinX = Integer.MAX_VALUE;
        int boxMaxX = Integer.MIN_VALUE;
        int boxMinZ = Integer.MAX_VALUE;
        int boxMaxZ = Integer.MIN_VALUE;

        for (int i = 0; i < ringCount; i++) {

            /*
             * Кольцо i получает LEVEL = min(i, 7) — 0 для source,
             * дальше растущий уровень вплоть до предельного 7.
             */
            int fluidLevel =
                    Math.min(i, 7);

            long placementTime =
                    gameTime
                            + (long) i * STEP_DELAY_TICKS;

            /*
             * Индекс "с конца": для последнего кольца (i = ringCount-1)
             * это 0 — оно исчезает первым, сразу в retreatStartTime.
             * Для кольца 0 индекс с конца максимален — оно исчезает
             * последним.
             */
            int indexFromEnd =
                    (ringCount - 1) - i;

            long removalTime =
                    retreatStartTime
                            + (long) indexFromEnd * RETREAT_STEP_DELAY_TICKS;

            for (BlockPos pos : rawRings.get(i)) {

                blocks.add(
                        new WaveBlock(
                                pos,
                                fluidLevel,
                                placementTime,
                                removalTime
                        )
                );

                boxMinX = Math.min(boxMinX, pos.getX());
                boxMaxX = Math.max(boxMaxX, pos.getX());
                boxMinZ = Math.min(boxMinZ, pos.getZ());
                boxMaxZ = Math.max(boxMaxZ, pos.getZ());
            }
        }

        /*
         * Волна считается завершённой, когда исчезло последнее
         * (нулевое) кольцо.
         */
        long endTime =
                retreatStartTime
                        + (long) (ringCount - 1) * RETREAT_STEP_DELAY_TICKS;

        LOGGER.info(
                "[ShorelineWave] wave {} created at {}: rings={}, "
                        + "gameTime={}, lastPlacementTime={}, "
                        + "retreatStartTime={}, endTime={}",
                waveId,
                startAbove,
                ringCount,
                gameTime,
                lastPlacementTime,
                retreatStartTime,
                endTime
        );

        return new Wave(
                waveId,
                level,
                blocks,
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

    /**
     * Ищет направление к ближайшему сухому соседу как (dx, dz).
     * Возвращает null, если рядом сухой земли нет.
     */
    private ShoreDirection findShoreDirection(
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
                    return new ShoreDirection(dx, dz);
                }
            }
        }

        return null;
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