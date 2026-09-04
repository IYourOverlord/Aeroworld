package org.example.aeroworld.worldgen.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunkSection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Генерирует карстовые воронки (sinkholes) в рельефе Layer 1.
 *
 * <p>Вызывается из {@code AeroWorldChunkGenerator.applyCarvers} после
 * ванильного карвинга и восстановления островов. Работает напрямую
 * с {@link ChunkAccess} — не требует регистрации как {@code WorldCarver},
 * и не зависит от {@code CarvingMask}/{@code Aquifer}.</p>
 *
 * <p><b>Потокобезопасность:</b> все параметры воронки (центр, радиус,
 * глубина, surfaceY) вычисляются детерминированно по (worldSeed, chunkX,
 * chunkZ) и chunk-independent {@link HeightSampler}. Это гарантирует,
 * что соседние чанки, обрабатываемые параллельно (C2ME), вырежут
 * идентичные формы на своей стороне границы — никаких «torn chunks».</p>
 */
public final class SinkholeCarver {

    private SinkholeCarver() {}

    /** Детерминированная функция высоты поверхности по мировым координатам. */
    @FunctionalInterface
    public interface HeightSampler {
        int getHeight(int wx, int wz);
    }

    // ── Кэш высот поверхности воронок ────────────────────────────────
    private static final ConcurrentHashMap<Long, Integer> HEIGHT_CACHE =
            new ConcurrentHashMap<>(256);

    private static int getCachedHeight(int wx, int wz, HeightSampler sampler) {
        long key = ((long) wx << 32) | (wz & 0xFFFFFFFFL);
        Integer cached = HEIGHT_CACHE.get(key);
        if (cached != null) return cached;

        int h = sampler.getHeight(wx, wz);
        if (HEIGHT_CACHE.size() > 2048) {
            HEIGHT_CACHE.clear();
        }
        HEIGHT_CACHE.put(key, h);
        return h;
    }

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
     * Вычисляет параметры воронки для чанка (ox, oz) из хэша.
     * Возвращает null если воронки нет (не прошла проверку шанса).
     */
    private static long sinkholeHash(long worldSeed, int chunkX, int chunkZ) {
        return mixSeed(worldSeed, chunkX, chunkZ);
    }

    public static void carveChunk(ChunkAccess chunk, long worldSeed,
                                  HeightSampler heightSampler) {
        carveChunk(chunk, worldSeed, heightSampler, null);
    }

    /**
     * Пытается вырезать воронки в данном чанке.
     */
    public static void carveChunk(ChunkAccess chunk, long worldSeed,
                                  HeightSampler heightSampler, BiomeManager biomeManager) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int minBuildHeight = chunk.getMinBuildHeight();

        // Проверяем ВСЕ чанки в радиусе ±2 (включая текущий) —
        // воронка в любом из них может заходить в наш чанк.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int ox = chunkX + dx;
                int oz = chunkZ + dz;
                long hash = sinkholeHash(worldSeed, ox, oz);

                // Шанс генерации
                if (((hash & 0xFFFFL) % CHANCE_INV) != 0) continue;

                // Параметры воронки — детерминированны по хэшу чанка-владельца
                int localX = (int) ((hash >>> 16) & 0xF);
                int localZ = (int) ((hash >>> 20) & 0xF);
                int radius = MIN_RADIUS + (int) (((hash >>> 24) & 0xFF) % (MAX_RADIUS - MIN_RADIUS + 1));
                int depth  = MIN_DEPTH  + (int) (((hash >>> 32) & 0xFF) % (MAX_DEPTH - MIN_DEPTH + 1));

                int centerWX = (ox << 4) + localX;
                int centerWZ = (oz << 4) + localZ;

                // Быстрая проверка: заходит ли круг воронки в наш чанк?
                int myMinWX = chunkX << 4;
                int myMinWZ = chunkZ << 4;
                int myMaxWX = myMinWX + 15;
                int myMaxWZ = myMinWZ + 15;

                int closestX = Math.max(myMinWX, Math.min(myMaxWX, centerWX));
                int closestZ = Math.max(myMinWZ, Math.min(myMaxWZ, centerWZ));
                double dist2 = (double)(closestX - centerWX) * (closestX - centerWX)
                        + (double)(closestZ - centerWZ) * (closestZ - centerWZ);
                if (dist2 > (double) radius * radius) continue;

                // Карстовые условия: исключаем океаны, реки и пляжи ДО вычисления высоты
                if (biomeManager != null) {
                    Holder<Biome> biome = biomeManager.getBiome(new BlockPos(centerWX, 64, centerWZ));
                    if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)
                            || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
                        continue;
                    }
                }

                // Кэшированный опрос высоты поверхности — 1 расчет на воронку вместо повторов во всех чанках
                int surfaceY = getCachedHeight(centerWX, centerWZ, heightSampler);

                if (surfaceY < WATER_LEVEL + 3) continue;

                int bottomY = Math.max(surfaceY - depth, minBuildHeight + BEDROCK_MARGIN);
                carveFromCenter(chunk, centerWX, centerWZ, surfaceY, bottomY, radius);
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

        int startX = Math.max(minWX, cx - radius);
        int endX   = Math.min(minWX + 15, cx + radius);
        int startZ = Math.max(minWZ, cz - radius);
        int endZ   = Math.min(minWZ + 15, cz + radius);

        double r2 = (double) radius * radius;

        for (int wx = startX; wx <= endX; wx++) {
            int localX = wx & 15;
            double ddx = wx - cx;
            double ddxSq = ddx * ddx;

            for (int wz = startZ; wz <= endZ; wz++) {
                int localZ = wz & 15;
                double ddz = wz - cz;
                double d2 = ddxSq + ddz * ddz;
                if (d2 > r2) continue;

                double t = d2 / r2; // 0 в центре, 1 на краю
                int carveBottom = (int) Math.round(bottomY + (surfaceY - bottomY) * t);

                for (int y = surfaceY; y >= carveBottom; y--) {
                    int secIdx = chunk.getSectionIndex(y);
                    LevelChunkSection section = chunk.getSection(secIdx);
                    if (section == null) continue;

                    int localY = y & 15;
                    BlockState current = section.getBlockState(localX, localY, localZ);
                    if (!isCarveTarget(current)) continue;

                    BlockState replacement = (y <= WATER_LEVEL && carveBottom <= WATER_LEVEL) ? WATER : AIR;
                    section.setBlockState(localX, localY, localZ, replacement, false);
                }
            }
        }
    }

    /**
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
