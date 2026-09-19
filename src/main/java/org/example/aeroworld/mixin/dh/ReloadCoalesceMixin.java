package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.ReloadCoalescer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Объединяет частые запросы перезагрузки секций LOD в LodQuadTree,
 * предотвращая лавинообразные пересчёты квад-дерева при массовой
 * аналитической генерации.
 *
 * capture: перехватывает queuePosToReload → отправляет в ReloadCoalescer вместо очереди;
 * release: при каждом reloadQueuedSections → сбрасывает созревшие запросы обратно в очередь.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public abstract class ReloadCoalesceMixin {

    @Inject(method = "queuePosToReload", at = @At("HEAD"), cancellable = true)
    private void aeroworld$captureReload(long pos, CallbackInfo ci) {
        ReloadCoalescer.capture(pos);
        ci.cancel();
    }

    @Inject(method = "reloadQueuedSections", at = @At("HEAD"))
    private void aeroworld$releaseReloads(CallbackInfo ci) {
        ConcurrentLinkedQueue<Long> queue =
                ((LodQuadTreeAccessor) this).aeroworld$sectionsToReload();
        ReloadCoalescer.flush(queue::add);
    }
}
