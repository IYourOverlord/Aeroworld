package org.example.aeroworld.mixin.structure;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.structure.DeepLakeMonumentPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

/**
 * {@code minecraft:monument} в AeroWorld живёт только на дне глубокого озера Layer 1, по одному на озеро.
 *
 * <p>Старт допускается только в чанке центра озера (остальные старты, в том числе от ванильного
 * {@code random_spread}, отклоняются), а низ монумента ({@code MONUMENT_MIN_Y}) считается от плоского дна озера:
 * ванильный Y 39 рассчитан на уровень моря 63, при {@code SEA_LEVEL = 0} монумент висел бы над озером.
 * Затрагивает только генерацию структур AeroWorld (флаг {@code IS_GENERATING}); в остальных измерениях
 * поведение ванильное.</p>
 */
@Mixin(value = OceanMonumentStructure.class, remap = false)
public abstract class OceanMonumentStructureMixin {

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true, remap = false)
    private void aeroworld$onlyAtDeepLakeCentre(Structure.GenerationContext context,
                                                CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        // Сбрасываем всегда: потоки воркеров переиспользуются, значение от чужого старта не должно протечь.
        DeepLakeMonumentPlacement.MONUMENT_MIN_Y.remove();
        if (!AeroWorldChunkGenerator.IS_GENERATING.get()
                || !(context.chunkGenerator() instanceof AeroWorldChunkGenerator generator)) {
            return;
        }
        Layer1TerrainGenerator terrain = generator.getLayer1Terrain();
        ChunkPos chunk = context.chunkPos();
        int[] centre = terrain == null ? null : terrain.getDeepLakeCentre(chunk.x, chunk.z);
        if (centre == null) {
            cir.setReturnValue(Optional.empty());
            return;
        }
        DeepLakeMonumentPlacement.MONUMENT_MIN_Y.set(
                terrain.getHeight(centre[0], centre[1]) - DeepLakeMonumentPlacement.BURY_DEPTH);
    }

    /**
     * Ванильная проверка «вокруг только океан» ({@code required_ocean_monument_surrounding}) идёт по экземпляру
     * биом-источника из поля {@code ChunkGenerator}, а там сид и Layer1 не применены (см. {@code BIOME_SYNC}):
     * {@code deep_lake} там не выставляется, и старт всегда отклонялся. Пропускаем её, когда старт уже принят
     * хэдом выше (центр глубокого озера, {@code MONUMENT_MIN_Y != null}); биом самой точки старта ванильный
     * {@code Structure.generate} проверит по настроенной копии ({@code getBiomeSource()}).
     */
    @Redirect(method = "findGenerationPoint",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/BiomeSource;getBiomesWithin(IIIILnet/minecraft/world/level/biome/Climate$Sampler;)Ljava/util/Set;",
                    remap = false),
            remap = false)
    private Set<Holder<Biome>> aeroworld$skipFieldSourceSurroundingCheck(BiomeSource source, int x, int y, int z,
                                                                         int radius, Climate.Sampler sampler) {
        return DeepLakeMonumentPlacement.MONUMENT_MIN_Y.get() != null
                ? Set.of() : source.getBiomesWithin(x, y, z, radius, sampler);
    }
}
