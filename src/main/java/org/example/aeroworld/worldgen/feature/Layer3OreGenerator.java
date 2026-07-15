package org.example.aeroworld.worldgen.feature;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Layer 3 ore generation — 200% of vanilla rate, 2x vein size.
 * Y range: 1000–1100.
 * No diamonds, lapis or emeralds — all three are Layer 4 exclusive.
 */
public class Layer3OreGenerator {

    private static final BlockState BS_COAL_ORE    = Blocks.COAL_ORE    .defaultBlockState();
    private static final BlockState BS_IRON_ORE    = Blocks.IRON_ORE    .defaultBlockState();
    private static final BlockState BS_GOLD_ORE    = Blocks.GOLD_ORE    .defaultBlockState();
    private static final BlockState BS_REDSTONE_ORE= Blocks.REDSTONE_ORE.defaultBlockState();

    private static final int BASE_Y = 1000;
    private static final int TOP_Y  = 1100;

    private static final int COAL_VEIN     = 17 * 2;
    private static final int IRON_VEIN     = 9  * 2;
    private static final int GOLD_VEIN     = 9  * 2;
    private static final int REDSTONE_VEIN = 8  * 2;

    // 200% of vanilla
    private static final int COAL_ATTEMPTS     = 20 * 2;
    private static final int IRON_ATTEMPTS     = 20 * 2;
    private static final int GOLD_ATTEMPTS     = 4  * 2;
    private static final int REDSTONE_ATTEMPTS = 8  * 2;

    public void generateOres(ChunkAccess chunk, RandomSource random, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        placeVeins(chunk, random, baseX, baseZ, BS_COAL_ORE,
                COAL_ATTEMPTS, COAL_VEIN);
        placeVeins(chunk, random, baseX, baseZ, BS_IRON_ORE,
                IRON_ATTEMPTS, IRON_VEIN);
        placeVeins(chunk, random, baseX, baseZ, BS_GOLD_ORE,
                GOLD_ATTEMPTS, GOLD_VEIN);
        placeVeins(chunk, random, baseX, baseZ, BS_REDSTONE_ORE,
                REDSTONE_ATTEMPTS, REDSTONE_VEIN);
    }

    private void placeVeins(ChunkAccess chunk, RandomSource random, int baseX, int baseZ,
                             BlockState ore, int attempts, int veinSize) {
        for (int i = 0; i < attempts; i++) {
            int x = baseX + random.nextInt(16);
            int y = BASE_Y + random.nextInt(TOP_Y - BASE_Y);
            int z = baseZ + random.nextInt(16);
            OreVeinHelper.placeVein(chunk, random, x, y, z, ore, veinSize);
        }
    }
}
