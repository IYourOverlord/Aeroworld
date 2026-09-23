package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Регулирует запуск задач генерации мира, отдавая приоритет загрузке геометрии на рендер,
 * если очередь рендера перегружена.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil", remap = false)
public class WorldGenSpeedGateMixin {

    @Inject(method = "worldGenThreadsCanRun", at = @At("HEAD"), cancellable = true)
    private static void aeroworld$yieldToRendering(CallbackInfoReturnable<Boolean> info) {
        PriorityTaskPicker.Executor renderLoader = ThreadPoolUtil.getRenderLoadingExecutor();
        int queueSize = renderLoader != null ? renderLoader.getQueueSize() : 0;
        boolean renderingIsBehind = renderLoader != null && queueSize > AeroThroughputLimits.RENDER_YIELD_QUEUE;
        AeroThroughputLimits.recordRenderGateCheck(renderingIsBehind, queueSize);
        if (renderingIsBehind) {
            info.setReturnValue(false);
        }
    }
}