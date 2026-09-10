package org.example.aeroworld.worldgen.biome;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.noise.AeroNoise;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * AeroBiomeSource — биомный источник для AeroWorld с кастомным noise-based распределением для Layer 1
 * и aeroworld:* биомами для островов.
 */
public class AeroBiomeSource extends BiomeSource {

    // JSON-формат: { "type": "aeroworld:aero_biome_source", "preset": "minecraft:overworld" }
    public static final MapCodec<AeroBiomeSource> CODEC =
            MultiNoiseBiomeSource.CODEC.xmap(
                    AeroBiomeSource::new,
                    src -> src.delegate
            );

    private static final String[] ALL_CLONED_BIOMES = {
            "badlands", "bamboo_jungle", "beach", "birch_forest", "cherry_grove",
            "cold_ocean", "dark_forest", "deep_cold_ocean", "deep_dark", "deep_frozen_ocean",
            "deep_lukewarm_ocean", "deep_ocean", "desert", "dripstone_caves", "eroded_badlands",
            "flower_forest", "forest", "frozen_ocean", "frozen_peaks", "frozen_river",
            "grove", "ice_spikes", "jagged_peaks", "jungle", "lukewarm_ocean",
            "lush_caves", "mangrove_swamp", "meadow", "mushroom_fields", "ocean",
            "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "plains", "river",
            "savanna", "savanna_plateau", "snowy_beach", "snowy_plains", "snowy_slopes",
            "snowy_taiga", "sparse_jungle", "stony_peaks", "stony_shore", "sunflower_plains",
            "swamp", "taiga", "warm_ocean", "windswept_forest", "windswept_gravelly_hills",
            "windswept_hills", "windswept_savanna", "wooded_badlands"
    };

    private final MultiNoiseBiomeSource delegate;
    private final long seed;
    private final org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1;

    private final AeroNoise tempNoise;
    private final AeroNoise humidityNoise;
    private final AeroNoise deepDarkNoise;

    private volatile java.util.Set<Holder<Biome>> cachedBiomes = null;

    public AeroBiomeSource(MultiNoiseBiomeSource delegate, long seed) {
        this(delegate, seed, null);
    }

    public AeroBiomeSource(MultiNoiseBiomeSource delegate, long seed,
                           org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1) {
        this.delegate      = delegate;
        this.seed          = seed;
        this.layer1        = layer1;
        this.tempNoise     = new AeroNoise(seed ^ 0x11223344L);
        this.humidityNoise = new AeroNoise(seed ^ 0x55667788L);
        this.deepDarkNoise = new AeroNoise(seed ^ 0x9A4B1C2DL);
    }

    public AeroBiomeSource(MultiNoiseBiomeSource delegate) {
        this(delegate, 0xAE40F9C3L);
    }

    public AeroBiomeSource withSeed(long newSeed) {
        if (newSeed == this.seed) return this;
        return new AeroBiomeSource(delegate, newSeed, this.layer1);
    }

    public AeroBiomeSource withRingChecker(org.example.aeroworld.worldgen.layer.Layer1FlatGenerator newLayer1) {
        if (newLayer1 == this.layer1) return this;
        return new AeroBiomeSource(delegate, this.seed, newLayer1);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    public java.util.Set<Holder<Biome>> possibleBiomes() {
        java.util.Set<Holder<Biome>> biomes = this.cachedBiomes;
        if (biomes == null) {
            biomes = collectPossibleBiomes().collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (biomes.size() > delegate.possibleBiomes().size()) {
                this.cachedBiomes = biomes;
            }
        }
        return biomes;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        Stream<Holder<Biome>> aeroClones = java.util.Arrays.stream(ALL_CLONED_BIOMES)
                .map(name -> AeroBiomeRegistryCache.get(ResourceLocation.fromNamespaceAndPath("aeroworld", name)))
                .filter(Optional::isPresent)
                .map(Optional::get);

        Stream<Holder<Biome>> vanillaFallback = delegate.possibleBiomes().stream();

        return Stream.concat(aeroClones, vanillaFallback);
    }

    private static final int DEEP_DARK_MAX_Y_BLOCK = -8;
    private static final int DEEP_DARK_MIN_Y_BLOCK = Layer1TerrainGenerator.MIN_Y;
    private static final double DEEP_DARK_NOISE_SCALE = 0.006;
    private static final double DEEP_DARK_THRESHOLD   = 0.30;

    private static final int LAYER1_MAX_NOISE_Y = Layer1TerrainGenerator.MAX_Y / 4;

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        // Островные слои (выше Layer 1)
        if (y > LAYER1_MAX_NOISE_Y) {
            return delegateWithSafety(x, 20, z, sampler, true);
        }

        double wx = x * 4.0;
        double wz = z * 4.0;

        // Deep Dark на глубине (-64..-8)
        int blockY = y * 4;
        if (blockY <= DEEP_DARK_MAX_Y_BLOCK && blockY >= DEEP_DARK_MIN_Y_BLOCK) {
            double dd = deepDarkNoise.fbm2D(wx * DEEP_DARK_NOISE_SCALE, wz * DEEP_DARK_NOISE_SCALE, 3, 2.0, 0.5);
            if (dd > DEEP_DARK_THRESHOLD) {
                Optional<Holder<Biome>> deepDark = findAeroBiome("deep_dark");
                if (deepDark.isPresent()) return deepDark.get();
            }
        }

        // Layer 1 кастомный шум
        Layer1TerrainGenerator terrain = (layer1 != null) ? layer1.getTerrainGenerator() : null;
        double cont = (terrain != null) ? terrain.getContinentality(wx, wz) : 0.2;
        double eros = (terrain != null) ? terrain.getErosion(wx, wz) : 0.0;

        double temp = tempNoise.fbm2D(wx * 0.0008, wz * 0.0008, 3, 2.0, 0.5);
        double humid = humidityNoise.fbm2D(wx * 0.0010, wz * 0.0010, 3, 2.0, 0.5);

        String biomeName = resolveLayer1Biome(cont, eros, temp, humid);
        return findAeroBiome(biomeName).orElseGet(() -> delegate.getNoiseBiome(x, y, z, sampler));
    }

    private String resolveLayer1Biome(double cont, double eros, double temp, double humid) {
        // 1. Океан
        if (cont < -0.05) {
            boolean deep = cont < -0.20;
            if (temp < -0.3) {
                return deep ? "deep_frozen_ocean" : "frozen_ocean";
            } else if (temp < 0.0) {
                return deep ? "deep_cold_ocean" : "cold_ocean";
            } else if (temp < 0.35) {
                return deep ? "deep_ocean" : "ocean";
            } else if (temp < 0.6) {
                return deep ? "deep_lukewarm_ocean" : "lukewarm_ocean";
            } else {
                return "warm_ocean";
            }
        }

        // 2. Побережье / пляж
        if (cont < 0.0) {
            if (eros < -0.2) return "stony_shore";
            if (temp < -0.2) return "snowy_beach";
            return "beach";
        }

        // 3. Суша: горы и пики
        if (eros < -0.35) {
            if (temp < -0.3) return "frozen_peaks";
            if (temp < 0.1)  return "jagged_peaks";
            if (temp < 0.5)  return "stony_peaks";
            return "windswept_hills";
        }

        // 4. Холодно
        if (temp < -0.3) {
            if (humid > 0.1) return "snowy_taiga";
            if (humid < -0.2 && eros < -0.1) return "ice_spikes";
            return "snowy_plains";
        }

        // 5. Умеренно-холодно
        if (temp < 0.0) {
            if (humid > 0.2) return "old_growth_pine_taiga";
            if (humid > -0.1) return "taiga";
            return "windswept_forest";
        }

        // 6. Умеренно
        if (temp < 0.4) {
            if (humid > 0.35) return "dark_forest";
            if (humid > 0.15) return "forest";
            if (humid > -0.1) return "birch_forest";
            if (humid > -0.3) return "meadow";
            return "plains";
        }

        // 7. Жарко
        if (humid > 0.4) {
            return "bamboo_jungle";
        } else if (humid > 0.2) {
            return "jungle";
        } else if (humid > 0.0) {
            return "swamp";
        } else if (humid > -0.25) {
            return "savanna";
        } else if (humid > -0.5) {
            return "desert";
        } else {
            return "badlands";
        }
    }

    private Holder<Biome> delegateWithSafety(int x, int y, int z, Climate.Sampler sampler,
                                              boolean excludeForIslands) {
        Holder<Biome> vanilla = delegate.getNoiseBiome(x, y, z, sampler);
        if (!excludeForIslands) {
            return vanilla;
        }

        ResourceLocation vanillaId = vanilla.unwrapKey()
                .map(k -> k.location())
                .orElse(ResourceLocation.withDefaultNamespace("plains"));

        if (isExcludedForIslands(vanillaId)) {
            return findAeroBiome("plains").orElse(vanilla);
        }

        return findAeroBiome(vanillaId.getPath()).orElseGet(() -> findAeroBiome("plains").orElse(vanilla));
    }

    private Optional<Holder<Biome>> findAeroBiome(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("aeroworld", path);
        Optional<Holder<Biome>> cached = AeroBiomeRegistryCache.get(id);
        if (cached.isPresent()) return cached;

        return delegate.possibleBiomes().stream()
                .filter(h -> h.unwrapKey().map(k -> k.location().equals(id)).orElse(false))
                .findFirst();
    }

    private static boolean isExcludedForIslands(ResourceLocation loc) {
        String p = loc.getPath();
        return p.contains("ocean") || p.equals("dripstone_caves")
                || p.equals("lush_caves") || p.equals("deep_dark");
    }
}