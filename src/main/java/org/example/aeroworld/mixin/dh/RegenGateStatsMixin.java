package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import org.example.aeroworld.worldgen.dh.AeroHybridStats;
import org.example.aeroworld.worldgen.dh.AeroHybridStats.RegenGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Этап 0 (только измерение): сколько раз DH пытался перезапросить листья для фазы 2 и какое условие его останавливало.
 * Условия повторяют начало {@code queueRegeneration} (нет очереди / регенерация выключена / в очереди есть грубая задача).
 * Удалить вместе с {@link AeroHybridStats}. {@code require = 0}: при другой версии DH счётчик просто не работает.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2", remap = false)
public class RegenGateStatsMixin {

    @Inject(method = "tryQueueRegeneration", at = @At("HEAD"), require = 0)
    private void aeroworld$statsRegenTry(DhBlockPos targetBlockPos, CallbackInfo info) {
        AeroHybridStats.onRegenTry();
    }

    @Inject(method = "queueRegeneration", at = @At("HEAD"), require = 0)
    private void aeroworld$statsRegenGate(GeneratedFullDataSourceProvider genProvider, DhBlockPos targetBlockPos,
            CallbackInfoReturnable<Boolean> info) {
        try {
            IFullDataSourceRetrievalQueue queue = genProvider.worldGenQueueRef.get();
            RegenGate gate;
            if (queue == null) {
                gate = RegenGate.NO_QUEUE;
            } else if (!queue.getCanRegenerate()) {
                gate = RegenGate.REGEN_OFF;
            } else if (queue.requestPosExistsWhere(p -> DhSectionPos.getDetailLevel(p) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)) {
                gate = RegenGate.COARSE_WAITING;
            } else {
                gate = RegenGate.PROCEED;
            }
            AeroHybridStats.onRegenGate(gate);
        } catch (Throwable ignored) {
            // измерение не должно ломать DH
        }
    }
}
