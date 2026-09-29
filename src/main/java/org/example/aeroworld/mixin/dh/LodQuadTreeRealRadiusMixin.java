package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Держит detail 0 (лист) до R*16 блоков вокруг игрока. Штатная формула даёт лишь 24-44 чанка,
 * а реальный chunk-gen запрашивается только для листьев. Патчится общий двухаргументный метод:
 * через него идут и обычный путь, и путь зума камеры.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public class LodQuadTreeRealRadiusMixin {

    @Inject(method = "calcDetailLevelFromDistance(DB)B", at = @At("HEAD"), cancellable = true)
    private void aeroworld$leafInsideRealRadius(double blockDistance, byte maxDetailLevel,
                                                CallbackInfoReturnable<Byte> info) {
        int radius = AeroThroughputLimits.realRadiusBlocks();
        if (radius > 0 && blockDistance <= radius) {
            info.setReturnValue(maxDetailLevel);
        }
    }
}