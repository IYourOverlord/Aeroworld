# AeroWorld - Project Index

Code card catalog and dependency graph of everything under `src/`. Verified against the code on 2026-10-13 (git 228610f). Minecraft 1.21.1, NeoForge 21.1.x, Java 21, root package `org.example.aeroworld`.

## How to read this file

- Java paths are relative to `src/main/java/org/example/aeroworld/`. Resource paths are relative to `src/main/resources/` unless stated.
- Card: `**Name** [tag] kind, lines - purpose`, then optional `api` (key entry points, not exhaustive), `uses` (project classes it references), `usedBy` (project classes that reference it).
- `uses` / `usedBy` are computed from source by whole-word match of top-level class names with comments and strings removed (same-package references included). Edges through Mixin targets, JSON, codecs, reflection and `mixins.json` are NOT in the graph; they are described in the cards and in the sections below. Self-checks are left out of the graph. References to `AeroWorld` that only read `MOD_ID` are omitted. `HUB` means more than 12 referencing classes (list omitted).
- `UNWIRED` / `NOT REGISTERED` / `usedBy: NONE` mark code that nothing executes today.
- Tags: entry, event, command, client, config, registry, structure (placement queue), gen-core, gen-layer (island layers), gen-layer1 (surface layer), structure-gen (start validation), vault, biome, cache, noise, util, data, lod-core (analytic LOD model), lod-dh (DH adapters), dh-core, dh-cache, dh-diag (measurement only), dh-exp (experiment), mixin (vanilla), mixin-dh (Distant Horizons), selfcheck (standalone main), doc-only.
- Update rule: when a file is added, removed or its role changes, change its card in the same commit, and keep the date and hash above current. Keep descriptions factual (what it does, who calls it); no history, no TODOs.

## Layers (values verified in code)

| Layer | Y range | Generator | LAYER_ID (cache key) | Settings |
|---|---|---|---|---|
| 1 surface | -64..300, SEA_LEVEL 0 | worldgen/layer/Layer1TerrainGenerator | - | fixed in code |
| 2 lower islands | 400..500 | worldgen/layer/LowerIslandGenerator | 0 | config/Layer2Settings |
| 3 high bodies | 1000..1100 | worldgen/layer/HighIslandGenerator | 1 | config/Layer3Settings (+Layer3BodySettings) |
| 4 jellyfish | 1900..2031 | worldgen/layer/UpperIslandGenerator | 2 | config/Layer4Settings |

Dimension: min_y -64, height 2096, `getGenDepth` 2164, `getSeaLevel` 0 (dimension_type JSON logical_height 2096).

## Runtime flows (follow the arrows through the cards)

1. Boot: `AeroWorld` ctor -> `AeroWorldConfig.register`, `AeroRegistries.register` (-> `AeroDimensions` chunk generator codec, `aero_biome_source`, `deep_lake_monument` placement), event listeners (`ProximityTriggerHandler`, `SpawnerProximityHandler`, `ShorelineWaveHandler`, `AeroStructureListener`, `AeroWorldCommands`, `AeroPlayerAnchors`, `AeroBiomeRegistryCache`), `AeroSeedWorldGenBinding.registerIfDhPresent`. On FMLLoadComplete: register physical structure `physical_structures:tank21`.
2. Chunk generation (`AeroWorldChunkGenerator`): `fillFromNoise` -> `Layer1TerrainGenerator.fillTerrain`, then `LowerIslandGenerator` / `HighIslandGenerator` / `UpperIslandGenerator.fillChunk` for each layer overlapping the chunk Y range (all through `SectionDirectChunkWriter`), `Layer2StructurePlacer` -> `IslandStructureScheduler.enqueue`, `Layer1TerrainGenerator.buildSurface`. `applyBiomeDecoration` -> vanilla decoration, `StructureSupportValidator.postPlacementCleanup`, `Layer1OreFilter`, island trees, `Layer2/3/4VaultTrialPlacer` (-> `IslandVaultTrialGenerator`), `AncientCityIslandSupportPlacer`. `applyCarvers` only lazily initialises the seed (no carving).
3. Structure starts: `createStructures` -> `StructureSupportValidator.validate` (-> `StructureCategoryResolver`, `TerrainColumnSampler`, `ValidationResult`); vanilla structure mixins in `mixin/structure` change End City, Nether fortress and ocean monument placement.
4. Physical structure placement (tank21 on RICH Layer 2 islands): worldgen thread `IslandStructureScheduler.enqueue` -> server thread `flushToPersistence` -> `PendingStructureData` -> `ProximityTriggerHandler` (player within 96 XZ / 128 Y; `StructurePlacementHelper`, `StructureSizeCache`) -> `PhysicalStructures` -> `AeroStructureListener`. Forced by `/aeroworld forcePlacePending`.
5. Distant Horizons LOD: `AeroSeedWorldGenBinding` -> `AeroSeedWorldGenerator.generateLod` -> `AeroColumnModel.buildSpans` (-> `AeroStructureCover`, `AeroTreeCover`, `Layer1ColumnCache`, the layer generators, `AeroBiomeSource`) -> `AeroStructureMarkers` / `SliceVote` merge -> `AeroColumnWriter` data points. Limits and pacing: `AeroThroughputLimits` + `mixin/dh`. Caches: `AeroAdjacencyCache`, `AeroCompleteSectionCache`. Validation: `AeroSeedGenValidation` (`/aeroworld validateSeedGen`).

## Java cards

### (root)  - Mod entry
- **AeroWorld** [entry] class, 141L - Mod entry point (@Mod). Wires everything: config, registries, game-bus listeners, DH binding; registers physical structure tank21 after load complete; invalidates StructureSizeCache on /reload. Holds static MOD_ID and the shared IslandStructureScheduler.
   - api: AeroWorld(IEventBus, ModContainer); static structureScheduler; registerAeroStructures()
   - uses: AeroBiomeRegistryCache, AeroPlayerAnchors, AeroRegistries, AeroSeedWorldGenBinding, AeroStructureListener, AeroWorldCommands, AeroWorldConfig, IslandStructureScheduler, ProximityTriggerHandler, ShorelineWaveHandler, SpawnerProximityHandler, StructureSizeCache
   - usedBy: AeroWorldChunkGenerator, Layer2StructurePlacer, ProximityTriggerHandler

### config  - Config spec + codec settings records
- **AeroWorldConfig** [config] class, 95L - ModConfigSpec for config/aeroworld-client.toml, section [distant_horizons]: dhOverrideEnabled, throughputLogIntervalSec, adjacencyCache{Enabled,Size,TtlSec}, completeSectionCache{Enabled,Size}, extendedRenderDistance{Enabled,Chunks}, horizontalQuality, realChunkRadius, renderYieldQueue. Read by DH classes only.
   - api: register(ModContainer); static *_ENABLED / *_SIZE value holders
   - usedBy: AeroAdjacencyCache, AeroCompleteSectionCache, AeroSeedWorldGenBinding, AeroSeedWorldGenerator, AeroThroughputLimits, AeroWorld
- **AeroWorldSettings** [config] record, 69L - Root codec record `aero_settings` stored inside the dimension JSON generator: layer2, layer3, layer4, dhOverride. Default-valued fallback ctor omits dhOverride.
   - api: record(Layer2Settings, Layer3Settings, Layer4Settings, DhOverrideSettings)
   - uses: DhOverrideSettings, Layer2Settings, Layer3Settings, Layer4Settings
   - usedBy: AeroWorldChunkGenerator
- **DhOverrideSettings** [config] record, 32L - Codec record: DH override enabled flag, per-layer detail-level thresholds (layer2/3/4DetailThreshold), throughput log interval. Has DEFAULT.
   - usedBy: AeroFastDistantTerrain, AeroWorldSettings
- **Layer2Settings** [config] record, 60L - Codec record for lower islands: spawnChance, gridChunks, min/maxRadius, maxHeight, yVariance, bridgeChance, bridgeMaxRange. Validates ranges in ctor.
   - usedBy: AeroWorldSettings, LowerIslandGenerator
- **Layer3BodySettings** [config] record, 84L - Codec record split out of Layer3Settings (16-field codec limit): meteorite wall thickness, crater threshold/min bottom, ring inner/mid/outer factors, ringCellSize, ringSparsityMask.
   - usedBy: Layer3Settings
- **Layer3Settings** [config] record, 71L - Codec record for high islands: spawnChance, gridChunks, min/maxRadius, maxHeight, noiseDeform, nested body (Layer3BodySettings). Exposes body fields through delegate accessors.
   - uses: Layer3BodySettings
   - usedBy: AeroWorldSettings, HighIslandGenerator
- **Layer4Settings** [config] record, 63L - Codec record for jellyfish islands: spawnChance, gridChunks, min/maxRadius, min/maxHeight, tentacleCount, tentacleMin/MaxLen, tentacleBend.
   - usedBy: AeroWorldSettings, UpperIslandGenerator

### registry  - DeferredRegisters and resource keys
- **AeroDimensions** [registry] class, 20L - DeferredRegister for CHUNK_GENERATOR codecs; registers `aeroworld:aero_generator` -> AeroWorldChunkGenerator codec.
   - api: CHUNK_GENERATORS, AERO_CHUNK_GENERATOR
   - uses: AeroWorldChunkGenerator
   - usedBy: AeroRegistries
- **AeroRegistries** [registry] class, 34L - Registers BIOME_SOURCE `aero_biome_source` and STRUCTURE_PLACEMENT `deep_lake_monument`, and triggers AeroDimensions registration on the mod bus.
   - api: register(IEventBus)
   - uses: AeroBiomeSource, AeroDimensions, DeepLakeMonumentPlacement
   - usedBy: AeroWorld, DeepLakeMonumentPlacement
- **AeroResourceKeys** [registry] class, 28L - ResourceKey constants for the `aeroworld:aeroworld` dimension type, level stem and level. Used for dimension checks.
   - usedBy: LayerSpawnRestriction
- **AeroWorldPreset** [doc-only] class, 14L - Placeholder: documents that the world preset is registered purely via datapack JSON. No code references it.

### client  - Client-only events
- **AeroWorldClientEvents** [client] class, 31L - Client-only (Dist.CLIENT) screen-init hook for the world-creation screen. Currently a stub; the preset button itself comes from the datapack tag.
   - api: onScreenInit(ScreenEvent.Init.Post)

### command  - /aeroworld commands
- **AeroWorldCommands** [command] class, 827L - Brigadier command tree `/aeroworld`: forcePlacePending, findIsland2 <islandType> <tier>, findIsland3 meteorite|planet, findIsland4, validateSeedGen <count>, DHopt enable|disable, biomeAt <x> <z>. Dev/ops tooling only.
   - api: register(RegisterCommandsEvent)
   - uses: AeroBiomeSource, AeroColumnModel, AeroFastDistantTerrain, AeroSeedGenValidation, AeroSeedWorldGenerator, AeroWorldChunkGenerator, BodyType, ChunkKey, HighIslandGenerator, IslandData, IslandPlacer, Layer2VaultTrialPlacer, LowerIslandGenerator, ProximityTriggerHandler, UpperIslandGenerator, VaultTrialSpawnTier
   - usedBy: AeroWorld

### event  - Game-bus tick/placement handlers
- **AeroStructureListener** [event] class, 45L - NeoForge-bus static listener for PhysicalStructurePlacedEvent (after Sable sub-level assembly). Post-placement hook for AeroWorld structures.
   - api: onStructurePlaced(event)
   - usedBy: AeroWorld
- **ProximityTriggerHandler** [event] class, 406L - Level-tick handler (every 20 ticks, AeroWorld dimension only). Flushes the scheduler queue to PendingStructureData, then for each player finds pending entries within 96 blocks XZ / 128 Y, finds real island surface, checks chunks ready + space clear, and places the physical structure with retry/backoff. Layer 2 surface window Y 390..510.
   - api: onLevelTick(LevelTickEvent.Post); forcePlaceAll(ServerLevel) used by the command
   - uses: AeroWorld, PendingStructureData, StructurePlacementHelper, StructureSizeCache
   - usedBy: AeroWorld, AeroWorldCommands
- **ShorelineWaveHandler** [event] class, 1153L - Level-tick wave simulation on shorelines (tick step 10): BFS rings of water levels 0..7 advance toward land, hold 40 ticks, then retract; applies sponge cleanup; Y filter -10..90. Self-contained, no worldgen dependency.
   - api: onLevelTick(LevelTickEvent.Post); records WaveBlock, Wave
   - usedBy: AeroWorld
- **SpawnerProximityHandler** [event] class, 139L - Level-tick handler (every 20 ticks): scans chunks (radius 2) in Y 250..450 for `physical_structures:structure_spawner` blocks within 10 blocks of a player and triggers them.
   - api: onLevelTick(LevelTickEvent.Post)
   - usedBy: AeroWorld

### spawning  - Mob spawn rules
- **LayerSpawnRestriction** [event] class, 32L - FinalizeSpawnEvent subscriber: cancels all mob spawning in the AeroWorld dimension on Layer 3 (Y 1000..1100) and Layer 4 (Y >= 1900).
   - api: onFinalizeSpawn(FinalizeSpawnEvent)
   - uses: AeroResourceKeys, HighIslandGenerator, UpperIslandGenerator

### structure  - Physical-structure placement queue (server thread)
- **IslandStructureScheduler** [structure] class, 100L - Thread-safe queue filled from worldgen threads (enqueue with island block XZ + structure id, no Y) and drained on the server thread into PendingStructureData. Deduplicates by seen chunks.
   - api: enqueue(int,int,ResourceLocation); flushToPersistence(ServerLevel); resetSeenChunks()
   - uses: PendingStructureData
   - usedBy: AeroWorld, AeroWorldChunkGenerator
- **PendingStructureData** [structure] class extends SavedData, 165L - SavedData persisting the queue of structures waiting to be placed. Entry(pos, id, attempts, nextRetryTick) with exponential backoff and exhaustion limit.
   - api: getOrCreate(ServerLevel); addEntry(pos,id); Entry.isReadyToRetry/isExhausted/withNextRetry
   - usedBy: IslandStructureScheduler, ProximityTriggerHandler
- **StructurePlacementHelper** [structure] class, 130L - Static helpers for safe physical-structure placement: read NBT size without instantiating a template, check all footprint chunks are FULL, check free space above the surface.
   - api: getStructureSize; areChunksReady; isSpaceClear
   - usedBy: ProximityTriggerHandler, StructureSizeCache
- **StructureSizeCache** [structure] class, 65L - In-memory cache of NBT structure sizes; invalidated on datapack reload.
   - api: getSize(ServerLevel, ResourceLocation); invalidate()
   - uses: StructurePlacementHelper
   - usedBy: AeroWorld, ProximityTriggerHandler

### worldgen  - Dimension ChunkGenerator
- **AeroWorldChunkGenerator** [gen-core] class extends NoiseBasedChunkGenerator, 487L - The dimension ChunkGenerator (extends NoiseBasedChunkGenerator; super is invoked only in createBiomes, createStructures and applyBiomeDecoration). Owns all layer generators and caches, lazily seeded via createState/initializeWithSeed. Pipeline: fillFromNoise -> Layer1 fillTerrain, then lower/high/upper fillChunk per Y overlap, Layer2 structure placement, Layer1 buildSurface; applyBiomeDecoration -> vanilla decoration, structure postPlacementCleanup, Layer1OreFilter, island trees, Layer2/3/4 vault/trial placers, AncientCity support. createStructures validates every start via StructureSupportValidator. minY -64, genDepth 2164, seaLevel 0. Flag -Daeroworld.biomeSync.
   - api: getLowerIslands/getHighIslands/getUpperIslands/getLayer1Terrain/getSettings/getAeroBiomeSource; getBaseColumn; initializeWithSeed(long); CODEC
   - uses: AeroBiomeSource, AeroColumnModel, AeroWorld, AeroWorldSettings, AncientCityIslandSupportPlacer, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, HighIslandGenerator, IslandData, IslandStructureScheduler, IslandVaultTrialCache, Layer1FlatGenerator, Layer1OreFilter, Layer1TerrainGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer, LowerIslandGenerator, SectionDirectChunkWriter, StructureSupportValidator, UpperIslandGenerator, ValidationResult
   - usedBy: AeroDimensions, AeroSeedGenValidation, AeroSeedWorldGenBinding, AeroSeedWorldGenerator, AeroStructureMarkers, AeroWorldCommands, DeepLakeMonumentPlacement, EndCityStructureMixin, NetherFortressStructureMixin, OceanMonumentStructureMixin

### worldgen/biome  - Biome source and registry cache
- **AeroBiomeRegistryCache** [biome] class, 108L - Caches the full dynamic biome registry (including aeroworld:* clones) at ServerAboutToStart so biome lookup works off the main thread. Cleared on server stop.
   - api: get(ResourceLocation) -> Optional<Holder<Biome>>
   - usedBy: AeroBiomeSource, AeroWorld
- **AeroBiomeSource** [biome] class extends BiomeSource, 456L - BiomeSource: Layer 1 biomes chosen from continentality/erosion/ridge/plateau/temperature noise (resolveLayer1Biome), deep_lake and deep_dark by position, aeroworld:* biomes for islands. Reconfigurable per seed; wraps a MultiNoiseBiomeSource delegate. Nested Params, BiomeColumnCache.
   - api: getNoiseBiome; static resolveLayer1Biome(...); withSeed; reconfigure; CODEC
   - uses: AeroBiomeRegistryCache, AeroNoise, Layer1FlatGenerator, Layer1TerrainGenerator, PlateauTerrace
   - usedBy: AeroColumnModel, AeroRegistries, AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldCommands

### worldgen/cache  - Island data/caches and key packing
- **BodyType** [data] enum, 15L - Enum METEORITE | PLANET for Layer 3 bodies; chosen 50/50 from the island centre in HighIslandGenerator.
   - usedBy: AeroColumnModel, AeroStructureCover, AeroWorldCommands, EndCityStructureMixin, HighIslandGenerator, IslandData, IslandVaultTrialGenerator
- **ChunkIslandCache** [cache] class, 80L - Shared LRU cache (Long2ObjectLinkedOpenHashMap + StampedLock) of island-centre lists keyed by (layerId, chunk). layerId: Lower 0, High 1, Upper 2.
   - api: get(layerId,cx,cz,factory); release; releaseAll; invalidate
   - usedBy: AeroWorldChunkGenerator, HighIslandGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer, LowerIslandGenerator, StructureSupportValidator, TerrainColumnSampler, UpperIslandGenerator
- **ChunkKey** [util] class, 25L - Packs (x,z) ints into one long (x high 32 bits) and back. Used everywhere a chunk or block XZ is a map key.
   - api: of(x,z); x(key); z(key)
   - usedBy: HUB, 15 referrers - grep the name
- **IslandCache** [cache] class, 76L - Thread-safe LRU cache of IslandData per island centre (per layer generator instance).
   - api: get(cx,cz,factory); invalidate
   - uses: ChunkKey, IslandData
   - usedBy: HighIslandGenerator, LowerIslandGenerator, UpperIslandGenerator
- **IslandData** [data] class, 142L - Immutable computed island properties: centre, bottomY/topY, radius, shape profile and noise, optional ellipsoid axes (Layer 3), BodyType, tentacle data (Layer 4). Computed once, then cached.
   - api: ctor overloads per layer; height(); centerY(); getEffectiveRadius()
   - uses: BodyType
   - usedBy: HUB, 14 referrers - grep the name
- **Layer1ColumnCache** [cache] class, 53L - ThreadLocal per-chunk cache of Layer 1 height and cave columns (avoids recomputing ~27 noise octaves across fillTerrain/cave decoration/buildSurface).
   - api: isForChunk; initForChunk(cx,cz,Layer1TerrainGenerator)
   - uses: Layer1TerrainGenerator
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, Layer1TerrainGenerator

### worldgen/util  - ChunkWriter abstractions
- **ChunkAccessWriter** [util] class implements ChunkWriter, 27L - ChunkWriter over a ChunkAccess using setBlockState (side effects on heightmaps).
   - uses: ChunkWriter
   - usedBy: AeroWorldChunkGenerator, HighIslandGenerator, LowerIslandGenerator, UpperIslandGenerator
- **ChunkWriter** [util] interface, 9L - Minimal block read/write interface (world coordinates) that lets generators target a chunk or a LOD buffer.
   - api: setBlockState(wx,wy,wz,state); getBlockState
   - usedBy: AeroWorldChunkGenerator, ChunkAccessWriter, HighIslandGenerator, Layer1TerrainGenerator, LowerIslandGenerator, SectionDirectChunkWriter, UpperIslandGenerator
- **SectionDirectChunkWriter** [util] class implements ChunkWriter, 91L - ChunkWriter that writes straight into LevelChunkSection, bypassing heightmap and block-count side effects; safe for parallel worldgen.
   - uses: ChunkWriter
   - usedBy: AeroWorldChunkGenerator

### worldgen/noise  - Noise, island grid, island SDF shape
- **AeroNoise** [noise] class, 138L - Lightweight seeded OpenSimplex-style 2D/3D noise and fBm; the only noise source for custom worldgen.
   - api: noise2D; noise3D; fbm2D; fbm3D
   - usedBy: AeroBiomeSource, AeroRiverNetwork, HighIslandGenerator, IslandShape, Layer1TerrainGenerator, LowerIslandGenerator, PlateauTerrace, UpperIslandGenerator
- **IslandPlacer** [noise] class, 495L - Deterministic seed-based island grid: one candidate per grid cell, archipelago centres with satellites, neighbourhood queries returning packed centres.
   - api: getIslandCentresForChunk(cx,cz,radius[,maxInfluence]); getCentreForCell; isArchipelagoCentre; getSatellitesForCentre; findArchipelagoCentreFor; NO_ISLAND
   - uses: ChunkKey
   - usedBy: AeroWorldCommands, EndCityStructureMixin, HighIslandGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, LowerIslandGenerator, UpperIslandGenerator
- **IslandShape** [noise] class, 167L - SDF helpers for Layer 2 island solids with per-island profile curve (linear/convex/concave/stepped) and scaled edge noise; XZCache precompute for speed.
   - api: precomputeXZ; isSolid; isSurface; isSubsurface; getSurfaceY; computeProfile
   - uses: AeroNoise
   - usedBy: IslandVaultTrialGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, LowerIslandGenerator

### worldgen/layer  - Per-layer terrain and island generators
- **AeroRiverNetwork** [gen-layer1] class, 228L - Pure function of seed and coordinates producing river channels, lake bowls and deep-lake mask (cell/node graph draining to ocean). Only carves; water fill comes from Layer 1 sea fill. DEEP_LAKE_SHARE = 10% of sink lakes.
   - api: sample(wx,wz,cont) -> Sample(river,lake,deep); deepLakeCentre(chunkX,chunkZ)
   - uses: AeroNoise
   - usedBy: Layer1TerrainGenerator
- **AeroRiverNetworkSelfCheck** [selfcheck] class, 93L - Standalone `main` self-check (javac + java -ea) for AeroRiverNetwork. Not part of the mod runtime.
- **HighIslandGenerator** [gen-layer] class, 762L - Layer 3 (Y 1000..1100, LAYER_ID 1): hollow meteorites and solid planets with three asteroid rings. IslandData via IslandPlacer. fillChunk has ChunkAccess and ChunkWriter overloads; ring helpers for LOD.
   - api: getIslandData; getIslandYBounds; getIslandRadius; fillChunk; getRingYRange; LAYER_MIN_Y/MAX_Y/ID
   - uses: AeroNoise, BodyType, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, IslandCache, IslandData, IslandPlacer, Layer3Settings
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, AeroStructureCover, AeroWorldChunkGenerator, AeroWorldCommands, EndCityStructureMixin, IslandVaultTrialGenerator, Layer3VaultTrialPlacer, LayerSpawnRestriction, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler
- **Layer1FlatGenerator** [gen-layer1] class, 47L - Backward-compat wrapper around Layer1TerrainGenerator (surfaceHeight/topmostHeight) used by structure validation and biome source.
   - api: getTerrainGenerator(); surfaceHeight; topmostHeight
   - uses: Layer1TerrainGenerator
   - usedBy: AeroBiomeSource, AeroWorldChunkGenerator, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler
- **Layer1TerrainGenerator** [gen-layer1] class, 846L - Layer 1 (Y -64..300, SEA_LEVEL 0): custom noise terrain (continentality, erosion, ridges, river valleys), ashen plateau canyons via PlateauTerrace, giant cave Y -50..-25, bedrock, deep-lake dig to DEEP_LAKE_FLOOR_Y -50, surface block selection (strata/badlands/stonesky bands). Biome-aware buildSurface. Nested records SurfaceBlocks, BiomeSurfaceInfo.
   - api: getHeight/getTopmostHeight(wx,wz); fillTerrain(ChunkWriter,cx,cz); buildSurface(chunk,biomeFn); isDeepLake; getDeepLakeCentre; getContinentality/getErosion; computeCaveTop/Bottom; getPlateauMask
   - uses: AeroNoise, AeroRiverNetwork, ChunkWriter, Layer1ColumnCache, PlateauTerrace
   - usedBy: AeroBiomeSource, AeroColumnModel, AeroSeedWorldGenerator, AeroTreeCover, AeroWorldChunkGenerator, AncientCityIslandSupportPlacer, DeepLakeMonumentPlacement, Layer1ColumnCache, Layer1FlatGenerator, OceanMonumentBuildingMixin, OceanMonumentStructureMixin, StructureSupportValidator
- **Layer2StructurePlacer** [gen-layer] class, 88L - During chunk fill enqueues `physical_structures:tank21` at the centre of every Layer 2 island whose tier (Layer2VaultTrialPlacer.pickTierStatic) is RICH, via AeroWorld.structureScheduler.
   - api: placeForChunk(chunk, LowerIslandGenerator, RandomSource)
   - uses: AeroWorld, ChunkIslandCache, ChunkKey, IslandPlacer, IslandShape, Layer2VaultTrialPlacer, LowerIslandGenerator, VaultTrialSpawnTier
   - usedBy: AeroWorldChunkGenerator
- **LowerIslandGenerator** [gen-layer] class implements LowerIslandGeneratorAccess, 975L - Layer 2 (Y 400..500, LAYER_ID 0): cone islands with 4 profiles and noise-deformed edge, archipelagos (centre + satellites), rim trees, stalactites, bridges between neighbours. Implements LowerIslandGeneratorAccess.
   - api: getIslandData; fillChunk; placeTreesInRegion; clearVanillaVegetationInCentralZone; getPlacer(); getShape(); releaseChunkCache; fillBridges(LOD)
   - uses: AeroNoise, AeroTreeCover, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, IslandCache, IslandData, IslandPlacer, IslandShape, Layer2Settings, LowerIslandGeneratorAccess
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldCommands, Layer2StructurePlacer, Layer2VaultTrialPlacer, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler
- **LowerIslandGeneratorAccess** [data] interface, 12L - Narrow interface exposing only worldSeed() of lower islands (used for deterministic tier predicates without full generator).
   - api: worldSeed()
   - usedBy: LowerIslandGenerator
- **PlateauTerrace** [gen-layer1] class, 193L - Pure math (no MC) for the ashen_plateau biome: blob mask, uplift, terracing, canyon/gully/pillar/bridge shaping. Shared by terrain height and biome selection so both agree. FLOOR_Y 8.
   - api: mask; core; gully; pillar; bridge; column; terrace; hasBridgeVoid
   - uses: AeroNoise
   - usedBy: AeroBiomeSource, Layer1TerrainGenerator
- **PlateauTerraceSelfCheck** [selfcheck] class, 145L - Standalone `main` self-check (javac + java -ea) for PlateauTerrace. Not part of the mod runtime.
- **UpperIslandGenerator** [gen-layer] class, 480L - Layer 4 (Y 1900..2031, LAYER_ID 2): jellyfish islands (dome plus 10 tentacles 90-120 long, hanging below LAYER_MIN_Y). Per-island data in IslandCache; XZ caches for cap and tentacles.
   - api: getIslandData; fillChunk; getCachedIslandCentresForChunk; getSearchRadius; getPlacer(); releaseChunkCache
   - uses: AeroNoise, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, IslandCache, IslandData, IslandPlacer, Layer4Settings
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldCommands, IslandVaultTrialGenerator, Layer4VaultTrialPlacer, LayerSpawnRestriction, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler

### worldgen/carver  - Carvers (unwired)
- **SinkholeCarver** [gen-layer1] class, 218L - Karst sinkhole carving directly on a ChunkAccess. UNWIRED: no code calls it (a comment in AeroWorldChunkGenerator.applyCarvers mentions it, but vanilla carvers are simply disabled there).
   - api: carveChunk(ChunkAccess, long seed, HeightSampler)
   - usedBy: NONE (unreferenced)

### worldgen/feature  - Post-decoration filters
- **Layer1OreFilter** [gen-layer1] class, 93L - Post-decoration filter run once per chunk in applyBiomeDecoration: removes ores that leaked from vanilla decoration (all custom layers generate none).
   - api: applyToChunk(ChunkAccess)
   - usedBy: AeroWorldChunkGenerator

### worldgen/feature/vault  - Vault / trial spawner placement
- **IslandVaultTrialCache** [vault] class, 175L - Per-layer progress cache so vault/trial-spawner counts per island are completed across the several chunks an island spans.
   - api: getOrCreate(layerId,cx,cz,vaultCount,trialCount); release; Progress
   - usedBy: AeroWorldChunkGenerator, IslandVaultTrialGenerator, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer
- **IslandVaultTrialGenerator** [vault] class, 982L - Layer-agnostic placement of Vault and Trial Spawner blocks inside island bodies; three entry points by island shape.
   - api: placeForIsland (cone); placeForEllipsoidIsland (Layer 3); placeForJellyfishIsland (Layer 4)
   - uses: BodyType, HighIslandGenerator, IslandData, IslandShape, IslandVaultTrialCache, UpperIslandGenerator, VaultTrialLootConfig, VaultTrialSpawnTier
   - usedBy: Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer
- **Layer2VaultTrialPlacer** [vault] class, 224L - Layer 2 entry for vault/trial generation; picks VaultTrialSpawnTier by archipelago role (centre/satellite/other) and per-layer loot config.
   - api: placeForChunk; archipelagoCentreTier(); satelliteTier(); pickTierStatic
   - uses: ChunkIslandCache, ChunkKey, IslandData, IslandPlacer, IslandShape, IslandVaultTrialCache, IslandVaultTrialGenerator, LowerIslandGenerator, VaultTrialLootConfig, VaultTrialSpawnTier
   - usedBy: AeroWorldChunkGenerator, AeroWorldCommands, Layer2StructurePlacer
- **Layer3VaultTrialPlacer** [vault] class, 139L - Layer 3 entry for vault/trial generation (ellipsoid bodies); mirrors Layer2VaultTrialPlacer.
   - api: placeForChunk
   - uses: ChunkIslandCache, ChunkKey, HighIslandGenerator, IslandData, IslandVaultTrialCache, IslandVaultTrialGenerator, VaultTrialLootConfig, VaultTrialSpawnTier
   - usedBy: AeroWorldChunkGenerator
- **Layer4VaultTrialPlacer** [vault] class, 132L - Layer 4 entry for vault/trial generation (jellyfish islands); mirrors Layer3VaultTrialPlacer.
   - api: placeForChunk
   - uses: ChunkIslandCache, ChunkKey, IslandData, IslandVaultTrialCache, IslandVaultTrialGenerator, UpperIslandGenerator, VaultTrialLootConfig, VaultTrialSpawnTier
   - usedBy: AeroWorldChunkGenerator
- **VaultTrialLootConfig** [vault] record, 96L - Record of loot table ids (vault normal/ominous, trial normal/ominous) plus spawn potentials for one layer profile. Loot tables live under data/aeroworld/loot_table/gameplay/layerN/.
   - usedBy: IslandVaultTrialGenerator, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer
- **VaultTrialSpawnTier** [vault] enum, 38L - Enum POOR(1 vault,1 spawner), MEDIUM(2,3), RICH(3,5): vault and trial-spawner counts per island. RICH Layer 2 islands also receive the tank structure.
   - api: vaultCount(); trialSpawnerCount()
   - usedBy: AeroWorldCommands, IslandVaultTrialGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer

### worldgen/structure  - Structure start validation + support platforms
- **AncientCityIslandSupportPlacer** [structure-gen] class, 130L - Builds a stepped stone platform under ancient cities so they do not hang in the Layer 1 cave void.
   - api: placeSupportForChunk(WorldGenLevel, ChunkAccess)
   - uses: Layer1TerrainGenerator
   - usedBy: AeroWorldChunkGenerator
- **DeepLakeMonumentPlacement** [structure-gen] class extends StructurePlacement, 68L - Custom StructurePlacement `aeroworld:deep_lake_monument`: one start per deep lake, only in the lake-centre chunk where the floor is flat at DEEP_LAKE_FLOOR_Y.
   - api: isPlacementChunk; CODEC; registered in AeroRegistries
   - uses: AeroRegistries, AeroWorldChunkGenerator, Layer1TerrainGenerator
   - usedBy: AeroRegistries, OceanMonumentBuildingMixin, OceanMonumentStructureMixin
- **StructureCategory** [structure-gen] enum, 39L - Enum SURFACE, ISLAND, UNDERGROUND, WATER, SKY_FLOATING, DENY: selects the validation mode (solid ground / layer-bounds checks).
   - api: requiresSolidGround(); requiresLayerBoundsCheck()
   - usedBy: StructureCategoryResolver, StructureSupportValidator, ValidationResult
- **StructureCategoryResolver** [structure-gen] class, 234L - Maps a structure ResourceLocation (and Y / actual layer) to a StructureCategory via deny list, whitelist, underground and water token rules.
   - api: resolve; resolveForY; resolveForActualLayer; isIslandLayerY; isVoidGapY
   - uses: HighIslandGenerator, Layer1FlatGenerator, LowerIslandGenerator, StructureCategory, UpperIslandGenerator
   - usedBy: StructureSupportValidator
- **StructureSoleYSelfCheck** [selfcheck] class, 21L - Self-check for StructureSupportValidator.medianOf; run from IDE with -ea (class pulls MC).
- **StructureSupportValidator** [structure-gen] class, 556L - Validates vanilla structure starts against custom terrain so villages etc. do not float in gaps; samples support grid, takes median sole Y, logs rejections, and erases rejected structures afterwards (postPlacementCleanup).
   - api: validate(id,start,heightSampler) -> ValidationResult; postPlacementCleanup(region,chunk,...); medianOf
   - uses: ChunkIslandCache, HighIslandGenerator, Layer1FlatGenerator, Layer1TerrainGenerator, LowerIslandGenerator, StructureCategory, StructureCategoryResolver, SupportSample, TerrainColumnSampler, UpperIslandGenerator, ValidationResult
   - usedBy: AeroWorldChunkGenerator, TerrainColumnSampler
- **SupportSample** [structure-gen] record, 8L - Record (x,z) of a failed support probe point, for diagnostics.
   - usedBy: StructureSupportValidator, ValidationResult
- **TerrainColumnSampler** [structure-gen] class, 638L - Answers solidity/water/island-top questions per XZ without reading blocks (chunks may be unloaded), using Layer 1-4 generator math and island caches. SAMPLE_GRID_STEP 8.
   - api: groundHeightAt; hasSolidBelow; isWaterCoveredAt; getIslandTopY; hasCollision; resolveActualLayer
   - uses: ChunkIslandCache, ChunkKey, HighIslandGenerator, IslandData, Layer1FlatGenerator, LowerIslandGenerator, StructureSupportValidator, UpperIslandGenerator
   - usedBy: StructureSupportValidator
- **ValidationResult** [structure-gen] class, 138L - Outcome of structure validation: accepted/denied/void-gap/insufficient-support/sky-floating/water, with bounds and failed SupportSamples.
   - api: static factories accepted/denied/voidGap/...; isAccepted()
   - uses: StructureCategory, SupportSample
   - usedBy: AeroWorldChunkGenerator, StructureSupportValidator

### worldgen/column  - Analytic column model for LOD + helpers
- **AeroColumnModel** [lod-core] class, 535L - Analytic vertical-column model of all four layers: buildSpans(x,z,minY,levelMax,...) returns bottom-to-top Span list (state, optional biome). Single source of truth for DH LOD generation and for validation against the real generator. Layers merged: Layer 1 surface/cave/ocean, islands, structure cover, trees.
   - api: record Span; buildSpans(...) overloads
   - uses: AeroBiomeSource, AeroStructureCover, AeroTreeCover, BodyType, ChunkKey, HighIslandGenerator, IslandData, Layer1ColumnCache, Layer1TerrainGenerator, LowerIslandGenerator, UpperIslandGenerator
   - usedBy: AeroColumnWriter, AeroSeedGenValidation, AeroSeedWorldGenerator, AeroStructureMarkers, AeroWorldChunkGenerator, AeroWorldCommands
- **AeroColumnWriter** [lod-dh] class, 216L - Converts AeroColumnModel spans into DH DhApiTerrainDataPoint lists with cached block/biome wrappers.
   - api: toDataPoints(spans,minY,maxY[,yOffset]); getBlockStateWrapper; getBiomeWrapper
   - uses: AeroColumnModel
   - usedBy: AeroSeedWorldGenerator
- **AeroStructureCover** [lod-core] class, 126L - Analytic structure coverage for LOD (Layer 3 planet End Cities only; Kind NONE | END_CITY): per chunk detects the city and overlays a simplified vertical block template. Constants must match EndCityStructureMixin (END_CITY_CLEARANCE).
   - api: solve(chunkX,chunkZ,HighIslandGenerator) -> Kind; sampleLayer3PlanetStructure(...)
   - uses: BodyType, ChunkKey, HighIslandGenerator, IslandData
   - usedBy: AeroColumnModel
- **AeroTreeCover** [lod-core] class, 261L - Analytic tree trunk/canopy spans for LOD per biome rule (Layer 1 and Layer 2 islands), deterministic from seed; no vanilla feature pipeline.
   - api: getRuleForBiome; sampleLayer1(...); records AeroTreeShape, Rule, TreeSpans
   - uses: Layer1TerrainGenerator
   - usedBy: AeroColumnModel, LowerIslandGenerator
- **SliceVote** [lod-core] class, 89L - Pure-Java merge of several vertical columns into one by Y-slice voting (air ignored, most frequent solid wins), same scheme as DH 2x2 merge. Also `carve` for overwriting a Y range. No MC types.
   - api: carve(segs,lo,hi,fill); vote(cols,key,empty)
   - usedBy: AeroSeedWorldGenerator, AeroStructureMarkers
- **SliceVoteSelfCheck** [selfcheck] class, 57L - Standalone `main` self-check (javac + java -ea) for SliceVote. Not part of the mod runtime.

### worldgen/dh  - Distant Horizons integration, caches, limits, diagnostics
- **AeroAdjReadStats** [dh-diag] class, 85L - Measures adjacent-section reads (getAdjForDirection): total time, repo query time, no-row cases, repeats. -Daeroworld.dhPoolStats.
   - api: firstHit; record(...); drain
   - usedBy: AdjReadStatsMixin, AeroThroughputLimits
- **AeroAdjReadStatsSelfCheck** [selfcheck] class, 35L - Standalone `main` self-check (javac + java -ea) for AeroAdjReadStats. Not part of the mod runtime.
- **AeroAdjacencyCache** [dh-cache] class, 386L - LRU+TTL cache of decoded FullDataSourceV2 neighbour sections to fix seams/holes at section borders. Controlled by AeroWorldConfig adjacency keys.
   - api: Entry.get(pos, loader); Entry.release/close
   - uses: AeroWorldConfig
   - usedBy: LodRenderSectionAdjDiagMixin, SqliteTuningMixin, StandInTerrainMixin
- **AeroCascadeLeaf** [dh-exp] class, 112L - Experiment (default OFF, -Daeroworld.dhCascadeLeafRadius=0): skip pushing coarse-section data down into detail-6 leaves far from the player, with re-arm when the player approaches. UNWIRED: no production caller (only its self-check).
   - api: setTarget(x,z); skip(detail,minX,minZ,width); rearmDue; shouldSkip(...)
   - usedBy: NONE (unreferenced)
- **AeroCascadeLeafSelfCheck** [selfcheck] class, 48L - Standalone `main` self-check (javac + java -ea) for AeroCascadeLeaf. Not part of the mod runtime.
- **AeroCompleteSectionCache** [dh-cache] class, 168L - LRU of positions confirmed fully generated, so getPositionsToRetrieve skips repeated quad-tree status scans. Hit/miss counters.
   - api: isConfirmedComplete; markComplete; invalidate; clear; logSummary
   - uses: AeroWorldConfig
   - usedBy: CompleteSectionCacheMixin, SqliteTuningMixin
- **AeroExecutorSampler** [dh-diag] class, 57L - Once-per-second sampler of DH pool running/queued tasks (generation, propagator, render loader, IO, builder); part of the dhPoolStats diagnostics.
   - api: startOnce(); sample()
   - uses: AeroPoolStats
   - usedBy: AeroThroughputLimits
- **AeroFastDistantTerrain** [dh-core] class, 71L - Per-layer detail-level thresholds for when LOD geometry is simplified; global on/off toggled by /aeroworld DHopt.
   - api: setEnabled/isEnabled; isLayerNCoarse(detail); subsamplesForDetailLevel
   - uses: DhOverrideSettings
   - usedBy: AeroSeedWorldGenerator, AeroWorldCommands
- **AeroHybridStats** [dh-diag] class, 319L - Stage-0 measurement counters for hybrid loading (section branches, regen gates, queue gates, unsaved counts), logged every 30 s; -Daeroworld.dhstats=false disables. Fed only by stat mixins.
   - api: onSection; onRegenGate; onCanQueue; onUnsaved; onRemaining; enums Branch, RegenGate, QueueGate
   - usedBy: CanQueueStatsMixin, QueueOrderStatsMixin, RegenGateStatsMixin
- **AeroHybridStatsSelfCheck** [selfcheck] class, 56L - Standalone `main` self-check (javac + java -ea) for AeroHybridStats. Not part of the mod runtime.
- **AeroLeafLedger** [dh-core] class, 75L - Persistent set (8-byte keys on disk) of detail-0 sections already given analytic data, so a repeat request is treated as phase 2 (real chunk gen) even after restart. Used only with -Daeroworld.dhTwoPhase.
   - api: contains(key); add(key); key(lodX,lodZ)
   - usedBy: AeroSeedWorldGenerator
- **AeroLeafLedgerSelfCheck** [selfcheck] class, 36L - Standalone `main` self-check (javac + java -ea) for AeroLeafLedger. Not part of the mod runtime.
- **AeroLeafSlots** [dh-core] class, 28L - Atomic counter limiting concurrent real-leaf (chunk-gen) tasks; cap 0 = unlimited.
   - api: tryAcquire(cap); release(); get()
   - usedBy: AeroThroughputLimits
- **AeroLeafSlotsSelfCheck** [selfcheck] class, 30L - Standalone `main` self-check (javac + java -ea) for AeroLeafSlots. Not part of the mod runtime.
- **AeroPlayerAnchors** [dh-core] class, 54L - Once-per-second snapshot of player positions per dimension for DH worker threads (reading ServerLevel.players() off-thread is unsafe).
   - api: onLevelTick; nearestChebyshev(dim,x,z); isWithin(dim,x,z,radius)
   - usedBy: AeroSeedWorldGenerator, AeroWorld
- **AeroPoolStats** [dh-diag] class, 157L - Measurement of DH pool task wait/body/after-body times per detail level; on with -Daeroworld.dhPoolStats=true.
   - api: wrap(Executor,detail,submitNs,body); sample(...); record; drain
   - usedBy: AeroExecutorSampler, AeroThroughputLimits
- **AeroPoolStatsSelfCheck** [selfcheck] class, 55L - Standalone `main` self-check (javac + java -ea) for AeroPoolStats. Not part of the mod runtime.
- **AeroPropStats** [dh-diag] class, 93L - Measures DH propagator tasks (kind 0 up / 1 down, per detail), wait/run times and repeat share. -Daeroworld.dhPoolStats.
   - api: notePending(pos,detail); wrap(kind,task); drain
   - usedBy: AeroThroughputLimits, PropagateStatsMixin
- **AeroPropStatsSelfCheck** [selfcheck] class, 34L - Standalone `main` self-check (javac + java -ea) for AeroPropStats. Not part of the mod runtime.
- **AeroSeedGenValidation** [dh-core] class, 122L - Dev validation: compares AeroColumnModel.buildSpans with AeroWorldChunkGenerator.getBaseColumn at N random XZ across layers; backs `/aeroworld validateSeedGen`.
   - api: runValidation(ctx,sampleCount)
   - uses: AeroColumnModel, AeroWorldChunkGenerator
   - usedBy: AeroWorldCommands
- **AeroSeedWorldGenBinding** [dh-core] class extends DhApiLevelLoadEvent, 147L - Registers AeroSeedWorldGenerator with Distant Horizons on DhApiLevelLoadEvent; stands down (hands back to stock DH generator) if -Daeroworld.dhOff, config disabled, or incompatible level. Applies configured extended render distance and horizontal quality.
   - api: registerIfDhPresent()
   - uses: AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldConfig
   - usedBy: AeroWorld
- **AeroSeedWorldGenerator** [dh-core] class implements IDhApiWorldGenerator, 428L - IDhApiWorldGenerator override: produces LOD sections analytically from AeroColumnModel (no chunk gen) with detail-based sub-sampling and SliceVote merge, structure markers, FastDistantTerrain coarse modes, optional two-phase leaf ledger, player-distance gating.
   - api: generateLod(...); generateApiChunks(...); buildDominantSpans(...); getReturnType; close
   - uses: AeroBiomeSource, AeroColumnModel, AeroColumnWriter, AeroFastDistantTerrain, AeroLeafLedger, AeroPlayerAnchors, AeroStructureMarkers, AeroThroughputLimits, AeroWorldChunkGenerator, AeroWorldConfig, HighIslandGenerator, Layer1ColumnCache, Layer1TerrainGenerator, LowerIslandGenerator, SliceVote, UpperIslandGenerator
   - usedBy: AeroSeedWorldGenBinding, AeroWorldCommands
- **AeroStructureIcon** [dh-core] class, 133L - Voxel miniature of a structure from ASCII layer schemas; MC-independent; mapping char->block in AeroStructureMarkers.
   - api: forPath(path); of(layers...); column(u,v) -> List<Seg>; shownOnLod(path)
   - usedBy: AeroStructureMarkers
- **AeroStructureIconSelfCheck** [selfcheck] class, 50L - Standalone `main` self-check (javac + java -ea) for AeroStructureIcon. Not part of the mod runtime.
- **AeroStructureMarkers** [dh-core] class, 247L - Places tiny structure icons on analytic LOD by probing structure starts (RandomSpread-like) via the real chunk generator; unknown structures get a coloured spire. Flag -Daeroworld.dhStructures=false disables.
   - api: forSection(baseX,baseZ,width,step) -> Map<Integer,Placed>; apply(spans,placed,maxY); records Marker, Placed
   - uses: AeroColumnModel, AeroStructureIcon, AeroWorldChunkGenerator, SliceVote
   - usedBy: AeroSeedWorldGenerator
- **AeroThroughputLimits** [dh-core] class, 318L - Central DH tuning hub: reads all -Daeroworld.dh* flags (TWO_PHASE, REAL_LEAF_CAP, RENDER_LOADER_PRIORITY 1..10 default 6, WORLD_GEN_BORDER 0..8, SAVE_DELAY_MS 250..30000 default 1000, SQLite sync), real radius / render yield from config, plus throughput logging and wrappers for pool stats. Consumed by most DH mixins.
   - api: realRadiusBlocks(); renderYieldQueue(); distantHorizonsThreadCount(); trackRealLeaf; startRealLeaf(cap,start); timedExecutor; recordAnalyticTask; recordChunksGenerated
   - uses: AeroAdjReadStats, AeroExecutorSampler, AeroLeafSlots, AeroPoolStats, AeroPropStats, AeroWorldConfig
   - usedBy: AeroSeedWorldGenerator, BatchGenerationEnvironmentNeoforgeMixin, GeneratorBusyMixin, LodQuadTreeRealRadiusMixin, RenderLoaderPriorityMixin, RetrievalQueueLimitMixin, SaveDelayMixin, SqliteTuningMixin, WorldGenSpeedGateMixin, WorldGenUtilRegenRadiusMixin

### mixin/structure  - Vanilla structure mixins (required)
- **EndCityStructureAccessor** [mixin] interface, 25L - Invoker accessor exposing private EndCityStructure.generatePieces to EndCityStructureMixin.
   - api: target: EndCityStructure; @Invoker generatePieces
   - usedBy: EndCityStructureMixin
- **EndCityStructureMixin** [mixin] class, 259L - Overrides EndCityStructure.findGenerationPoint (HEAD, cancellable): in AeroWorld places exactly one End City (no ship) at the exact centre of each Layer 3 PLANET, reusing vanilla piece generation through the accessor.
   - api: target: EndCityStructure#findGenerationPoint; config: aeroworld_structures.mixins.json
   - uses: AeroWorldChunkGenerator, BodyType, ChunkKey, EndCityStructureAccessor, HighIslandGenerator, IslandData, IslandPlacer
- **NetherFortressStructureMixin** [mixin] class, 71L - Redirect in NetherFortressStructure.generatePieces: pins the fortress to Y -55..-50 (inside the Layer 1 giant cave).
   - api: target: NetherFortressStructure#generatePieces; config: aeroworld_structures.mixins.json
   - uses: AeroWorldChunkGenerator
- **OceanMonumentBuildingMixin** [mixin] class, 42L - Adapts OceanMonumentPieces.MonumentBuilding constants (vanilla min Y 39, water fill top) to the deep-lake floor Y -50.
   - api: target: OceanMonumentPieces$MonumentBuilding (<init> constant 39, postProcess Math.max); config: aeroworld_structures.mixins.json
   - uses: DeepLakeMonumentPlacement, Layer1TerrainGenerator
- **OceanMonumentStructureMixin** [mixin] class, 72L - Overrides OceanMonumentStructure.findGenerationPoint: monuments exist only at the deep-lake centre chunk, one per lake, base at the flat lake floor.
   - api: target: OceanMonumentStructure#findGenerationPoint; config: aeroworld_structures.mixins.json
   - uses: AeroWorldChunkGenerator, DeepLakeMonumentPlacement, Layer1TerrainGenerator

### mixin/dh  - Distant Horizons mixins (soft dependency)
- **AdjReadStatsMixin** [mixin-dh] class, 55L - Measurement only: times adjacent-section reads and no-row results. Gated by dhPoolStats.
   - api: target: FullDataSourceProviderV2#getAdjForDirection (@Redirect + RETURN); uses AeroAdjReadStats
   - uses: AeroAdjReadStats
- **BatchGenerationEnvironmentNeoforgeMixin** [mixin-dh] class, 126L - Three @Redirects in generateChunks: widen DH neighbour-chunk border (extraRadius of both getIterator calls and the border-size field) by WORLD_GEN_BORDER (-Daeroworld.dhBorder, default 0 = no change).
   - api: target: DhChunkGenerator_neoforge#generateChunks; uses AeroThroughputLimits
   - uses: AeroThroughputLimits
- **CanQueueStatsMixin** [mixin-dh] class, 73L - Measurement only: why canQueueRetrievalNow admits or refuses tasks, queue depth, unsaved counts. Gated by -Daeroworld.dhStatsMixins.
   - api: target: GeneratedFullDataSourceProvider#canQueueRetrievalNow, setEstimatedRemainingRetrievalChunkCount (HEAD/RETURN); uses AeroHybridStats
   - uses: AeroHybridStats
- **CompleteSectionCacheMixin** [mixin-dh] class, 59L - Short-circuits getPositionsToRetrieve for sections already confirmed complete; records completion on RETURN.
   - api: target: GeneratedFullDataSourceProvider#getPositionsToRetrieve(JB) (HEAD cancellable + RETURN); uses AeroCompleteSectionCache
   - uses: AeroCompleteSectionCache
- **DhWorldGenBorderMixinPlugin** [mixin-dh] class implements IMixinConfigPlugin, 83L - IMixinConfigPlugin of aeroworld.mixins.json: applies DH mixins only if DH core Initializer is on the classpath and -Daeroworld.dhOff is not set; gates CanQueueStatsMixin by dhStatsMixins and PropagateStatsMixin/AdjReadStatsMixin by dhPoolStats. getMixins() returns null (adds nothing).
   - api: shouldApplyMixin; onLoad
- **GeneratorBusyMixin** [mixin-dh] class, 32L - Widens the backlog allowed before the world generation queue reports busy, so analytic generation is not throttled.
   - api: target: WorldGenerationQueue#isGeneratorBusy (RETURN, cancellable)
   - uses: AeroThroughputLimits
- **LodQuadTreeRealRadiusMixin** [mixin-dh] class, 25L - Keeps detail 0 leaves within REAL radius of the player (stock formula gives only 24-44 chunks). NOT REGISTERED in any mixins.json: inactive.
   - api: target: LodQuadTree#calcDetailLevelFromDistance (HEAD, cancellable); uses AeroThroughputLimits
   - uses: AeroThroughputLimits
- **LodRenderSectionAdjDiagMixin** [mixin-dh] class, 75L - Diagnostic: counts missing/empty neighbour sections when LOD buffers are built (seam diagnosis), logs first cases.
   - api: target: LodRenderSection#getRenderSourceForPos (RETURN)
   - uses: AeroAdjacencyCache
- **PropagateStatsMixin** [mixin-dh] class, 46L - Measurement only: wraps propagator parent/child update tasks to count tasks and repeats. Gated by dhPoolStats.
   - api: target: FullDataUpdatePropagatorV2#runParentUpdates, runChildUpdates (@Redirect); uses AeroPropStats
   - uses: AeroPropStats
- **QueueOrderStatsMixin** [mixin-dh] class, 48L - Measurement only: queue ordering stats when a detail-6 task starts. NOT REGISTERED: inactive.
   - api: target: WorldGenerationQueue#startWorldGenTaskGroup (HEAD); uses AeroHybridStats
   - uses: AeroHybridStats
- **RegenGateStatsMixin** [mixin-dh] class, 49L - Measurement only: why DH regeneration requests stop (gate conditions). NOT REGISTERED: inactive.
   - api: target: FullDataUpdatePropagatorV2#tryQueueRegeneration, queueRegeneration (HEAD); uses AeroHybridStats
   - uses: AeroHybridStats
- **RenderLoaderPriorityMixin** [mixin-dh] class, 23L - Overrides the Render Loader pool thread priority constant 6 with RENDER_LOADER_PRIORITY (default unchanged).
   - api: target: ThreadPoolUtil#setupThreadPools (@ModifyConstant 6)
   - uses: AeroThroughputLimits
- **RetrievalQueueLimitMixin** [mixin-dh] class, 19L - Raises the retrieval queue limit constant 20 so workers do not idle.
   - api: target: GeneratedFullDataSourceProvider#getMaxRetrievalQueueCount (@ModifyConstant)
   - uses: AeroThroughputLimits
- **SaveDelayMixin** [mixin-dh] class, 24L - Sets LOD save delay (default 1 s via SAVE_DELAY_MS) instead of stock 10 s.
   - api: target: GeneratedFullDataSourceProvider#<init> (@ModifyConstant)
   - uses: AeroThroughputLimits
- **SqliteTuningMixin** [mixin-dh] class, 115L - Applies SQLite pragmas to DH repos on init (256 MB page cache, mmap 256 MB, temp_store MEMORY, synchronous NORMAL or configured); invalidates adjacency/complete-section cache entries on save and deleteWithKey, clears both on deleteAll (FullDataSourceV2Repo only).
   - api: target: AbstractDhRepo#<init>, close, save, deleteWithKey, deleteAll; uses AeroAdjacencyCache, AeroCompleteSectionCache
   - uses: AeroAdjacencyCache, AeroCompleteSectionCache, AeroThroughputLimits
- **StandInTerrainMixin** [mixin-dh] class, 120L - Fills LOD holes: when the requested section has no data, walks up the parent chain (loaded through the adjacency cache) and downsamples parent data to stand in.
   - api: target: LodRenderSection#getRenderSourceForPos (RETURN, cancellable); uses AeroAdjacencyCache
   - uses: AeroAdjacencyCache
- **TranslucentAdjWallMixin** [mixin-dh] class, 61L - Cancels full-height side walls of translucent (water) columns at section borders when the neighbour data is still empty.
   - api: target: ColumnBox#makeAdjVerticalQuad (HEAD, cancellable)
- **WorldGenSpeedGateMixin** [mixin-dh] class, 28L - Gates worldgen thread start in favour of render loading when render queue is overloaded (render yield queue).
   - api: target: ThreadPoolUtil#worldGenThreadsCanRun (HEAD, cancellable)
   - uses: AeroThroughputLimits
- **WorldGenUtilRegenRadiusMixin** [mixin-dh] class, 33L - Overrides max leaf regeneration distance with the configured real-chunk radius.
   - api: target: WorldGenUtil#getMaxRegenDistanceInBlocks (HEAD, cancellable)
   - uses: AeroThroughputLimits


## Mixin configs

| Config (resources/) | Package | required | Plugin | Entries |
|---|---|---|---|---|
| aeroworld.mixins.json | mixin.dh | false | DhWorldGenBorderMixinPlugin | BatchGenerationEnvironmentNeoforgeMixin, LodRenderSectionAdjDiagMixin, TranslucentAdjWallMixin, RetrievalQueueLimitMixin, CanQueueStatsMixin, RenderLoaderPriorityMixin, GeneratorBusyMixin, WorldGenSpeedGateMixin, SaveDelayMixin, SqliteTuningMixin, StandInTerrainMixin, CompleteSectionCacheMixin, WorldGenUtilRegenRadiusMixin, PropagateStatsMixin, AdjReadStatsMixin |
| aeroworld_structures.mixins.json | mixin.structure | true | none | NetherFortressStructureMixin, EndCityStructureMixin, EndCityStructureAccessor, OceanMonumentStructureMixin, OceanMonumentBuildingMixin |

Both are listed in `templates/META-INF/neoforge.mods.toml`. Classes in `mixin/dh` missing from the table (LodQuadTreeRealRadiusMixin, QueueOrderStatsMixin, RegenGateStatsMixin) are not registered and never apply (the plugin's `getMixins()` returns null). All DH targets live under `com.seibel.distanthorizons.*`.

## JVM flags (`-Daeroworld.*`)

| Flag | Default | Read by | Effect |
|---|---|---|---|
| dhOff | false | DhWorldGenBorderMixinPlugin, AeroSeedWorldGenBinding | no DH mixins, no DH generator override |
| dhPoolStats | false | DhWorldGenBorderMixinPlugin, AeroPoolStats, AeroPropStats, AeroAdjReadStats | enables PropagateStatsMixin, AdjReadStatsMixin and pool/propagator/adjacent-read measurement |
| dhStatsMixins | false | DhWorldGenBorderMixinPlugin | enables CanQueueStatsMixin |
| dhstats | true | AeroHybridStats | hybrid loading counters and 30 s log |
| dhTwoPhase | false | AeroThroughputLimits | analytic SURFACE first, real chunk generation on repeat request (uses AeroLeafLedger) |
| dhRealLeafCap | 0 | AeroThroughputLimits | max concurrent real leaf generations (0 = unlimited) |
| dhRenderLoaderPriority | 6 (1..10) | AeroThroughputLimits | Render Loader pool thread priority |
| dhBorder | 0 (0..8) | AeroThroughputLimits | extra neighbour border for DH chunk generation |
| dhSaveDelayMs | 1000 (250..30000) | AeroThroughputLimits | LOD save delay |
| dhCascadeLeafRadius | 0 | AeroCascadeLeaf | experiment radius; 0 = off (class unwired) |
| dhStructures | true | AeroStructureMarkers | `false` disables structure icons on LOD |
| biomeSync | false | AeroWorldChunkGenerator | `true` (experimental): reconfigure the field biome source in place instead of using a copy, so LOD and real chunks share one instance |

## Resource and data files

| Path | Role |
|---|---|
| templates/META-INF/neoforge.mods.toml | Mod metadata with Gradle placeholders. Required: neoforge, minecraft, physical_structures [1,). Optional: distanthorizons. Registers both mixin configs. |
| aeroworld.mixins.json, aeroworld_structures.mixins.json | Mixin configs (see above). |
| assets/aeroworld/lang/en_us.json, ru_ru.json | Translations. |
| data/aeroworld/dimension_type/aeroworld.json | Dimension type: min_y -64, height and logical_height 2096, skylight on, no ceiling. |
| data/aeroworld/dimension/aeroworld.json | Level stem: generator `aeroworld:aero_generator`, biome source `aeroworld:aero_biome_source` (preset minecraft:overworld), vanilla noise settings overworld, and the `aero_settings` for layer2, layer3, layer4 (decoded by AeroWorldSettings). |
| data/aeroworld/worldgen/world_preset/aeroworld.json | World preset: overworld uses the aeroworld dimension, Nether and End stay vanilla. |
| data/minecraft/tags/worldgen/world_preset/normal.json | Adds `aeroworld:aeroworld` to the selectable presets. |
| data/aeroworld/presets/*.json (default, dense_archipelago, grand_isolation, skyblock_classic, vast_wilderness) | Alternative `aero_settings` value sets with a `_description`; copy into the dimension JSON. Not read by Java. |
| data/aeroworld/worldgen/biome/*.json | Custom biome clones (`aeroworld:<name>`) for Layer 1 and islands, including plateau, deep_lake, wisteria_grove, volcanic_wastes, karst_highlands, ashen_plateau. Chosen by AeroBiomeSource. |
| data/aeroworld/tags/worldgen/biome/aero_biomes.json | Tag `#aeroworld:aero_biomes` listing all custom biomes. |
| data/aeroworld/neoforge/biome_modifier/remove_ores.json | remove_features of ore features on `#aeroworld:aero_biomes`. |
| data/aeroworld/worldgen/configured_feature, placed_feature | Custom features: wisteria, flower_wisteria, cherry_or_wisteria, lavafall. |
| data/aeroworld/worldgen/structure_set/deep_lake_monuments.json | minecraft:monument with placement `aeroworld:deep_lake_monument` (DeepLakeMonumentPlacement). |
| data/aeroworld/loot_table/gameplay/layer2, layer3, layer4/ | vault_normal, vault_ominous, trial_spawner_normal, trial_spawner_ominous for each layer; ids held in VaultTrialLootConfig. |
| data/minecraft/worldgen/structure/end_city.json, bastion_remnant.json | Vanilla structure overrides: biomes `#aeroworld:aero_biomes`; bastion start height moved into the Layer 1 cave (fortress is handled by NetherFortressStructureMixin). |
| data/minecraft/worldgen/structure_set/*.json | Vanilla structure placement overrides for the dimension. |
| data/minecraft/tags/worldgen/biome/has_structure/*.json, is_overworld.json, required_ocean_monument_surrounding.json | Biome tags mapping vanilla structures to `aeroworld:*` biomes. |
| data/physical_structures/physical_structures/tank21.json, data/physical_structures/structures/tank21.nbt | Physical structure definition (id physical_structures:tank21, rotation none) and its NBT. Registered in code by AeroWorld.registerAeroStructures. |
