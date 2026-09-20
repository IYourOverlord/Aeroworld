package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ФИКС: дальние LOD не отображались при генерации через API-оверрайд SeedGen.
 *
 * DH-путь vanilla-генерации ({@code LodDataBuilder.createFromChunk}) ставит на
 * создаваемый {@link FullDataSourceV2} флаг {@code applyToParent = TRUE}, благодаря
 * которому DH даунсемплит (агрегирует) мелкие LOD-данные в грубые родительские
 * уровни, используемые рендером для дальних блоков.
 *
 * API-путь SeedGen использует вместо него {@code LodDataBuilder.createFromApiChunkData},
 * который НЕ выставляет {@code applyToParent}. Из-за этого иерархия грубых LOD
 * никогда не строится: в SQLite сохраняются только точные (chunk-уровень) источники
 * с {@code applyToParent = 0}, выше детализации ~detail 8 ничего нет, и рендер
 * отрисовывает лишь небольшой участок вокруг спавна, хотя DH-оверлей показывает
 * огромную сгенерированную территорию (все LOD-данные в БД, но не агрегированы).
 *
 * Здесь выставляется {@code applyToParent = TRUE} ровно так же, как в vanilla
 * {@code createFromChunk}. Далее {@code FullDataSourceV2.updateFromDataSource()}
 * в {@code WorldGenerationQueue.lambda$startApiChunkGenerationEvent$2} прокидывает
 * этот флаг в агрегированный источник (подробность < 15), и грубые LOD строятся.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder", remap = false)
public abstract class LodDataBuilderApplyToParentMixin {

	private static final Logger AERO_LOGGER = LoggerFactory.getLogger("AeroWorld/ApplyToParent");
	private static final AtomicLong AERO_COUNTER = new AtomicLong();

	@Inject(
			method = "createFromApiChunkData",
			at = @At("RETURN"),
			remap = false
	)
	private static void aeroworld$setApplyToParent(
			DhApiChunk chunk, boolean runValidation,
			CallbackInfoReturnable<FullDataSourceV2> cir) {
		FullDataSourceV2 source = cir.getReturnValue();
		if (source != null) {
			source.applyToParent = Boolean.TRUE;
			long n = AERO_COUNTER.incrementAndGet();
			if (n == 1 || n % 100000 == 0) {
				AERO_LOGGER.info("[AeroWorld] applyToParent=TRUE set on {} API data sources (pos={})", n, source.getPos());
			}
		}
	}
}