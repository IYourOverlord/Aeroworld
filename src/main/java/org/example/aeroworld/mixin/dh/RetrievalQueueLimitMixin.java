package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Увеличивает лимит очереди извлечения данных из БД/генератора,
 * предотвращая простой рабочих потоков при быстром перемещении.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider", remap = false)
public class RetrievalQueueLimitMixin {

    @ModifyConstant(method = "canQueueRetrievalNow(Z)Z", constant = @Constant(intValue = 20))
    private int aeroworld$deepenRetrievalLimit(int requestsPerThread) {
        return requestsPerThread * AeroThroughputLimits.QUEUE_SCALE;
    }
}