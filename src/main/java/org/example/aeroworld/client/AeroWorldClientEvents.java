package org.example.aeroworld.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.example.aeroworld.AeroWorld;

/**
 * Client-only event handler.
 * Handles UI customization for the AeroWorld world preset on the world creation screen.
 *
 * The world preset button itself is registered via the datapack tag system
 * (data/minecraft/tags/worldgen/world_preset/normal.json).
 * This class handles any additional client-side rendering tweaks.
 */
@EventBusSubscriber(modid = AeroWorld.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class AeroWorldClientEvents {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // Reserved for future UI customization:
        // - Custom icon for the AeroWorld preset button
        // - Layer info overlay
        // The world preset button is already injected by Minecraft's built-in
        // WorldPreset system via the normal.json tag.
    }
}
