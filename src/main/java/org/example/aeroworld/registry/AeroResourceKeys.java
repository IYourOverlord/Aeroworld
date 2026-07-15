package org.example.aeroworld.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.example.aeroworld.AeroWorld;

public class AeroResourceKeys {

    // ── Dimension Type ────────────────────────────────────────────────
    public static final ResourceKey<DimensionType> AEROWORLD_DIMENSION_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(AeroWorld.MOD_ID, "aeroworld"));

    // ── Level Stem (the dimension slot) ──────────────────────────────
    public static final ResourceKey<LevelStem> AEROWORLD_STEM =
            ResourceKey.create(Registries.LEVEL_STEM,
                    ResourceLocation.fromNamespaceAndPath(AeroWorld.MOD_ID, "aeroworld"));

    // ── Level (world key used at runtime) ────────────────────────────
    public static final ResourceKey<Level> AEROWORLD_LEVEL =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(AeroWorld.MOD_ID, "aeroworld"));
}
