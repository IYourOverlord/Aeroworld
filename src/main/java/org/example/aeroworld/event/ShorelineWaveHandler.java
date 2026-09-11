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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Симуляция наката волны на берег.
 *
 * <h3>Как это работает</h3>
 * Вдоль кромки океан/песок с шагом {@value CELL} блоков выбираются точки.
 * У каждой точки вычисляется направление "вглубь суши" (нормаль к берегу) —
 * горизонтальный вектор от ближайшей воды к точке, продолженный дальше.
 * Раз в свой цикл точка запускает "гребень" волны: вдоль этого направления
 * последовательно, с небольшой задержкой между шагами, ставятся настоящие
 * source-блоки воды ({@code setBlock(..., 11)} — те же флаги, что у ведра).
 *
 * <b>Важно:</b> source ставится не ВМЕСТО блока песка, а в воздух НАД ним
 * ({@code pos.above()}). Если бы вода заменяла сам песок, при перекрытии
 * соседних гребней (или на изрезанной кромке) source мог оказаться со всех
 * сторон окружён другим песком/сушей и физически не иметь свободной стороны
 * для стекания — вода "запиралась" точечными лужами прямо в толще пляжа.
 * Постановка над поверхностью гарантирует как минимум одну свободную грань
 * (вверх и в стороны, если соседняя клетка суши ниже или уровня), так что
 * ваниль всегда может её растащить — от кромки к суше пробегает видимый
 * фронт наката, а не запертая точка. Дальше воду ведёт САМА ВАНИЛЬ — её
 * растекание в стороны никто не трогает.
 *
 * После того как гребень доходит до дальней точки, начинается ОТКАТ: те же
 * позиции (тоже "над песком") убираются в ТОМ ЖЕ порядке, в котором
 * ставились (от берега вглубь), что визуально читается как волна,
 * вернувшаяся обратно в море. Каждая позиция снимается, только если там
 * всё ещё стоит наш собственный source-блок (не трогаем воду, растёкшуюся
 * туда самой ванилью или поставленную соседним гребнем).
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
    // Сколько блоков в глубину суши пробегает гребень волны.
    private static final int WAVE_DEPTH = 5;
    // Задержка (тиков) между постановкой соседних блоков гребня — скорость наката.
    private static final int STEP_DELAY_TICKS = 3;
    // Сколько тиков гребень держится у дальней точки перед откатом.
    private static final int HOLD_TICKS = 8;
    // Грубый Y-фильтр: побережье слоя 1 всегда возле уровня моря (WATER_LEVEL=44,
    // BASE_SURFACE_Y=48) — острова слоёв 2-4 (Y≥400) не должны попадать сюда.
    private static final int MIN_Y_FILTER = -10;
    private static final int MAX_Y_FILTER = 90;

    private static final BlockState BS_WATER_SOURCE = Blocks.WATER.defaultBlockState(); // LEVEL=0 = source
    // Те же флаги, что использует BucketItem при выливании ведра.
    private static final int PLACE_FLAGS = 11; // UPDATE_CLIENTS | UPDATE_NEIGHBORS | UPDATE_INVISIBLE

    /** Один шаг гребня: поставить source в pos в момент dueGameTime. */
    private record PendingPlace(ServerLevel level, BlockPos pos, long dueGameTime) {}
    /** Один шаг отката: убрать (вернуть песок) source в pos, если он ещё наш. */
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

        // ── Постановка блоков наступающего гребня, чей срок настал ──────────
        while (!pendingPlaces.isEmpty() && pendingPlaces.peekFirst().dueGameTime() <= gameTime) {
            PendingPlace pp = pendingPlaces.pollFirst();
            if (pp.level() == level
                    && level.hasChunkAt(pp.pos())
                    && pp.level().getBlockState(pp.pos()).isAir()) {
                pp.level().setBlock(pp.pos(), BS_WATER_SOURCE, PLACE_FLAGS);
            }
        }

        // ── Снятие блоков отступающего гребня, чей срок настал ──────────────
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

                BlockPos sandPos = new BlockPos(wx, topY, wz);
                if (!level.getBlockState(sandPos).is(Blocks.SAND)) continue; // не сухой песок — пропустить
                BlockPos abovePos = sandPos.above();
                if (!level.getBlockState(abovePos).isAir()) continue; // над песком не свободно — пропустить

                int[] inland = inlandDirection(level, sandPos);
                if (inland == null) continue; // не у самой кромки океана

                launchWave(level, abovePos, inland[0], inland[1], gameTime);
            }
        }
    }

    /**
     * Строит цепочку позиций НАД песком (воздух прямо над поверхностью пляжа)
     * от берега вглубь суши вдоль направления {@code (dx, dz)} и планирует их
     * последовательную постановку (накат), а следом — снятие в том же порядке
     * (откат), начиная после того, как накат полностью завершится.
     */
    private void launchWave(ServerLevel level, BlockPos startAbove, int dx, int dz, long gameTime) {
        List<BlockPos> chain = new ArrayList<>(WAVE_DEPTH);
        chain.add(startAbove.immutable());
        int x = startAbove.getX();
        int z = startAbove.getZ();
        for (int step = 1; step < WAVE_DEPTH; step++) {
            x += dx;
            z += dz;
            int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (topY < MIN_Y_FILTER || topY > MAX_Y_FILTER) break;
            BlockPos sandPos = new BlockPos(x, topY, z);
            if (!level.getBlockState(sandPos).is(Blocks.SAND)) break; // гребень уткнулся не в песок — обрываем
            BlockPos abovePos = sandPos.above();
            if (!level.getBlockState(abovePos).isAir()) break; // сверху не свободно — обрываем
            chain.add(abovePos.immutable());
        }

        long lastPlaceDue = gameTime;
        for (int i = 0; i < chain.size(); i++) {
            long due = gameTime + (long) (i + 1) * STEP_DELAY_TICKS;
            lastPlaceDue = due;
            pendingPlaces.addLast(new PendingPlace(level, chain.get(i).immutable(), due));
        }

        long revertStart = lastPlaceDue + HOLD_TICKS;
        for (int i = 0; i < chain.size(); i++) {
            long due = revertStart + (long) i * STEP_DELAY_TICKS;
            pendingReverts.addLast(new PendingRevert(level, chain.get(i).immutable(), due));
        }
    }

    /**
     * Определяет горизонтальное направление "от воды к суше" в точке берега:
     * ищет среди 8 соседей клетку с настоящей водой и возвращает противоположный
     * от неё единичный вектор (dx, dz). Возвращает {@code null}, если рядом
     * воды не найдено (точка не является кромкой берега).
     */
    private int[] inlandDirection(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int sumDx = 0;
        int sumDz = 0;
        boolean found = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                p.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                boolean water = level.getBlockState(p).is(Blocks.WATER);
                if (!water) {
                    p.setY(pos.getY() - 1);
                    water = level.getBlockState(p).is(Blocks.WATER);
                }
                if (water) {
                    found = true;
                    sumDx -= dx;
                    sumDz -= dz;
                }
            }
        }
        if (!found) return null;

        int outDx = Integer.signum(sumDx);
        int outDz = Integer.signum(sumDz);
        if (outDx == 0 && outDz == 0) {
            // Вода со всех сторон уравновешена (узкий мыс/симметрия) — берём
            // произвольную, но детерминированную ось на основе хэша позиции.
            outDx = ((pos.getX() + pos.getZ()) & 1) == 0 ? 1 : 0;
            outDz = outDx == 0 ? 1 : 0;
        }
        return new int[] { outDx, outDz };
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