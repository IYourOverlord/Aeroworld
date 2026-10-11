package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Этап 4в (эксперимент, по умолчанию без изменений): приоритет пула Render Loader. В {@code ThreadPoolUtil.setupThreadPools}
 * он создаётся с {@code Thread.NORM_PRIORITY + 1} (константа 6, единственная такая в методе), и диспетчер потоков DH
 * обслуживает его раньше пула генерации. Флаг {@code -Daeroworld.dhRenderLoaderPriority=N} подменяет это число.
 * {@code require = 0}: при другой версии DH тихо не работает; применение видно по строке {@code RenderLoaderPriority active}.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil", remap = false)
public class RenderLoaderPriorityMixin {

    @ModifyConstant(method = "setupThreadPools", constant = @Constant(intValue = 6), require = 0)
    private static int aeroworld$renderLoaderPriority(int dhPriority) {
        AeroThroughputLimits.logRenderLoaderPriorityOnce(dhPriority);
        return AeroThroughputLimits.RENDER_LOADER_PRIORITY;
    }
}
