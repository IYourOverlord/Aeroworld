package org.example.aeroworld.worldgen.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

public class ChunkAccessWriter implements ChunkWriter {
    private final ChunkAccess chunk;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public ChunkAccessWriter(ChunkAccess chunk) {
        this.chunk = chunk;
    }

    @Override
    public void setBlockState(int wx, int wy, int wz, BlockState state) {
        pos.set(wx, wy, wz);
        chunk.setBlockState(pos, state, false);
    }

    @Override
    public BlockState getBlockState(int wx, int wy, int wz) {
        pos.set(wx, wy, wz);
        return chunk.getBlockState(pos);
    }
}
