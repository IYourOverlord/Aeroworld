package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import java.util.Set;
import org.example.aeroworld.worldgen.dh.AeroCascadeLeaf;
import org.example.aeroworld.worldgen.dh.AeroPropStats;
import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пропагатор DH: измерение лавины задач и экспериментальная ручка каскада в листья.
 * <p>
 * Измерение ({@code -Daeroworld.dhPoolStats=true}): в {@code runParentUpdates}/{@code runChildUpdates} позиция запоминается при
 * {@code updatingPosSet.add}, затем задача оборачивается в {@code execute}; строка {@code Prop:} в логе.
 * <p>
 * Ручка ({@code -Daeroworld.dhCascadeLeafRadius=N}, блоки, 0 = выключено): в {@code runChildUpdates} родитель детализации 7
 * дальше N блоков от игрока не ставится в работу, а его флаг {@code ApplyToChildren} сбрасывается (иначе он выбирался бы
 * каждый проход). Для DH это выглядит как «позиция уже обновляется» ({@code continue}).
 * Плагин подмешивает класс, если включён любой из флагов. {@code require = 0}: при другой версии DH молча не работает.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2", remap = false)
public class PropagateStatsMixin {

    @Shadow @Final private FullDataSourceProviderV2 provider;

    @Redirect(method = "runParentUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean aeroworld$upAdd(Set<Object> set, Object pos) {
        boolean added = set.add(pos);
        if (added && AeroPropStats.ENABLED) AeroPropStats.notePending((Long) pos, DhSectionPos.getDetailLevel((Long) pos));
        return added;
    }

    @Redirect(method = "runParentUpdates", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/threading/PriorityTaskPicker$Executor;execute(Ljava/lang/Runnable;)V"), require = 0)
    private void aeroworld$upExecute(PriorityTaskPicker.Executor executor, Runnable task) {
        executor.execute(AeroPropStats.ENABLED ? AeroPropStats.wrap(0, task) : task);
    }

    @Inject(method = "runChildUpdates", at = @At("HEAD"), require = 0)
    private void aeroworld$childTarget(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos, CallbackInfo info) {
        AeroCascadeLeaf.setTarget(targetBlockPos.getX(), targetBlockPos.getZ());
    }

    @Redirect(method = "runChildUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean aeroworld$downAdd(Set<Object> set, Object boxed) {
        long pos = (Long) boxed;
        if (AeroCascadeLeaf.RADIUS > 0 && AeroCascadeLeaf.skip(DhSectionPos.getDetailLevel(pos),
                DhSectionPos.getMinCornerBlockX(pos), DhSectionPos.getMinCornerBlockZ(pos), DhSectionPos.getBlockWidth(pos))) {
            if (AeroCascadeLeaf.firstUse()) AeroThroughputLimits.logCascadeLeafActive(AeroCascadeLeaf.RADIUS);
            this.provider.repo.setApplyToChild(pos, false);
            return false;
        }
        boolean added = set.add(boxed);
        if (added && AeroPropStats.ENABLED) AeroPropStats.notePending(pos, DhSectionPos.getDetailLevel(pos));
        return added;
    }

    @Redirect(method = "runChildUpdates", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/threading/PriorityTaskPicker$Executor;execute(Ljava/lang/Runnable;)V"), require = 0)
    private void aeroworld$downExecute(PriorityTaskPicker.Executor executor, Runnable task) {
        executor.execute(AeroPropStats.ENABLED ? AeroPropStats.wrap(1, task) : task);
    }
}
