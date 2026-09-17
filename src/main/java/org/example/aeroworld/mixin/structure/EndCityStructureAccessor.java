package org.example.aeroworld.mixin.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.EndCityStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor-мискин: открывает приватный
 * {@code EndCityStructure.generatePieces(StructurePiecesBuilder, BlockPos, Rotation, Structure.GenerationContext)}
 * для {@link EndCityStructureMixin}, чтобы переиспользовать ванильную
 * генерацию пивсов ({@code EndCityPieces.startHouseTower(...)}) без
 * копирования/переопределения этой логики.
 */
@Mixin(value = EndCityStructure.class, remap = false)
public interface EndCityStructureAccessor {

    @Invoker("generatePieces")
    void aeroworld$invokeGeneratePieces(StructurePiecesBuilder builder, BlockPos startPos,
                                         Rotation rotation, Structure.GenerationContext context);
}
