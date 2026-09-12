package org.example.aeroworld.spawning;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.registry.AeroResourceKeys;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

/**
 * Blocks ALL mob spawning on Layer 3 (Y 1000–1100) and Layer 4 (Y >= 1900)
 * inside the AeroWorld dimension.
 */
@EventBusSubscriber(modid = AeroWorld.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class LayerSpawnRestriction {

    private static final int LAYER_3_MIN_Y = HighIslandGenerator.LAYER_MIN_Y;
    private static final int LAYER_3_MAX_Y = HighIslandGenerator.LAYER_MAX_Y;
    private static final int LAYER_4_MIN_Y = UpperIslandGenerator.LAYER_MIN_Y;

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (!level.dimension().equals(AeroResourceKeys.AEROWORLD_LEVEL)) return;
        double y = event.getY();
        if (y >= LAYER_4_MIN_Y || (y >= LAYER_3_MIN_Y && y <= LAYER_3_MAX_Y)) {
            event.setSpawnCancelled(true);
        }
    }
}