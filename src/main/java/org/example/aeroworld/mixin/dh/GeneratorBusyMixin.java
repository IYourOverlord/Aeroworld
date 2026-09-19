package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Расширяет допустимый бэклог задач генерации в очереди WorldGenerationQueue,
 * предотвращая ложные срабатывания isGeneratorBusy при аналитическом SeedGen.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public class GeneratorBusyMixin {

    @Inject(method = "isGeneratorBusy", at = @At("RETURN"), cancellable = true)
    private void aeroworld$allowDeeperBacklog(CallbackInfoReturnable<Boolean> info) {
        if (!info.getReturnValueZ() || ThreadPoolUtil.getWorldGenExecutor() == null) {
            return;
        }
        int allowed = AeroThroughputLimits.distantHorizonsThreadCount() * AeroThroughputLimits.IN_FLIGHT_SCALE;
        WorldGenerationQueue self = (WorldGenerationQueue) (Object) this;
        if (self.getInProgressTaskCount() <= allowed) {
            info.setReturnValue(false);
        }
    }
}
