package org.example.aeroworld.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;


public class Layer2OreGenerator {

    private static final BlockState BS_COAL_ORE    = Blocks.COAL_ORE    .defaultBlockState();
    private static final BlockState BS_IRON_ORE    = Blocks.IRON_ORE    .defaultBlockState();
    private static final BlockState BS_GOLD_ORE    = Blocks.GOLD_ORE    .defaultBlockState();
    private static final BlockState BS_REDSTONE_ORE= Blocks.REDSTONE_ORE.defaultBlockState();
    private static final BlockState BS_COPPER_ORE  = Blocks.COPPER_ORE  .defaultBlockState();

    private static final int BASE_Y = 300;
    private static final int TOP_Y  = 400;

    // 2x vein sizes
    private static final int COAL_VEIN     = 17 * 2;
    private static final int IRON_VEIN     = 9  * 2;
    private static final int GOLD_VEIN     = 9  * 2;
    private static final int REDSTONE_VEIN = 8  * 2;
    private static final int COPPER_VEIN   = 10 * 2;

    // 100% of vanilla attempts
    private static final int COAL_ATTEMPTS     = 20;
    private static final int IRON_ATTEMPTS     = 20;
    private static final int GOLD_ATTEMPTS     = 4;
    private static final int REDSTONE_ATTEMPTS = 8;
    private static final int COPPER_ATTEMPTS   = 16;

    public void generateOres(ChunkAccess chunk, RandomSource random, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        placeVeins(chunk, random, baseX, baseZ, BS_COAL_ORE,
                COAL_ATTEMPTS, COAL_VEIN, BASE_Y, TOP_Y);
        placeVeins(chunk, random, baseX, baseZ, BS_IRON_ORE,
                IRON_ATTEMPTS, IRON_VEIN, BASE_Y, TOP_Y);
        placeVeins(chunk, random, baseX, baseZ, BS_GOLD_ORE,
                GOLD_ATTEMPTS, GOLD_VEIN, BASE_Y, TOP_Y);
        placeVeins(chunk, random, baseX, baseZ, BS_REDSTONE_ORE,
                REDSTONE_ATTEMPTS, REDSTONE_VEIN, BASE_Y, TOP_Y);
        placeVeins(chunk, random, baseX, baseZ, BS_COPPER_ORE,
                COPPER_ATTEMPTS, COPPER_VEIN, BASE_Y, TOP_Y);
    }

    private void placeVeins(ChunkAccess chunk, RandomSource random, int baseX, int baseZ,
                             BlockState ore, int attempts, int veinSize, int minY, int maxY) {
        for (int i = 0; i < attempts; i++) {
            int x = baseX + random.nextInt(16);
            int y = minY + random.nextInt(Math.max(1, maxY - minY));
            int z = baseZ + random.nextInt(16);
            OreVeinHelper.placeVein(chunk, random, x, y, z, ore, veinSize);
        }
    }
}
