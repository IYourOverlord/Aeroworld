package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import org.example.aeroworld.worldgen.dh.AeroReloadStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Этап 5б, только измерение: как часто DH перестраивает уже показанные секции. {@code @Inject HEAD} на
 * {@code queuePosToReload} считает постановки (секция и четыре соседа, как в DH) и интервалы между ними;
 * {@code @Redirect} на {@code uploadRenderDataToGpuAsync} внутри {@code reloadQueuedSections} считает реально начатые
 * перестройки. Поведение DH не меняется. Включается плагином только при {@code -Daeroworld.dhPoolStats=true}.
 * {@code require = 0}: каждая инъекция при первом срабатывании пишет свою строку {@code Reload hook ... active};
 * нет строки значит имена методов не совпали (DH 3.3.3 против 3.3.2). Итог: строка {@code Reload:} в логе.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public class ReloadStatsMixin {

    private static final Logger AERO_LOGGER = LoggerFactory.getLogger("AeroWorld/Reload");
    private static final java.util.concurrent.atomic.AtomicBoolean AERO_TRIG_SEEN = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean AERO_BUILD_SEEN = new java.util.concurrent.atomic.AtomicBoolean();

    @Inject(method = "queuePosToReload", at = @At("HEAD"), require = 0)
    private void aeroworld$reloadTrigger(long pos, CallbackInfo ci) {
        if (AERO_TRIG_SEEN.compareAndSet(false, true)) AERO_LOGGER.info("[AeroWorld DH SeedGen] Reload hook trigger active");
        long now = System.nanoTime();
        AeroReloadStats.noteTrigger(DhSectionPos.getDetailLevel(pos), pos, now);
        for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS) {
            long adjacent = DhSectionPos.getAdjacentPos(pos, direction);
            AeroReloadStats.noteTrigger(DhSectionPos.getDetailLevel(adjacent), adjacent, now);
        }
    }

    @Redirect(method = "reloadQueuedSections", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodRenderSection;uploadRenderDataToGpuAsync()Z"), require = 0)
    private boolean aeroworld$reloadBuild(LodRenderSection section) {
        if (AERO_BUILD_SEEN.compareAndSet(false, true)) AERO_LOGGER.info("[AeroWorld DH SeedGen] Reload hook rebuild active");
        boolean started = section.uploadRenderDataToGpuAsync();
        AeroReloadStats.noteRebuild(DhSectionPos.getDetailLevel(section.pos), section.pos, System.nanoTime(), started);
        return started;
    }
}
