package org.example.aeroworld.mixin.structure;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code /locate biome} ({@code ServerLevel.findClosestBiome3d}) искал по настроенной копии
 * {@code AeroWorldChunkGenerator.getBiomeSource()} (сид мира + Layer1), а реальные чанки берут биомы из экземпляра в поле
 * {@code ChunkGenerator} (см. {@code BIOME_SYNC}). Карты не совпадали, и найденная точка не гарантировала биом.
 * Подменяем источник только для этого вызова; остальные читатели {@code getBiomeSource()} (структуры) не затронуты.
 */
@Mixin(value = ServerLevel.class, remap = false)
public abstract class LocateBiomeSourceMixin {

    @Redirect(method = "findClosestBiome3d",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getBiomeSource()Lnet/minecraft/world/level/biome/BiomeSource;",
                    remap = false),
            remap = false)
    private BiomeSource aeroworld$locateUsesFieldSource(ChunkGenerator generator) {
        return generator instanceof AeroWorldChunkGenerator aero ? aero.fieldBiomeSource() : generator.getBiomeSource();
    }
}
