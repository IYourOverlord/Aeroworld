package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import java.util.Set;
import org.example.aeroworld.worldgen.dh.AeroPropStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Пропагатор DH (только измерение): сколько задач и каких ставит пропагатор и сколько из них повторы по той же позиции.
 * В {@code runParentUpdates}/{@code runChildUpdates} позиция запоминается при {@code updatingPosSet.add}, затем задача
 * оборачивается в {@code execute}; строка {@code Prop:} в логе. Включается плагином только при {@code -Daeroworld.dhPoolStats=true}.
 * {@code require = 0}: при другой версии DH измерение молча не работает (в логе не будет строки {@code Prop:}).
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2", remap = false)
public class PropagateStatsMixin {

    @Redirect(method = "runParentUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean aeroworld$upAdd(Set<Object> set, Object pos) {
        boolean added = set.add(pos);
        if (added) AeroPropStats.notePending((Long) pos, DhSectionPos.getDetailLevel((Long) pos));
        return added;
    }

    @Redirect(method = "runParentUpdates", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/threading/PriorityTaskPicker$Executor;execute(Ljava/lang/Runnable;)V"), require = 0)
    private void aeroworld$upExecute(PriorityTaskPicker.Executor executor, Runnable task) {
        executor.execute(AeroPropStats.wrap(0, task));
    }

    @Redirect(method = "runChildUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean aeroworld$downAdd(Set<Object> set, Object pos) {
        boolean added = set.add(pos);
        if (added) AeroPropStats.notePending((Long) pos, DhSectionPos.getDetailLevel((Long) pos));
        return added;
    }

    @Redirect(method = "runChildUpdates", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/threading/PriorityTaskPicker$Executor;execute(Ljava/lang/Runnable;)V"), require = 0)
    private void aeroworld$downExecute(PriorityTaskPicker.Executor executor, Runnable task) {
        executor.execute(AeroPropStats.wrap(1, task));
    }
}
