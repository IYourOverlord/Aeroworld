package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Повышает приоритет "Render Loader" исполнителя в планировщике DH,
 * уменьшая его учётное время выполнения при сортировке очереди задач.
 * Это заставляет DH чаще выбирать рендер-загрузку вместо генерации,
 * снижая визуальные задержки при быстрой аналитической генерации.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker", remap = false)
public class SchedulerPriorityMixin {

    private static final String RENDER_LOADER = "Render Loader";

    @Inject(method = "lambda$getExecutorIteratorSortedByShortestTotalRunTime$0",
            at = @At("RETURN"), cancellable = true)
    private static void aeroworld$favourRendering(
            PriorityTaskPicker.Executor executor,
            CallbackInfoReturnable<Long> info) {
        if (RENDER_LOADER.equals(((ExecutorNameAccessor) executor).aeroworld$name())) {
            info.setReturnValue(info.getReturnValueJ() / AeroThroughputLimits.RENDER_PRIORITY);
        }
    }
}
