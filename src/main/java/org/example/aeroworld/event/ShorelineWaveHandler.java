package org.example.aeroworld.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.example.aeroworld.AeroWorld;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Симуляция "накатывающих" точек воды вдоль побережья.
 *
 * <h3>Как это работает</h3>
 * С шагом ~{@value CELL} блоков вдоль кромки океан/песок выбираются точки.
 * Раз в свой цикл в такой точке кладётся НАСТОЯЩИЙ source-блок воды — ровно
 * так же, как если бы игрок вылил ведро ({@code setBlock(..., 11)}, тот же
 * набор флагов, что использует {@code BucketItem}). Дальше воду ведёт
 * САМА ВАНИЛЬ: она растекается по обычной жидкостной физике на соседние
 * блоки, без единой строчки нашего кода.
 *
 * Ровно через {@link #SOURCE_LIFETIME_TICKS} тиков (~1 секунда) убирается
 * ТОЛЬКО тот единственный блок, который мы сами поставили — через
 * запланированную запись (позиция, тик-дедлайн) в лёгкой in-memory очереди
 * (ничего не пишется в NBT/сохранение мира). Уже растёкшаяся вода этим не
 * трогается вообще — код не знает о ней и не лезет в соседние блоки.
 *
 * <p>Раньше здесь было переключение sand↔water В ОДНОЙ И ТОЙ ЖЕ точке по
 * фазе — из-за чего при перекрытии зон растекания соседних точек (шаг 4
 * блока при радиусе растекания воды до ~7) один "накат" стирал воду,
 * растёкшуюся от соседнего, и получалось ровно наоборот тому, что просили:
 * мигающий песок вместо растекающейся воды. Текущая версия трогает КАЖДУЮ
 * позицию только ОДИН раз (поставить) и ОДИН раз (убрать через дедлайн) —
 * никакого повторного вмешательства.
 */
public final class ShorelineWaveHandler {

    // Шаг сетки точек волны вдоль берега — как просили, 3-5 блоков.
    private static final int CELL = 4;
    // Радиус поиска (в ячейках CELL) вокруг каждого игрока.
    private static final int SEARCH_RADIUS_CELLS = 10; // ~40 блоков в каждую сторону
    // Как часто (тиков) проверять, не пора ли какой-то точке "накатить".
    private static final int TICK_INTERVAL = 10; // раз в 0.5 сек
    // Раз в сколько тиков одна и та же точка может накатывать повторно.
    private static final int CYCLE_TICKS = 100; // 5 секунд
    // Через сколько тиков после появления убрать именно source-блок.
    private static final int SOURCE_LIFETIME_TICKS = 20; // ~1 секунда
    // Грубый Y-фильтр: побережье слоя 1 всегда возле уровня моря (WATER_LEVEL=44,
    // BASE_SURFACE_Y=48) — острова слоёв 2-4 (Y≥400) не должны попадать сюда.
    private static final int MIN_Y_FILTER = -10;
    private static final int MAX_Y_FILTER = 90;

    private static final BlockState BS_SAND         = Blocks.SAND.defaultBlockState();
    private static final BlockState BS_WATER_SOURCE = Blocks.WATER.defaultBlockState(); // LEVEL=0 = source
    // Те же флаги, что использует BucketItem при выливании ведра.
    private static final int PLACE_FLAGS = 11; // UPDATE_CLIENTS | UPDATE_NEIGHBORS | UPDATE_INVISIBLE

    private record PendingRevert(ServerLevel level, BlockPos pos, long dueGameTime) {}

    private final Deque<PendingRevert> pendingReverts = new ArrayDeque<>();

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Работаем только в AeroWorld-измерении
        if (!level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false)) return;

        long gameTime = level.getGameTime();

        // ── Снятие source-блоков, чей срок вышел (каждый тик — очередь
        //    обычно короткая, дёшево) ──────────────────────────────────────
        while (!pendingReverts.isEmpty() && pendingReverts.peekFirst().dueGameTime() <= gameTime) {
            PendingRevert pr = pendingReverts.pollFirst();
            if (pr.level() == level && pr.level().getBlockState(pr.pos()).is(Blocks.WATER)) {
                pr.level().setBlock(pr.pos(), BS_SAND, PLACE_FLAGS);
            }
        }

        // ── Проверка новых накатов — реже, вокруг каждого игрока ────────────
        if (gameTime % TICK_INTERVAL != 0) return;
        for (ServerPlayer player : level.players()) {
            spawnNear(level, player.blockPosition(), gameTime);
        }
    }

    private void spawnNear(ServerLevel level, BlockPos center, long gameTime) {
        long seed = level.getSeed();
        int baseCellX = Math.floorDiv(center.getX(), CELL);
        int baseCellZ = Math.floorDiv(center.getZ(), CELL);

        for (int dcx = -SEARCH_RADIUS_CELLS; dcx <= SEARCH_RADIUS_CELLS; dcx++) {
            for (int dcz = -SEARCH_RADIUS_CELLS; dcz <= SEARCH_RADIUS_CELLS; dcz++) {
                int cellX = baseCellX + dcx;
                int cellZ = baseCellZ + dcz;

                long h = cellHash(cellX, cellZ, seed);

                // Своя фаза цикла у каждой точки — накаты не в такт друг другу.
                long phaseOffset = Long.remainderUnsigned(h >>> 32, CYCLE_TICKS);
                long phase = Math.floorMod(gameTime + phaseOffset, (long) CYCLE_TICKS);
                if (phase >= TICK_INTERVAL) continue; // ещё не настал момент этой точки

                int ox = (int) Long.remainderUnsigned(h, CELL);
                int oz = (int) Long.remainderUnsigned(h >>> 16, CELL);
                int wx = cellX * CELL + ox;
                int wz = cellZ * CELL + oz;

                BlockPos probe = new BlockPos(wx, 64, wz);
                if (!level.hasChunkAt(probe)) continue; // чанк не загружен — пропустить

                int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                if (topY < MIN_Y_FILTER || topY > MAX_Y_FILTER) continue;

                BlockPos pos = new BlockPos(wx, topY, wz);
                if (!level.getBlockState(pos).is(Blocks.SAND)) continue; // не сухой песок — пропустить
                if (!nearActualWater(level, pos)) continue; // не у самой кромки океана

                // Кладём source ровно как ведро — дальше растекание ведёт ваниль.
                level.setBlock(pos, BS_WATER_SOURCE, PLACE_FLAGS);
                pendingReverts.addLast(new PendingRevert(level, pos.immutable(), gameTime + SOURCE_LIFETIME_TICKS));
            }
        }
    }

    /** Есть ли настоящая вода (океан) в соседних (±1 блок по горизонтали и на 1 ниже) позициях. */
    private boolean nearActualWater(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                p.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                if (level.getBlockState(p).is(Blocks.WATER)) return true;
                p.setY(pos.getY() - 1);
                if (level.getBlockState(p).is(Blocks.WATER)) return true;
            }
        }
        return false;
    }

    private static long cellHash(int cellX, int cellZ, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + cellX * 1442695040888963407L + 0x51ED270B4A2C1D3FL;
        h = h * 6364136223846793005L + cellZ * 1442695040888963407L + 0x2A9D3B7C6E1F0854L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return h;
    }
}
