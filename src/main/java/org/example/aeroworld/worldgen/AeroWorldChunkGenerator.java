package org.example.aeroworld.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.config.AeroWorldSettings;
import org.example.aeroworld.structure.IslandStructureScheduler;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.biome.AeroBiomeSource;
import org.example.aeroworld.worldgen.feature.*;
import org.example.aeroworld.worldgen.layer.*;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.structure.StructureSupportValidator;
import org.example.aeroworld.worldgen.structure.ValidationResult;
import org.example.aeroworld.worldgen.structure.AncientCityIslandSupportPlacer;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.util.ChunkAccessWriter;
import org.example.aeroworld.worldgen.util.ChunkWriter;
import org.example.aeroworld.worldgen.util.SectionDirectChunkWriter;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class AeroWorldChunkGenerator extends NoiseBasedChunkGenerator {

    public static final MapCodec<AeroWorldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings),
                    AeroWorldSettings.CODEC.optionalFieldOf("aero_settings", AeroWorldSettings.DEFAULT)
                            .forGetter(g -> g.settings)
            ).apply(instance, AeroWorldChunkGenerator::new)
    );

    private final AeroWorldSettings settings;
    private final AtomicReference<AeroBiomeSource> aeroSource;

    private volatile Layer1TerrainGenerator layer1Terrain;
    private volatile Layer1FlatGenerator   layer1;
    private volatile LowerIslandGenerator  lowerIslands;
    private volatile HighIslandGenerator   highIslands;
    private volatile UpperIslandGenerator  upperIslands;

    private volatile ChunkIslandCache sharedChunkIslandCache = new ChunkIslandCache();
    private volatile org.example.aeroworld.worldgen.feature.vault.IslandVaultTrialCache sharedVaultTrialCache =
            new org.example.aeroworld.worldgen.feature.vault.IslandVaultTrialCache();

    private static final BlockState BS_STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BS_WATER = Blocks.WATER.defaultBlockState();

    private volatile Layer2StructurePlacer structurePlacer;
    private volatile org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer layer2VaultTrialPlacer;
    private volatile org.example.aeroworld.worldgen.feature.vault.Layer3VaultTrialPlacer layer3VaultTrialPlacer;
    private volatile org.example.aeroworld.worldgen.feature.vault.Layer4VaultTrialPlacer layer4VaultTrialPlacer;
    private volatile StructureSupportValidator structureValidator;

    private volatile long    worldSeed       = 12345L;
    private volatile boolean seedInitialized = false;
    /** true, если worldSeed получен от реального сида мира; такой сид никогда не перезаписывается seed_probe. */
    private volatile boolean seedFromWorld   = false;
    private volatile RandomState lastRandomState = null;

    private static final BlockState BS_AIR_SENTINEL = Blocks.AIR.defaultBlockState();

    public AeroWorldChunkGenerator(BiomeSource biomeSource,
                                   Holder<NoiseGeneratorSettings> settingsHolder,
                                   AeroWorldSettings settings) {
        super(biomeSource instanceof MultiNoiseBiomeSource mnbs
                ? new AeroBiomeSource(mnbs)
                : biomeSource, settingsHolder);
        this.settings = settings;
        BiomeSource resolved = super.getBiomeSource();
        this.aeroSource = new AtomicReference<>(
                resolved instanceof AeroBiomeSource abs ? abs : null);
    }

    public AeroWorldChunkGenerator(BiomeSource biomeSource,
                                   Holder<NoiseGeneratorSettings> settingsHolder) {
        this(biomeSource, settingsHolder, AeroWorldSettings.DEFAULT);
    }

    public LowerIslandGenerator getLowerIslands() { return lowerIslands; }
    public long getWorldSeed() { return worldSeed; }
    public HighIslandGenerator getHighIslands()   { return highIslands; }
    public UpperIslandGenerator getUpperIslands() { return upperIslands; }
    public Layer1TerrainGenerator getLayer1Terrain() { return layer1Terrain; }
    public AeroWorldSettings getSettings() { return settings; }
    public AeroBiomeSource getAeroBiomeSource() { return aeroSource.get(); }
    public boolean isSeedInitialized() { return seedInitialized; }

    private long seedFrom(RandomState randomState) {
        return randomState.getOrCreateRandomFactory(
                        ResourceLocation.fromNamespaceAndPath("aeroworld", "seed_probe"))
                .at(0, 0, 0).nextLong();
    }

    /** Единая точка входа для реального сида мира. */
    public synchronized void initializeWithSeed(long seed) {
        seedFromWorld = true;
        applySeed(seed);
    }

    private void applySeed(long seed) {
        if (seedInitialized && worldSeed == seed) return;
        worldSeed       = seed;
        seedInitialized = true;

        sharedChunkIslandCache = new ChunkIslandCache();
        sharedVaultTrialCache  = new org.example.aeroworld.worldgen.feature.vault.IslandVaultTrialCache();

        layer1Terrain = new Layer1TerrainGenerator(seed);
        layer1        = new Layer1FlatGenerator(layer1Terrain);
        lowerIslands  = new LowerIslandGenerator(seed, settings.layer2(), sharedChunkIslandCache);
        highIslands   = new HighIslandGenerator(seed, settings.layer3(), sharedChunkIslandCache);
        upperIslands  = new UpperIslandGenerator(seed, settings.layer4(), sharedChunkIslandCache);

        structureValidator = new StructureSupportValidator(layer1, lowerIslands, highIslands, upperIslands, sharedChunkIslandCache);

        structurePlacer        = new Layer2StructurePlacer(seed, sharedChunkIslandCache);
        layer2VaultTrialPlacer = new org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer(seed, sharedChunkIslandCache, sharedVaultTrialCache);
        layer3VaultTrialPlacer = new org.example.aeroworld.worldgen.feature.vault.Layer3VaultTrialPlacer(seed, sharedChunkIslandCache, sharedVaultTrialCache);
        layer4VaultTrialPlacer = new org.example.aeroworld.worldgen.feature.vault.Layer4VaultTrialPlacer(seed, sharedChunkIslandCache, sharedVaultTrialCache);

        AeroWorld.structureScheduler = new IslandStructureScheduler();

        AeroBiomeSource current = aeroSource.get();
        if (current != null) {
            AeroBiomeSource updated = current.withSeed(seed).withRingChecker(layer1);
            aeroSource.set(updated);
        }
    }

    private void init(RandomState randomState) {
        if (randomState == lastRandomState) return;
        lastRandomState = randomState;
        if (seedFromWorld) return;
        synchronized (this) {
            if (!seedFromWorld) applySeed(seedFrom(randomState));
        }
    }

    @Override
    public ChunkGeneratorStructureState createState(
            net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> structureSets,
            RandomState randomState, long seed) {
        initializeWithSeed(seed);
        lastRandomState = randomState;
        return super.createState(structureSets, randomState, seed);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public BiomeSource getBiomeSource() {
        AeroBiomeSource src = aeroSource.get();
        return src != null ? src : super.getBiomeSource();
    }

    public static double highIslandEffectiveRadius(IslandData d) {
        return d.getEffectiveRadius();
    }

    @Override public int getMinY()     { return -64; }
    @Override public int getGenDepth() { return 2164; }
    @Override public int getSeaLevel() { return Layer1TerrainGenerator.SEA_LEVEL; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState random) {
        init(random);
        int levelMax = level.getMinBuildHeight() + level.getHeight() - 1;
        return org.example.aeroworld.worldgen.column.AeroColumnModel.getBaseHeight(
                x, z, type, level.getMinBuildHeight(), levelMax,
                layer1Terrain, lowerIslands, highIslands, upperIslands
        );
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState random) {
        init(random);

        int minY   = level.getMinBuildHeight();
        int height = level.getHeight();
        int levelMax = minY + height - 1;

        BlockState[] states = new BlockState[height];
        java.util.Arrays.fill(states, BS_AIR_SENTINEL);

        java.util.List<org.example.aeroworld.worldgen.column.AeroColumnModel.Span> spans =
                org.example.aeroworld.worldgen.column.AeroColumnModel.buildSpans(
                        x, z, minY, levelMax,
                        layer1Terrain, lowerIslands, highIslands, upperIslands,
                        null, false
                );

        for (org.example.aeroworld.worldgen.column.AeroColumnModel.Span span : spans) {
            int start = Math.max(0, span.bottomY() - minY);
            int end = Math.min(height - 1, span.topY() - minY);
            for (int i = start; i <= end; i++) {
                states[i] = span.state();
            }
        }

        return new NoiseColumn(minY, states);
    }

    /**
     * Флаг для {@code NetherFortressStructureMixin}: генерация piece'ов
     * структуры (в т.ч. {@code moveInsideHeights}) идёт глубоко внутри
     * ванильного {@code Structure.generate}, вызываемого из
     * {@code super.createStructures(...)}, без доступа к чанк-генератору
     * как параметру метода — поэтому измерение AeroWorld определяется через
     * этот ThreadLocal, а не через {@code instanceof} на captured-аргументе.
     */
    public static final ThreadLocal<Boolean> IS_GENERATING = ThreadLocal.withInitial(() -> false);

    @Override
    public void createStructures(RegistryAccess registryAccess,
                                 ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager,
                                 ChunkAccess chunk,
                                 StructureTemplateManager structureTemplateManager) {
        IS_GENERATING.set(true);
        try {
            super.createStructures(registryAccess, structureState, structureManager,
                    chunk, structureTemplateManager);
        } finally {
            IS_GENERATING.set(false);
        }

        if (!seedInitialized) {
            initializeWithSeed(structureState.getLevelSeed());
        }

        StructureSupportValidator validator = structureValidator;
        if (validator == null) {
            return;
        }

        StructureSupportValidator.Layer1HeightSampler heightSampler = (x, z, type) -> {
            if (layer1Terrain != null) {
                if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) {
                    return layer1Terrain.getHeight(x, z);
                } else {
                    return layer1Terrain.getTopmostHeight(x, z);
                }
            }
            return Layer1TerrainGenerator.SEA_LEVEL;
        };

        Map<net.minecraft.world.level.levelgen.structure.Structure, StructureStart> allStarts = chunk.getAllStarts();
        if (!allStarts.isEmpty()) {
            allStarts.forEach((structure, start) -> {
                if (start == null || start == StructureStart.INVALID_START || !start.isValid()) return;
                ResourceLocation structureId = registryAccess
                        .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                        .getKey(structure);
                if (structureId == null) return;

                ValidationResult result = validator.validate(structureId, start, heightSampler);
                if (!result.accepted) {
                    structureManager.setStartForStructure(
                            SectionPos.of(chunk.getPos(), chunk.getMinSection()),
                            structure, StructureStart.INVALID_START, chunk);
                }
            });
        }

        Map<net.minecraft.world.level.levelgen.structure.Structure, LongSet> allRefs = chunk.getAllReferences();
        allRefs.forEach((structure, refs) -> {
            ResourceLocation structureId = registryAccess
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getKey(structure);

            if (refs.isEmpty() || structureId == null) return;

            StructureStart start = structureManager.getStartForStructure(
                    SectionPos.of(chunk.getPos(), chunk.getMinSection()),
                    structure, chunk);
            if (start == null || start == StructureStart.INVALID_START) return;

            ValidationResult result = validator.validate(structureId, start, heightSampler);
            if (!result.accepted) {
                structureManager.setStartForStructure(
                        SectionPos.of(chunk.getPos(), chunk.getMinSection()),
                        structure, StructureStart.INVALID_START, chunk);
            }
        });
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        int y = pos.getY();
        String layer = y <= Layer1TerrainGenerator.MAX_Y ? "Layer 1 (Custom Terrain)"
                : y < 500  ? "Layer 2 (Lower Islands)"
                : y < 1500 ? "Layer 3 (High Islands)"
                :            "Layer 4 (Upper Islands)";
        info.add("[AeroWorld] " + layer + "  Y=" + y);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState, Blender blender,
            StructureManager structureManager, ChunkAccess chunk) {
        init(randomState);
        return super.createBiomes(randomState, blender, structureManager, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {

        init(randomState);

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        final int chunkMinY = chunk.getMinBuildHeight();
        final int chunkMaxY = chunk.getMaxBuildHeight();

        ChunkWriter chunkWriter = new SectionDirectChunkWriter(chunk);

        // Layer 1: кастомный рельеф
        if (chunkMinY <= Layer1TerrainGenerator.MAX_Y && chunkMaxY >= Layer1TerrainGenerator.MIN_Y) {
            if (layer1Terrain != null) {
                layer1Terrain.fillTerrain(chunkWriter, chunkX, chunkZ);
            }
        }

        // Layer 2 (Lower Islands): Y 300..400
        if (chunkMinY <= LowerIslandGenerator.LAYER_MAX_Y && chunkMaxY >= LowerIslandGenerator.LAYER_MIN_Y) {
            if (lowerIslands != null) {
                lowerIslands.fillChunk(chunkWriter, chunkX, chunkZ);
                if (structurePlacer != null) {
                    structurePlacer.placeForChunk(chunk, lowerIslands,
                            RandomSource.create(worldSeed ^ ((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L) ^ 0xDEADBEEFL));
                }
            }
        }

        // Layer 3 (High Islands): Y 1000..1100
        if (chunkMinY <= HighIslandGenerator.LAYER_MAX_Y && chunkMaxY >= HighIslandGenerator.LAYER_MIN_Y) {
            if (highIslands != null) {
                highIslands.fillChunk(chunkWriter, chunkX, chunkZ);
            }
        }

        // Layer 4 (Upper Islands): Y 1900..2031
        if (chunkMinY <= UpperIslandGenerator.LAYER_MAX_Y && chunkMaxY >= UpperIslandGenerator.LAYER_MIN_Y) {
            if (upperIslands != null) {
                upperIslands.fillChunk(chunkWriter, chunkX, chunkZ);
            }
        }

        Heightmap.primeHeightmaps(chunk, Set.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
        init(random);
        if (layer1Terrain != null) {
            layer1Terrain.buildSurface(chunk, (wx, wz) -> chunk.getNoiseBiome(wx >> 2, Layer1TerrainGenerator.SEA_LEVEL >> 2, wz >> 2));
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Ванильные carvers (пещеры, каньоны) отключены: используется кастомная генерация пещер (SinkholeCarver).
        if (!seedInitialized || worldSeed != seed) {
            initializeWithSeed(seed);
        }
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel region, ChunkAccess chunk,
                                     StructureManager structureManager) {
        if (region instanceof WorldGenRegion wgr) {
            init(wgr.getLevel().getChunkSource().randomState());
        }

        // Делегируем стандартную декорацию биомов ванильному пайплайну (структуры, растительность)
        super.applyBiomeDecoration(region, chunk, structureManager);

        // ── Постфактум-зачистка невалидных структур ────────────────────────────
        // createStructures инвалидирует StructureStart раньше (STRUCTURE_STARTS/
        // STRUCTURE_REFERENCES), но реальная постройка piece'ов происходит здесь,
        // внутри super.applyBiomeDecoration — инвалидация с предыдущего статуса
        // на практике до него не долетает. Единственный надёжный способ отловить
        // парящие деревни/порталы — проверить структуру ПОСЛЕ фактической
        // постройки и стереть то, что не прошло валидацию. См. javadoc
        // StructureSupportValidator.postPlacementCleanup().
        if (structureValidator != null) {
            StructureSupportValidator.Layer1HeightSampler cleanupHeightSampler = (x, z, type) -> {
                if (layer1Terrain != null) {
                    if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) {
                        return layer1Terrain.getHeight(x, z);
                    } else {
                        return layer1Terrain.getTopmostHeight(x, z);
                    }
                }
                return Layer1TerrainGenerator.SEA_LEVEL;
            };
            structureValidator.postPlacementCleanup(region, chunk,
                    region.registryAccess(), cleanupHeightSampler);
        }

        // Удаляем ванильные руды, просочившиеся через biome features (Layer 1)
        Layer1OreFilter.applyToChunk(chunk);

        if (lowerIslands != null) {
            lowerIslands.clearVanillaVegetationInCentralZone(region, chunk);
            lowerIslands.placeTreesInRegion(region, chunk);
        }

        if (lowerIslands != null && layer2VaultTrialPlacer != null) {
            layer2VaultTrialPlacer.placeForChunk(region, chunk, lowerIslands, lowerIslands.getShape());
        }
        if (highIslands != null && layer3VaultTrialPlacer != null) {
            layer3VaultTrialPlacer.placeForChunk(region, chunk, highIslands);
        }
        if (upperIslands != null && layer4VaultTrialPlacer != null) {
            layer4VaultTrialPlacer.placeForChunk(region, chunk, upperIslands);
        }

        // Островная каменная ступенчатая опора под Ancient City в пещере
        AncientCityIslandSupportPlacer.placeSupportForChunk(region, chunk);
    }


}