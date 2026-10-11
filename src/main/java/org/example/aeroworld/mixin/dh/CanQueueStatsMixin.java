package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import org.example.aeroworld.worldgen.dh.AeroHybridStats;
import org.example.aeroworld.worldgen.dh.AeroHybridStats.QueueGate;
import com.seibel.distanthorizons.core.util.delayedSaveCache.DelayedDataSourceSaveCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Этап 0 (только измерение): как часто {@code canQueueRetrievalNow} пускает новые задачи и почему отказывает
 * (полна очередь ожидания или много чанков ждёт обновления), плюс средняя глубина очереди.
 * Причины отказа вычисляются заново из тех же публичных значений, что и в самом методе. Удалить вместе с {@link AeroHybridStats}.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider", remap = false)
public class CanQueueStatsMixin {

    @Shadow @Final protected DelayedDataSourceSaveCache delayedFullDataSourceSaveCache;

    /** Этап 4: условие «несохранённых секций >= лимит» не отказ, а flush, и после RETURN его уже не видно, поэтому смотрим на входе. */
    @Inject(method = "canQueueRetrievalNow(Z)Z", at = @At("HEAD"), require = 0)
    private void aeroworld$statsUnsaved(boolean prune, CallbackInfoReturnable<Boolean> info) {
        try {
            AeroHybridStats.onUnsaved(this.delayedFullDataSourceSaveCache.getUnsavedCount(),
                    GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount());
        } catch (Throwable ignored) {
            // измерение не должно ломать DH
        }
    }

    /** Этап 4: из чего складывается счётчик «chunks left» (его значение задаёт LodQuadTree каждый тик). */
    @Inject(method = "setEstimatedRemainingRetrievalChunkCount(J)V", at = @At("HEAD"), require = 0)
    private void aeroworld$statsRemaining(long newCount, CallbackInfo info) {
        try {
            IFullDataSourceRetrievalQueue queue = ((GeneratedFullDataSourceProvider) (Object) this).worldGenQueueRef.get();
            if (queue != null) {
                AeroHybridStats.onRemaining(newCount, queue.getQueuedChunkCount(), queue.getWaitingTaskCount(), queue.getInProgressTaskCount());
            }
        } catch (Throwable ignored) {
            // измерение не должно ломать DH
        }
    }

    @Inject(method = "canQueueRetrievalNow(Z)Z", at = @At("RETURN"), require = 0)
    private void aeroworld$statsCanQueue(boolean prune, CallbackInfoReturnable<Boolean> info) {
        try {
            IFullDataSourceRetrievalQueue queue = ((GeneratedFullDataSourceProvider) (Object) this).worldGenQueueRef.get();
            int max = GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount();
            int waiting = queue == null ? -1 : queue.getWaitingTaskCount();
            QueueGate gate;
            if (info.getReturnValueZ()) {
                gate = QueueGate.OPEN;
            } else if (queue != null && WorldChunkUpdateManager.INSTANCE.getTotalQueuedCount() >= max) {
                gate = QueueGate.CHUNK_UPDATES_FULL;
            } else if (queue != null && waiting >= max) {
                gate = QueueGate.QUEUE_FULL;
            } else {
                gate = QueueGate.OTHER;
            }
            AeroHybridStats.onCanQueue(gate, waiting, max);
        } catch (Throwable ignored) {
            // измерение не должно ломать DH
        }
    }
}
