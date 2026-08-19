package org.example.aeroworld.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
import org.example.aeroworld.worldgen.structure.StructureCavityCarver;
import org.example.aeroworld.worldgen.structure.ValidationResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class AeroWorldChunkGenerator extends ChunkGenerator {

    public static final MapCodec<AeroWorldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    NoiseBasedChunkGenerator.CODEC.fieldOf("vanilla_generator")
                            .forGetter(g -> g.vanillaGenerator),
                    AeroWorldSettings.CODEC.optionalFieldOf("aero_settings", AeroWorldSettings.DEFAULT)
                            .forGetter(g -> g.settings)
            ).apply(instance, AeroWorldChunkGenerator::new)
    );

    private final NoiseBasedChunkGenerator vanillaGenerator;
    private final AeroWorldSettings settings;

    private final AtomicReference<AeroBiomeSource> aeroSource;

    private Layer1FlatGenerator  layer1;
    private LowerIslandGenerator lowerIslands;
    private HighIslandGenerator  highIslands;
    private UpperIslandGenerator upperIslands;

    /**
     * Один общий ChunkIslandCache для всех трёх генераторов островных слоёв.
     * Заменяет три отдельных кэша по 1024 слота каждый — теперь один на 1024 слота
     * с layerId в ключе. Пересоздаётся при смене seed, чтобы не утечь записи
     * от предыдущего мира.
     */
    private volatile ChunkIslandCache sharedChunkIslandCache = new ChunkIslandCache();

    // ── Кэшированные BlockState для applyLayer1Surface и getBaseColumn ────────
    private static final BlockState BS_STONE      = Blocks.STONE      .defaultBlockState();
    private static final BlockState BS_DEEPSLATE  = Blocks.DEEPSLATE  .defaultBlockState();
    private static final BlockState BS_SAND       = Blocks.SAND       .defaultBlockState();
    private static final BlockState BS_RED_SAND   = Blocks.RED_SAND   .defaultBlockState();
    private static final BlockState BS_TERRACOTTA = Blocks.TERRACOTTA .defaultBlockState();
    private static final BlockState BS_GRASS      = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BS_DIRT       = Blocks.DIRT       .defaultBlockState();

    // ── Placers структур ──────────────────────────────────────────────────────
    private Layer2StructurePlacer structurePlacer;
    private Layer3StructurePlacer layer3StructurePlacer;
    // ─────────────────────────────────────────────────────────────────────────

    private StructureSupportValidator structureValidator;

    private final Layer1OreGenerator layer1Ores = new Layer1OreGenerator();
    private final Layer2OreGenerator layer2Ores = new Layer2OreGenerator();
    private final Layer3OreGenerator layer3Ores = new Layer3OreGenerator();
    private final Layer4OreGenerator layer4Ores = new Layer4OreGenerator();
    private Layer1SinkholeCarver sinkholeCarver;
    private Layer1CoralScatter coralScatter;

    private long    worldSeed       = 12345L;
    private boolean seedInitialized = false;
    private volatile RandomState lastRandomState = null; // fast-path: skip seedFrom if same instance

    // oreFilteredChunks удалён: Layer1OreFilter теперь вызывается только для
    // центрального чанка (applyToChunk), без сканирования соседей и без Set.
    // Старая схема порождала race condition: сосед помечался в Set раньше чем
    // его собственные руды были сгенерированы, и фильтр его пропускал.

    private static final LevelHeightAccessor VANILLA_HEIGHT = new LevelHeightAccessor() {
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight()         { return 384; }
    };

    /** Полный диапазон мира — для getBaseColumn и getBaseHeight. */
    private static final LevelHeightAccessor FULL_HEIGHT = new LevelHeightAccessor() {
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight()         { return 2164; } // -64..2099
    };

    /** AIR-sentinel: одна константа вместо null в NoiseColumn. */
    private static final BlockState BS_AIR_SENTINEL = Blocks.AIR.defaultBlockState();


    public AeroWorldChunkGenerator(BiomeSource biomeSource,
                                   NoiseBasedChunkGenerator vanillaGenerator,
                                   AeroWorldSettings settings) {
        super(biomeSource instanceof MultiNoiseBiomeSource mnbs
                ? new AeroBiomeSource(mnbs)
                : biomeSource);
        this.vanillaGenerator = vanillaGenerator;
        this.settings         = settings;
        this.aeroSource       = new AtomicReference<>((AeroBiomeSource) getBiomeSource());
    }

    public AeroWorldChunkGenerator(BiomeSource biomeSource,
                                   NoiseBasedChunkGenerator vanillaGenerator) {
        this(biomeSource, vanillaGenerator, AeroWorldSettings.DEFAULT);
    }

    // ── Публичные геттеры генераторов слоёв ──────────────────────────────────

    /** Layer 2: нижние острова (Y 300–400). Null до первой инициализации seed. */
    public LowerIslandGenerator getLowerIslands() { return lowerIslands; }

    /** Layer 3: высотные острова (Y 1000–1100). Null до первой инициализации seed. */
    public HighIslandGenerator getHighIslands()   { return highIslands; }

    /** Layer 4: верхние острова (Y 1900–2031). Null до первой инициализации seed. */
    public UpperIslandGenerator getUpperIslands() { return upperIslands; }

    // ── Seed helpers ──────────────────────────────────────────────────────────

    private long seedFrom(RandomState randomState) {
        return randomState.getOrCreateRandomFactory(
                        ResourceLocation.fromNamespaceAndPath("aeroworld", "seed_probe"))
                .at(0, 0, 0).nextLong();
    }

    private synchronized void initializeWithSeed(long seed) {
        if (seedInitialized && worldSeed == seed) return;
        worldSeed       = seed;
        seedInitialized = true;

        // Пересоздаём общий кэш при смене seed — старые записи принадлежат другому миру.
        sharedChunkIslandCache = new ChunkIslandCache();

        layer1       = new Layer1FlatGenerator(seed);
        lowerIslands = new LowerIslandGenerator(seed, settings.layer2(), sharedChunkIslandCache);
        highIslands  = new HighIslandGenerator(seed, settings.layer3(), sharedChunkIslandCache);
        upperIslands = new UpperIslandGenerator(seed, settings.layer4(), sharedChunkIslandCache);
        sinkholeCarver = new Layer1SinkholeCarver(seed, lowerIslands, highIslands, upperIslands);
        coralScatter   = new Layer1CoralScatter(seed);

        structureValidator = new StructureSupportValidator(layer1, lowerIslands, highIslands, upperIslands, sharedChunkIslandCache);

        // ── Инициализируем placers с актуальным seed ──────────────────────────
        structurePlacer       = new Layer2StructurePlacer(seed, sharedChunkIslandCache);
        layer3StructurePlacer = new Layer3StructurePlacer(seed, sharedChunkIslandCache);
        // ─────────────────────────────────────────────────────────────────────

        AeroWorld.structureScheduler = new IslandStructureScheduler();

        // oreFilteredChunks removed — no per-world state to reset here.

        AeroBiomeSource updated = aeroSource.get().withSeed(seed).withRingChecker(layer1);
        aeroSource.set(updated);
    }

    private void init(RandomState randomState) {
        if (randomState == lastRandomState) return; // fast-path: same instance → already initialised
        initializeWithSeed(seedFrom(randomState));
        lastRandomState = randomState;
    }

    // ── ChunkGenerator overrides ──────────────────────────────────────────────

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override public int getMinY()     { return -64; }
    @Override public int getGenDepth() { return 2164; }
    @Override public int getSeaLevel() { return -1; }

    /**
     * Возвращает высоту верхней поверхности в колонке (x, z).
     *
     * <p>Проверяем острова сверху вниз (Layer 4 → Layer 2 → Layer 1).
     * Для скорости используем только IslandPlacer + IslandData (без fbm2D-шума).
     */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState random) {
        // Structure-система может дёрнуть getBaseHeight ДО fillFromNoise/
        // applyCarvers этого чанка (см. createStructures → findGenerationPoint
        // у ShipwreckStructure/MineshaftStructure и т.д.) — тогда layer1 и
        // прочие генераторы слоёв ещё не созданы. init() — дешёвый fast-path
        // при повторном randomState, поэтому вызываем его на каждый вход.
        init(random);

        int levelMax = level.getMinBuildHeight() + level.getHeight() - 1;
        int chunkX = x >> 4, chunkZ = z >> 4;

        // Layer 4 (Y 1900..2031)
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            for (int[] c : upperIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, upperIslands.getSearchRadius())) {
                IslandData d = upperIslands.getIslandData(c[0], c[1]);
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) return d.topY + 1;
            }
        }
        // Layer 3 (Y 1000..1100)
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            for (int[] c : highIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, highIslands.getSearchRadius())) {
                IslandData d = highIslands.getIslandData(c[0], c[1]);
                double dx = x - d.cx, dz = z - d.cz;
                double effR = (d.ellipsoidAxes != null) ? Math.max(d.ellipsoidAxes[0], d.ellipsoidAxes[2]) : d.radius;
                if (dx * dx + dz * dz <= effR * effR) return d.topY + 1;
            }
        }
        // Layer 2 (Y 300..400)
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            for (int[] c : lowerIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, lowerIslands.getSearchRadius())) {
                IslandData d = lowerIslands.getIslandData(c[0], c[1]);
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) return d.topY + 1;
            }
        }
        // Layer 1 (поверхность) — реальная высота по колонке (горы/холмы),
        // а не фиксированная константа.
        return layer1.surfaceHeight(x, z) + 1;
    }

    /**
     * Возвращает вертикальный столбец блоков для сэмплирования высот.
     *
     * <p>Используем максимум из переданного {@code level} и {@code FULL_HEIGHT},
     * фильтруем острова по реальному {@code levelMax} — это гарантирует
     * корректное отображение всех слоёв в любом контексте.
     */
    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState random) {
        // См. комментарий в getBaseHeight — тот же риск вызова до fillFromNoise.
        init(random);

        // Берём наибольший диапазон из переданного level и FULL_HEIGHT —
        // гарантирует корректное отображение всех слоёв AeroWorld.
        int minY   = Math.min(level.getMinBuildHeight(), FULL_HEIGHT.getMinBuildHeight());
        int maxY   = Math.max(level.getMinBuildHeight() + level.getHeight() - 1,
                FULL_HEIGHT.getMinBuildHeight() + FULL_HEIGHT.getHeight() - 1);
        int height = maxY - minY + 1;
        int levelMax = maxY; // верхняя граница — для guard'ов на острова

        BlockState[] states = new BlockState[height];

        // ── Layer 1: поверхность -64..surfY (реальная высота по колонке) ────
        int surfY = layer1.surfaceHeight(x, z);
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            if (y >= Layer1FlatGenerator.LAYER_MIN_Y && y <= surfY) {
                states[i] = (y < 0) ? BS_DEEPSLATE : BS_STONE;
            } else {
                states[i] = BS_AIR_SENTINEL;
            }
        }

        // ── Острова Layer 2–4: только если level покрывает их диапазон ───
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            for (int[] c : lowerIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, lowerIslands.getSearchRadius())) {
                IslandData d = lowerIslands.getIslandData(c[0], c[1]);
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz > d.radius * d.radius) continue;
                for (int y = d.bottomY; y <= d.topY; y++) { int idx = y - minY; if (idx >= 0 && idx < states.length) states[idx] = BS_STONE; }
            }
        }
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            for (int[] c : highIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, highIslands.getSearchRadius())) {
                IslandData d = highIslands.getIslandData(c[0], c[1]);
                double dx = x - d.cx, dz = z - d.cz;
                double effR = (d.ellipsoidAxes != null) ? Math.max(d.ellipsoidAxes[0], d.ellipsoidAxes[2]) : d.radius;
                if (dx * dx + dz * dz > effR * effR) continue;
                for (int y = d.bottomY; y <= d.topY; y++) { int idx = y - minY; if (idx >= 0 && idx < states.length) states[idx] = BS_STONE; }
            }
        }
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            for (int[] c : upperIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, upperIslands.getSearchRadius())) {
                IslandData d = upperIslands.getIslandData(c[0], c[1]);
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

        StructureSupportValidator validator = structureValidator;
        if (validator == null) return;

        chunk.getAllReferences().forEach((structure, refs) -> {
            if (refs.isEmpty()) return;

            ResourceLocation structureId = registryAccess
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getKey(structure);
            if (structureId == null) return;

            StructureStart start = structureManager.getStartForStructure(
                    SectionPos.of(chunk.getPos(), chunk.getMinSection()),
                    structure, chunk);
            if (start == null || start == StructureStart.INVALID_START) return;

            ValidationResult result = validator.validate(structureId, start);
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
        String layer = y <= Layer1FlatGenerator.LAYER_MAX_Y ? "Layer 1 (Mountains/Base)"
                : y < 500  ? "Layer 2 (Lower Islands)"
                : y < 1500 ? "Layer 3 (High Islands)"
                :            "Layer 4 (Upper Islands)";
        info.add("[AeroWorld] " + layer + "  Y=" + y);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {

        init(randomState);

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // ── Пункт Q: ранний выход по Y-диапазону секции ──────────────────────
        final int chunkMinY = chunk.getMinBuildHeight();
        final int chunkMaxY = chunk.getMaxBuildHeight();

        Layer1FlatGenerator.BiomeResolver biomeResolver = buildBiomeResolver(randomState);

        // Layer 1: Y -64..50
        if (chunkMinY <= Layer1FlatGenerator.LAYER_MAX_Y
                && chunkMaxY >= Layer1FlatGenerator.LAYER_MIN_Y) {
            layer1.fillChunk(chunk, chunkX, chunkZ, biomeResolver);

            // Вырезаем воздушную полость под структурами (см. StructureCavityCarver) —
            // ДО этого момента рельеф Layer 1 залил сплошной камень везде, включая
            // объём будущих jigsaw-структур (деревни, ancient_city и т.д.). Сама
            // структура печатается позже, в applyBiomeDecoration → super() →
            // StructureStart.placeInChunk, но заменяет камень только там, где стоят
            // её пьесы — пространство МЕЖДУ пьесами (естественно открытое в
            // оригинале) иначе остаётся сплошным камнем, и к каждой комнате
            // приходится прокапываться. Carve здесь даёт структуре ту же "уже
            // пористую" почву, которую в ваниле обеспечивает density-рельеф.
            StructureCavityCarver.carveForChunk(chunk, structureManager, Layer1FlatGenerator.LAYER_MAX_Y);

            // Карстовые воронки под островами слоёв 2/3/4 — карвятся ПОСЛЕ
            // основного рельефа слоя 1, чтобы "прорезать" уже готовую землю,
            // а не пытаться предсказать её заранее.
            if (sinkholeCarver != null) {
                sinkholeCarver.carveChunk(chunk, chunkX, chunkZ);
            }

            // Кораллы на пляжном песке у кромки воды — тоже после основного
            // рельефа, т.к. зависит от финальной раскладки песка/суши.
            if (coralScatter != null) {
                coralScatter.scatter(chunk, chunkX, chunkZ, layer1);
            }
        }

        // Layer 2 (Lower Islands): Y 300..400
        if (chunkMinY <= LowerIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= LowerIslandGenerator.LAYER_MIN_Y) {
            lowerIslands.fillChunk(chunk, chunkX, chunkZ);
            // Тот же фикс, что и у Layer3StructurePlacer ниже: регистрируем
            // структуру здесь, а не в applyBiomeDecoration, иначе для чанков
            // вдали от игрока tank_11 никогда не попадёт в scheduler.
            if (structurePlacer != null) {
                structurePlacer.placeForChunk(chunk, lowerIslands,
                        RandomSource.create(
                                worldSeed ^ ((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L) ^ 0xDEADBEEFL));
            }
        }

        // Layer 3 (High Islands): Y 1000..1100
        if (chunkMinY <= HighIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= HighIslandGenerator.LAYER_MIN_Y) {
            highIslands.fillChunk(chunk, chunkX, chunkZ);
            // Регистрируем структуры Layer 3 здесь, а не в applyBiomeDecoration —
            // fillFromNoise вызывается для ВСЕХ чанков, включая те что вне
            // зоны декорации (далеко от игрока). applyBiomeDecoration вызывается
            // только для чанков рядом с игроком, поэтому Layer 3 острова
            // (каждые ~26×26 чанков, Y 1000+) никогда не попадали в scheduler.
            if (layer3StructurePlacer != null) {
                layer3StructurePlacer.placeForChunk(chunk, highIslands,
                        RandomSource.create(
                                worldSeed ^ ((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L) ^ 0xCAFEBABEL));
            }
        }

        // Layer 4 (Upper Islands): Y 1900..2031
        if (chunkMinY <= UpperIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= UpperIslandGenerator.LAYER_MIN_Y) {
            upperIslands.fillChunk(chunk, chunkX, chunkZ);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private Layer1FlatGenerator.BiomeResolver buildBiomeResolver(RandomState randomState) {
        AeroBiomeSource src = aeroSource.get();
        Climate.Sampler sampler = randomState.sampler();
        return (wx, wz) -> {
            Holder<Biome> biome = src.getNoiseBiome(wx >> 2, 12, wz >> 2, sampler);
            return biome.unwrapKey()
                    .map(k -> k.location())
                    .orElse(ResourceLocation.withDefaultNamespace("plains"));
        };
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
        init(random);
        applyLayer1Surface(chunk, buildBiomeResolver(random));
    }

    private void applyLayer1Surface(ChunkAccess chunk, Layer1FlatGenerator.BiomeResolver biomeResolver) {
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                // biomeResolver кэширует результат Climate.Sampler — без повторного
                // обращения к шуму. Тот же объект уже использован в fillFromNoise.
                ResourceLocation biomeKey = biomeResolver.get(wx, wz);

                // Профиль колонки — если это русло реки/озера, fillChunk уже
                // покрасил дно песком и залил воду; красить поверх грассом не нужно.
                Layer1FlatGenerator.ColumnProfile profile = layer1.columnProfile(wx, wz);
                if (profile.waterY != -1) continue;

                int surfaceY = profile.groundY;
                int subsurfaceY = surfaceY - 1;

                String path = biomeKey.getPath();
                boolean isSandy    = path.equals("desert")
                        || path.equals("beach") || path.equals("snowy_beach");
                boolean isBadlands = path.equals("badlands")
                        || path.equals("wooded_badlands") || path.equals("eroded_badlands");

                if (isSandy) {
                    chunk.setBlockState(pos.set(wx, surfaceY,    wz), BS_SAND,       false);
                    chunk.setBlockState(pos.set(wx, subsurfaceY, wz), BS_SAND,       false);
                } else if (isBadlands) {
                    chunk.setBlockState(pos.set(wx, surfaceY,    wz), BS_RED_SAND,   false);
                    chunk.setBlockState(pos.set(wx, subsurfaceY, wz), BS_TERRACOTTA,  false);
                } else {
                    chunk.setBlockState(pos.set(wx, surfaceY,    wz), BS_GRASS, false);
                    chunk.setBlockState(pos.set(wx, subsurfaceY, wz), BS_DIRT,        false);
                }
            }
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        initializeWithSeed(seed);
        vanillaGenerator.applyCarvers(region, seed, random, biomeManager,
                structureManager, chunk, step);
        // ИСПРАВЛЕНО (см. диагностику Voxy-бага): раньше восстановление островов
        // откладывалось до applyBiomeDecoration через carverTouchedChunks, а
        // applyBiomeDecoration вызывается ТОЛЬКО для чанков рядом с игроком
        // (см. аналогичный комментарий у Layer3StructurePlacer в fillFromNoise).
        // Любой чанк, догенерированный вдали от игрока (фоновая подгрузка для
        // дальней прорисовки, Voxy/Distant Horizons и т.п.), доходил до FULL
        // со повреждёнными carver'ами островами и НИКОГДА не восстанавливался —
        // повреждение запекалось в чанк навсегда. Для толстых сфер Layer 3 это
        // было почти незаметно, а тонкие мосты Layer 2 и щупальца Layer 4
        // (радиус кончика 1.5 блока) carver рвал на куски или удалял целиком.
        // Теперь восстановление выполняется сразу же, безусловно, для каждого
        // чанка — независимо от близости игрока.
        restoreIslandsInChunk(chunk);
    }

    private void restoreIslandsInChunk(ChunkAccess chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Пункт Q — те же guards что в fillFromNoise: applyCarvers тоже может
        // получить ChunkAccess с ограниченным Y-диапазоном в некоторых фазах.
        final int chunkMinY = chunk.getMinBuildHeight();
        final int chunkMaxY = chunk.getMaxBuildHeight();

        if (lowerIslands != null
                && chunkMinY <= LowerIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= LowerIslandGenerator.LAYER_MIN_Y) {
            lowerIslands.fillChunk(chunk, chunkX, chunkZ);
        }
        if (highIslands != null
                && chunkMinY <= HighIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= HighIslandGenerator.LAYER_MIN_Y) {
            highIslands.fillChunk(chunk, chunkX, chunkZ);
        }
        if (upperIslands != null
                && chunkMinY <= UpperIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= UpperIslandGenerator.LAYER_MIN_Y) {
            upperIslands.fillChunk(chunk, chunkX, chunkZ);
        }

        // Освобождаем записи всех трёх слоёв за один вызов через общий кэш.
        sharedChunkIslandCache.releaseAll(chunkX, chunkZ, 3);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel region, ChunkAccess chunk,
                                     StructureManager structureManager) {
        if (!(region instanceof WorldGenRegion wgr)) {
            super.applyBiomeDecoration(region, chunk, structureManager);
            return;
        }
        RandomState randomState = wgr.getLevel().getChunkSource().randomState();
        init(randomState);

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        long base  = worldSeed ^ ((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);

        // restoreIslandsInChunk теперь вызывается напрямую из applyCarvers
        // (для каждого чанка, безусловно) — см. комментарий там.

        // structurePlacer (tank_11, Layer 2) и layer3StructurePlacer (haul_01, Layer 3)
        // теперь вызываются в fillFromNoise — чтобы охватить все чанки,
        // а не только те что рядом с игроком (applyBiomeDecoration вызывается
        // только для чанков рядом с игроком).
        // ─────────────────────────────────────────────────────────────────────

        super.applyBiomeDecoration(region, chunk, structureManager);

        // Крупные, кучные, разноплановые деревья на вершинах гор Layer 1 —
        // ванильные горные биомы почти безлесные, это намеренная кастомная
        // декорация поверх обычной. См. javadoc MountainForestScatter.
        MountainForestScatter.scatterForChunk(wgr, this, chunk, layer1, worldSeed);

        // Листья деревьев (±2 блока по XZ) — пишем через WorldGenLevel (регион 3×3 чанка).
        // В fillChunk через ChunkAccess запись за границы чанка некорректна.
        if (lowerIslands != null) lowerIslands.placeTreesInRegion(region, chunk);

        // Генерация руды во всех слоях отключена полностью.
        // layer1Ores.generateOres(chunk, RandomSource.create(base ^ 0x5555L), chunkX, chunkZ);
        // layer2Ores.generateOres(chunk, RandomSource.create(base ^ 0x2222L), chunkX, chunkZ);
        // layer3Ores.generateOres(chunk, RandomSource.create(base ^ 0x3333L), chunkX, chunkZ);
        // layer4Ores.generateOres(chunk, RandomSource.create(base ^ 0x4444L), chunkX, chunkZ);

        // Фильтр теперь зачищает ВЕСЬ чанк по всей высоте от любой руды —
        // как от кастомных генераторов (отключены выше), так и от ванильной
        // руды, которую могла разместить super.applyBiomeDecoration().
        Layer1OreFilter.applyToChunk(chunk);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        vanillaGenerator.spawnOriginalMobs(region);
    }

}