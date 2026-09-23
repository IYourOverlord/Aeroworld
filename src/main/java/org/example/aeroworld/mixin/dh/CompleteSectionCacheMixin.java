package org.example.aeroworld.mixin.dh;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.example.aeroworld.worldgen.dh.AeroCompleteSectionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Кэш полностью сгенерированных секций (п.8 PROGRESS.md, аналог {@code CompleteSectionCache}
 * из Distant Horizons SeedGen).
 * <p>
 * {@code GeneratedFullDataSourceProvider.getPositionsToRetrieve(long, byte)} — точка входа
 * пайплайна проверки статуса секций DH (п.8.1): вызывается каждым тиком {@code LodQuadTree}
 * ({@code tryQueuePosForRetrieval}) для каждой близкой world-gen секции. На КАЖДЫЙ вызов —
 * даже для уже подтверждённо полностью сгенерированных секций — метод читает
 * {@code repo.getColumnGenerationStepForPos} из SQLite и сканирует до 4096 байт
 * generation-step, что является чистым повторным обходом дерева квадрантов без результата.
 * <p>
 * HEAD-инъекция закорачивает вызов немедленным пустым списком (семантически идентично
 * ветке {@code positionFullyGenerated} оригинального метода), если {@link AeroCompleteSectionCache}
 * уже подтвердил полноту данной пары (pos, generatorDetailLevel). RETURN-инъекция фиксирует
 * в кэше результат оригинального вызова: пустой список -> секция полностью сгенерирована.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider", remap = false)
public abstract class CompleteSectionCacheMixin {

    @Inject(method = "getPositionsToRetrieve(JB)Lit/unimi/dsi/fastutil/longs/LongArrayList;",
            at = @At("HEAD"), cancellable = true)
    private void aeroworld$shortCircuitComplete(long pos, byte generatorDetailLevel,
                                                 CallbackInfoReturnable<LongArrayList> cir) {
        try {
            if (AeroCompleteSectionCache.isConfirmedComplete(pos, generatorDetailLevel)) {
                AeroCompleteSectionCache.recordHit();
                cir.setReturnValue(new LongArrayList());
            } else {
                AeroCompleteSectionCache.recordMiss();
            }
        } catch (Throwable ignored) {
            // Не ронять сервер при изменениях DH API — требование §3.9
        }
    }

    @Inject(method = "getPositionsToRetrieve(JB)Lit/unimi/dsi/fastutil/longs/LongArrayList;",
            at = @At("RETURN"))
    private void aeroworld$recordComplete(long pos, byte generatorDetailLevel,
                                           CallbackInfoReturnable<LongArrayList> cir) {
        try {
            LongArrayList result = cir.getReturnValue();
            if (result != null && result.isEmpty()) {
                AeroCompleteSectionCache.markComplete(pos, generatorDetailLevel);
            }
        } catch (Throwable ignored) {
            // Не ронять сервер при изменениях DH API — требование §3.9
        }
    }
}
