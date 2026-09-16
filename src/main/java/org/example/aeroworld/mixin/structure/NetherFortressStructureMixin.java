package org.example.aeroworld.mixin.structure;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code minecraft:bastion_remnant} — обычный {@code minecraft:jigsaw} с полем
 * {@code start_height}, поэтому его высота переносится в главную пещеру Layer 1
 * чисто датапаком (см. {@code data/minecraft/worldgen/structure/bastion_remnant.json}).
 *
 * {@code minecraft:fortress} — нет: {@link NetherFortressStructure} — legacy
 * захардкоженная структура без height-конфига. {@code findGenerationPoint}
 * фиксирует стартовую точку на Y=64, а {@code generatePieces} в конце ВСЕГДА
 * пересчитывает финальную высоту через
 * {@code StructurePiecesBuilder.moveBelowSeaLevel(seaLevel, minY, random, height)}
 * — то есть даже если бы стартовый Y=64 удалось поменять датапаком (нельзя),
 * это всё равно перезаписывается этим вызовом. В AeroWorld
 * {@code getSeaLevel() == 0}, из-за чего крепость утапливается к Y≈-3..-5 —
 * мимо гигантской пещеры (Y {@link Layer1TerrainGenerator#CAVE_BOTTOM_Y}..
 * {@link Layer1TerrainGenerator#CAVE_TOP_Y}). Единственная точка, где высоту
 * можно перехватить — сам этот вызов.
 *
 * <p>Затрагивает ТОЛЬКО измерение AeroWorld: guard по
 * {@code instanceof AeroWorldChunkGenerator}. Ванильный Нижний мир и любые
 * другие измерения используют {@code NoiseBasedChunkGenerator} напрямую —
 * под условие не попадают, вызывают оригинальный
 * {@code moveBelowSeaLevel} без изменений.
 */
@Mixin(value = NetherFortressStructure.class, remap = false)
public abstract class NetherFortressStructureMixin {

    @Redirect(
            method = "generatePieces",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePiecesBuilder;moveBelowSeaLevel(IILnet/minecraft/util/RandomSource;I)I"
            )
    )
    private static int aeroworld$placeFortressInMainCave(StructurePiecesBuilder collector,
                                                           int seaLevel, int minY,
                                                           RandomSource random, int height,
                                                           Structure.GenerationContext context) {
        if (!(context.chunkGenerator() instanceof AeroWorldChunkGenerator)) {
            return collector.moveBelowSeaLevel(seaLevel, minY, random, height);
        }

        // Смещаем уже собранные piece'ы так, чтобы низ структуры попал в
        // случайную точку внутри главной пещеры, с запасом под высоту (height)
        // — тем же смыслом, что и оригинальный seaLevel - height - random(3).
        int caveBottom = Layer1TerrainGenerator.CAVE_BOTTOM_Y;
        int caveTop    = Layer1TerrainGenerator.CAVE_TOP_Y;
        int band       = Math.max(1, (caveTop - caveBottom) - height);

        BoundingBox current = collector.getBoundingBox();
        int targetMinY = caveBottom + random.nextInt(band);
        int offset     = targetMinY - current.minY();

        collector.offsetPiecesVertically(offset);
        return offset;
    }
}
