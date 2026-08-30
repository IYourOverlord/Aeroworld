package org.example.aeroworld.test;

import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.structure.StructureSet;

public class TestState {
    public static RandomState test(ChunkGeneratorStructureState state) {
        return state.randomState();
    }
}
