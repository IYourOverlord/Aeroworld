package org.example.aeroworld.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Shared spherical ore vein placement logic used by all layer ore generators.
 */
public final class OreVeinHelper {

    private OreVeinHelper() {}

    /**
     * Places a spherical ore vein centred at (cx, cy, cz).
     * Only replaces STONE blocks. Probabilistic edge falloff for organic shape.
     *
     * veinSize ≤ 0 is a no-op — prevents accidentally placing single-block
     * "veins" when radius rounds to 0 (Math.cbrt(0)*0.9 = 0, r = 0, the loop
     * body still executes once for dx=dy=dz=0 with distSq=0 and prob=1.0).
     */
    public static void placeVein(ChunkAccess chunk, RandomSource random,
                                  int cx, int cy, int cz,
                                  BlockState oreState, int veinSize) {
        if (veinSize <= 0) return;   // safeguard — ничего не ставим

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

                    double placeProbability = 1.0 - Math.sqrt(distSq) * invRadius * 0.5;
                    if (random.nextDouble() > placeProbability) continue;

                    int wx = cx + dx;
                    int wy = cy + dy;
                    int wz = cz + dz;

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
