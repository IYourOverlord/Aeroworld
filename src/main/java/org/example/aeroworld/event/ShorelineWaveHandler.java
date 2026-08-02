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

/**
 * Симуляция "накатывающих" точек воды вдоль побережья: не единая волна, а
 * отдельные точки песка с шагом ~{@value CELL} блоков, каждая независимо
 * циклически превращается в воду и обратно в песок (появилась → постояла →
 * исчезла), со сдвинутой фазой — так что вдоль берега они мигают не в такт,
 * имитируя набегающий прибой.
 *
 * <h3>Как это работает (без хранения состояния)</h3>
 * Состояние КАЖДОЙ точки — чистая функция от игрового времени и координат
 * (детерминированный хэш): {@code flooded = (gameTime + hash(x,z)) % CYCLE < FLOOD_LEN}.
 * Ничего не запоминается между тиками и не сохраняется в мир — при
 * выгрузке/загрузке чанка состояние просто пересчитывается заново из
 * текущего игрового времени. Обрабатываются только точки в квадрате вокруг
 * каждого игрока (дёшево, не сканирует все загруженные чанки).
 *
 * <h3>Упрощение</h3>
 * Вода не "растекается" физически — это ОДИН и тот же блок в одной точке,
 * переключающийся sand↔water. Настоящее растекание (flowing water на
 * соседние блоки) рискованно — может необратимо размыть рельеф за пределами
 * точки. Визуально одиночный пульс на каждой точке уже даёт эффект прибоя.
 */
public final class ShorelineWaveHandler {

    // Шаг сетки точек волны вдоль берега — как просили, 3-5 блоков.
    private static final int CELL = 4;
    // Радиус поиска (в ячейках CELL) вокруг каждого игрока.
    private static final int SEARCH_RADIUS_CELLS = 10; // ~40 блоков в каждую сторону
    // Как часто (тиков) пересчитывать точки волны.
    private static final int TICK_INTERVAL = 10; // раз в 0.5 сек
    // Длина полного цикла одной точки (появилась → исчезла → пауза).
    private static final int CYCLE_TICKS = 100; // 5 секунд
    // Какая доля цикла — "залито водой".
    private static final double FLOOD_FRACTION = 0.30;
    // Грубый Y-фильтр: побережье слоя 1 всегда возле уровня моря (WATER_LEVEL=44,
    // BASE_SURFACE_Y=48) — острова слоёв 2-4 (Y≥400) не должны попадать сюда.
    private static final int MIN_Y_FILTER = -10;
    private static final int MAX_Y_FILTER = 90;

    private static final BlockState BS_SAND  = Blocks.SAND .defaultBlockState();
    private static final BlockState BS_WATER = Blocks.WATER.defaultBlockState();

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Работаем только в AeroWorld-измерении
        if (!level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false)) return;

        long gameTime = level.getGameTime();
        if (gameTime % TICK_INTERVAL != 0) return;

        for (ServerPlayer player : level.players()) {
            processNear(level, player.blockPosition(), gameTime);
        }
    }

    private void processNear(ServerLevel level, BlockPos center, long gameTime) {
        long seed = level.getSeed();
        int baseCellX = Math.floorDiv(center.getX(), CELL);
        int baseCellZ = Math.floorDiv(center.getZ(), CELL);

        for (int dcx = -SEARCH_RADIUS_CELLS; dcx <= SEARCH_RADIUS_CELLS; dcx++) {
            for (int dcz = -SEARCH_RADIUS_CELLS; dcz <= SEARCH_RADIUS_CELLS; dcz++) {
                int cellX = baseCellX + dcx;
                int cellZ = baseCellZ + dcz;

                long h = cellHash(cellX, cellZ, seed);
                int ox = (int) Long.remainderUnsigned(h, CELL);
                int oz = (int) Long.remainderUnsigned(h >>> 16, CELL);
                int wx = cellX * CELL + ox;
                int wz = cellZ * CELL + oz;

                BlockPos probe = new BlockPos(wx, 64, wz);
                if (!level.hasChunkAt(probe)) continue; // чанк не загружен — пропустить

                int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                if (topY < MIN_Y_FILTER || topY > MAX_Y_FILTER) continue;

                BlockPos pos = new BlockPos(wx, topY, wz);
                BlockState cur = level.getBlockState(pos);
                boolean isSandHere  = cur.is(Blocks.SAND);
                boolean isWaterHere = cur.is(Blocks.WATER);
                if (!isSandHere && !isWaterHere) continue; // это не наша точка

                if (!nearActualWater(level, pos)) continue; // не у самой кромки

                long phaseOffset = Long.remainderUnsigned(h >>> 32, CYCLE_TICKS);
                long phase = Math.floorMod(gameTime + phaseOffset, (long) CYCLE_TICKS);
                boolean shouldFlood = phase < (long) (CYCLE_TICKS * FLOOD_FRACTION);

                if (shouldFlood && isSandHere) {
                    level.setBlockAndUpdate(pos, BS_WATER);
                } else if (!shouldFlood && isWaterHere) {
                    level.setBlockAndUpdate(pos, BS_SAND);
                }
            }
        }
    }

    /** Есть ли настоящая вода в соседних (по горизонтали, ±1 блок и на 1 ниже) позициях. */
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
