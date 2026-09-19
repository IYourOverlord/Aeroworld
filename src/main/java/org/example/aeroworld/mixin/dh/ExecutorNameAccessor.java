package org.example.aeroworld.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker$Executor", remap = false)
public interface ExecutorNameAccessor {
    @Accessor("name")
    String aeroworld$name();
}
