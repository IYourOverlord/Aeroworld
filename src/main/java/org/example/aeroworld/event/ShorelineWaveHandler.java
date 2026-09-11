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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Симуляция наката волны на берег.
 *
 * <h3>Как это работает</h3>
 * Вдоль кромки океан/песок с шагом {@value CELL} блоков выбираются стартовые
 * точки — позиции самой воды (source), у которых есть хотя бы один сухой
 * сосед (трава/песок) на той же высоте. Раз в свой цикл такая точка
 * запускает "веер" наката: BFS от воздуха НАД этим water-блоком по соседним
 * сухим клеткам суши, на каждом шаге суши ставится тонкий flowing-блок воды
 * ({@code LEVEL=7} — минимальная толщина растёкшейся воды, ванилью не
 * распространяется сам, так как это не source). Каждый следующий "слой" BFS
 * (по расстоянию от старта) ставится с задержкой — это и даёт эффект
 * расходящегося веером наката, а не мгновенной заливки.
 *
 * Блок ставится в воздух НАД сухой поверхностью (как и раньше) — так гребень
 * не проваливается физически в толщу песка/травы и всегда виден поверх неё.
 *
 * У каждого поставленного блока свой TTL — он исчезает (заменяется на воздух)
 * ровно через {@value TTL_TICKS} тиков после постановки, независимо от
 * остальных блоков веера (собственный откат по таймеру, а не общий откат всей
 * цепочки как раньше).
 */
public final class ShorelineWaveHandler {

    // Шаг сетки точек волны вдоль берега.
    private static final int CELL = 4;
    // Радиус поиска (в ячейках CELL) вокруг каждого игрока.
    private static final int SEARCH_RADIUS_CELLS = 10; // ~40 блоков в каждую сторону
    // Как часто (тиков) проверять, не пора ли какой-то точке запустить волну.
    private static final int TICK_INTERVAL = 10; // раз в 0.5 сек
    // Раз в сколько тиков одна и та же точка может накатывать повторно.
    private static final int CYCLE_TICKS = 100; // 5 секунд
    // Максимальная глубина BFS-веера (шагов по суше от кромки воды).
    private static final int WAVE_DEPTH = 5;
    // Задержка (тиков) между соседними "кольцами" BFS-веера — скорость наката.
    private static final int STEP_DELAY_TICKS = 3;
    // Время жизни одного блока тонкой воды после постановки (2 секунды).
    private static final int TTL_TICKS = 40;
    // Грубый Y-фильтр: побережье слоя 1 всегда возле уровня моря (WATER_LEVEL=44,
    // BASE_SURFACE_Y=48) — острова слоёв 2-4 (Y≥400) не должны попадать сюда.
    private static final int MIN_Y_FILTER = -10;
    private static final int MAX_Y_FILTER = 90;

    // Тонкая растёкшаяся вода: flowing (не source), минимальная толщина.
    private static final BlockState BS_WATER_THIN = Blocks.WATER.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, 7);
    // Те же флаги, что использует BucketItem при выливании ведра.
    private static final int PLACE_FLAGS = 11; // UPDATE_CLIENTS | UPDATE_NEIGHBORS | UPDATE_INVISIBLE

    /** Один шаг веера: поставить тонкую воду в pos в момент dueGameTime. */
    private record PendingPlace(ServerLevel level, BlockPos pos, long dueGameTime) {}
    /** Снятие ранее поставленного блока по истечении его TTL. */
    private record PendingRevert(ServerLevel level, BlockPos pos, long dueGameTime) {}

    private final Deque<PendingPlace> pendingPlaces = new ArrayDeque<>();
    private final Deque<PendingRevert> pendingReverts = new ArrayDeque<>();

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Работаем только в AeroWorld-измерении
        if (!level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false)) return;

        long gameTime = level.getGameTime();

        // ── Постановка блоков наступающего веера, чей срок настал ──────────
        while (!pendingPlaces.isEmpty() && pendingPlaces.peekFirst().dueGameTime() <= gameTime) {
            PendingPlace pp = pendingPlaces.pollFirst();
            if (pp.level() == level
                    && level.hasChunkAt(pp.pos())
                    && pp.level().getBlockState(pp.pos()).isAir()) {
                pp.level().setBlock(pp.pos(), BS_WATER_THIN, PLACE_FLAGS);
                pendingReverts.addLast(new PendingRevert(pp.level(), pp.pos(), gameTime + TTL_TICKS));
            }
        }

        // ── Снятие блоков веера по истечении их персонального TTL ───────────
        while (!pendingReverts.isEmpty() && pendingReverts.peekFirst().dueGameTime() <= gameTime) {
            PendingRevert pr = pendingReverts.pollFirst();
            if (pr.level() == level
                    && level.hasChunkAt(pr.pos())
                    && pr.level().getBlockState(pr.pos()).is(Blocks.WATER)) {
                pr.level().setBlock(pr.pos(), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            }
        }

        // ── Проверка новых волн — реже, вокруг каждого игрока ────────────
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

                // Своя фаза цикла у каждой точки — волны не в такт друг другу.
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

                BlockPos waterPos = new BlockPos(wx, topY, wz);
                // Стартовая точка веера — сама вода (source) на кромке.
                if (!level.getBlockState(waterPos).is(Blocks.WATER)) continue;
                BlockPos abovePos = waterPos.above();
                if (!level.getBlockState(abovePos).isAir()) continue; // над водой не свободно — пропустить

                if (!hasDryNeighbor(level, waterPos)) continue; // не у самой кромки суши

                launchWaveFan(level, abovePos, waterPos.getY(), gameTime);
            }
        }
    }

    /** Проверяет, есть ли среди 8 горизонтальных соседей сухая поверхность (песок/трава) на той же высоте. */
    private boolean hasDryNeighbor(ServerLevel level, BlockPos waterPos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                p.set(waterPos.getX() + dx, waterPos.getY(), waterPos.getZ() + dz);
                if (isDryGround(level, p)) return true;
            }
        }
        return false;
    }

    private boolean isDryGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return (state.is(Blocks.SAND) || state.is(Blocks.GRASS_BLOCK))
                && level.getBlockState(pos.above()).isAir();
    }

    /**
     * Строит веер тонкой воды методом BFS от точки над водой ({@code startAbove})
     * по соседним сухим клеткам суши (песок/трава). Каждое "кольцо" BFS
     * (группа клеток на одинаковом расстоянии от старта) ставится с
     * нарастающей задержкой — так веер визуально расходится от кромки воды
     * вглубь берега. Глубина ограничена {@value WAVE_DEPTH} шагами.
     */
    private void launchWaveFan(ServerLevel level, BlockPos startAbove, int shoreY, long gameTime) {
        Set<Long> visited = new HashSet<>();
        visited.add(packXZ(startAbove.getX(), startAbove.getZ()));

        Deque<int[]> currentRing = new ArrayDeque<>();
        currentRing.add(new int[] { startAbove.getX(), startAbove.getZ() });

        for (int depth = 1; depth <= WAVE_DEPTH && !currentRing.isEmpty(); depth++) {
            Deque<int[]> nextRing = new ArrayDeque<>();
            long due = gameTime + (long) depth * STEP_DELAY_TICKS;

            for (int[] cell : currentRing) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (dx != 0 && dz != 0) continue; // только 4 стороны, без диагоналей
                        int nx = cell[0] + dx;
                        int nz = cell[1] + dz;
                        long key = packXZ(nx, nz);
                        if (!visited.add(key)) continue;

                        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz) - 1;
                        if (topY < MIN_Y_FILTER || topY > MAX_Y_FILTER) continue;
                        if (Math.abs(topY - shoreY) > 1) continue; // резкий перепад высоты — не берег

                        BlockPos dryPos = new BlockPos(nx, topY, nz);
                        if (!isDryGround(level, dryPos)) continue; // не суша или сверху занято

                        BlockPos abovePos = dryPos.above();
                        pendingPlaces.addLast(new PendingPlace(level, abovePos.immutable(), due));
                        nextRing.add(new int[] { nx, nz });
                    }
                }
            }
            currentRing = nextRing;
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
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