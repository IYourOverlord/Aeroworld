package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ограничивает штатную регенерацию листьев радиусом R*16 блоков (вместо surfaceRegenMaxDistancePercent
 * от дальности LOD). generationMaxChunkRadius не трогаем: он режет и грубую генерацию.
 * На dedicated-сервере без игрока DH сам подставляет -1 (без ограничения).
 */
@Mixin(targets = "com.seibel.distanthorizons.core.util.WorldGenUtil", remap = false)
public class WorldGenUtilRegenRadiusMixin {

    @Inject(method = "getMaxRegenDistanceInBlocks()I", at = @At("HEAD"), cancellable = true)
    private static void aeroworld$regenInsideRealRadius(CallbackInfoReturnable<Integer> info) {
        int radius = AeroThroughputLimits.realRadiusBlocks();
        if (radius > 0) {
            info.setReturnValue(radius);
        }
    }
}