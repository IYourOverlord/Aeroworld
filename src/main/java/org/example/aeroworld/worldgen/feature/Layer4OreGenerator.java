package org.example.aeroworld.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Генератор руд для 4-го слоя (Y 1900–2031).
 *
 * ТОЛЬКО алмазы, лазурит и изумруды — больше ничего.
 * Диапазон совпадает с UpperIslandGenerator: LAYER_MIN_Y=1900, LAYER_MAX_Y=2031.
 *
 * Жилы кладутся только в STONE-блоки острова — не в воздух, не поверх других руд.
 * Layer1OreFilter после этого дополнительно чистит слой от любых нежелательных руд.
 */
public class Layer4OreGenerator {

    private static final BlockState BS_DIAMOND_ORE = Blocks.DIAMOND_ORE.defaultBlockState();
    private static final BlockState BS_LAPIS_ORE   = Blocks.LAPIS_ORE  .defaultBlockState();
    private static final BlockState BS_EMERALD_ORE = Blocks.EMERALD_ORE.defaultBlockState();

    /** Совпадает с UpperIslandGenerator.LAYER_MIN_Y */
    public static final int BASE_Y = 1900;
    /** Совпадает с UpperIslandGenerator.LAYER_MAX_Y = -64 + 2096 - 1 = 2031 */
    public static final int TOP_Y  = 2031;

    // Щедро — алмазы должны быть главной наградой слоя
    private static final int DIAMOND_VEIN     = 10;
    private static final int LAPIS_VEIN       = 12;
    private static final int EMERALD_VEIN     = 8;

    private static final int DIAMOND_ATTEMPTS = 5;
    private static final int LAPIS_ATTEMPTS   = 3;
    private static final int EMERALD_ATTEMPTS = 8;

    public void generateOres(ChunkAccess chunk, RandomSource random, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        placeVeins(chunk, random, baseX, baseZ,
                BS_DIAMOND_ORE, DIAMOND_ATTEMPTS, DIAMOND_VEIN);
        placeVeins(chunk, random, baseX, baseZ,
                BS_LAPIS_ORE, LAPIS_ATTEMPTS, LAPIS_VEIN);
        placeVeins(chunk, random, baseX, baseZ,
                BS_EMERALD_ORE, EMERALD_ATTEMPTS, EMERALD_VEIN);
    }

    private void placeVeins(ChunkAccess chunk, RandomSource random, int baseX, int baseZ,
                             BlockState ore, int attempts, int veinSize) {
        for (int i = 0; i < attempts; i++) {
            int x = baseX + random.nextInt(16);
            int y = BASE_Y + random.nextInt(TOP_Y - BASE_Y);
            int z = baseZ + random.nextInt(16);
            placeExclusiveVein(chunk, random, x, y, z, ore, veinSize);
        }
    }

    /**
     * Кладёт руду только в STONE — гарантирует что руды появляются
     * только внутри тела острова, не в воздухе между островами.
     */
    private static void placeExclusiveVein(ChunkAccess chunk, RandomSource random,
                                            int cx, int cy, int cz,
                                            BlockState oreState, int veinSize) {
        double radius    = Math.cbrt(veinSize) * 0.9;
        double radiusSq  = radius * radius;
        double invRadius = 1.0 / radius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int r = (int) Math.ceil(radius);

        for (int dx = -r; dx <= r; dx++) {
            int dx2 = dx * dx;
            for (int dy = -r; dy <= r; dy++) {
                int dy2 = dy * dy;
                if (dx2 + dy2 > radiusSq) continue;
                for (int dz = -r; dz <= r; dz++) {
                    int distSq = dx2 + dy2 + dz * dz;
                    if (distSq > radiusSq) continue;

                    double prob = 1.0 - Math.sqrt(distSq) * invRadius * 0.5;
                    if (random.nextDouble() > prob) continue;

                    int wx = cx + dx;
                    int wy = cy + dy;
                    int wz = cz + dz;

                    if (wy < BASE_Y || wy > TOP_Y) continue;
                    if ((wx >> 4) != (cx >> 4) || (wz >> 4) != (cz >> 4)) continue;

                    pos.set(wx, wy, wz);
                    if (chunk.getBlockState(pos).is(Blocks.STONE)) {
                        chunk.setBlockState(pos, oreState, false);
                    }
                }
            }
        }
    }
}
