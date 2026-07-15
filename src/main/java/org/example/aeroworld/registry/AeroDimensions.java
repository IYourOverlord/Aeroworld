package org.example.aeroworld.registry;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;

import java.util.function.Supplier;

public class AeroDimensions {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, AeroWorld.MOD_ID);

    public static final Supplier<MapCodec<AeroWorldChunkGenerator>> AERO_CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("aero_generator", () -> AeroWorldChunkGenerator.CODEC);
}
