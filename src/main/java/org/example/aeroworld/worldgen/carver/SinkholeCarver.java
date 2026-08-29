package org.example.aeroworld.worldgen.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.example.aeroworld.worldgen.noise.AeroNoise;

/**
 * Генерирует карстовые воронки (sinkholes) в рельефе Layer 1.
 *
 * <p>Вызывается из {@code AeroWorldChunkGenerator.applyCarvers} после
 * ванильного карвинга и восстановления островов. Работает напрямую
 * с {@link ChunkAccess} — не требует регистрации как {@code WorldCarver},
 * и не зависит от {@code CarvingMask}/{@code Aquifer}.</p>
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Детерминированный PRNG по (chunkX, chunkZ, worldSeed) решает,
 *       есть ли воронка в этом чанке (вероятность ~1/12).</li>
 *   <li>Если да — выбирает случайные (localX, localZ) внутри чанка,
 *       радиус (15–30 блоков) и глубину (15–35 блоков).</li>
 *   <li>Для каждого блока в радиусе: если расстояние по XZ меньше
 *       текущего радиуса на этой высоте (параболоид) — заменяет
 *       на воздух (или воду, если ниже WATER_LEVEL).</li>
 *   <li>Воронка может выходить за границы чанка — это нормально,
 *       соседний чанк обработает свою часть при своём applyCarvers.</li>
 * </ol>
 */
public final class SinkholeCarver {

    private SinkholeCarver() {}

    // ── Параметры воронок ─────────────────────────────────────────────
    /** Вероятность воронки в одном чанке (1/CHANCE_INV). */
    private static final int CHANCE_INV = 12;

    private static final int MIN_RADIUS = 15;
    private static final int MAX_RADIUS = 30;
    private static final int MIN_DEPTH  = 15;
    private static final int MAX_DEPTH  = 35;

    /** Ниже этого Y дно воронки заполняется водой. */
    private static final int WATER_LEVEL = 62;

    /** Минимальная толщина «дна» — не прорезаем до бедрока. */
    private static final int BEDROCK_MARGIN = 5;

    // ── Блоки ─────────────────────────────────────────────────────────
    private static final BlockState AIR   = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    // Допустимые блоки для вырезания (твёрдый грунт)
    private static boolean isCarveTarget(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.SAND)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.TERRACOTTA)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MUD);
    }

    /**
     * Пытается вырезать воронку в данном чанке.
     *
     * @param chunk     чанк, в котором идёт карвинг
     * @param worldSeed seed мира
     */
    public static void carveChunk(ChunkAccess chunk, long worldSeed) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Детерминированный hash — одинаковый результат для одного и того
        // же чанка при любом порядке загрузки.
        long hash = mixSeed(worldSeed, chunkX, chunkZ);

        // Шанс генерации: берём младшие биты хэша
        if (((hash & 0xFFFFL) % CHANCE_INV) != 0) return;

        // Параметры воронки из хэша
        int localX = (int) ((hash >>> 16) & 0xF);  // 0..15
        int localZ = (int) ((hash >>> 20) & 0xF);  // 0..15
        int radius = MIN_RADIUS + (int) (((hash >>> 24) & 0xFF) % (MAX_RADIUS - MIN_RADIUS + 1));
        int depth  = MIN_DEPTH  + (int) (((hash >>> 32) & 0xFF) % (MAX_DEPTH - MIN_DEPTH + 1));

        int centerWX = (chunkX << 4) + localX;
        int centerWZ = (chunkZ << 4) + localZ;

        // Высота поверхности в центре воронки
        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);

        // Не генерируем воронки в океане / слишком низко
        if (surfaceY < WATER_LEVEL + 3) return;

        int bottomY = Math.max(surfaceY - depth, chunk.getMinBuildHeight() + BEDROCK_MARGIN);

        // Вырезаем параболоидную чашу только в пределах ЭТОГО чанка.
        // Воронка может быть шире 16 блоков — соседние чанки вырежут
        // свою часть при своём вызове carveChunk (каждый чанк по хэшу
        // соседей тоже проверит, нужно ли резать).

        // Сначала обработаем «свой» центр
        carveFromCenter(chunk, centerWX, centerWZ, surfaceY, bottomY, radius);

        // Затем проверяем соседние чанки — может, их воронка заходит к нам
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = chunkX + dx;
                int nz = chunkZ + dz;
                long nhash = mixSeed(worldSeed, nx, nz);
                if (((nhash & 0xFFFFL) % CHANCE_INV) != 0) continue;

                int nlx = (int) ((nhash >>> 16) & 0xF);
                int nlz = (int) ((nhash >>> 20) & 0xF);
                int nr  = MIN_RADIUS + (int) (((nhash >>> 24) & 0xFF) % (MAX_RADIUS - MIN_RADIUS + 1));
                int nd  = MIN_DEPTH  + (int) (((nhash >>> 32) & 0xFF) % (MAX_DEPTH - MIN_DEPTH + 1));

                int ncx = (nx << 4) + nlx;
                int ncz = (nz << 4) + nlz;

                // Быстрая проверка: заходит ли воронка соседа в наш чанк?
                int minWX = chunkX << 4;
                int minWZ = chunkZ << 4;
                int maxWX = minWX + 15;
                int maxWZ = minWZ + 15;

                // Ближайшая точка нашего чанка к центру соседней воронки
                int closestX = Math.max(minWX, Math.min(maxWX, ncx));
                int closestZ = Math.max(minWZ, Math.min(maxWZ, ncz));
                double dist2 = (closestX - ncx) * (closestX - ncx)
                        + (closestZ - ncz) * (closestZ - ncz);
                if (dist2 > (double) nr * nr) continue;

                // Высоту поверхности в центре соседней воронки мы не можем
                // запросить напрямую (соседний чанк может быть не загружен).
                // Берём высоту в НАШЕМ чанке в ближайшей к центру точке.
                int relX = closestX - (chunkX << 4);
                int relZ = closestZ - (chunkZ << 4);
                int nSurf = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, relX, relZ);
                if (nSurf < WATER_LEVEL + 3) continue;

                int nBottom = Math.max(nSurf - nd, chunk.getMinBuildHeight() + BEDROCK_MARGIN);
                carveFromCenter(chunk, ncx, ncz, nSurf, nBottom, nr);
            }
        }
    }

    /**
     * Вырезает параболоидную чашу с центром (cx, cz) в пределах данного чанка.
     */
    private static void carveFromCenter(ChunkAccess chunk,
                                        int cx, int cz,
                                        int surfaceY, int bottomY, int radius) {
        int minWX = chunk.getPos().x << 4;
        int minWZ = chunk.getPos().z << 4;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Итерируем только по блокам ЭТОГО чанка, попадающим в круг
        int startX = Math.max(minWX, cx - radius);
        int endX   = Math.min(minWX + 15, cx + radius);
        int startZ = Math.max(minWZ, cz - radius);
        int endZ   = Math.min(minWZ + 15, cz + radius);

        double r2 = (double) radius * radius;

        for (int wx = startX; wx <= endX; wx++) {
            for (int wz = startZ; wz <= endZ; wz++) {
                double dx = wx - cx;
                double dz = wz - cz;
                double dist2 = dx * dx + dz * dz;
                if (dist2 > r2) continue;

                // Параболоидный профиль: в центре — максимальная глубина,
                // к краю — выходит на уровень поверхности.
                double t = dist2 / r2; // 0 в центре, 1 на краю
                int carveBottom = (int) Math.round(bottomY + (surfaceY - bottomY) * t);

                // Вырезаем сверху вниз
                for (int y = surfaceY; y >= carveBottom; y--) {
                    pos.set(wx, y, wz);
                    BlockState current = chunk.getBlockState(pos);
                    if (!isCarveTarget(current)) continue;

                    if (y <= WATER_LEVEL && carveBottom <= WATER_LEVEL) {
                        // Дно ниже уровня воды — заливаем водой
                        chunk.setBlockState(pos, WATER, false);
                    } else {
                        chunk.setBlockState(pos, AIR, false);
                    }
                }
            }
        }
    }

    /**
     * Детерминированный хэш позиции чанка с seed мира.
     * Stafford variant 13 of Murmur3 finalizer.
     */
    private static long mixSeed(long seed, int chunkX, int chunkZ) {
        long h = seed ^ ((long) chunkX * 0x9E3779B97F4A7C15L)
                ^ ((long) chunkZ * 0x6C62272E07BB0142L);
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }
}
