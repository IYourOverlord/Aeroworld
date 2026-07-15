package org.example.aeroworld.spawning;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.registry.AeroResourceKeys;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

/**
 * Blocks ALL mob spawning at Y >= 2000 (Layer 4) inside the AeroWorld dimension.
 */
@EventBusSubscriber(modid = AeroWorld.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class LayerSpawnRestriction {

    private static final int LAYER_4_MIN_Y = UpperIslandGenerator.LAYER_MIN_Y;

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (!level.dimension().equals(AeroResourceKeys.AEROWORLD_LEVEL)) return;
        if (event.getY() >= LAYER_4_MIN_Y) {
            event.setSpawnCancelled(true);
        }
    }
}
