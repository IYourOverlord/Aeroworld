# AeroWorld - Project Index

Code card catalog and dependency graph of everything under `src/`. Verified against the code on 2026-10-14 (git efdbe51). Minecraft 1.21.1, NeoForge 21.1.x, Java 21, root package `org.example.aeroworld`.

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
2. Chunk generation (`AeroWorldChunkGenerator`): `fillFromNoise` -> `Layer1TerrainGenerator.fillTerrain`, then `LowerIslandGenerator` / `HighIslandGenerator` / `UpperIslandGenerator.fillChunk` for each layer overlapping the chunk Y range (all through `SectionDirectChunkWriter`), `Layer2StructurePlacer` -> `IslandStructureScheduler.enqueue` (inside the Layer 2 fill). Then the separate `buildSurface` stage -> `Layer1TerrainGenerator.buildSurface`. `applyBiomeDecoration` -> vanilla decoration, `StructureSupportValidator.postPlacementCleanup`, `Layer1OreFilter`, Layer 2 island trees, `Layer2/3/4VaultTrialPlacer` (-> `IslandVaultTrialGenerator`), `AncientCityIslandSupportPlacer`. `applyCarvers` only lazily initialises the seed (no carving).
3. Structure starts: `createStructures` -> `StructureSupportValidator.validate` (-> `StructureCategoryResolver`, `TerrainColumnSampler`, `ValidationResult`); vanilla structure mixins in `mixin/structure` change End City, Nether fortress and ocean monument placement.
4. Physical structure placement (tank21 on RICH Layer 2 islands): worldgen thread `IslandStructureScheduler.enqueue` -> server thread `flushToPersistence` -> `PendingStructureData` -> `ProximityTriggerHandler` (player within 96 XZ / 128 Y; `StructurePlacementHelper`, `StructureSizeCache`) -> `PhysicalStructures` -> `AeroStructureListener`. Forced by `/aeroworld forcePlacePending`.
5. Distant Horizons LOD: `AeroSeedWorldGenBinding` -> `AeroSeedWorldGenerator.generateLod`. Detail-0 leaves near a player (`AeroPlayerAnchors`, realChunkRadius) are handed to DH's real chunk generator (single-phase by default, `AeroLeafLedger` with dhTwoPhase); all other sections are analytic: `AeroColumnModel.buildSpans` -> `AeroColumnModel.buildSpans` (-> `AeroStructureCover`, `AeroTreeCover`, `Layer1ColumnCache`, the layer generators, `AeroBiomeSource`) -> `AeroStructureMarkers` / `SliceVote` merge -> `AeroColumnWriter` data points. Limits and pacing: `AeroThroughputLimits` + `mixin/dh`. Caches: `AeroAdjacencyCache`, `AeroCompleteSectionCache`. Validation: `AeroSeedGenValidation` (`/aeroworld validateSeedGen`).

## Java cards

### (root)  - Mod entry
- **AeroWorld** [entry] class, 141L - Mod entry point (@Mod). The constructor registers config and registries, creates the IslandStructureScheduler, and wires game-bus listeners (reload listener, AeroBiomeRegistryCache start/stop, ProximityTriggerHandler, SpawnerProximityHandler, ShorelineWaveHandler, AeroStructureListener, AeroWorldCommands, AeroPlayerAnchors tick) and the DH binding; registers physical structure tank21 after load complete; invalidates StructureSizeCache on /reload. Holds static MOD_ID and structureScheduler (AeroWorldChunkGenerator replaces the scheduler each time it applies a seed). LayerSpawnRestriction and AeroWorldClientEvents subscribe themselves through @EventBusSubscriber, not here.
   - api: AeroWorld(IEventBus, ModContainer); static structureScheduler; private registerAeroStructures()
   - uses: AeroBiomeRegistryCache, AeroPlayerAnchors, AeroRegistries, AeroSeedWorldGenBinding, AeroStructureListener, AeroWorldCommands, AeroWorldConfig, IslandStructureScheduler, ProximityTriggerHandler, ShorelineWaveHandler, SpawnerProximityHandler, StructureSizeCache
   - usedBy: AeroWorldChunkGenerator, Layer2StructurePlacer, ProximityTriggerHandler

### config  - Config spec + codec settings records
- **AeroWorldConfig** [config] class, 95L - ModConfigSpec (type CLIENT) for config/aeroworld-client.toml, section [distant_horizons], values held in DH_* fields: dhOverrideEnabled (true), throughputLogIntervalSec (30), adjacencyCache{Enabled (true), Size (512), TtlSec (10)}, completeSectionCache{Enabled (true), Size (65536)}, extendedRenderDistance{Enabled (false), Chunks (128)}, horizontalQuality (HIGH), realChunkRadius (64 chunks; 0 = no real-chunk leaves), renderYieldQueue (2500). Read by DH classes only; callers on server threads must tolerate an unloaded config (they catch IllegalStateException).
   - api: register(ModContainer); static DH_* value holders
   - usedBy: AeroAdjacencyCache, AeroCompleteSectionCache, AeroSeedWorldGenBinding, AeroSeedWorldGenerator, AeroThroughputLimits, AeroWorld
- **AeroWorldSettings** [config] record, 68L - Root codec record `aero_settings` stored inside the dimension JSON generator: layer2, layer3, layer4, dhOverride. Default-valued fallback ctor omits dhOverride.
   - api: record(Layer2Settings, Layer3Settings, Layer4Settings, DhOverrideSettings)
   - uses: DhOverrideSettings, Layer2Settings, Layer3Settings, Layer4Settings
   - usedBy: AeroWorldChunkGenerator
- **DhOverrideSettings** [config] record, 31L - Codec record (JSON key dh_override): enabled flag, per-layer detail-level thresholds (layer2/3/4DetailThreshold, defaults 7/6/6, read by AeroFastDistantTerrain) and throughputLogIntervalSec, which is decoded but never read (the runtime interval comes from AeroWorldConfig). Has DEFAULT.
   - usedBy: AeroFastDistantTerrain, AeroWorldSettings
- **Layer2Settings** [config] record, 59L - Codec record for lower islands: spawnChance, gridChunks, min/maxRadius, maxHeight, yVariance, bridgeChance, bridgeMaxRange. Validates ranges in ctor.
   - usedBy: AeroWorldSettings, LowerIslandGenerator
- **Layer3BodySettings** [config] record, 84L - Codec record split out of Layer3Settings (16-field codec limit): meteorite wall thickness, crater threshold/min bottom, ring inner/mid/outer factors, ringCellSize, ringSparsityMask.
   - usedBy: Layer3Settings
- **Layer3Settings** [config] record, 71L - Codec record for high islands: spawnChance, gridChunks, min/maxRadius, maxHeight, noiseDeform, nested body (Layer3BodySettings). Exposes body fields through delegate accessors.
   - uses: Layer3BodySettings
   - usedBy: AeroWorldSettings, HighIslandGenerator
- **Layer4Settings** [config] record, 62L - Codec record for jellyfish islands: spawnChance, gridChunks, min/maxRadius, min/maxHeight, tentacleCount, tentacleMin/MaxLen, tentacleBend.
   - usedBy: AeroWorldSettings, UpperIslandGenerator

### registry  - DeferredRegisters and resource keys
- **AeroDimensions** [registry] class, 19L - DeferredRegister for CHUNK_GENERATOR codecs; registers `aeroworld:aero_generator` -> AeroWorldChunkGenerator codec.
   - api: CHUNK_GENERATORS, AERO_CHUNK_GENERATOR
   - uses: AeroWorldChunkGenerator
   - usedBy: AeroRegistries
- **AeroRegistries** [registry] class, 34L - Registers BIOME_SOURCE `aero_biome_source` and STRUCTURE_PLACEMENT `deep_lake_monument`, and triggers AeroDimensions registration on the mod bus.
   - api: register(IEventBus)
   - uses: AeroBiomeSource, AeroDimensions, DeepLakeMonumentPlacement
   - usedBy: AeroWorld, DeepLakeMonumentPlacement
- **AeroResourceKeys** [registry] class, 27L - ResourceKey constants for the `aeroworld:aeroworld` dimension type, level stem and level. Used for dimension checks.
   - usedBy: LayerSpawnRestriction
- **AeroWorldPreset** [doc-only] class, 13L - Placeholder: documents that the world preset is registered purely via datapack JSON. No code references it.

### client  - Client-only events
- **AeroWorldClientEvents** [client] class, 30L - Client-only (Dist.CLIENT) @EventBusSubscriber with a ScreenEvent.Init.Post handler whose body is empty. The world-creation preset button comes from the datapack tag, not from this class.
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
- **ProximityTriggerHandler** [event] class, 406L - Level-tick handler for dimensions whose dimension type is in the aeroworld namespace. Every tick it flushes the scheduler queue to PendingStructureData; every 20 ticks, for each pending entry whose backoff expired and which has a player within 96 blocks XZ and within 128 blocks of the Layer 2 (Y 390..510) or Layer 3 (Y 990..1150) surface window, it finds the real island surface (heightmap, then a column scan), checks chunks ready and, from the third attempt, space clear, then places the physical structure (excraft ids go through StructureSourceProviderRegistry) with retry/backoff. forcePlaceAll loads the 5x5 chunks around each pending entry and places it (used by /aeroworld forcePlacePending).
   - api: onLevelTick(LevelTickEvent.Post); forcePlaceAll(ServerLevel) used by the command
   - uses: AeroWorld, PendingStructureData, StructurePlacementHelper, StructureSizeCache
   - usedBy: AeroWorld, AeroWorldCommands
- **ShorelineWaveHandler** [event] class, 1153L - Level-tick wave simulation on shorelines (tick step 10): BFS rings of water levels 0..7 advance toward land, hold 40 ticks, then retract; applies sponge cleanup; Y filter -10..90. Self-contained, no worldgen dependency.
   - api: onLevelTick(LevelTickEvent.Post); records WaveBlock, Wave
   - usedBy: AeroWorld
- **SpawnerProximityHandler** [event] class, 139L - Level-tick handler (every 20 ticks, AeroWorld dimension only): scans chunks (radius 2) around players whose Y is in 250..450 for `physical_structures:structure_spawner` blocks within 10 blocks of the player and triggers them through reflection.
   - api: onLevelTick(LevelTickEvent.Post)
   - usedBy: AeroWorld

### spawning  - Mob spawn rules
- **LayerSpawnRestriction** [event] class, 32L - FinalizeSpawnEvent subscriber: cancels all mob spawning in the AeroWorld dimension on Layer 3 (Y 1000..1100) and Layer 4 (Y >= 1900).
   - api: onFinalizeSpawn(FinalizeSpawnEvent)
   - uses: AeroResourceKeys, HighIslandGenerator, UpperIslandGenerator

### structure  - Physical-structure placement queue (server thread)
- **IslandStructureScheduler** [structure] class, 100L - Thread-safe queue filled from worldgen threads (enqueue with island block XZ + structure id, no Y) and drained on the server thread into PendingStructureData. Deduplicates by the chunk of the island centre (seen-chunk set; resetSeenChunks has no caller).
   - api: enqueue(int,int,ResourceLocation); flushToPersistence(ServerLevel); resetSeenChunks()
   - uses: PendingStructureData
   - usedBy: AeroWorld, AeroWorldChunkGenerator
- **PendingStructureData** [structure] class extends SavedData, 165L - SavedData persisting the queue of structures waiting to be placed. Entry(pos, id, attempts, nextRetryTick): retry delay 20 ticks << attempts capped at 200, exhausted after 10 attempts; entries are unique by (id, x, z).
   - api: getOrCreate(ServerLevel); addEntry(pos,id); Entry.isReadyToRetry/isExhausted/withNextRetry
   - usedBy: IslandStructureScheduler, ProximityTriggerHandler
- **StructurePlacementHelper** [structure] class, 129L - Static helpers for safe physical-structure placement: read NBT size without instantiating a template, check all footprint chunks are FULL, check free space above the surface.
   - api: getStructureSize; areChunksReady; isSpaceClear
   - usedBy: ProximityTriggerHandler, StructureSizeCache
- **StructureSizeCache** [structure] class, 64L - In-memory cache of NBT structure sizes (a missing structure is cached as missing too); invalidated on datapack reload.
   - api: getSize(ServerLevel, ResourceLocation); invalidate()
   - uses: StructurePlacementHelper
   - usedBy: AeroWorld, ProximityTriggerHandler

### worldgen  - Dimension ChunkGenerator
- **AeroWorldChunkGenerator** [gen-core] class extends NoiseBasedChunkGenerator, 487L - The dimension ChunkGenerator (extends NoiseBasedChunkGenerator; super is invoked only in createState, createBiomes, createStructures and applyBiomeDecoration). Owns all layer generators and caches, lazily seeded via createState/initializeWithSeed; applying a seed also recreates AeroWorld.structureScheduler. Pipeline: fillFromNoise -> Layer1 fillTerrain, then lower/high/upper fillChunk per Y overlap (Layer 2 also calls Layer2StructurePlacer); buildSurface is a separate stage -> Layer1 buildSurface; applyBiomeDecoration -> vanilla decoration, structure postPlacementCleanup, Layer1OreFilter, Layer 2 island trees (clearVanillaVegetationInCentralZone + placeTreesInRegion), Layer2/3/4 vault/trial placers, AncientCity support. createStructures runs vanilla generation with the IS_GENERATING thread-local set, then validates every start and reference via StructureSupportValidator. minY -64, genDepth 2164, seaLevel 0. Flag -Daeroworld.biomeSync.
   - api: getLowerIslands/getHighIslands/getUpperIslands/getLayer1Terrain/getSettings/getAeroBiomeSource; getBaseColumn; initializeWithSeed(long); CODEC
   - uses: AeroBiomeSource, AeroColumnModel, AeroWorld, AeroWorldSettings, AncientCityIslandSupportPlacer, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, HighIslandGenerator, IslandData, IslandStructureScheduler, IslandVaultTrialCache, Layer1FlatGenerator, Layer1OreFilter, Layer1TerrainGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer, LowerIslandGenerator, SectionDirectChunkWriter, StructureSupportValidator, UpperIslandGenerator, ValidationResult
   - usedBy: AeroDimensions, AeroSeedGenValidation, AeroSeedWorldGenBinding, AeroSeedWorldGenerator, AeroStructureMarkers, AeroWorldCommands, DeepLakeMonumentPlacement, EndCityStructureMixin, NetherFortressStructureMixin, OceanMonumentStructureMixin

### worldgen/biome  - Biome source and registry cache
- **AeroBiomeRegistryCache** [biome] class, 108L - Caches the full dynamic biome registry (including aeroworld:* clones) at ServerAboutToStart so biome lookup works off the main thread; get(id) caches found holders only. The server, render and main threads get an empty result while the registry is not ready, other threads wait for it. Cleared on server stop.
   - api: get(ResourceLocation) -> Optional<Holder<Biome>>
   - usedBy: AeroBiomeSource, AeroWorld
- **AeroBiomeSource** [biome] class extends BiomeSource, 456L - BiomeSource: Layer 1 biomes chosen from continentality/erosion/ridge/plateau/temperature noise (resolveLayer1Biome), deep_lake and deep_dark by position, aeroworld:* biomes for islands. Reconfigurable per seed; wraps a MultiNoiseBiomeSource delegate. Nested Params, BiomeColumnCache.
   - api: getNoiseBiome; static resolveLayer1Biome(...); withSeed; reconfigure; CODEC
   - uses: AeroBiomeRegistryCache, AeroNoise, Layer1FlatGenerator, Layer1TerrainGenerator, PlateauTerrace
   - usedBy: AeroColumnModel, AeroRegistries, AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldCommands

### worldgen/cache  - Island data/caches and key packing
- **BodyType** [data] enum, 14L - Enum METEORITE | PLANET for Layer 3 bodies; chosen 50/50 from the island centre in HighIslandGenerator.
   - usedBy: AeroColumnModel, AeroStructureCover, AeroWorldCommands, EndCityStructureMixin, HighIslandGenerator, IslandData, IslandVaultTrialGenerator
- **ChunkIslandCache** [cache] class, 80L - Shared bounded cache (4096 entries; Long2ObjectLinkedOpenHashMap + StampedLock) of island-centre lists keyed by (layerId, chunk). Eviction is by insertion order (a hit does not refresh the entry), so it is FIFO, not LRU. layerId: Lower 0, High 1, Upper 2.
   - api: get(layerId,cx,cz,factory); invalidate; size; release/releaseAll (no-ops)
   - usedBy: AeroWorldChunkGenerator, HighIslandGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer, LowerIslandGenerator, StructureSupportValidator, TerrainColumnSampler, UpperIslandGenerator
- **ChunkKey** [util] class, 24L - Packs (x,z) ints into one long (x high 32 bits) and back. Used everywhere a chunk or block XZ is a map key.
   - api: of(x,z); x(key); z(key)
   - usedBy: HUB, 15 referrers - grep the name
- **IslandCache** [cache] class, 75L - Thread-safe bounded cache of IslandData per island centre (default 512 entries, per layer generator instance). Evicts the oldest inserted entry (FIFO; hits do not refresh).
   - api: get(cx,cz,factory); invalidate
   - uses: ChunkKey, IslandData
   - usedBy: HighIslandGenerator, LowerIslandGenerator, UpperIslandGenerator
- **IslandData** [data] class, 141L - Immutable computed island properties: centre, bottomY/topY, radius, shape profile and noise, optional ellipsoid axes (Layer 3), BodyType, tentacle data (Layer 4). Computed once, then cached.
   - api: ctor overloads per layer; height(); centerY(); getEffectiveRadius()
   - uses: BodyType
   - usedBy: HUB, 14 referrers - grep the name
- **Layer1ColumnCache** [cache] class, 52L - Plain per-chunk holder of 256-column arrays (surfaceY, caveTop, caveBottom) for Layer 1 height and cave range. Layer1TerrainGenerator keeps one per thread (ThreadLocal); DH code creates its own instance per task. Saves recomputing the same noise across fillTerrain, cave decoration and buildSurface.
   - api: isForChunk; initForChunk(cx,cz,Layer1TerrainGenerator)
   - uses: Layer1TerrainGenerator
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, Layer1TerrainGenerator

### worldgen/util  - ChunkWriter abstractions
- **ChunkAccessWriter** [util] class implements ChunkWriter, 26L - ChunkWriter over a ChunkAccess using setBlockState (side effects on heightmaps).
   - uses: ChunkWriter
   - usedBy: AeroWorldChunkGenerator, HighIslandGenerator, LowerIslandGenerator, UpperIslandGenerator
- **ChunkWriter** [util] interface, 8L - Minimal block read/write interface (world coordinates) that lets generators target a chunk or a LOD buffer.
   - api: setBlockState(wx,wy,wz,state); getBlockState
   - usedBy: AeroWorldChunkGenerator, ChunkAccessWriter, HighIslandGenerator, Layer1TerrainGenerator, LowerIslandGenerator, SectionDirectChunkWriter, UpperIslandGenerator
- **SectionDirectChunkWriter** [util] class implements ChunkWriter, 90L - ChunkWriter that writes straight into LevelChunkSection, bypassing heightmap and block-count side effects; safe for parallel worldgen.
   - uses: ChunkWriter
   - usedBy: AeroWorldChunkGenerator

### worldgen/noise  - Noise, island grid, island SDF shape
- **AeroNoise** [noise] class, 138L - Lightweight seeded Perlin-style gradient noise (permutation table, quintic fade) with 2D/3D evaluation and fBm; the noise primitive of all custom generators.
   - api: noise2D; noise3D; fbm2D; fbm3D
   - usedBy: AeroBiomeSource, AeroRiverNetwork, HighIslandGenerator, IslandShape, Layer1TerrainGenerator, LowerIslandGenerator, PlateauTerrace, UpperIslandGenerator
- **IslandPlacer** [noise] class, 495L - Deterministic seed-based island grid: one candidate per grid cell gated by spawnChance, neighbour push-apart to avoid overlap, archipelago centres (25% of cells when enabled) with 5-6 satellites 40-70 blocks away; neighbourhood queries return packed block-XZ centres (ChunkKey.of(blockX, blockZ)).
   - api: getIslandCentresForChunk(cx,cz,radius[,maxInfluence]); getCentreForCell; isArchipelagoCentre; getSatellitesForCentre; findArchipelagoCentreFor; NO_ISLAND
   - uses: ChunkKey
   - usedBy: AeroWorldCommands, EndCityStructureMixin, HighIslandGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, LowerIslandGenerator, UpperIslandGenerator
- **IslandShape** [noise] class, 166L - Radial-profile helpers for Layer 2 island solids: per-island profile curve (linear/convex/concave/stepped) giving the radius at each height, with scaled edge noise; XZCache precompute for speed. Not a signed-distance function.
   - api: precomputeXZ; isSolid; isSurface; isSubsurface; getSurfaceY; computeProfile
   - uses: AeroNoise
   - usedBy: IslandVaultTrialGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, LowerIslandGenerator

### worldgen/layer  - Per-layer terrain and island generators
- **AeroRiverNetwork** [gen-layer1] class, 227L - Pure function of seed and coordinates producing river channels, lake bowls and deep-lake mask (cell/node graph draining to ocean). Only carves; water fill comes from Layer 1 sea fill. DEEP_LAKE_SHARE = 10% of sink lakes.
   - api: sample(wx,wz,cont) -> Sample(river,lake,deep); deepLakeCentre(chunkX,chunkZ)
   - uses: AeroNoise
   - usedBy: Layer1TerrainGenerator
- **AeroRiverNetworkSelfCheck** [selfcheck] class, 92L - Standalone `main` self-check (javac + java -ea) for AeroRiverNetwork. Not part of the mod runtime.
- **HighIslandGenerator** [gen-layer] class, 762L - Layer 3 (Y 1000..1100, LAYER_ID 1): hollow meteorites and solid planets with three asteroid rings. IslandData via IslandPlacer. fillChunk has ChunkAccess and ChunkWriter overloads; ring helpers for LOD.
   - api: getIslandData; getIslandYBounds; getIslandRadius; fillChunk; getRingYRange; LAYER_MIN_Y/MAX_Y/ID
   - uses: AeroNoise, BodyType, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, IslandCache, IslandData, IslandPlacer, Layer3Settings
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, AeroStructureCover, AeroWorldChunkGenerator, AeroWorldCommands, EndCityStructureMixin, IslandVaultTrialGenerator, Layer3VaultTrialPlacer, LayerSpawnRestriction, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler
- **Layer1FlatGenerator** [gen-layer1] class, 47L - Backward-compat wrapper around Layer1TerrainGenerator (surfaceHeight/topmostHeight) used by structure validation and biome source; setVanillaSource is a no-op.
   - api: getTerrainGenerator(); surfaceHeight; topmostHeight
   - uses: Layer1TerrainGenerator
   - usedBy: AeroBiomeSource, AeroWorldChunkGenerator, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler
- **Layer1TerrainGenerator** [gen-layer1] class, 846L - Layer 1 (Y -64..300, SEA_LEVEL 0): custom noise terrain (continentality, erosion, ridges, river valleys), ashen plateau canyons via PlateauTerrace, giant cave Y -50..-25, bedrock, deep-lake dig to DEEP_LAKE_FLOOR_Y -50, surface block selection (strata/badlands/stonesky bands). Biome-aware buildSurface. Nested records SurfaceBlocks, BiomeSurfaceInfo.
   - api: getHeight/getTopmostHeight(wx,wz); fillTerrain(ChunkWriter,cx,cz); buildSurface(chunk,biomeFn); isDeepLake; getDeepLakeCentre; getContinentality/getErosion; computeCaveTop/Bottom; getPlateauMask
   - uses: AeroNoise, AeroRiverNetwork, ChunkWriter, Layer1ColumnCache, PlateauTerrace
   - usedBy: AeroBiomeSource, AeroColumnModel, AeroSeedWorldGenerator, AeroTreeCover, AeroWorldChunkGenerator, AncientCityIslandSupportPlacer, DeepLakeMonumentPlacement, Layer1ColumnCache, Layer1FlatGenerator, OceanMonumentBuildingMixin, OceanMonumentStructureMixin, StructureSupportValidator
- **Layer2StructurePlacer** [gen-layer] class, 88L - During chunk fill enqueues `physical_structures:tank21` at the centre of every standalone Layer 2 island (archipelago centres and satellites are skipped) whose tier (Layer2VaultTrialPlacer.pickTierStatic) is RICH, via AeroWorld.structureScheduler.
   - api: placeForChunk(chunk, LowerIslandGenerator, RandomSource)
   - uses: AeroWorld, ChunkIslandCache, ChunkKey, IslandPlacer, IslandShape, Layer2VaultTrialPlacer, LowerIslandGenerator, VaultTrialSpawnTier
   - usedBy: AeroWorldChunkGenerator
- **LowerIslandGenerator** [gen-layer] class implements LowerIslandGeneratorAccess, 975L - Layer 2 (Y 400..500, LAYER_ID 0): cone islands with 4 profiles and noise-deformed edge, archipelagos (centre + satellites), rim trees (oak/birch), stalactites, bridges between neighbours. Implements LowerIslandGeneratorAccess. Real-chunk bridges are placed by a private fillBridges; LOD uses the public collectBridgeBlocks and sampleTreeColumn (own tree noise, not AeroTreeCover rules).
   - api: getIslandData; fillChunk; placeTreesInRegion; clearVanillaVegetationInCentralZone; getPlacer(); getShape(); releaseChunkCache; LOD: collectBridgeBlocks(wx,wz,centres,BridgeBlockSink), sampleTreeColumn(x,z,data,topY), getDeformedBottomY, getCachedIslandCentresForChunk
   - uses: AeroNoise, AeroTreeCover, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, IslandCache, IslandData, IslandPlacer, IslandShape, Layer2Settings, LowerIslandGeneratorAccess
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldCommands, Layer2StructurePlacer, Layer2VaultTrialPlacer, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler
- **LowerIslandGeneratorAccess** [data] interface, 11L - Narrow interface exposing only worldSeed() of lower islands (used for deterministic tier predicates without full generator).
   - api: worldSeed()
   - usedBy: LowerIslandGenerator
- **PlateauTerrace** [gen-layer1] class, 192L - Pure math (no MC) for the ashen_plateau biome: blob mask, uplift, terracing, canyon/gully/pillar/bridge shaping. Shared by terrain height and biome selection so both agree. FLOOR_Y 8.
   - api: mask; core; gully; pillar; bridge; column; terrace; hasBridgeVoid
   - uses: AeroNoise
   - usedBy: AeroBiomeSource, Layer1TerrainGenerator
- **PlateauTerraceSelfCheck** [selfcheck] class, 144L - Standalone `main` self-check (javac + java -ea) for PlateauTerrace. Not part of the mod runtime.
- **UpperIslandGenerator** [gen-layer] class, 480L - Layer 4 (Y 1900..2031, LAYER_ID 2): jellyfish islands (dome plus 10 tentacles 90-120 long, hanging below LAYER_MIN_Y). Per-island data in IslandCache; XZ caches for cap and tentacles.
   - api: getIslandData; fillChunk; getCachedIslandCentresForChunk; getSearchRadius; getPlacer(); releaseChunkCache
   - uses: AeroNoise, ChunkAccessWriter, ChunkIslandCache, ChunkKey, ChunkWriter, IslandCache, IslandData, IslandPlacer, Layer4Settings
   - usedBy: AeroColumnModel, AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldCommands, IslandVaultTrialGenerator, Layer4VaultTrialPlacer, LayerSpawnRestriction, StructureCategoryResolver, StructureSupportValidator, TerrainColumnSampler

### worldgen/carver  - Carvers (unwired)
- **SinkholeCarver** [gen-layer1] class, 217L - Karst sinkhole carving directly on a ChunkAccess. UNWIRED: no code calls it (a comment in AeroWorldChunkGenerator.applyCarvers mentions it, but vanilla carvers are simply disabled there).
   - api: carveChunk(ChunkAccess, long seed, HeightSampler)
   - usedBy: NONE (unreferenced)

### worldgen/feature  - Post-decoration filters
- **Layer1OreFilter** [gen-layer1] class, 92L - Post-decoration filter run once per chunk in applyBiomeDecoration: removes ores that leaked from vanilla decoration (all custom layers generate none).
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
- **Layer2VaultTrialPlacer** [vault] class, 224L - Layer 2 entry for vault/trial generation. Tier by role: archipelago centre MEDIUM; satellite POOR and only 25% of satellites get a vault/trial; standalone islands POOR 50% / MEDIUM 35% / RICH 15% by seeded hash (pickTierStatic). Uses the Layer 2 loot config.
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
- **VaultTrialLootConfig** [vault] record, 95L - Record of loot table ids (vault normal/ominous, trial normal/ominous) plus spawn potentials for one layer profile; constants LAYER_2, LAYER_3, LAYER_4. Loot tables live under data/aeroworld/loot_table/gameplay/layerN/.
   - usedBy: IslandVaultTrialGenerator, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer
- **VaultTrialSpawnTier** [vault] enum, 37L - Enum POOR(1 vault,1 spawner), MEDIUM(2,3), RICH(3,5): vault and trial-spawner counts per island. Standalone RICH Layer 2 islands also receive the tank structure.
   - api: vaultCount(); trialSpawnerCount()
   - usedBy: AeroWorldCommands, IslandVaultTrialGenerator, Layer2StructurePlacer, Layer2VaultTrialPlacer, Layer3VaultTrialPlacer, Layer4VaultTrialPlacer

### worldgen/structure  - Structure start validation + support platforms
- **AncientCityIslandSupportPlacer** [structure-gen] class, 129L - Builds a stepped stone platform under ancient cities so they do not hang in the Layer 1 cave void.
   - api: placeSupportForChunk(WorldGenLevel, ChunkAccess)
   - uses: Layer1TerrainGenerator
   - usedBy: AeroWorldChunkGenerator
- **DeepLakeMonumentPlacement** [structure-gen] class extends StructurePlacement, 67L - Custom StructurePlacement `aeroworld:deep_lake_monument`: one start per deep lake, only in the lake-centre chunk where the floor is flat at DEEP_LAKE_FLOOR_Y.
   - api: isPlacementChunk; CODEC; registered in AeroRegistries
   - uses: AeroRegistries, AeroWorldChunkGenerator, Layer1TerrainGenerator
   - usedBy: AeroRegistries, OceanMonumentBuildingMixin, OceanMonumentStructureMixin
- **StructureCategory** [structure-gen] enum, 38L - Enum SURFACE, ISLAND, UNDERGROUND, WATER, SKY_FLOATING, DENY: selects the validation mode (solid ground / layer-bounds checks).
   - api: requiresSolidGround(); requiresLayerBoundsCheck()
   - usedBy: StructureCategoryResolver, StructureSupportValidator, ValidationResult
- **StructureCategoryResolver** [structure-gen] class, 234L - Maps a structure ResourceLocation (and Y / actual layer) to a StructureCategory via deny list, whitelist, underground and water token rules.
   - api: resolve; resolveForY; resolveForActualLayer; isIslandLayerY; isVoidGapY
   - uses: HighIslandGenerator, Layer1FlatGenerator, LowerIslandGenerator, StructureCategory, UpperIslandGenerator
   - usedBy: StructureSupportValidator
- **StructureSoleYSelfCheck** [selfcheck] class, 20L - Self-check for StructureSupportValidator.medianOf; run from IDE with -ea (class pulls MC).
- **StructureSupportValidator** [structure-gen] class, 556L - Validates vanilla structure starts against custom terrain so villages etc. do not float in gaps; samples support grid, takes median sole Y, logs rejections, and erases rejected structures afterwards (postPlacementCleanup).
   - api: validate(id,start,heightSampler) -> ValidationResult; postPlacementCleanup(region,chunk,...); medianOf
   - uses: ChunkIslandCache, HighIslandGenerator, Layer1FlatGenerator, Layer1TerrainGenerator, LowerIslandGenerator, StructureCategory, StructureCategoryResolver, SupportSample, TerrainColumnSampler, UpperIslandGenerator, ValidationResult
   - usedBy: AeroWorldChunkGenerator, TerrainColumnSampler
- **SupportSample** [structure-gen] record, 7L - Record (x,z) of a failed support probe point, for diagnostics.
   - usedBy: StructureSupportValidator, ValidationResult
- **TerrainColumnSampler** [structure-gen] class, 638L - Answers solidity/water/island-top questions per XZ without reading blocks (chunks may be unloaded), using Layer 1-4 generator math and island caches. SAMPLE_GRID_STEP 8.
   - api: groundHeightAt; hasSolidBelow; isWaterCoveredAt; getIslandTopY; hasCollision; resolveActualLayer
   - uses: ChunkIslandCache, ChunkKey, HighIslandGenerator, IslandData, Layer1FlatGenerator, LowerIslandGenerator, StructureSupportValidator, UpperIslandGenerator
   - usedBy: StructureSupportValidator
- **ValidationResult** [structure-gen] class, 137L - Outcome of structure validation: accepted/denied/void-gap/insufficient-support/sky-floating/water, with bounds and failed SupportSamples.
   - api: static factories accepted/denied/voidGap/...; isAccepted()
   - uses: StructureCategory, SupportSample
   - usedBy: AeroWorldChunkGenerator, StructureSupportValidator

### worldgen/column  - Analytic column model for LOD + helpers
- **AeroColumnModel** [lod-core] class, 535L - Analytic vertical-column model of all four layers: buildSpans(x,z,minY,levelMax,...) returns a bottom-to-top Span list (state, optional biome). Single source of truth for DH LOD generation and for validation against the real generator. Layer 1: stone/strata/surface layers (depth 3, stony/karst/strata 8, badlands 15), giant cave split with deep_dark biome below, trees (not on plateau), ocean fill with kelp/seagrass and ice; Layer 2: island columns, trees, bridges; Layer 3: ellipsoid bodies, planet rings, meteorite cavities, planet End City column; Layer 4: dome cap and tentacle ranges. Adjacent same-state same-biome spans are merged.
   - api: record Span; buildSpans(...) overloads; mergeSpans; getBaseHeight (used by AeroWorldChunkGenerator.getBaseHeight)
   - uses: AeroBiomeSource, AeroStructureCover, AeroTreeCover, BodyType, ChunkKey, HighIslandGenerator, IslandData, Layer1ColumnCache, Layer1TerrainGenerator, LowerIslandGenerator, UpperIslandGenerator
   - usedBy: AeroColumnWriter, AeroSeedGenValidation, AeroSeedWorldGenerator, AeroStructureMarkers, AeroWorldChunkGenerator, AeroWorldCommands
- **AeroColumnWriter** [lod-dh] class, 216L - Converts AeroColumnModel spans into DH DhApiTerrainDataPoint lists (top to bottom, gaps filled with air, sky light 14 below water/ice and 15 elsewhere) with cached block/biome wrappers; biome falls back to aeroworld:plains.
   - api: toDataPoints(spans,minY,maxY[,yOffset]); getBlockStateWrapper; getBiomeWrapper
   - uses: AeroColumnModel
   - usedBy: AeroSeedWorldGenerator
- **AeroStructureCover** [lod-core] class, 125L - Analytic structure coverage for LOD (Layer 3 planet End Cities only): sampleLayer3PlanetStructure gives a 9x9 end-stone-brick base (5 high) with a 3x3 purpur tower to +36 above the planet ellipsoid top plus clearance. Constants must match EndCityStructureMixin (END_CITY_CLEARANCE). solve/Kind (NONE | END_CITY) have no production caller.
   - api: solve(chunkX,chunkZ,HighIslandGenerator) -> Kind; sampleLayer3PlanetStructure(...)
   - uses: BodyType, ChunkKey, HighIslandGenerator, IslandData
   - usedBy: AeroColumnModel
- **AeroTreeCover** [lod-core] class, 260L - Analytic tree trunk/canopy spans for LOD, deterministic from seed; no vanilla feature pipeline. sampleLayer1 applies per-biome rules (4x4-block cells, one tree per cell, SPHERE/CONE/FLAT canopy) on Layer 1 only; Layer 2 builds its own TreeSpans in LowerIslandGenerator.sampleTreeColumn.
   - api: getRuleForBiome; sampleLayer1(...); records AeroTreeShape, Rule, TreeSpans
   - uses: Layer1TerrainGenerator
   - usedBy: AeroColumnModel, LowerIslandGenerator
- **SliceVote** [lod-core] class, 88L - Pure-Java merge of several vertical columns into one by Y-slice voting (air ignored, most frequent solid wins), same scheme as DH 2x2 merge. Also `carve` for overwriting a Y range. No MC types.
   - api: carve(segs,lo,hi,fill); vote(cols,key,empty)
   - usedBy: AeroSeedWorldGenerator, AeroStructureMarkers
- **SliceVoteSelfCheck** [selfcheck] class, 56L - Standalone `main` self-check (javac + java -ea) for SliceVote. Not part of the mod runtime.

### worldgen/dh  - Distant Horizons integration, caches, limits, diagnostics
- **AeroAdjReadStats** [dh-diag] class, 84L - Measures adjacent-section reads (getAdjForDirection): total time, repo query time, no-row cases, repeats. -Daeroworld.dhPoolStats.
   - api: firstHit; record(...); drain
   - usedBy: AdjReadStatsMixin, AeroThroughputLimits
- **AeroAdjReadStatsSelfCheck** [selfcheck] class, 34L - Standalone `main` self-check (javac + java -ea) for AeroAdjReadStats. Not part of the mod runtime.
- **AeroAdjacencyCache** [dh-cache] class, 385L - Refcounted LRU+TTL cache (default 512 entries, 10 s TTL) of FullDataSourceV2 sections loaded through a supplier; empty sources are not cached. Used by StandInTerrainMixin to load parent sections when building stand-in terrain; invalidated by SqliteTuningMixin. Controlled by AeroWorldConfig adjacency keys and dhOverrideEnabled.
   - api: static get(pos, loader) -> Entry; Entry.get()/release()/close(); invalidate; clear; size; logSummary
   - uses: AeroWorldConfig
   - usedBy: LodRenderSectionAdjDiagMixin, SqliteTuningMixin, StandInTerrainMixin
- **AeroCascadeLeaf** [dh-exp] class, 111L - Experiment (default OFF, -Daeroworld.dhCascadeLeafRadius=0): skip pushing coarse-section data down into detail-6 leaves far from the player, with re-arm when the player approaches. UNWIRED: no production caller (only its self-check).
   - api: setTarget(x,z); skip(detail,minX,minZ,width); rearmDue; shouldSkip(...)
   - usedBy: NONE (unreferenced)
- **AeroCascadeLeafSelfCheck** [selfcheck] class, 47L - Standalone `main` self-check (javac + java -ea) for AeroCascadeLeaf. Not part of the mod runtime.
- **AeroCompleteSectionCache** [dh-cache] class, 167L - Bounded map (default 65536, config completeSectionCacheSize; the oldest confirmation is evicted, hits do not refresh) of positions confirmed fully generated, so getPositionsToRetrieve skips repeated quad-tree status scans. Active only when completeSectionCacheEnabled and dhOverrideEnabled are on. Hit/miss counters.
   - api: isConfirmedComplete; markComplete; invalidate; clear; logSummary
   - uses: AeroWorldConfig
   - usedBy: CompleteSectionCacheMixin, SqliteTuningMixin
- **AeroExecutorSampler** [dh-diag] class, 56L - Package-private. Daemon thread started once (by AeroThroughputLimits when dhPoolStats is on) that samples the DH pools (WorldGen, UpdatePropagator, RenderLoading, IO, ChunkToLodBuilder) every second into AeroPoolStats.
   - api: startOnce()
   - uses: AeroPoolStats
   - usedBy: AeroThroughputLimits
- **AeroFastDistantTerrain** [dh-core] class, 71L - Per-layer detail-level thresholds (defaults Layer 2/3/4 = 7/6/6 from DhOverrideSettings): at or above the threshold AeroSeedWorldGenerator drops that layer's generator from the LOD column entirely; also the sub-sample counts per detail level (1: 6, 2: 5, 3: 4, else 3). Global on/off toggled by /aeroworld DHopt.
   - api: setEnabled/isEnabled; isLayerNCoarse(detail); subsamplesForDetailLevel
   - uses: DhOverrideSettings
   - usedBy: AeroSeedWorldGenerator, AeroWorldCommands
- **AeroHybridStats** [dh-diag] class, 318L - Counters logged every 30 s; -Daeroworld.dhstats=false disables. In production only CanQueueStatsMixin feeds it (queue gate, unsaved and remaining counts, and only when dhStatsMixins is on); the section-branch, analytic/real timing and stuck-probe hooks have no caller, and QueueOrderStatsMixin/RegenGateStatsMixin (the other feeders) are not registered.
   - api: onSection; onRegenGate; onCanQueue; onUnsaved; onRemaining; enums Branch, RegenGate, QueueGate
   - usedBy: CanQueueStatsMixin, QueueOrderStatsMixin, RegenGateStatsMixin
- **AeroHybridStatsSelfCheck** [selfcheck] class, 55L - Standalone `main` self-check (javac + java -ea) for AeroHybridStats. Not part of the mod runtime.
- **AeroLeafLedger** [dh-core] class, 74L - Persistent set (8-byte keys, append-only file <world>/data/aeroworld_dh_leaves_<dim>.bin, flushed every 64 adds) of detail-0 sections already given analytic data, so a repeat request is treated as phase 2 (real chunk gen) even after restart. Used only with -Daeroworld.dhTwoPhase.
   - api: contains(key); add(key); key(lodX,lodZ)
   - usedBy: AeroSeedWorldGenerator
- **AeroLeafLedgerSelfCheck** [selfcheck] class, 35L - Standalone `main` self-check (javac + java -ea) for AeroLeafLedger. Not part of the mod runtime.
- **AeroLeafSlots** [dh-core] class, 27L - Package-private atomic counter limiting concurrent real-leaf (chunk-gen) tasks; cap 0 = unlimited.
   - api: tryAcquire(cap); release(); get()
   - usedBy: AeroThroughputLimits
- **AeroLeafSlotsSelfCheck** [selfcheck] class, 29L - Standalone `main` self-check (javac + java -ea) for AeroLeafSlots. Not part of the mod runtime.
- **AeroPlayerAnchors** [dh-core] class, 53L - Every 20 game ticks of each ServerLevel, snapshots packed player block XZ per dimension for DH worker threads (reading ServerLevel.players() off-thread is unsafe). nearestChebyshev and isWithin (square box) are used by AeroSeedWorldGenerator to decide which leaves get real chunks.
   - api: onLevelTick; nearestChebyshev(dim,x,z); isWithin(dim,x,z,radius)
   - usedBy: AeroSeedWorldGenerator, AeroWorld
- **AeroPoolStats** [dh-diag] class, 156L - Package-private, one instance per AeroThroughputLimits. Measures DH pool task wait/body/after-body times per detail level and receives the AeroExecutorSampler pool samples; on with -Daeroworld.dhPoolStats=true.
   - api: wrap(Executor,detail,submitNs,body); sample(...); record; drain
   - usedBy: AeroExecutorSampler, AeroThroughputLimits
- **AeroPoolStatsSelfCheck** [selfcheck] class, 54L - Standalone `main` self-check (javac + java -ea) for AeroPoolStats. Not part of the mod runtime.
- **AeroPropStats** [dh-diag] class, 92L - Measures DH propagator tasks (kind 0 up / 1 down, per detail), wait/run times and repeat share. -Daeroworld.dhPoolStats.
   - api: notePending(pos,detail); wrap(kind,task); drain
   - usedBy: AeroThroughputLimits, PropagateStatsMixin
- **AeroPropStatsSelfCheck** [selfcheck] class, 33L - Standalone `main` self-check (javac + java -ea) for AeroPropStats. Not part of the mod runtime.
- **AeroSeedGenValidation** [dh-core] class, 122L - Dev validation: for N random XZ within +-100000 compares block by block AeroColumnModel.buildSpans (sampleBiomes off) with AeroWorldChunkGenerator.getBaseColumn over the full height, logging the first 20 mismatching columns; backs `/aeroworld validateSeedGen`.
   - api: runValidation(ctx,sampleCount)
   - uses: AeroColumnModel, AeroWorldChunkGenerator
   - usedBy: AeroWorldCommands
- **AeroSeedWorldGenBinding** [dh-core] class extends DhApiLevelLoadEvent, 147L - Registers AeroSeedWorldGenerator with Distant Horizons on DhApiLevelLoadEvent. With -Daeroworld.dhOff nothing is bound. On each level load it first applies the configured extended render distance and horizontal quality, then stands down (stock DH generator stays) unless the level is a ServerLevel whose generator is AeroWorldChunkGenerator and both the client config and the world's dh_override setting are enabled; otherwise it seeds the generator and registers the override.
   - api: registerIfDhPresent()
   - uses: AeroSeedWorldGenerator, AeroWorldChunkGenerator, AeroWorldConfig
   - usedBy: AeroWorld
- **AeroSeedWorldGenerator** [dh-core] class implements IDhApiWorldGenerator, 428L - IDhApiWorldGenerator override with a hybrid strategy. Leaves (detail 0) within realChunkRadius+32 blocks of a player (when DH chunk generation is enabled and mode is not PRE_EXISTING_ONLY) use DH's real chunk generator in single-phase mode (default; capped by REAL_LEAF_CAP, analytic fallback if declined), or with dhTwoPhase an analytic SURFACE pass first and real generation on the repeat request (AeroLeafLedger). Everything else is analytic from AeroColumnModel with detail-based sub-sampling (buildDominantSpans + SliceVote), structure markers, FastDistantTerrain coarse layers; FEATURES step for detail 0, SURFACE otherwise.
   - api: generateLod(...); generateApiChunks(...); buildDominantSpans(...); getReturnType; close
   - uses: AeroBiomeSource, AeroColumnModel, AeroColumnWriter, AeroFastDistantTerrain, AeroLeafLedger, AeroPlayerAnchors, AeroStructureMarkers, AeroThroughputLimits, AeroWorldChunkGenerator, AeroWorldConfig, HighIslandGenerator, Layer1ColumnCache, Layer1TerrainGenerator, LowerIslandGenerator, SliceVote, UpperIslandGenerator
   - usedBy: AeroSeedWorldGenBinding, AeroWorldCommands
- **AeroStructureIcon** [dh-core] class, 132L - Voxel miniature of a structure from ASCII layer schemas; MC-independent; mapping char->block in AeroStructureMarkers. shownOnLod excludes mineshafts, ancient city, trial chambers, buried treasure, fortress, stronghold and end city.
   - api: forPath(path); of(layers...); column(u,v) -> List<Seg>; shownOnLod(path)
   - usedBy: AeroStructureMarkers
- **AeroStructureIconSelfCheck** [selfcheck] class, 49L - Standalone `main` self-check (javac + java -ea) for AeroStructureIcon. Not part of the mod runtime.
- **AeroStructureMarkers** [dh-core] class, 246L - Places tiny structure icons (details 0..5) on analytic LOD: for structure sets with random-spread placement and an icon, it finds start chunks per 32x32-chunk cell (cached) and trial-generates each through the real generator's createStructures on a throwaway ProtoChunk; the icon is carved above the host surface and nearby trees are cleared. Unknown structures get a coloured spire. Disabled until restart if trial generation fails. Flag -Daeroworld.dhStructures=false disables.
   - api: forSection(baseX,baseZ,width,step) -> Map<Integer,Placed>; apply(spans,placed,maxY); records Marker, Placed
   - uses: AeroColumnModel, AeroStructureIcon, AeroWorldChunkGenerator, SliceVote
   - usedBy: AeroSeedWorldGenerator
- **AeroThroughputLimits** [dh-core] class, 318L - Central DH tuning hub: static constants from the dh flags (TWO_PHASE, REAL_LEAF_CAP, RENDER_LOADER_PRIORITY 1..10 default 6, WORLD_GEN_BORDER 0..8, SAVE_DELAY_MS 250..30000 default 1000), constants (SQLITE_SYNC "NORMAL", QUEUE_SCALE 4, IN_FLIGHT_SCALE 4, SECTIONS_PER_CHUNK 131), real radius / render yield from config; per-generator instance owns the real-leaf slots, throughput logging and pool-stats wrappers, and the static gate diagnostics (RenderYieldGate/BacklogGate every 5 s). Consumed by most DH mixins.
   - api: realRadiusBlocks(); renderYieldQueue(); distantHorizonsThreadCount(); trackRealLeaf; startRealLeaf(cap,start); timedExecutor; recordAnalyticTask; recordChunksGenerated
   - uses: AeroAdjReadStats, AeroExecutorSampler, AeroLeafSlots, AeroPoolStats, AeroPropStats, AeroWorldConfig
   - usedBy: AeroSeedWorldGenerator, BatchGenerationEnvironmentNeoforgeMixin, GeneratorBusyMixin, LodQuadTreeRealRadiusMixin, RenderLoaderPriorityMixin, RetrievalQueueLimitMixin, SaveDelayMixin, SqliteTuningMixin, WorldGenSpeedGateMixin, WorldGenUtilRegenRadiusMixin

### mixin/structure  - Vanilla structure mixins (required)
- **EndCityStructureAccessor** [mixin] interface, 24L - Invoker accessor exposing private EndCityStructure.generatePieces to EndCityStructureMixin.
   - api: target: EndCityStructure; @Invoker generatePieces
   - usedBy: EndCityStructureMixin
- **EndCityStructureMixin** [mixin] class, 259L - Overrides EndCityStructure.findGenerationPoint (HEAD, cancellable): in AeroWorld places exactly one End City (no ship) at the exact centre of each Layer 3 PLANET, reusing vanilla piece generation through the accessor.
   - api: target: EndCityStructure#findGenerationPoint; config: aeroworld_structures.mixins.json
   - uses: AeroWorldChunkGenerator, BodyType, ChunkKey, EndCityStructureAccessor, HighIslandGenerator, IslandData, IslandPlacer
- **NetherFortressStructureMixin** [mixin] class, 71L - Redirect in NetherFortressStructure.generatePieces: pins the fortress to Y -55..-50 (inside the Layer 1 giant cave).
   - api: target: NetherFortressStructure#generatePieces; config: aeroworld_structures.mixins.json
   - uses: AeroWorldChunkGenerator
- **OceanMonumentBuildingMixin** [mixin] class, 41L - Adapts OceanMonumentPieces.MonumentBuilding constants (vanilla min Y 39, water fill top) to the deep-lake floor Y -50.
   - api: target: OceanMonumentPieces$MonumentBuilding (<init> constant 39, postProcess Math.max); config: aeroworld_structures.mixins.json
   - uses: DeepLakeMonumentPlacement, Layer1TerrainGenerator
- **OceanMonumentStructureMixin** [mixin] class, 71L - Overrides OceanMonumentStructure.findGenerationPoint: monuments exist only at the deep-lake centre chunk, one per lake, base at the flat lake floor.
   - api: target: OceanMonumentStructure#findGenerationPoint; config: aeroworld_structures.mixins.json
   - uses: AeroWorldChunkGenerator, DeepLakeMonumentPlacement, Layer1TerrainGenerator

### mixin/dh  - Distant Horizons mixins (soft dependency)
- **AdjReadStatsMixin** [mixin-dh] class, 54L - Measurement only: times adjacent-section reads and no-row results. Gated by dhPoolStats.
   - api: target: FullDataSourceProviderV2#getAdjForDirection (@Redirect + RETURN); uses AeroAdjReadStats
   - uses: AeroAdjReadStats
- **BatchGenerationEnvironmentNeoforgeMixin** [mixin-dh] class, 126L - Three @Redirects in generateChunks of DH's neoforge chunk generator: replace the MAX_WORLD_GEN_CHUNK_BORDER_NEEDED constant with WORLD_GEN_BORDER and add WORLD_GEN_BORDER to extraRadius of both getIterator calls (-Daeroworld.dhBorder, default 0 = no extra border).
   - api: target: DhChunkGenerator_neoforge#generateChunks; uses AeroThroughputLimits
   - uses: AeroThroughputLimits
- **CanQueueStatsMixin** [mixin-dh] class, 72L - Measurement only: why canQueueRetrievalNow admits or refuses tasks, queue depth, unsaved counts. Gated by -Daeroworld.dhStatsMixins.
   - api: target: GeneratedFullDataSourceProvider#canQueueRetrievalNow, setEstimatedRemainingRetrievalChunkCount (HEAD/RETURN); uses AeroHybridStats
   - uses: AeroHybridStats
- **CompleteSectionCacheMixin** [mixin-dh] class, 58L - Short-circuits getPositionsToRetrieve for sections already confirmed complete; records completion on RETURN.
   - api: target: GeneratedFullDataSourceProvider#getPositionsToRetrieve(JB) (HEAD cancellable + RETURN); uses AeroCompleteSectionCache
   - uses: AeroCompleteSectionCache
- **DhWorldGenBorderMixinPlugin** [mixin-dh] class implements IMixinConfigPlugin, 82L - IMixinConfigPlugin of aeroworld.mixins.json: applies DH mixins only if DH core Initializer is on the classpath and -Daeroworld.dhOff is not set; gates CanQueueStatsMixin by dhStatsMixins and PropagateStatsMixin/AdjReadStatsMixin by dhPoolStats. getMixins() returns null (adds nothing).
   - api: shouldApplyMixin; onLoad
- **GeneratorBusyMixin** [mixin-dh] class, 32L - When DH reports the generation queue busy and a world-gen executor exists, reports busy only if in-progress tasks exceed DH thread count x IN_FLIGHT_SCALE (4); records the check in AeroThroughputLimits (BacklogGate).
   - api: target: WorldGenerationQueue#isGeneratorBusy (RETURN, cancellable)
   - uses: AeroThroughputLimits
- **LodQuadTreeRealRadiusMixin** [mixin-dh] class, 25L - calcDetailLevelFromDistance returns the max detail level for any distance within the real-chunk radius (config realChunkRadius << 4 blocks), so leaves near the player stay at full detail. NOT REGISTERED in any mixins.json: inactive.
   - api: target: LodQuadTree#calcDetailLevelFromDistance (HEAD, cancellable); uses AeroThroughputLimits
   - uses: AeroThroughputLimits
- **LodRenderSectionAdjDiagMixin** [mixin-dh] class, 75L - Diagnostic, always active (not flag-gated): counts missing/empty neighbour sections returned by getRenderSourceForPos per detail level, logs the first 6 per level and a summary every 10000 lookups together with AeroAdjacencyCache.logSummary.
   - api: target: LodRenderSection#getRenderSourceForPos (RETURN)
   - uses: AeroAdjacencyCache
- **PropagateStatsMixin** [mixin-dh] class, 45L - Measurement only: wraps propagator parent/child update tasks to count tasks and repeats. Gated by dhPoolStats.
   - api: target: FullDataUpdatePropagatorV2#runParentUpdates, runChildUpdates (@Redirect); uses AeroPropStats
   - uses: AeroPropStats
- **QueueOrderStatsMixin** [mixin-dh] class, 47L - Measurement only: queue ordering stats when a detail-6 task starts. NOT REGISTERED: inactive.
   - api: target: WorldGenerationQueue#startWorldGenTaskGroup (HEAD); uses AeroHybridStats
   - uses: AeroHybridStats
- **RegenGateStatsMixin** [mixin-dh] class, 48L - Measurement only: why DH regeneration requests stop (gate conditions). NOT REGISTERED: inactive.
   - api: target: FullDataUpdatePropagatorV2#tryQueueRegeneration, queueRegeneration (HEAD); uses AeroHybridStats
   - uses: AeroHybridStats
- **RenderLoaderPriorityMixin** [mixin-dh] class, 22L - Replaces the Render Loader pool priority constant 6 in setupThreadPools with RENDER_LOADER_PRIORITY (default 6, so unchanged) and logs the values once; require = 0.
   - api: target: ThreadPoolUtil#setupThreadPools (@ModifyConstant 6)
   - uses: AeroThroughputLimits
- **RetrievalQueueLimitMixin** [mixin-dh] class, 19L - Multiplies the retrieval queue limit constant 20 (requests per thread) by QUEUE_SCALE (4) so workers do not idle.
   - api: target: GeneratedFullDataSourceProvider#getMaxRetrievalQueueCount (@ModifyConstant)
   - uses: AeroThroughputLimits
- **SaveDelayMixin** [mixin-dh] class, 23L - Sets LOD save delay (default 1 s via SAVE_DELAY_MS) instead of stock 10 s.
   - api: target: GeneratedFullDataSourceProvider#<init> (@ModifyConstant)
   - uses: AeroThroughputLimits
- **SqliteTuningMixin** [mixin-dh] class, 115L - Applies SQLite pragmas to DH repos after construction (cache_size 256 MB, mmap_size 256 MB, temp_store MEMORY, synchronous = AeroThroughputLimits.SQLITE_SYNC, currently NORMAL); runs wal_checkpoint(TRUNCATE) on close; invalidates adjacency/complete-section cache entries on save and deleteWithKey and clears both on deleteAll (FullDataSourceV2Repo only).
   - api: target: AbstractDhRepo#<init>, close, save, deleteWithKey, deleteAll; uses AeroAdjacencyCache, AeroCompleteSectionCache
   - uses: AeroAdjacencyCache, AeroCompleteSectionCache, AeroThroughputLimits
- **StandInTerrainMixin** [mixin-dh] class, 120L - Fills LOD holes: when getRenderSourceForPos returns null/empty and the target is below root detail, walks up the parent chain (loaded through AeroAdjacencyCache) to the first non-empty section, rebuilds the child chain downwards with updateFromDataSource, converts it to a render source and returns it as the stand-in.
   - api: target: LodRenderSection#getRenderSourceForPos (RETURN, cancellable); uses AeroAdjacencyCache
   - uses: AeroAdjacencyCache
- **TranslucentAdjWallMixin** [mixin-dh] class, 60L - Cancels makeAdjVerticalQuad for translucent (alpha < 255) side walls when the neighbour column is empty, its first point has zero height, or it has a translucent point overlapping the wall's Y range.
   - api: target: ColumnBox#makeAdjVerticalQuad (HEAD, cancellable)
- **WorldGenSpeedGateMixin** [mixin-dh] class, 28L - Gates worldgen thread start in favour of render loading when render queue is overloaded (render yield queue).
   - api: target: ThreadPoolUtil#worldGenThreadsCanRun (HEAD, cancellable)
   - uses: AeroThroughputLimits
- **WorldGenUtilRegenRadiusMixin** [mixin-dh] class, 33L - Overrides getMaxRegenDistanceInBlocks: 2 x real-chunk radius (blocks) + 128 when the radius is above 0, otherwise 0 (regeneration off).
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
| dhstats | true | AeroHybridStats | hybrid loading counters and 30 s log (fed only by CanQueueStatsMixin today) |
| dhTwoPhase | false | AeroThroughputLimits | analytic SURFACE first, real chunk generation on repeat request (uses AeroLeafLedger) |
| dhRealLeafCap | 0 | AeroThroughputLimits | max concurrent real leaf generations (0 = unlimited) |
| dhRenderLoaderPriority | 6 (1..10) | AeroThroughputLimits | Render Loader pool thread priority |
| dhBorder | 0 (0..8) | AeroThroughputLimits | extra neighbour border for DH chunk generation |
| dhSaveDelayMs | 1000 (250..30000) | AeroThroughputLimits | LOD save delay |
| dhCascadeLeafRadius | 0 | AeroCascadeLeaf | experiment radius; 0 = off (class unwired) |
| dhStructures | true | AeroStructureMarkers | `false` disables structure icons on LOD |
| biomeSync | false | AeroWorldChunkGenerator | experimental. `false`: seeding replaces only a copy of the biome source and real chunks and LOD read the field instance. `true`: seed and Layer 1 ring checker are applied to that instance in place, which changes real-chunk biomes; do not enable on existing worlds |

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
