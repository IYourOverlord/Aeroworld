package org.example.aeroworld.mixin.structure;

import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.structure.DeepLakeMonumentPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Главное здание океанического монумента ({@code OceanMonumentPieces.MonumentBuilding}) держит два ванильных
 * числа, рассчитанных на уровень моря 63:
 * <ul>
 *   <li>низ здания в конструкторе: Y 39 (все комнаты строятся от этого {@code minY});</li>
 *   <li>верх заливки водой в {@code postProcess}: {@code Math.max(level.getSeaLevel(), 64)}.</li>
 * </ul>
 * Первое заменяется на {@code MONUMENT_MIN_Y} (дно глубокого озера), второе на {@code SEA_LEVEL} (редирект самого
 * {@code Math.max}, а не константы 64: {@code level.getSeaLevel()} может отдать и ванильные 63), иначе над озером
 * повис бы водяной столб до Y 64. Заливка трогается только у зданий ниже нуля, то есть у озёрных; ванильные
 * монументы (низ на 39) и остальные измерения не меняются.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces$MonumentBuilding",
        remap = false)
public abstract class OceanMonumentBuildingMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 39), remap = false)
    private static int aeroworld$monumentBottomY(int vanilla) {
        Integer minY = DeepLakeMonumentPlacement.MONUMENT_MIN_Y.get();
        return minY != null ? minY : vanilla;
    }

    @Redirect(method = "postProcess",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", remap = false),
            remap = false)
    private int aeroworld$monumentWaterTop(int seaLevel, int vanillaTop) {
        return ((StructurePiece) (Object) this).getBoundingBox().minY() < 0
                ? Layer1TerrainGenerator.SEA_LEVEL : Math.max(seaLevel, vanillaTop);
    }
}
