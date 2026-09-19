package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Сокращает задержку сохранения LOD-данных в GeneratedFullDataSourceProvider.
 * Штатное значение (10 с) слишком велико для аналитического SeedGen —
 * при быстрой генерации буфер переполняется раньше.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider", remap = false)
public class SaveDelayMixin {

    @ModifyConstant(
            method = "<init>(Lcom/seibel/distanthorizons/core/level/IDhLevel;"
                    + "Lcom/seibel/distanthorizons/core/file/structure/ISaveStructure;"
                    + "Ljava/io/File;)V",
            constant = @Constant(intValue = 10000))
    private int aeroworld$shortenSaveDelay(int original) {
        return AeroThroughputLimits.SAVE_DELAY_MS;
    }
}
