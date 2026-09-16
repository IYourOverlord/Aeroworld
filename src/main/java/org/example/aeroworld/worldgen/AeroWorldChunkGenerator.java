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

    private long seedFrom(RandomState randomState) {
        return randomState.getOrCreateRandomFactory(
                        ResourceLocation.fromNamespaceAndPath("aeroworld", "seed_probe"))
                .at(0, 0, 0).nextLong();
    }

    private synchronized void initializeWithSeed(long seed) {
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
        initializeWithSeed(seedFrom(randomState));
        lastRandomState = randomState;
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

    /**
     * Эффективный радиус LOD-bounding-box острова Layer 3 для грубых
     * {@code getBaseHeight}/{@code getBaseColumn} проверок: для планет с
     * кольцами (см. {@code IslandData.ringRadii}) это внешняя граница
     * третьего кольца, иначе — макс. горизонтальная полуось эллипсоида.
     */
    private static double highIslandEffectiveRadius(IslandData d) {
        if (d.ringRadii != null) return d.ringRadii[5];
        return (d.ellipsoidAxes != null) ? Math.max(d.ellipsoidAxes[0], d.ellipsoidAxes[2]) : d.radius;
    }

    @Override public int getMinY()     { return -64; }
    @Override public int getGenDepth() { return 2164; }
    @Override public int getSeaLevel() { return Layer1TerrainGenerator.SEA_LEVEL; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState random) {
        init(random);

        int levelMax = level.getMinBuildHeight() + level.getHeight() - 1;
        int chunkX = x >> 4, chunkZ = z >> 4;

        // Layer 4 (Y 1900..2031)
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            LongArrayList centres = upperIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, upperIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = upperIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) return d.topY + 1;
            }
        }
        // Layer 3 (Y 1000..1100)
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            LongArrayList centres = highIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, highIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = highIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double effR = highIslandEffectiveRadius(d);
                if (dx * dx + dz * dz <= effR * effR) return d.topY + 1;
            }
        }
        // Layer 2 (Y 300..400)
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            LongArrayList centres = lowerIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, lowerIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = lowerIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) return d.topY + 1;
            }
        }

        // Layer 1
        if (layer1Terrain != null) {
            if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) {
                return layer1Terrain.getHeight(x, z);
            } else {
                return layer1Terrain.getTopmostHeight(x, z);
            }
        }

        return Layer1TerrainGenerator.SEA_LEVEL;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState random) {
        init(random);

        int minY   = level.getMinBuildHeight();
        int height = level.getHeight();
        int levelMax = minY + height - 1;

        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            states[i] = BS_AIR_SENTINEL;
        }

        // Layer 1
        if (layer1Terrain != null && minY <= Layer1TerrainGenerator.MAX_Y) {
            int surfaceY = layer1Terrain.getHeight(x, z);
            int seaLevel = Layer1TerrainGenerator.SEA_LEVEL;
            boolean hasCave = surfaceY >= seaLevel;
            int caveTop = hasCave ? layer1Terrain.computeCaveTop(x, z) : Integer.MIN_VALUE;
            int caveBottom = hasCave ? layer1Terrain.computeCaveBottom(x, z) : Integer.MAX_VALUE;

            for (int y = minY; y <= surfaceY && y <= levelMax; y++) {
                if (hasCave && y >= caveBottom && y <= caveTop) {
                    continue;
                }
                int idx = y - minY;
                if (idx >= 0 && idx < states.length) {
                    states[idx] = BS_STONE;
                }
            }
            if (surfaceY < seaLevel) {
                for (int y = surfaceY + 1; y <= seaLevel && y <= levelMax; y++) {
                    int idx = y - minY;
                    if (idx >= 0 && idx < states.length) {
                        states[idx] = BS_WATER;
                    }
                }
            }
        }

        // Layer 2–4
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = lowerIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, lowerIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = lowerIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz > d.radius * d.radius) continue;
                for (int y = d.bottomY; y <= d.topY; y++) { int idx = y - minY; if (idx >= 0 && idx < states.length) states[idx] = BS_STONE; }
            }
        }
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = highIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, highIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = highIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double effR = highIslandEffectiveRadius(d);
                if (dx * dx + dz * dz > effR * effR) continue;
                for (int y = d.bottomY; y <= d.topY; y++) { int idx = y - minY; if (idx >= 0 && idx < states.length) states[idx] = BS_STONE; }
            }
        }
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = upperIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, upperIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = upperIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz > d.radius * d.radius) continue;
                for (int y = d.bottomY; y <= d.topY; y++) { int idx = y - minY; if (idx >= 0 && idx < states.length) states[idx] = BS_STONE; }
            }
        }

        return new NoiseColumn(minY, states);
    }

    @Override
    public void createStructures(RegistryAccess registryAccess,
                                 ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager,
                                 ChunkAccess chunk,
                                 StructureTemplateManager structureTemplateManager) {
        super.createStructures(registryAccess, structureState, structureManager,
                chunk, structureTemplateManager);

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