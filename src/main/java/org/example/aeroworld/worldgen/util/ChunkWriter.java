package org.example.aeroworld.worldgen.util;

import net.minecraft.world.level.block.state.BlockState;

public interface ChunkWriter {
    void setBlockState(int wx, int wy, int wz, BlockState state);
    BlockState getBlockState(int wx, int wy, int wz);
}
