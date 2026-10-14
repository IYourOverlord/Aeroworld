package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.aeroworld.worldgen.dh.AeroReloadCoalescer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Этап 5б, ручка: склейка перезагрузок секций. {@code queuePosToReload} (секция и четыре соседа) не кладёт позиции сразу
 * в {@code sectionsToReload}, а откладывает их в {@link AeroReloadCoalescer}; в начале каждого такта
 * {@code reloadQueuedSections} готовые позиции (тишина или потолок удержания) переносятся в очередь DH, дальше работает
 * штатный код. Секции с уровнем детализации ниже {@code dhReloadExemptBelowDetail} идут в очередь сразу, как раньше.
 * Включён по умолчанию (этап 5б принят); плагин отключает миксин при {@code -Daeroworld.dhReloadQuietMs=0}.
 * {@code require = 0}: каждая инъекция при первом срабатывании пишет свою строку {@code ReloadCoalesce hook ... active}.
 * Поле {@code sectionsToReload} (private final, DH 3.3.2) читается через {@code @Shadow}; при другой версии миксин не
 * применится и склейки не будет.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public class ReloadCoalesceMixin {

    @Unique private static final Logger AERO_LOGGER = LoggerFactory.getLogger("AeroWorld/ReloadCoalesce");
    @Unique private static final AtomicBoolean AERO_HOLD_SEEN = new AtomicBoolean();
    @Unique private static final AtomicBoolean AERO_RELEASE_SEEN = new AtomicBoolean();

    @Shadow @Final private ConcurrentLinkedQueue<Long> sectionsToReload;

    @Unique private final AeroReloadCoalescer aeroworld$coalescer =
            new AeroReloadCoalescer(AeroReloadCoalescer.QUIET_MS, AeroReloadCoalescer.HOLD_MS);

    @Inject(method = "queuePosToReload", at = @At("HEAD"), cancellable = true, require = 0)
    private void aeroworld$hold(long pos, CallbackInfo ci) {
        if (AERO_HOLD_SEEN.compareAndSet(false, true)) {
            AERO_LOGGER.info("[AeroWorld DH SeedGen] ReloadCoalesce hook hold active: quiet={} ms, hold={} ms, exempt below d{}",
                    AeroReloadCoalescer.QUIET_MS, AeroReloadCoalescer.HOLD_MS, AeroReloadCoalescer.EXEMPT_BELOW_DETAIL);
        }
        long now = System.nanoTime();
        this.aeroworld$route(pos, now);
        for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS) {
            this.aeroworld$route(DhSectionPos.getAdjacentPos(pos, direction), now);
        }
        ci.cancel();
    }

    @Inject(method = "reloadQueuedSections", at = @At("HEAD"), require = 0)
    private void aeroworld$release(CallbackInfo ci) {
        if (AERO_RELEASE_SEEN.compareAndSet(false, true)) {
            AERO_LOGGER.info("[AeroWorld DH SeedGen] ReloadCoalesce hook release active");
        }
        this.aeroworld$coalescer.releaseReady(System.nanoTime(), p -> this.sectionsToReload.add(p));
    }

    @Unique
    private void aeroworld$route(long pos, long now) {
        if (DhSectionPos.getDetailLevel(pos) < AeroReloadCoalescer.EXEMPT_BELOW_DETAIL) {
            AeroReloadCoalescer.noteBypass();
            this.sectionsToReload.add(pos);
        } else {
            this.aeroworld$coalescer.trigger(pos, now);
        }
    }
}
