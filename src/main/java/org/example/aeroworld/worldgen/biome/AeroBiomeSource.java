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
            "windswept_hills", "windswept_savanna", "wooded_badlands",
            "alpine_meadow", "karst_highlands", "autumn_forest", "heather_moor", "volcanic_wastes"
    };

    private final MultiNoiseBiomeSource delegate;
    private final long seed;
    private final org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1;

    private final AeroNoise tempNoise;
    private final AeroNoise humidityNoise;
    private final AeroNoise deepDarkNoise;

    private volatile java.util.Set<Holder<Biome>> cachedBiomes = null;

    private final java.util.concurrent.ConcurrentHashMap<Holder<Biome>, Holder<Biome>> islandBiomeMap =
            new java.util.concurrent.ConcurrentHashMap<>(64);
    private final java.util.concurrent.ConcurrentHashMap<String, Optional<Holder<Biome>>> aeroBiomeNameCache =
            new java.util.concurrent.ConcurrentHashMap<>(64);

    @SuppressWarnings("unchecked")
    private static final class BiomeColumnCache {
        private static final int MASK = 63;
        final int[] xs = new int[64];
        final int[] zs = new int[64];
        final boolean[] valid = new boolean[64];
        final Holder<Biome>[] islandBiomes = new Holder[64];
        final Holder<Biome>[] layer1Biomes = new Holder[64];
        final boolean[] hasDeepDark = new boolean[64];
        final Holder<Biome>[] deepDarkBiomes = new Holder[64];
    }

    private final ThreadLocal<BiomeColumnCache> threadColumnCache =
            ThreadLocal.withInitial(BiomeColumnCache::new);

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
        int slot = (x * 31 + z) & BiomeColumnCache.MASK;
        BiomeColumnCache cache = threadColumnCache.get();

        Holder<Biome> islandBiome;
        Holder<Biome> layer1Biome;
        boolean hasDD;
        Holder<Biome> ddBiome;

        if (cache.valid[slot] && cache.xs[slot] == x && cache.zs[slot] == z) {
            islandBiome = cache.islandBiomes[slot];
            layer1Biome = cache.layer1Biomes[slot];
            hasDD = cache.hasDeepDark[slot];
            ddBiome = cache.deepDarkBiomes[slot];
        } else {
            double wx = x * 4.0;
            double wz = z * 4.0;

            double dd = deepDarkNoise.fbm2D(wx * DEEP_DARK_NOISE_SCALE, wz * DEEP_DARK_NOISE_SCALE, 3, 2.0, 0.5);
            hasDD = dd > DEEP_DARK_THRESHOLD;
            ddBiome = hasDD ? findAeroBiome("deep_dark").orElse(null) : null;

            double temp = tempNoise.fbm2D(wx * 0.0008, wz * 0.0008, 3, 2.0, 0.5);
            double humid = humidityNoise.fbm2D(wx * 0.0010, wz * 0.0010, 3, 2.0, 0.5);
            String islandName = resolveIslandBiome(temp, humid);
            islandBiome = findAeroBiome(islandName).orElseGet(() -> delegateWithSafety(x, 20, z, sampler, true));

            Layer1TerrainGenerator terrain = (layer1 != null) ? layer1.getTerrainGenerator() : null;
            double cont = (terrain != null) ? terrain.getContinentality(wx, wz) : 0.2;
            double eros = (terrain != null) ? terrain.getErosion(wx, wz) : 0.0;
            double ridge = (terrain != null) ? terrain.getRidgeStrength((int) wx, (int) wz) : 0.0;

            String biomeName = resolveLayer1Biome(cont, eros, ridge, temp, humid);
            layer1Biome = findAeroBiome(biomeName).orElseGet(() -> delegate.getNoiseBiome(x, y, z, sampler));

            cache.xs[slot] = x;
            cache.zs[slot] = z;
            cache.islandBiomes[slot] = islandBiome;
            cache.layer1Biomes[slot] = layer1Biome;
            cache.hasDeepDark[slot] = hasDD;
            cache.deepDarkBiomes[slot] = ddBiome;
            cache.valid[slot] = true;
        }

        if (y > LAYER1_MAX_NOISE_Y) {
            return islandBiome;
        }

        int blockY = y * 4;
        if (hasDD && ddBiome != null && blockY <= DEEP_DARK_MAX_Y_BLOCK && blockY >= DEEP_DARK_MIN_Y_BLOCK) {
            return ddBiome;
        }

        return layer1Biome;
    }

    public static String resolveLayer1Biome(double cont, double eros, double ridge, double temp, double humid) {
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

        // 3. Continuous ridge biomes.
        if (ridge >= 0.70) {
            if (temp < -0.10) return "frozen_peaks";
            if (temp >= 0.20 && humid <= -0.18) return "volcanic_wastes";
            return humid >= 0.05 ? "alpine_meadow" : "karst_highlands";
        }

        // 4. Суша: горы и пики
        if (eros < -0.35) {
            if (temp < -0.3) return "frozen_peaks";
            if (temp < 0.1)  return "jagged_peaks";
            if (temp < 0.5)  return "stony_peaks";
            return "windswept_hills";
        }

        // 5. Холодно
        if (temp < -0.3) {
            if (humid > 0.1) return "snowy_taiga";
            if (humid < -0.2 && eros < -0.1) return "ice_spikes";
            return "snowy_plains";
        }

        // 6. Умеренно-холодно
        if (temp < 0.0) {
            if (humid > 0.2) return "old_growth_pine_taiga";
            if (humid > -0.1) return "taiga";
            return "windswept_forest";
        }

        // 7. Temperate
        if (temp < 0.16) {
            if (humid > 0.35) return "dark_forest";
            if (humid > 0.18) return "autumn_forest";
            if (humid > -0.08) return "birch_forest";
            if (humid > -0.24) return "meadow";
            return "heather_moor";
        }

        // 8. Warm climates. Thresholds are intentionally centred on the observed
        // fBm range so every vanilla arid biome receives a meaningful share.
        if (temp >= 0.16 && humid <= -0.18) {
            return ridge >= 0.45 ? "eroded_badlands" : "badlands";
        }
        if (temp >= 0.16 && humid <= 0.05) {
            return "desert";
        }
        if (temp >= 0.16 && humid <= 0.20) {
            return "savanna";
        }
        if (temp >= 0.16 && humid <= 0.38) {
            return "jungle";
        }
        return "bamboo_jungle";
    }

    /**
     * Округляет блочную координату к сетке кварт-ячеек (4 блока), в точности как
     * ванильное хранилище биомов чанка ({@code ChunkAccess.getNoiseBiome(quartX, quartY, quartZ)}
     * -> {@link #getNoiseBiome} -> {@code wx = quartX * 4.0}). Без этого округления
     * аналитический путь ({@code AeroColumnModel}, Distant Horizons SeedGen) сэмплирует климатический
     * шум по точной блочной координате и вблизи порогов {@link #resolveLayer1Biome}/{@link #resolveIslandBiome}
     * может получить другую категорию биома, чем реально сгенерированный чанк с тем же XZ.
     */
    private static double quartSnap(int blockCoord) {
        return (blockCoord >> 2) << 2;
    }

    public String getLayer1BiomeName(int blockX, int blockZ) {
        Layer1TerrainGenerator terrain = (layer1 != null) ? layer1.getTerrainGenerator() : null;
        double wx = quartSnap(blockX);
        double wz = quartSnap(blockZ);
        double cont = (terrain != null) ? terrain.getContinentality(wx, wz) : 0.2;
        double eros = (terrain != null) ? terrain.getErosion(wx, wz) : 0.0;
        double ridge = (terrain != null) ? terrain.getRidgeStrength((int) wx, (int) wz) : 0.0;

        double temp = tempNoise.fbm2D(wx * 0.0008, wz * 0.0008, 3, 2.0, 0.5);
        double humid = humidityNoise.fbm2D(wx * 0.0010, wz * 0.0010, 3, 2.0, 0.5);

        return resolveLayer1Biome(cont, eros, ridge, temp, humid);
    }

    public boolean isDeepDark(int blockX, int blockZ) {
        double wx = quartSnap(blockX);
        double wz = quartSnap(blockZ);
        double dd = deepDarkNoise.fbm2D(wx * DEEP_DARK_NOISE_SCALE, wz * DEEP_DARK_NOISE_SCALE, 3, 2.0, 0.5);
        return dd > DEEP_DARK_THRESHOLD;
    }

    public String getIslandBiomeName(int blockX, int blockZ) {
        double wx = quartSnap(blockX);
        double wz = quartSnap(blockZ);
        double temp = tempNoise.fbm2D(wx * 0.0008, wz * 0.0008, 3, 2.0, 0.5);
        double humid = humidityNoise.fbm2D(wx * 0.0010, wz * 0.0010, 3, 2.0, 0.5);
        return resolveIslandBiome(temp, humid);
    }

    public static String resolveIslandBiome(double temp, double humid) {
        if (temp < -0.3) {
            return humid > 0.1 ? "snowy_taiga" : "snowy_plains";
        }
        if (temp < 0.0) {
            return humid > 0.2 ? "old_growth_pine_taiga" : (humid > -0.1 ? "taiga" : "windswept_forest");
        }
        if (temp < 0.16) {
            if (humid > 0.35) return "dark_forest";
            if (humid > 0.18) return "autumn_forest";
            if (humid > -0.08) return "birch_forest";
            if (humid > -0.24) return "meadow";
            return "heather_moor";
        }
        if (temp >= 0.16 && humid <= -0.18) {
            return "badlands";
        }
        if (temp >= 0.16 && humid <= 0.05) {
            return "desert";
        }
        if (temp >= 0.16 && humid <= 0.20) {
            return "savanna";
        }
        if (temp >= 0.16 && humid <= 0.38) {
            return "jungle";
        }
        return "bamboo_jungle";
    }

    private Holder<Biome> delegateWithSafety(int x, int y, int z, Climate.Sampler sampler,
                                             boolean excludeForIslands) {
        Holder<Biome> vanilla = delegate.getNoiseBiome(x, y, z, sampler);
        if (!excludeForIslands) {
            return vanilla;
        }

        return islandBiomeMap.computeIfAbsent(vanilla, this::mapVanillaToIslandBiome);
    }

    private Holder<Biome> mapVanillaToIslandBiome(Holder<Biome> vanilla) {
        ResourceLocation vanillaId = vanilla.unwrapKey()
                .map(k -> k.location())
                .orElse(ResourceLocation.withDefaultNamespace("plains"));

        if (isExcludedForIslands(vanillaId)) {
            return findAeroBiome("plains").orElse(vanilla);
        }

        return findAeroBiome(vanillaId.getPath()).orElseGet(() -> findAeroBiome("plains").orElse(vanilla));
    }

    public Optional<Holder<Biome>> findAeroBiome(String path) {
        return aeroBiomeNameCache.computeIfAbsent(path, this::lookupAeroBiome);
    }

    private Optional<Holder<Biome>> lookupAeroBiome(String path) {
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