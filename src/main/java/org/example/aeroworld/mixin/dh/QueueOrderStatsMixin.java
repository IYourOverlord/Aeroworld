package org.example.aeroworld.mixin.dh;

import java.util.concurrent.atomic.AtomicInteger;

import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.example.aeroworld.worldgen.dh.AeroHybridStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Этап 0 (только измерение, поведение DH не меняется): при старте листовой (detail 6) задачи считает,
 * сколько грубых задач ещё ждёт в очереди и сколько из них накрывают этот лист. Приоритет DH —
 * {@code chebyshev / sectionDetail} ({@code tryStartNextWorldGenTask}), для 6 против 7 разница мала.
 * К этому моменту задача уже удалена из {@code waitingTaskByPos}. Удалить вместе с {@link AeroHybridStats}.
 * <p>
 * ponytail: полный обход очереди ожидания на каждый старт листа, O(ожидающих); потолок — лимит очереди
 * (20 x потоки x QUEUE_SCALE). Только на время измерений.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public class QueueOrderStatsMixin {

    @Inject(method = "startWorldGenTaskGroup", at = @At("HEAD"))
    private void aeroworld$statsLeafStart(DataSourceRetrievalTask task, CallbackInfo info) {
        try {
            long leaf = task.pos;
            if (!AeroHybridStats.ENABLED || DhSectionPos.getDetailLevel(leaf) != DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL) {
                return;
            }
            AtomicInteger coarse = new AtomicInteger();
            AtomicInteger ancestors = new AtomicInteger();
            ((WorldGenerationQueue) (Object) this).requestPosExistsWhere(p -> {
                if (DhSectionPos.getDetailLevel(p) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL) {
                    coarse.incrementAndGet();
                    if (DhSectionPos.contains(p, leaf)) ancestors.incrementAndGet();
                }
                return false; // обходим всех
            });
            AeroHybridStats.onLeafStart(coarse.get(), ancestors.get(), DhSectionPos.getX(leaf), DhSectionPos.getZ(leaf));
        } catch (Throwable ignored) {
            // измерение не должно ломать генерацию DH
        }
    }
}
