package org.example.aeroworld.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.ConcurrentLinkedQueue;

@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public interface LodQuadTreeAccessor {
    @Accessor("sectionsToReload")
    ConcurrentLinkedQueue<Long> aeroworld$sectionsToReload();
}
