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
import org.example.aeroworld.worldgen.structure.StructureCavityCarver;
import org.example.aeroworld.worldgen.structure.ValidationResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class AeroWorldChunkGenerator extends NoiseBasedChunkGenerator {

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

    // ИСПРАВЛЕНО (баг "весь мир плоский, всюду один биом, редкие кубы воды"
    // при многопоточной генерации чанков через C2ME): эти четыре поля
    // присваиваются ВНУТРИ synchronized initializeWithSeed() одним потоком,
    // но читаются БЕЗ синхронизации из fillFromNoise/getBaseHeight/
    // getBaseColumn/applyLayer1Surface, которые C2ME вызывает параллельно
    // из десятков worker-потоков. Без volatile здесь нет happens-before
    // между "поток А создал layer1 внутри synchronized" и "поток Б читает
    // layer1 снаружи synchronized" — по Java Memory Model поток Б может
    // сколь угодно долго видеть устаревшее значение поля (null) или, что
    // ещё хуже, частично опубликованный объект. На практике это проявлялось
    // как: почти все чанки получали columnProfile() → vanillaSource==null
    // → плоский суходольный fallback (BASE_SURFACE_Y=48, видно на F3 как
    // Y≈48-49 и biome=aeroworld:river из старого WATER_LEVEL=44 фолбэка),
    // и лишь изредка (когда поток случайно видел актуальное состояние)
    // проскакивал настоящий ванильный рельеф — отсюда "вырванные" кубы воды
    // на границах чанков, сгенерированных разными потоками в разный момент.
    private volatile Layer1FlatGenerator  layer1;
    private volatile LowerIslandGenerator lowerIslands;
    private volatile HighIslandGenerator  highIslands;
    private volatile UpperIslandGenerator upperIslands;

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
    // volatile по той же причине, что и layer1/lowerIslands/highIslands/
    // upperIslands выше — присваиваются внутри synchronized initializeWithSeed()
    // одним потоком, читаются без синхронизации из fillFromNoise/
    // applyBiomeDecoration, вызываемых параллельно C2ME worker-потоками.
    private volatile Layer2StructurePlacer structurePlacer;
    private volatile org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer layer2VaultTrialPlacer;
    private volatile org.example.aeroworld.worldgen.feature.vault.Layer3VaultTrialPlacer layer3VaultTrialPlacer;
    private volatile org.example.aeroworld.worldgen.feature.vault.Layer4VaultTrialPlacer layer4VaultTrialPlacer;
    // ─────────────────────────────────────────────────────────────────────────

    private volatile StructureSupportValidator structureValidator;

    private final Layer1OreGenerator layer1Ores = new Layer1OreGenerator();
    private final Layer2OreGenerator layer2Ores = new Layer2OreGenerator();
    private final Layer3OreGenerator layer3Ores = new Layer3OreGenerator();
    private final Layer4OreGenerator layer4Ores = new Layer4OreGenerator();
    private volatile Layer1SinkholeCarver sinkholeCarver;
    private volatile Layer1CoralScatter coralScatter;

    // volatile — читаются (worldSeed в placeForChunk-вызовах, seedInitialized
    // в createStructures) без синхронизации из параллельных C2ME-потоков;
    // записываются только внутри synchronized initializeWithSeed().
    private volatile long    worldSeed       = 12345L;
    private volatile boolean seedInitialized = false;
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
                : biomeSource, vanillaGenerator.generatorSettings());
        this.vanillaGenerator = vanillaGenerator;
        this.settings         = settings;
        // ИСПРАВЛЕНО (NPE "this.aeroSource is null" при создании мира):
        // раньше здесь вызывался виртуальный getBiomeSource() — переопределённый
        // метод (см. ниже), который сам читает поле this.aeroSource. Поле
        // aeroSource в момент этого вызова ещё НЕ присвоено (Java инициализирует
        // final-поля строго по порядку объявления/присвоения в конструкторе),
        // поэтому getBiomeSource() получал aeroSource == null и падал/возвращал
        // null. Берём biome source из super(...) напрямую через getBiomeSourceRaw
        // эквивалент — используем поле, установленное родительским конструктором,
        // без обращения к переопределённому методу.
        BiomeSource resolved = super.getBiomeSource();
        this.aeroSource = new AtomicReference<>(
                resolved instanceof AeroBiomeSource abs ? abs : null);
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
        layer2VaultTrialPlacer = new org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer(seed, sharedChunkIslandCache);
        layer3VaultTrialPlacer = new org.example.aeroworld.worldgen.feature.vault.Layer3VaultTrialPlacer(seed, sharedChunkIslandCache);
        layer4VaultTrialPlacer = new org.example.aeroworld.worldgen.feature.vault.Layer4VaultTrialPlacer(seed, sharedChunkIslandCache);
        // ─────────────────────────────────────────────────────────────────────

        AeroWorld.structureScheduler = new IslandStructureScheduler();

        // oreFilteredChunks removed — no per-world state to reset here.

        // Защита от NPE: aeroSource может быть null, если исходный biomeSource,
        // переданный в конструктор (например, из data pack world preset), не был
        // MultiNoiseBiomeSource и поэтому не был обёрнут в AeroBiomeSource — см.
        // конструктор выше. В таком случае мир создаётся с "чужим" источником
        // биомов, и остров-специфичная логика (ring-valley/ocean/river/lake)
        // просто не применяется, вместо падения всей загрузки реестра.
        AeroBiomeSource current = aeroSource.get();
        if (current != null) {
            AeroBiomeSource updated = current.withSeed(seed).withRingChecker(layer1);
            aeroSource.set(updated);
        }
    }

    // ВРЕМЕННАЯ ДИАГНОСТИКА (баг "мир по-прежнему плоский после удаления
    // HAUL-01/Layer3StructurePlacer, 29.08.2026") — логирует на уровне INFO
    // (не debug, чтобы точно попасть в лог при дефолтных настройках)
    // первый вызов init() с новым RandomState: seed, null ли vanillaGenerator
    // (поле-конструктор, не должно быть null), и что реально возвращает
    // vanillaGenerator.getBaseColumn(0,0,...) сразу после setVanillaSource —
    // сырую высоту дна для контрольной точки (0,0).
    private final java.util.concurrent.atomic.AtomicBoolean DIAG_INIT_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void init(RandomState randomState) {
        if (randomState == lastRandomState) return; // fast-path: same instance → already initialised
        initializeWithSeed(seedFrom(randomState));
        // Прокидываем ванильный генератор + актуальный RandomState в Layer1 —
        // источник истины высоты/воды после перехода на ванильный рельеф
        // (см. Layer1FlatGenerator, блок "Ванильный рельеф/вода"). layer1
        // уже создан выше, внутри initializeWithSeed().
        if (layer1 != null) {
            layer1.setVanillaSource(vanillaGenerator, randomState);
        }
        lastRandomState = randomState;

        if (DIAG_INIT_LOGGED.compareAndSet(false, true)) {
            long seed = seedFrom(randomState);
            AeroWorld.LOGGER.info(
                    "[AeroWorld][DIAG] init(): seed={} vanillaGenerator={} layer1={}",
                    seed, vanillaGenerator, layer1);
            if (vanillaGenerator != null && layer1 != null) {
                // Пробуем НЕСКОЛЬКО удалённых друг от друга точек — если
                // ванильный рельеф реально плоский (не только в точке 0,0,
                // которая могла случайно попасть на береговую линию), это
                // будет видно по одинаковым groundY во всех точках.
                // Дополнительно логируем то, что видит СAM layer1 через
                // columnProfile() — если тут разброс есть, а в игре рельеф
                // плоский, значит баг ниже по цепочке (fillChunk/getBaseHeight).
                int[][] probePoints = { {0,0}, {500,500}, {-800,300}, {2000,-1500}, {100,4000} };
                for (int[] p : probePoints) {
                    try {
                        Layer1FlatGenerator.ColumnProfile prof = layer1.columnProfile(p[0], p[1]);
                        AeroWorld.LOGGER.info(
                                "[AeroWorld][DIAG] columnProfile({},{}) => groundY={} waterY={} | surfaceHeight={} topmostHeight={}",
                                p[0], p[1], prof.groundY, prof.waterY,
                                layer1.surfaceHeight(p[0], p[1]), layer1.topmostHeight(p[0], p[1]));
                    } catch (Throwable t) {
                        AeroWorld.LOGGER.error("[AeroWorld][DIAG] columnProfile(" + p[0] + "," + p[1] + ") THREW:", t);
                    }
                }
            }
        }
    }

    // ── ChunkGenerator overrides ──────────────────────────────────────────────

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public BiomeSource getBiomeSource() {
        // КОРНЕВАЯ ПРИЧИНА "нет водорослей/кораллов/рыбы в океане": родительский
        // ChunkGenerator.getBiomeSource() возвращает BiomeSource, переданный в
        // super(...) конструктор ОДИН РАЗ — тот самый "исходный" AeroBiomeSource,
        // у которого layer1 == null (withRingChecker(layer1) ещё не вызывался).
        // initializeWithSeed() кладёт ОБНОВЛЁННЫЙ инстанс (с layer1, а значит и
        // с isOceanColumn/isRiverColumn/isLakeColumn/isBeachColumn) только в
        // aeroSource (AtomicReference) — но ничего не переопределяло
        // getBiomeSource(), так что игра (F3, спавн мобов, реальная выдача
        // биома чанка, а через это — vegetal_decoration и другие ванильные
        // фичи биома) продолжала стучаться в СТАРЫЙ инстанс с layer1==null.
        // В AeroBiomeSource.getNoiseBiome() блок `if (layer1 != null) {...}`
        // (весь ocean/river/lake/beach/ring-valley резолвинг) из-за этого
        // молча пропускался ВСЕГДА в реальной игре — оставалась только
        // климатическая таблица (plains/taiga/meadow/...), у которой нет
        // seagrass/kelp/coral. Внутренний buildBiomeResolver() (используется
        // при заливке рельефа/поверхности) уже брал aeroSource.get() правильно
        // — потому физический рельеф (сам факт наличия воды, песок на дне)
        // был корректным, а вот итоговый БИОМ и, соответственно, фичи —- нет.
        AeroBiomeSource src = aeroSource.get();
        return src != null ? src : super.getBiomeSource();
    }

    @Override public int getMinY()     { return -64; }
    @Override public int getGenDepth() { return 2164; }
    // ИСПРАВЛЕНО (история): было захардкожено -1 — практически "океана нет"
    // для всех ванильных систем, завязанных на getSeaLevel() (ocean_monument,
    // размещение подводной растительности/кораллов, туман/рендер клиента).
    //
    // ОБНОВЛЕНО (переход Layer1FlatGenerator на ванильный рельеф/воду —
    // см. Layer1FlatGenerator, блок "Ванильный рельеф/вода"): физическая
    // вода Layer 1 теперь строится тем же vanillaGenerator, что и
    // возвращает getSeaLevel() — берём уровень моря НАПРЯМУЮ у него, а не у
    // устаревшей константы Layer1FlatGenerator.WATER_LEVEL (=44), которая
    // была уровнем воды старой самописной системы и с новым (ванильным,
    // обычно 63) рельефом уже не совпадает. Несовпадение этих двух чисел —
    // тот же класс бага, что и раньше: часть ванильной фиче-плейсмент
    // логики может тихо считать мир "безводным на этом Y" и отбраковывать
    // подводные фичи, если getSeaLevel() расходится с фактическим уровнем
    // воды в рельефе.
    @Override public int getSeaLevel() { return vanillaGenerator.getSeaLevel(); }

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
        //
        // ВАЖНО про type: ванильная структурная система (деревни, аванпосты
        // и т.п.) ищет место для постройки через heightmap-типы, означающие
        // "поверхность мира сверху" — WORLD_SURFACE(_WG), MOTION_BLOCKING(_NO_LEAVES).
        // Если здесь всегда отдавать дно (surfaceHeight = groundY), структура
        // в озере/океане получает Y дна и топит здание под водой. Для этих
        // типов отдаём topmostHeight() — дно ИЛИ поверхность воды, смотря что
        // выше — как и должен вести себя "мир сверху". OCEAN_FLOOR(_WG) по
        // смыслу обязан оставаться дном, поэтому не трогаем его.
        if (isSurfaceFromAboveHeightmap(type)) {
            return layer1.topmostHeight(x, z) + 1;
        }
        return layer1.surfaceHeight(x, z) + 1;
    }

    /**
     * {@code true} для heightmap-типов, которые по ванильной семантике
     * означают "первая непустая точка, если смотреть сверху" (т.е. включая
     * воду как потенциальную опору) — в противовес OCEAN_FLOOR-типам,
     * которые всегда означают именно твёрдое дно.
     */
    private static boolean isSurfaceFromAboveHeightmap(Heightmap.Types type) {
        return switch (type) {
            case WORLD_SURFACE, WORLD_SURFACE_WG,
                 MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES -> true;
            default -> false;
        };
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

        // Ленивая инициализация seed-зависимых полей (layer1/lowerIslands/…/
        // structureValidator) — обычно происходит через init(randomState) из
        // fillFromNoise, но createStructures может быть вызван раньше (структуры
        // генерируются до заполнения шумом), и тогда structureValidator ещё null
        // → вся валидация структур молча пропускается без единой записи в лог.
        // ChunkGeneratorStructureState.getLevelSeed() даёт тот же seed без
        // необходимости в RandomState.
        if (!seedInitialized) {
            initializeWithSeed(structureState.getLevelSeed());
        }

        StructureSupportValidator validator = structureValidator;
        if (validator == null) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld][StructureVal] createStructures вызван для чанка ({},{}), но structureValidator == null "
                            + "даже после initializeWithSeed — валидация ПРОПУЩЕНА для этого чанка.",
                    chunk.getPos().x, chunk.getPos().z);
            return;
        }

        Map<net.minecraft.world.level.levelgen.structure.Structure, LongSet> allRefs = chunk.getAllReferences();
        if (!allRefs.isEmpty()) {
            AeroWorld.LOGGER.info(
                    "[AeroWorld][StructureVal] createStructures: чанк ({},{}), всего структур с ссылками в чанке: {}.",
                    chunk.getPos().x, chunk.getPos().z, allRefs.size());
        }

        allRefs.forEach((structure, refs) -> {
            ResourceLocation structureId = registryAccess
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getKey(structure);

            if (refs.isEmpty()) {
                AeroWorld.LOGGER.info(
                        "[AeroWorld][StructureVal]   {} в чанке ({},{}): refs пуст (структура ссылается СЮДА с другого чанка, но не отсюда) — пропущено.",
                        structureId, chunk.getPos().x, chunk.getPos().z);
                return;
            }
            if (structureId == null) return;

            StructureStart start = structureManager.getStartForStructure(
                    SectionPos.of(chunk.getPos(), chunk.getMinSection()),
                    structure, chunk);
            if (start == null || start == StructureStart.INVALID_START) {
                AeroWorld.LOGGER.info(
                        "[AeroWorld][StructureVal]   {} в чанке ({},{}): start={} — нет валидного старта В ЭТОМ чанке, валидация невозможна отсюда.",
                        structureId, chunk.getPos().x, chunk.getPos().z, start);
                return;
            }

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
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState, Blender blender,
            StructureManager structureManager, ChunkAccess chunk) {
        // ФИКС гонки "нет водорослей/кораллов/рыбы/монумента в океане" (второй
        // слой бага после исправления layer1==null в getNoiseBiome): порядок
        // этапов генерации чанка — createBiomes ПЕРВЫЙ, затем fillFromNoise.
        // init(randomState) раньше вызывался только из fillFromNoise (и других
        // поздних этапов), поэтому на момент createBiomes aeroSource ещё
        // содержал исходный AeroBiomeSource с layer1==null (см. комментарий в
        // getBiomeSource() выше) — ocean/river/lake/beach резолвинг молча
        // пропускался для КАЖДОГО чанка при его первой генерации биомов.
        // С многопоточной генерацией (C2ME) гонка проявлялась почти всегда,
        // т.к. createBiomes разных чанков стартует параллельно раньше, чем
        // fillFromNoise того же чанка успевает вызвать init(). init() сам
        // содержит fast-path (randomState == lastRandomState) — повторный
        // вызов отсюда безопасен и дешев.
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

        // ── Пункт Q: ранний выход по Y-диапазону секции ──────────────────────
        final int chunkMinY = chunk.getMinBuildHeight();
        final int chunkMaxY = chunk.getMaxBuildHeight();

        // Layer 1: делегируем ванильному NoiseBasedChunkGenerator целиком —
        // полноценный overworld-рельеф (горы, 3D-пещеры, аквиферы, deepslate
        // и т.д.) вместо самописного плоского заполнения. Ванильный генератор
        // заполняет блоки в диапазоне своих noise settings (-64..320), блоки
        // выше 320 не трогает — Layer 2/3/4 пишут поверх без конфликта.
        if (chunkMinY <= Layer1FlatGenerator.LAYER_MAX_Y
                && chunkMaxY >= Layer1FlatGenerator.LAYER_MIN_Y) {
            vanillaGenerator.fillFromNoise(blender, randomState, structureManager, chunk).join();

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
            // Регистрируем структуры Layer 2 здесь (не в applyBiomeDecoration):
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
            // Layer3StructurePlacer (HAUL-01.excraft) удалён полностью —
            // ResourceLocation.fromNamespaceAndPath("excraft", "HAUL-01")
            // кидал ResourceLocationException (заглавные буквы/дефис
            // недопустимы в пути ResourceLocation) в статическом
            // инициализаторе класса, что приводило к ExceptionInInitializerError
            // → initializeWithSeed() падал на КАЖДОМ вызове → layer1 и
            // остальные seed-зависимые поля никогда не создавались → весь
            // мир генерировался плоским (см. диагностику 29.08.2026).
        }

        // Layer 4 (Upper Islands): Y 1900..2031
        if (chunkMinY <= UpperIslandGenerator.LAYER_MAX_Y
                && chunkMaxY >= UpperIslandGenerator.LAYER_MIN_Y) {
            upperIslands.fillChunk(chunk, chunkX, chunkZ);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private Layer1FlatGenerator.BiomeResolver buildBiomeResolver(RandomState randomState) {
        // Fallback на super.getBiomeSource(), если aeroSource == null (biomeSource
        // не был MultiNoiseBiomeSource, см. конструктор) — иначе NPE на src.getNoiseBiome(...).
        AeroBiomeSource cached = aeroSource.get();
        BiomeSource src = cached != null ? cached : super.getBiomeSource();
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
        // Делегируем ванильному генератору — правильные surface rules по
        // биомам (снег, подзол, мицелий, гравийные берега, песок, терракота
        // и т.д.) вместо упрощённых 3-х типов (grass/sand/badlands).
        vanillaGenerator.buildSurface(region, structureManager, random, chunk);
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
                    chunk.setBlockState(pos.set(wx, surfaceY, wz), BS_SAND, false);
                    // На узкой прибрежной кромке (isShoreEdge) fillChunk уже
                    // оставил под surfaceY воздушную полость (см.
                    // Layer1FlatGenerator.SHORE_HOLLOW_DEPTH) — не запечатываем
                    // её вторым слоем песка, иначе полость исчезнет. Для
                    // остального песчаного рельефа (не кромка) поведение как
                    // раньше — сплошные 2 блока песка.
                    if (!profile.isShoreEdge) {
                        chunk.setBlockState(pos.set(wx, subsurfaceY, wz), BS_SAND, false);
                    }
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
        // (см. аналогичный комментарий у Layer2StructurePlacer в fillFromNoise).
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

        // structurePlacer (tank_11, Layer 2) вызывается в fillFromNoise —
        // чтобы охватить все чанки, а не только те что рядом с игроком
        // (applyBiomeDecoration вызывается только для чанков рядом с игроком).
        // Layer3StructurePlacer (HAUL-01.excraft) удалён полностью — см.
        // комментарий в fillFromNoise, блок "Layer 3 (High Islands)".
        // ─────────────────────────────────────────────────────────────────────

        super.applyBiomeDecoration(region, chunk, structureManager);

        // Крупные, кучные, разноплановые деревья на вершинах гор Layer 1 —
        // ванильные горные биомы почти безлесные, это намеренная кастомная
        // декорация поверх обычной. См. javadoc MountainForestScatter.
        MountainForestScatter.scatterForChunk(wgr, this, chunk, layer1, worldSeed);

        // Листья деревьев (±2 блока по XZ) — пишем через WorldGenLevel (регион 3×3 чанка).
        // В fillChunk через ChunkAccess запись за границы чанка некорректна.
        if (lowerIslands != null) lowerIslands.placeTreesInRegion(region, chunk);

        // Vault/Trial Spawner на островах Layer 2 — замурованы в теле острова
        // (см. IslandVaultTrialGenerator javadoc). Нужен WorldGenLevel (не
        // ChunkAccess) ради registryAccess() при инициализации NBT blockEntity,
        // поэтому вызывается здесь, а не в fillFromNoise.
        if (lowerIslands != null && layer2VaultTrialPlacer != null) {
            layer2VaultTrialPlacer.placeForChunk(region, chunk, lowerIslands, lowerIslands.getShape());
        }

        // Vault/Trial Spawner на островах Layer 3 (эллипсоиды) — по аналогии с
        // Layer 2, но через IslandVaultTrialGenerator.placeForEllipsoidIsland
        // (без IslandShape, геометрия эллипсоида берётся напрямую из
        // HighIslandGenerator). См. Layer3VaultTrialPlacer javadoc.
        if (highIslands != null && layer3VaultTrialPlacer != null) {
            layer3VaultTrialPlacer.placeForChunk(region, chunk, highIslands);
        }

        // Vault/Trial Spawner на островах Layer 4 (медузы — купол + щупальца) —
        // по аналогии с Layer 2/3, но через IslandVaultTrialGenerator.placeForJellyfishIsland
        // (структуры ставятся только на купол, не в щупальца — см. javadoc
        // Layer4VaultTrialPlacer/IslandVaultTrialGenerator.placeForJellyfishIsland).
        if (upperIslands != null && layer4VaultTrialPlacer != null) {
            layer4VaultTrialPlacer.placeForChunk(region, chunk, upperIslands);
        }

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