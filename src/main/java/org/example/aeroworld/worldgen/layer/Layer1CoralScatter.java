package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.noise.AeroNoise;

/**
 * Редкие кораллы на пляжном песке у самой кромки воды.
 *
 * <p>Используются ЦЕЛЬНЫЕ коралловые блоки (не веточки/веера) — в отличие
 * от {@code CoralFanBlock}/{@code CoralPlantBlock}, {@code CoralBlock} не
 * отмирает без соседней воды, так что декорация остаётся стабильной даже
 * если игрок засыпет соседнюю воду песком.
 *
 * <p>Проверка "рядом ли вода" сделана через {@link Layer1FlatGenerator#columnProfile}
 * (чистая математика по шуму), а не чтением соседних блоков чанка — так
 * безопасно обращаться к координатам ЗА пределами текущего чанка (соседний
 * чанк на этом этапе генерации может быть ещё не создан).
 */
public final class Layer1CoralScatter {

    // Доля пляжных колонок у самой воды, получающих коралл (немного, не ковром).
    private static final double CORAL_CHANCE = 0.05;

    private static final BlockState[] CORALS = {
            Blocks.TUBE_CORAL_BLOCK  .defaultBlockState(),
            Blocks.BRAIN_CORAL_BLOCK .defaultBlockState(),
            Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState(),
            Blocks.FIRE_CORAL_BLOCK  .defaultBlockState(),
            Blocks.HORN_CORAL_BLOCK  .defaultBlockState(),
    };

    private final AeroNoise placementNoise;

    public Layer1CoralScatter(long worldSeed) {
        this.placementNoise = new AeroNoise(worldSeed ^ 0x0C0FA1_BEACEL);
    }

    public void scatter(ChunkAccess chunk, int chunkX, int chunkZ, Layer1FlatGenerator layer1) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                if (!layer1.isBeachColumn(wx, wz)) continue;
                if (!nearWater(layer1, wx, wz)) continue; // только у самой кромки, не по всему пляжу

                int topY = layer1.surfaceHeight(wx, wz);
                pos.set(wx, topY, wz);
                if (!chunk.getBlockState(pos).is(Blocks.SAND)) continue;

                double roll = (placementNoise.noise2D(wx * 0.7, wz * 0.7) + 1.0) * 0.5; // 0..1
                if (roll > CORAL_CHANCE) continue;

                double pick = (placementNoise.noise2D(wx * 0.13 + 500, wz * 0.13 + 500) + 1.0) * 0.5;
                int idx = Math.min(CORALS.length - 1, (int) (pick * CORALS.length));

                chunk.setBlockState(pos.above(), CORALS[idx], false);
            }
        }
    }

    /** Проверяет соседние (в радиусе 2 блоков) колонки через шум, а не блоки чанка. */
    private boolean nearWater(Layer1FlatGenerator layer1, int wx, int wz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (layer1.columnProfile(wx + dx, wz + dz).waterY != -1) return true;
            }
        }
        return false;
    }
}
