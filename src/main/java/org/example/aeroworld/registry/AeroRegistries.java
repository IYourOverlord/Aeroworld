package org.example.aeroworld.registry;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.biome.AeroBiomeSource;

import java.util.function.Supplier;

public class AeroRegistries {

    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, AeroWorld.MOD_ID);

    public static final Supplier<MapCodec<AeroBiomeSource>> AERO_BIOME_SOURCE =
            BIOME_SOURCES.register("aero_biome_source", () -> AeroBiomeSource.CODEC);

    public static void register(IEventBus modEventBus) {
        AeroDimensions.CHUNK_GENERATORS.register(modEventBus);
        BIOME_SOURCES.register(modEventBus);
    }
}