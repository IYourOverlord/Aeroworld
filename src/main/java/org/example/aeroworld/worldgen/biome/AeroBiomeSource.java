package org.example.aeroworld.worldgen.biome;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.layer.PlateauTerrace;
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
            "alpine_meadow", "karst_highlands", "autumn_forest", "heather_moor", "volcanic_wastes",
            "wisteria_grove", "ashen_plateau", "deep_lake"
    };

    /** Биом глубокого озера: клон deep_ocean, нужен для океанического монумента на дне. */
    public static final String DEEP_LAKE = "deep_lake";

    private final MultiNoiseBiomeSource delegate;
    /**
     * Всё, что зависит от сида мира и Layer1, одним неизменяемым блоком: {@link #reconfigure} подменяет его целиком,
     * поэтому читатели (потоки генерации чанков и LOD) всегда видят согласованный набор.
     */
    /** Соли шума температуры и влажности: их же использует {@code Layer1TerrainGenerator} для проверки биома jungle. */
    public static final long TEMP_SALT = 0x11223344L;
    public static final long HUMID_SALT = 0x55667788L;

    private static final class Params {
        final long seed;
        final org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1;
        final AeroNoise tempNoise;
        final AeroNoise humidityNoise;
        final AeroNoise deepDarkNoise;
        /** Какие реальные входы рельефа брать; null = все (настроенная копия). У поля: набор из {@link #OVERLAY}. */
        final java.util.Set<BiomeOverlay> overlay;

        Params(long seed, org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1) {
            this(seed, layer1, null);
        }

        Params(long seed, org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1,
               java.util.Set<BiomeOverlay> overlay) {
            this.seed          = seed;
            this.layer1        = layer1;
            this.overlay       = overlay;
            this.tempNoise     = new AeroNoise(seed ^ TEMP_SALT);
            this.humidityNoise = new AeroNoise(seed ^ HUMID_SALT);
            this.deepDarkNoise = new AeroNoise(seed ^ 0x9A4B1C2DL);
        }
    }

    private volatile Params params;

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
        Params owner; // для каких параметров заполнен кэш; при смене сброс (см. getNoiseBiome)
    }

    private final ThreadLocal<BiomeColumnCache> threadColumnCache =
            ThreadLocal.withInitial(BiomeColumnCache::new);

    public AeroBiomeSource(MultiNoiseBiomeSource delegate, long seed) {
        this(delegate, seed, null);
    }

    public AeroBiomeSource(MultiNoiseBiomeSource delegate, long seed,
                           org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1) {
        this.delegate = delegate;
        this.params   = new Params(seed, layer1);
    }

    /** Сид источника, созданного в поле ChunkGenerator (по нему считает биомы реальная генерация). */
    public static final long DEFAULT_SEED = 0xAE40F9C3L;

    /**
     * Замороженные входы карты реальных чанков (поле ChunkGenerator без Layer1): суша без хребта и плато.
     * Для cont >= 0 {@link #resolveLayer1Biome} от cont не зависит, поэтому раскладка суши от этих значений не меняется.
     */
    public static final double FIELD_CONT = 0.2, FIELD_EROSION = 0.0, FIELD_RIDGE = 0.0, FIELD_PLATEAU = 0.0;

    /**
     * Единая формула температуры/влажности. wx/wz - координаты кварт-ячейки (quart*4): так считает
     * {@link #getNoiseBiome}, а аналитические пути приводят блок к ней через {@code (b >> 2) << 2}.
     */
    public static double temperature(AeroNoise n, double wx, double wz) {
        return n.fbm2D(wx * 0.0008, wz * 0.0008, 3, 2.0, 0.5);
    }

    public static double humidity(AeroNoise n, double wx, double wz) {
        return n.fbm2D(wx * 0.0010, wz * 0.0010, 3, 2.0, 0.5);
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Включённые слои рельефа для поля ({@code -Daeroworld.biomeOverlay=water,plateau,...}); по умолчанию
     * {@code water,plateau,rivers}, {@code none} выключает всё.
     * <p>WATER даёт реальные континентальность и маску глубокого озера: океаны, пляжи, deep_lake. На суше (cont >= 0)
     * при замороженных эрозии, хребте и плато {@link #resolveLayer1Biome} от cont не зависит, поэтому раскладка суши прежняя;
     * исключение: deep_lake перекрывает сушу там, где реально есть глубокое озеро. {@code stony_shore} недостижим
     * (нужна эрозия < -0.2, а она заморожена в 0): на берегу будут только beach и snowy_beach.
     */
    public static final java.util.Set<BiomeOverlay> OVERLAY =
            BiomeOverlay.fromProperty(System.getProperty("aeroworld.biomeOverlay"), m -> LOGGER.warn(m));

    /** Разрешённые биомы суши ({@code -Daeroworld.biomeLand}, по умолчанию пусто), см. {@link BiomeOverlay#LAND_BIOMES}. */
    public static final java.util.Set<String> LAND =
            BiomeOverlay.parseLand(System.getProperty("aeroworld.biomeLand"), m -> LOGGER.warn(m));

    private static final BiomeOverlay.Inputs FIELD_INPUTS =
            new BiomeOverlay.Inputs(FIELD_CONT, FIELD_EROSION, FIELD_RIDGE, FIELD_PLATEAU, false);

    /** Биом карты реальных чанков по замороженным входам (без океанов, гор и deep_lake). */
    public static String resolveFieldBiome(double temp, double humid) {
        return resolveLayer1Biome(FIELD_CONT, FIELD_EROSION, FIELD_RIDGE, FIELD_PLATEAU, temp, humid, false);
    }

    public AeroBiomeSource(MultiNoiseBiomeSource delegate) {
        this(delegate, DEFAULT_SEED);
    }

    public AeroBiomeSource withSeed(long newSeed) {
        Params p = params;
        if (newSeed == p.seed) return this;
        return new AeroBiomeSource(delegate, newSeed, p.layer1);
    }

    public AeroBiomeSource withRingChecker(org.example.aeroworld.worldgen.layer.Layer1FlatGenerator newLayer1) {
        Params p = params;
        if (newLayer1 == p.layer1) return this;
        return new AeroBiomeSource(delegate, p.seed, newLayer1);
    }

    /**
     * Меняет сид и Layer1 <b>у этого же экземпляра</b>. Нужен, потому что ванильный {@code ChunkGenerator} держит
     * источник биомов в своём поле и {@code createBiomes} читает именно его: копия через {@link #withSeed}/{@link #withRingChecker}
     * оставляла реальным чанкам старый экземпляр (сид по умолчанию, без континентальности), а LOD считал по новому, и биомы
     * (а с ними песок/снег/подзол) расходились.
     */
    public synchronized void reconfigure(long newSeed,
                                         org.example.aeroworld.worldgen.layer.Layer1FlatGenerator newLayer1) {
        Params p = params;
        if (p.seed == newSeed && p.layer1 == newLayer1) return;
        params = new Params(newSeed, newLayer1);
    }

    /**
     * Привязывает рельеф к ЭТОМУ экземпляру, сид не трогает (в отличие от {@link #reconfigure}): температура и влажность
     * остаются по {@link #DEFAULT_SEED}, раскладка суши прежняя, реальные входы берутся только для слоёв из {@link #OVERLAY}.
     * Потокобезопасно: {@code Params} неизменяем, подмена одним volatile-присваиванием; читатели берут {@code params}
     * один раз на вызов, а потоковый кэш колонок сбрасывается при {@code cache.owner != p}.
     */
    public synchronized void attachTerrain(org.example.aeroworld.worldgen.layer.Layer1FlatGenerator newLayer1) {
        Params p = params;
        if (p.layer1 == newLayer1 && p.overlay == OVERLAY) return;
        params = new Params(p.seed, newLayer1, OVERLAY);
        LOGGER.info("[Biome] рельеф привязан к полю: overlay={}, land={}", OVERLAY, LAND);
    }

    /** Реальные входы рельефа в точке; cont и ridge считаются один раз (нужны и плато). */
    private static final class TerrainReal implements BiomeOverlay.Real {
        private final Layer1TerrainGenerator t;
        private final double wx, wz;
        private double cont = Double.NaN, ridge = Double.NaN;
        private Boolean lake; // select() зовёт deepLake() и для legacy, и для full: считаем один раз

        TerrainReal(Layer1TerrainGenerator t, double wx, double wz) { this.t = t; this.wx = wx; this.wz = wz; }

        public double cont() { if (Double.isNaN(cont)) cont = t.getContinentality(wx, wz); return cont; }
        public double eros() { return t.getErosion(wx, wz); }
        public double ridge() { if (Double.isNaN(ridge)) ridge = t.getRidgeStrength((int) wx, (int) wz); return ridge; }
        public double plateau() { return t.getPlateauMask((int) wx, (int) wz, cont(), ridge()); }
        public boolean deepLake() { if (lake == null) lake = t.isDeepLake((int) wx, (int) wz); return lake; }
        public boolean river() { return t.isRiverChannel((int) wx, (int) wz); }
    }

    /** Входы формулы биома в точке: замороженные без рельефа, иначе реальные с учётом overlay (null = все). */
    private static BiomeOverlay.Inputs inputs(Layer1TerrainGenerator terrain, java.util.Set<BiomeOverlay> overlay,
                                              double wx, double wz) {
        if (terrain == null) return FIELD_INPUTS;
        return BiomeOverlay.select(overlay, new TerrainReal(terrain, wx, wz), FIELD_INPUTS);
    }

    private static BiomeOverlay.Inputs inputs(Params p, double wx, double wz) {
        return inputs(p.layer1 != null ? p.layer1.getTerrainGenerator() : null, p.overlay, wx, wz);
    }

    /**
     * Биом карты реальных чанков в точке (wx, wz кварт-ячейки) при заданном overlay (null = всё реальное, как у настроенной
     * копии). Одна формула для источника и для {@code Layer1TerrainGenerator.isJungle}, чтобы сенот не оказался в клетке,
     * которую источник называет океаном.
     */
    public static String resolveFieldBiomeAt(Layer1TerrainGenerator terrain, java.util.Set<BiomeOverlay> overlay,
                                             double wx, double wz, double temp, double humid) {
        if (terrain == null) return resolveFieldBiome(temp, humid);
        TerrainReal real = new TerrainReal(terrain, wx, wz);
        String biome = BiomeOverlay.resolveWithLand(overlay, LAND, real, FIELD_INPUTS,
                in -> resolveLayer1Biome(in.cont(), in.eros(), in.ridge(), in.plateau(), temp, humid, in.deepLake()));
        return BiomeOverlay.withRiver(overlay, real, biome, temp);
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
        Params p = this.params;
        BiomeColumnCache cache = threadColumnCache.get();
        if (cache.owner != p) {
            java.util.Arrays.fill(cache.valid, false);
            cache.owner = p;
        }

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

            double dd = p.deepDarkNoise.fbm2D(wx * DEEP_DARK_NOISE_SCALE, wz * DEEP_DARK_NOISE_SCALE, 3, 2.0, 0.5);
            hasDD = dd > DEEP_DARK_THRESHOLD;
            ddBiome = hasDD ? findAeroBiome("deep_dark").orElse(null) : null;

            double temp = temperature(p.tempNoise, wx, wz);
            double humid = humidity(p.humidityNoise, wx, wz);
            String islandName = resolveIslandBiome(temp, humid);
            islandBiome = findAeroBiome(islandName).orElseGet(() -> delegateWithSafety(x, 20, z, sampler, true));

            String biomeName = resolveFieldBiomeAt(p.layer1 != null ? p.layer1.getTerrainGenerator() : null,
                    p.overlay, wx, wz, temp, humid);
            boolean deepLake = DEEP_LAKE.equals(biomeName);
            layer1Biome = findAeroBiome(biomeName).orElseGet(() -> delegate.getNoiseBiome(x, y, z, sampler));
            // Пятно deep_dark на Y <= -8 не должно заменять биом на дне озера: ванильная проверка биома старта
            // монумента берёт биом на высоте дна и иначе отклоняла бы его.
            if (deepLake) {
                hasDD = false;
                ddBiome = null;
            }

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

    public static String resolveLayer1Biome(double cont, double eros, double ridge, double plateau, double temp, double humid) {
        return resolveLayer1Biome(cont, eros, ridge, plateau, temp, humid, false);
    }

    /** То же, но {@code deepLake} (маска {@code Layer1TerrainGenerator.isDeepLake}) даёт биом {@link #DEEP_LAKE}. */
    public static String resolveLayer1Biome(double cont, double eros, double ridge, double plateau, double temp,
                                            double humid, boolean deepLake) {
        if (deepLake) return DEEP_LAKE;

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

        // 2б. Плато ashen_plateau: маска из Layer1TerrainGenerator.getPlateauMask (та же, что поднимает рельеф).
        if (PlateauTerrace.isPlateauBiome(plateau, cont, ridge, temp)) {
            return "ashen_plateau";
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

        // 7. Temperate (temp здесь уже >= 0; лестница общая с островами)
        if (temp < 0.16) return resolveIslandBiome(temp, humid);

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
        Params p = params;
        double wx = quartSnap(blockX);
        double wz = quartSnap(blockZ);
        double temp = temperature(p.tempNoise, wx, wz);
        double humid = humidity(p.humidityNoise, wx, wz);
        return resolveFieldBiomeAt(p.layer1 != null ? p.layer1.getTerrainGenerator() : null, p.overlay, wx, wz, temp, humid);
    }

    public boolean isDeepDark(int blockX, int blockZ) {
        Params p = params;
        double wx = quartSnap(blockX);
        double wz = quartSnap(blockZ);
        double dd = p.deepDarkNoise.fbm2D(wx * DEEP_DARK_NOISE_SCALE, wz * DEEP_DARK_NOISE_SCALE, 3, 2.0, 0.5);
        if (dd <= DEEP_DARK_THRESHOLD) return false;
        // как в getNoiseBiome: на дне глубокого озера deep_dark не перекрывает biom озера
        return p.layer1 == null || !inputs(p, wx, wz).deepLake();
    }

    public String getIslandBiomeName(int blockX, int blockZ) {
        double wx = quartSnap(blockX);
        double wz = quartSnap(blockZ);
        Params p = params;
        double temp = temperature(p.tempNoise, wx, wz);
        double humid = humidity(p.humidityNoise, wx, wz);
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
            if (humid > 0.06) return "wisteria_grove";
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
        Optional<Holder<Biome>> cached = aeroBiomeNameCache.get(path);
        if (cached != null) {
            return cached;
        }
        Optional<Holder<Biome>> resolved = lookupAeroBiome(path);
        // Only cache successful resolutions: a miss usually means the biome
        // registry / AeroBiomeRegistryCache wasn't warmed up yet (e.g. first
        // DH SeedGen worker call racing world init). Caching Optional.empty()
        // here would permanently poison this biome name for the whole session.
        if (resolved.isPresent()) {
            aeroBiomeNameCache.put(path, resolved);
        }
        return resolved;
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