package org.example.aeroworld.worldgen.layer;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.tags.BlockTags;
import org.example.aeroworld.worldgen.cache.ChunkIslandCache;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandCache;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.noise.AeroNoise;
import org.example.aeroworld.worldgen.noise.IslandPlacer;
import org.example.aeroworld.worldgen.noise.IslandShape;
import org.example.aeroworld.worldgen.util.ChunkWriter;
import org.example.aeroworld.worldgen.util.ChunkAccessWriter;
import org.example.aeroworld.config.Layer2Settings;

import java.util.ArrayList;
import java.util.List;


public class LowerIslandGenerator {

    public static final int LAYER_MIN_Y   = 400;
    public static final int LAYER_MAX_Y   = 500;

    /** Идентификатор слоя для общего ChunkIslandCache. */
    public static final int LAYER_ID = 0;

    private static final double NOISE_DEFORM  = 18.0;

    /**
     * Деревья растут только в кольце у края острова: normDist ∈ [EDGE_BAND_START, 1.0],
     * где normDist = sqrt(distSq) / radius (0 = центр, 1 = край).
     * Например 0.6 означает, что внутренние 60% радиуса — без деревьев.
     */
    private static final double TREE_EDGE_BAND_START = 0.6;

    /**
     * Порог шума для спавна сталактита под нижней поверхностью острова (выше = реже).
     * AeroNoise.noise2D — classic Perlin, фактическая амплитуда ~[-0.7, 0.7],
     * поэтому порог держим существенно ниже 1.0.
     */
    private static final double STALACTITE_THRESHOLD = 0.15;

    // ── Кэшированные BlockState ────────────────────────────────────────────────
    private static final BlockState BS_GRASS_BLOCK  = Blocks.GRASS_BLOCK    .defaultBlockState();
    private static final BlockState BS_DIRT         = Blocks.DIRT           .defaultBlockState();
    private static final BlockState BS_STONE        = Blocks.STONE          .defaultBlockState();
    private static final BlockState BS_OAK_LOG      = Blocks.OAK_LOG        .defaultBlockState();
    private static final BlockState BS_BIRCH_LOG    = Blocks.BIRCH_LOG      .defaultBlockState();
    private static final BlockState BS_OAK_LEAVES   = Blocks.OAK_LEAVES     .defaultBlockState();
    private static final BlockState BS_BIRCH_LEAVES = Blocks.BIRCH_LEAVES   .defaultBlockState();
    private static final BlockState BS_MANGROVE     = Blocks.MANGROVE_ROOTS .defaultBlockState();
    private static final BlockState BS_AMETHYST_BLOCK   = Blocks.AMETHYST_BLOCK.defaultBlockState();
    private static final BlockState BS_AMETHYST_CLUSTER = Blocks.AMETHYST_CLUSTER.defaultBlockState();
    private static final BlockState BS_DRIPSTONE_BLOCK = Blocks.DRIPSTONE_BLOCK.defaultBlockState();
    private static final BlockState BS_DRIPSTONE_TIP_DOWN = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
            .setValue(BlockStateProperties.DRIPSTONE_THICKNESS, DripstoneThickness.TIP);
    private static final BlockState BS_DRIPSTONE_FRUSTUM_DOWN = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
            .setValue(BlockStateProperties.DRIPSTONE_THICKNESS, DripstoneThickness.FRUSTUM);
    private static final BlockState BS_DRIPSTONE_MIDDLE_DOWN = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
            .setValue(BlockStateProperties.DRIPSTONE_THICKNESS, DripstoneThickness.MIDDLE);
    private static final BlockState BS_DRIPSTONE_BASE_DOWN = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
            .setValue(BlockStateProperties.DRIPSTONE_THICKNESS, DripstoneThickness.BASE);

    // ── Настройки (из пресета) ────────────────────────────────────────────────
    private final int    maxHeight;
    private final double maxRadius;
    private final double minRadius;
    private final int    gridChunks;
    private final double spawnChance;
    private final int    yVariance;
    private final int    bridgeMaxRange;
    private final double bridgeChance;

    // ── Шумы и помощники ──────────────────────────────────────────────────────
    private final IslandPlacer placer;
    private final IslandShape  shape;
    private final AeroNoise    heightVariance;
    private final AeroNoise    bridgeNoise;
    private final AeroNoise    treeNoise;
    private final AeroNoise    stalactiteNoise;
    private final long         seed;

    /**
     * Минимальный радиус поиска ячеек, гарантирующий захват всех островов,
     * чей физический радиус может достигать текущего чанка.
     * Вычисляется из конфига: ceil(maxRadius / (gridChunks * 16)) + 1.
     * +1 — запас на смещение центра острова внутри ячейки сетки.
     */
    private final int searchRadius;

    // ── Кэши ──────────────────────────────────────────────────────────────────
    /**
     * Кэш свойств островов: yBounds + radius.
     * Вычисляется один раз на (cx,cz), затем переиспользуется
     * всеми 256 блоками чанка и повторным вызовом из applyCarvers.
     */
    private final IslandCache islandCache = new IslandCache();

    /**
     * Кэш списка центров островов для чанка.
     * Устраняет повторный обход ячеек сетки при втором вызове fillChunk
     * из restoreIslandsInChunk.
     */
    private final ChunkIslandCache chunkCache;

    public LowerIslandGenerator(long worldSeed, Layer2Settings cfg, ChunkIslandCache sharedChunkCache) {
        this.seed           = worldSeed;
        this.maxHeight      = cfg.maxHeight();
        this.maxRadius      = cfg.maxRadius();
        this.minRadius      = cfg.minRadius();
        this.gridChunks     = cfg.gridChunks();
        this.spawnChance    = cfg.spawnChance();
        this.yVariance      = cfg.yVariance();
        this.bridgeMaxRange = 80;
        this.bridgeChance   = cfg.bridgeChance();
        this.searchRadius   = Math.max(2, (int) Math.ceil(cfg.maxRadius() / (gridChunks * 16.0)) + 1);
        this.chunkCache     = sharedChunkCache;
        this.placer         = new IslandPlacer(worldSeed ^ 0x2L, gridChunks, spawnChance, true);
        this.shape          = new IslandShape(worldSeed ^ 0x3L);
        this.heightVariance = new AeroNoise(worldSeed ^ 0x4L);
        this.bridgeNoise    = new AeroNoise(worldSeed ^ 0x5L);
        this.treeNoise      = new AeroNoise(worldSeed ^ 0x6L);
        this.stalactiteNoise = new AeroNoise(worldSeed ^ 0x7L);
    }

    /** Конструктор с дефолтными настройками для тестов (создаёт собственный ChunkIslandCache). */
    public LowerIslandGenerator(long worldSeed) {
        this(worldSeed, Layer2Settings.DEFAULT, new ChunkIslandCache());
    }

    // ── Получение данных острова через кэш ────────────────────────────────────

    /**
     * Возвращает закэшированные данные острова.
     * Все вычисления (noise2D, Math.round) выполняются ровно один раз на (cx,cz).
     */
    /** Публичный доступ к кэшированным данным острова. Используется TerrainColumnSampler. */
    public IslandData getIslandData(int cx, int cz) {
        return islandCache.get(cx, cz, key -> computeIslandData(cx, cz));
    }

    private IslandData computeIslandData(int cx, int cz) {
        // Определяем архипелажный статус этого острова: либо он сам центр архипелага
        // (тогда масштаб применяется к нему самому), либо спутник существующего центра
        // (тогда масштаб и базовые параметры считаются от координат центра архипелага,
        // чтобы все острова архипелага были одного размера — половина от обычного).
        long selfPacked = ChunkKey.of(cx, cz);
        boolean isArchipelagoCentre = placer.isArchipelagoCentre(selfPacked);
        long archipelagoCentre = isArchipelagoCentre
                ? selfPacked
                : placer.findArchipelagoCentreFor(cx, cz, searchRadius);
        boolean isArchipelagoIsland = isArchipelagoCentre || archipelagoCentre != IslandPlacer.NO_ISLAND;

        // Базовые параметры (высота/радиус) всегда считаются от координат центра
        // архипелага, если это спутник — гарантирует одинаковый масштаб у всех
        // островов одного архипелага независимо от собственной позиции спутника.
        int baseCx = isArchipelagoIsland && !isArchipelagoCentre
                ? ChunkKey.x(archipelagoCentre) : cx;
        int baseCz = isArchipelagoIsland && !isArchipelagoCentre
                ? ChunkKey.z(archipelagoCentre) : cz;

        double nOff  = heightVariance.noise2D(baseCx * 0.0015 + 9999, baseCz * 0.0015 + 9999);
        int yOffset  = (int) Math.round(nOff * yVariance);

        int layerMid = (LAYER_MIN_Y + LAYER_MAX_Y) / 2;
        int centreY  = layerMid + yOffset;

        double hFrac = 0.4 + (heightVariance.noise2D(baseCx * 0.007, baseCz * 0.007) + 1.0) * 0.5 * 0.6;
        int islandH  = (int)(maxHeight * hFrac);

        double v      = (heightVariance.noise2D(baseCx * 0.004, baseCz * 0.004) + 1.0) * 0.5;
        double radius = minRadius + v * (maxRadius - minRadius);

        if (isArchipelagoIsland) {
            double scale = isArchipelagoCentre
                    ? IslandPlacer.ARCHIPELAGO_SCALE
                    : IslandPlacer.ARCHIPELAGO_SCALE * IslandPlacer.SATELLITE_SCALE;
            islandH = (int) Math.round(islandH * scale);
            radius  = radius * scale;
        }

        int bottomY  = centreY - islandH / 2;
        int topY     = bottomY + islandH;

        if (bottomY < LAYER_MIN_Y) { bottomY = LAYER_MIN_Y; topY = bottomY + islandH; }
        if (topY    > LAYER_MAX_Y) { topY    = LAYER_MAX_Y; bottomY = topY - islandH; }
        bottomY = Math.max(bottomY, LAYER_MIN_Y);

        // Вычисляем один раз — profile и noiseIntensity зависят только от центра острова.
        // Без кэширования они пересчитывались для каждой из 256 XZ-колонок чанка (пункт R).
        // Профиль/шум всегда берём от собственных координат острова (cx,cz) —
        // это делает форму каждого спутника уникальной, а не идентичной копией центра.
        int    profile        = IslandShape.computeProfile(cx, cz);
        double noiseIntensity = shape.computeNoiseIntensity(cx, cz);

        return new IslandData(cx, cz, bottomY, topY, radius, profile, noiseIntensity);
    }

    // ── Публичные методы (обратная совместимость) ─────────────────────────────

    public int[] getIslandYBounds(int cx, int cz) {
        IslandData d = getIslandData(cx, cz);
        return new int[]{d.bottomY, d.topY};
    }

    public double getIslandRadius(int cx, int cz) {
        return getIslandData(cx, cz).radius;
    }

    // ── Заполнение чанка ──────────────────────────────────────────────────────

    public void fillChunk(ChunkAccess chunk, int chunkX, int chunkZ) {
        fillChunk(new ChunkAccessWriter(chunk), chunkX, chunkZ);
    }

    public void fillChunk(ChunkWriter chunk, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Список центров — из кэша (при повторном вызове из applyCarvers — бесплатно)
        LongArrayList centres = chunkCache.get(LAYER_ID, chunkX, chunkZ,
                key -> placer.getIslandCentresForChunk(chunkX, chunkZ, searchRadius));

        if (centres.isEmpty()) return;

        // Ранний фильтр по AABB: отбрасываем острова, которые физически не могут
        // задеть текущий чанк даже с учётом мостов и искажений.
        // maxRadius + NOISE_DEFORM (собственный радиус) + bridgeMaxRange (длина моста)
        double maxInfluence = maxRadius + NOISE_DEFORM + bridgeMaxRange;
        int maxMargin = (int) Math.ceil(maxInfluence);
        
        LongArrayList filteredCentres = new LongArrayList();
        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            int cx = ChunkKey.x(packed);
            int cz = ChunkKey.z(packed);
            if (cx + maxMargin < baseX || cx - maxMargin > baseX + 15) continue;
            if (cz + maxMargin < baseZ || cz - maxMargin > baseZ + 15) continue;
            filteredCentres.add(packed);
        }

        if (filteredCentres.isEmpty()) return;

        // Предвычисляем данные всех островов ДО вложенного цикла по блокам.
        // Это гарантирует, что внутри цикла обращения к islandCache — только хиты.
        IslandData[] islandData = new IslandData[filteredCentres.size()];
        for (int i = 0; i < filteredCentres.size(); i++) {
            long packed = filteredCentres.getLong(i);
            islandData[i] = getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
        }

        // Precompute active bridge pairs: dist/roll/bridgeY depend only on island centers.
        // Without this, fillBridges recomputed Math.sqrt + hash for every XZ-column (256×).
        List<BridgePair> bridgePairs = new ArrayList<>();

        // Гарантированные аметистовые мосты: каждый спутник архипелага соединяется
        // со своим центром со 100% вероятностью (в отличие от обычных мостов —
        // без этого архипелаг мог остаться без связей при неудачном roll).
        for (IslandData src : islandData) {
            long srcArchCentre = placer.isArchipelagoCentre(ChunkKey.of(src.cx, src.cz))
                    ? ChunkKey.of(src.cx, src.cz)
                    : placer.findArchipelagoCentreFor(src.cx, src.cz, searchRadius);
            if (srcArchCentre == IslandPlacer.NO_ISLAND) continue;
            // src — спутник или центр архипелага. Соединяем спутник с центром.
            if (placer.isArchipelagoCentre(ChunkKey.of(src.cx, src.cz))) continue; // сам центр — не соединяем с собой
            int centreCx = ChunkKey.x(srcArchCentre);
            int centreCz = ChunkKey.z(srcArchCentre);
            for (IslandData other : islandData) {
                if (other.cx == centreCx && other.cz == centreCz) {
                    bridgePairs.add(new BridgePair(src, other, Math.min(src.topY, other.topY) - 1, true));
                    break;
                }
            }
        }

        for (IslandData src : islandData) {
            for (IslandData other : islandData) {
                if (other.cx == src.cx && other.cz == src.cz) continue;
                // Пропускаем пары, уже добавленные как гарантированный аметистовый мост архипелага.
                boolean alreadyBridged = false;
                for (BridgePair bp : bridgePairs) {
                    if (bp.src().cx == src.cx && bp.src().cz == src.cz
                            && bp.other().cx == other.cx && bp.other().cz == other.cz) {
                        alreadyBridged = true;
                        break;
                    }
                }
                if (alreadyBridged) continue;

                double distSq = (double)(src.cx - other.cx) * (src.cx - other.cx)
                              + (double)(src.cz - other.cz) * (src.cz - other.cz);
                if (distSq > (double) bridgeMaxRange * bridgeMaxRange) continue;
                long bridgeHash = hash(src.cx, src.cz, other.cx, other.cz);
                double roll = ((bridgeHash >>> 1) & 0xFFFFFFL) / (double) 0xFFFFFFL;
                if (roll > bridgeChance) continue;

                boolean amethyst = placer.isSameArchipelago(src.cx, src.cz, other.cx, other.cz)
                        || placer.isSameArchipelago(other.cx, other.cz, src.cx, src.cz);
                bridgePairs.add(new BridgePair(src, other, Math.min(src.topY, other.topY) - 1, amethyst));
            }
        }

        // Precalculate AABB bounds to skip terrain generation for islands outside the chunk
        boolean[] inBounds = new boolean[islandData.length];
        for (int i = 0; i < islandData.length; i++) {
            IslandData d = islandData[i];
            double margin = NOISE_DEFORM + d.radius;
            inBounds[i] = !(d.cx + margin < baseX || d.cx - margin > baseX + 15 ||
                            d.cz + margin < baseZ || d.cz - margin > baseZ + 15);
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                for (int i = 0; i < islandData.length; i++) {
                    IslandData d = islandData[i];

                    if (inBounds[i]) {
                        // Precompute XZ-deformation once per column per island (outside Y-loop).
                        // Eliminates 9 noise calls × (topY - bottomY) per column.
                        // Горячий путь: передаём profile + noiseIntensity из IslandData.
                        // computeNoiseIntensity() и computeProfile() не вызываются.
                        IslandShape.XZCache xz = shape.precomputeXZ(
                                wx, wz, d.cx, d.cz, d.radius, NOISE_DEFORM,
                                d.shapeNoiseIntensity, d.shapeProfile);

                        // Terrain blocks — top-down pass so we know surface/subsurface without
                        // re-calling isSolid. prevSolid=false means current block is exposed at top
                        // → surface; depthFromSurface tracks subsurface depth (1–3 = DIRT).
                        boolean prevSolid = false; // solid state of the block one step ABOVE
                        int depthFromSurface = 0;
                        int surfaceY = -1;
                        int bottomSurfaceY = -1; // самый нижний solid-блок острова (низ), для сталактитов
                        for (int wy = d.topY; wy >= d.bottomY; wy--) {
                            boolean solid = shape.isSolid(wy, d.bottomY, d.topY, xz);
                            if (solid) {
                                BlockState block;
                                if (!prevSolid) {
                                    // Top exposed face → surface
                                    block = BS_GRASS_BLOCK;
                                    depthFromSurface = 0;
                                    surfaceY = wy;
                                } else {
                                    depthFromSurface++;
                                    block = depthFromSurface <= 3
                                            ? BS_DIRT
                                            : BS_STONE;
                                }
                                chunk.setBlockState(wx, wy, wz, block);
                                bottomSurfaceY = wy;
                            }
                            prevSolid = solid;
                        }

                        // Стволы пишем здесь (1 блок в ширину — не вылезают за чанк).
                        // Листья (±2 блока) пишутся в placeTreesInRegion через WorldGenLevel.
                        if (isInTreeEdgeBand(xz.distSq, d.radius)) {
                            placeTrunk(chunk, wx, wz, surfaceY, pos);
                        }

                        // Сталактиты на нижней грани острова (1-3 блока вниз).
                        placeStalactite(chunk, wx, wz, bottomSurfaceY);
                    }

                    // Bridges
                    fillBridges(chunk, wx, wz, d, bridgePairs, pos);
                }
            }
        }

        // Освобождаем запись в chunkCache после полной обработки чанка.
        // fillChunk вызывается из fillFromNoise, а затем может быть вызван
        // повторно из restoreIslandsInChunk (applyCarvers).
        // Освобождаем только при втором вызове — проверяем это косвенно через
        // наличие в кэше: release вызывается из AeroWorldChunkGenerator
        // после applyCarvers.
    }

    /**
     * Вызывается из AeroWorldChunkGenerator.applyCarvers после восстановления
     * островов. Освобождает запись чанка из ChunkIslandCache.
     */
    public void releaseChunkCache(int chunkX, int chunkZ) {
        chunkCache.release(LAYER_ID, chunkX, chunkZ);
    }

    /** Доступ к IslandPlacer для TerrainColumnSampler. */
    public IslandPlacer getPlacer() { return placer; }

    /** Радиус поиска ячеек для этого слоя. Используется TerrainColumnSampler. */
    public int getSearchRadius() { return searchRadius; }

    /** Форма острова этого слоя. Используется Layer2VaultTrialPlacer для поиска точек внутри тела острова. */
    public IslandShape getShape() { return shape; }

    // ── Очистка ванильной растительности в центре острова ─────────────────────

    /**
     * Удаляет ванильную растительность (деревья, саженцы, траву, грибы),
     * попавшую в центральную зону острова (тот же {@code TREE_EDGE_BAND_START},
     * что и для кастомных деревьев Layer 2) через биомную декорацию
     * {@code super.applyBiomeDecoration()}.
     *
     * <p>Центральная зона зарезервирована под Vault/Trial Spawner
     * ({@link org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer}) —
     * кастомные деревья там и так не растут (см. {@link #isInTreeEdgeBand}),
     * но ванильные биомные деревья/трава об этом не знают и накладываются сверху.
     *
     * <p>Вызывать сразу после {@code super.applyBiomeDecoration()}, до
     * {@link #placeTreesInRegion} — сканирует колонку от {@code d.topY} до
     * {@code d.topY + 16} (запас на высоту деревьев) и сносит блоки из
     * {@link BlockTags#LOGS}, {@link BlockTags#LEAVES}, {@link BlockTags#SAPLINGS},
     * {@link BlockTags#REPLACEABLE_BY_TREES} и {@link BlockTags#FLOWERS}.
     */
    public void clearVanillaVegetationInCentralZone(WorldGenLevel region, ChunkAccess chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int baseX  = chunkX << 4;
        int baseZ  = chunkZ << 4;

        LongArrayList centres = chunkCache.get(LAYER_ID, chunkX, chunkZ,
                key -> placer.getIslandCentresForChunk(chunkX, chunkZ, searchRadius));
        if (centres.isEmpty()) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = baseX + lx;
                    int wz = baseZ + lz;

                    IslandShape.XZCache xz = shape.precomputeXZ(
                            wx, wz, d.cx, d.cz, d.radius, NOISE_DEFORM,
                            d.shapeNoiseIntensity, d.shapeProfile);

                    // Только центральная зона (там, где кастомные деревья не растут).
                    if (isInTreeEdgeBand(xz.distSq, d.radius)) continue;
                    if (!shape.isSolid(d.topY, d.bottomY, d.topY, xz)) continue; // XZ вне острова

                    for (int wy = d.topY; wy <= d.topY + 16; wy++) {
                        pos.set(wx, wy, wz);
                        BlockState bs = region.getBlockState(pos);
                        if (bs.isAir()) continue;
                        if (bs.is(BlockTags.LOGS) || bs.is(BlockTags.LEAVES)
                                || bs.is(BlockTags.SAPLINGS) || bs.is(BlockTags.FLOWERS)
                                || bs.is(BlockTags.REPLACEABLE_BY_TREES)) {
                            region.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }

    // ── Деревья ───────────────────────────────────────────────────────────────

    /**
     * Проверяет, находится ли XZ-точка в кольце у края острова, где разрешён рост деревьев.
     * normDist = sqrt(distSq) / radius ∈ [0,1]: 0 — центр острова, 1 — край.
     * Сравнение возведено в квадрат, чтобы избежать sqrt в горячем пути.
     */
    private static boolean isInTreeEdgeBand(double distSq, double radius) {
        double bandStartSq = (TREE_EDGE_BAND_START * radius) * (TREE_EDGE_BAND_START * radius);
        return distSq >= bandStartSq;
    }

    /**
     * Ствол дерева (1×1 по XZ) — безопасно писать в ChunkAccess: никогда не
     * выходит за границы чанка. Возвращает высоту верхнего блока ствола или -1
     * если дерево здесь не растёт.
     */
    private int placeTrunk(ChunkWriter chunk, int wx, int wz,
                            int surfaceY, BlockPos.MutableBlockPos pos) {
        if (surfaceY < 0) return -1;
        double tn = treeNoise.noise2D(wx * 0.18, wz * 0.18);
        if (tn < 0.55) return -1;

        double typeSample = treeNoise.noise2D(wx * 0.07 + 500, wz * 0.07 + 500);
        boolean isBirch = typeSample > 0.3;
        int trunkHeight = 4 + (int)((treeNoise.noise2D(wx * 0.31, wz * 0.31) + 1.0) * 0.5 * 3);

        BlockState log = isBirch
                ? BS_BIRCH_LOG
                : BS_OAK_LOG;
        for (int dy = 1; dy <= trunkHeight; dy++) {
            int wy = surfaceY + dy;
            if (chunk.getBlockState(wx, wy, wz).isAir()) chunk.setBlockState(wx, wy, wz, log);
        }
        return surfaceY + trunkHeight;
    }

    /**
     * Сталактит (Pointed Dripstone) на нижней грани острова: растёт вниз от блока
     * dripstone_block, установленного вместо нижнего solid-блока острова.
     * Длина 1–3 сегмента, направление VERTICAL_DIRECTION=DOWN, форма TIP/FRUSTUM/MIDDLE/BASE
     * как у ванильного сталактита. Пишется только в текущий чанк (1×1 по XZ) — безопасно
     * для ChunkAccess.
     */
    private void placeStalactite(ChunkWriter chunk, int wx, int wz, int bottomSurfaceY) {
        if (bottomSurfaceY < 0) return;

        double sn = stalactiteNoise.noise2D(wx * 0.22 + 1000, wz * 0.22 + 1000);
        if (sn < STALACTITE_THRESHOLD) return;

        double lenSample = stalactiteNoise.noise2D(wx * 0.37 + 2000, wz * 0.37 + 2000);
        int length = 1 + (int)((lenSample + 1.0) * 0.5 * 3); // 1..3
        if (length > 3) length = 3;

        // Основание сталактита заменяет нижний solid-блок острова на dripstone_block.
        chunk.setBlockState(wx, bottomSurfaceY, wz, BS_DRIPSTONE_BLOCK);

        for (int dy = 1; dy <= length; dy++) {
            int wy = bottomSurfaceY - dy;
            if (wy < LAYER_MIN_Y) break;
            if (!chunk.getBlockState(wx, wy, wz).isAir()) break;

            BlockState segment;
            if (dy == length) {
                segment = BS_DRIPSTONE_TIP_DOWN; // самый нижний сегмент — острие
            } else if (length == 1) {
                segment = BS_DRIPSTONE_TIP_DOWN;
            } else if (dy == 1) {
                segment = BS_DRIPSTONE_BASE_DOWN; // сегмент у основания
            } else if (dy == length - 1) {
                segment = BS_DRIPSTONE_FRUSTUM_DOWN; // сужение перед остриём
            } else {
                segment = BS_DRIPSTONE_MIDDLE_DOWN;
            }
            chunk.setBlockState(wx, wy, wz, segment);
        }
    }

    /**
     * Размещает листья для всех деревьев чанка через WorldGenLevel (регион 3×3 чанка).
     * Вызывается из AeroWorldChunkGenerator.applyBiomeDecoration — фаза populate,
     * где запись в соседние чанки корректна (как у ванильного TreeFeature).
     *
     * <p>surfaceY пересчитывается через shape.isSolid() — та же детерминированная
     * математика что в fillChunk, без обращения к ChunkIslandCache.
     */
    public void placeTreesInRegion(WorldGenLevel region, ChunkAccess chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int baseX  = chunkX << 4;
        int baseZ  = chunkZ << 4;

        LongArrayList centres = chunkCache.get(LAYER_ID, chunkX, chunkZ,
                key -> placer.getIslandCentresForChunk(chunkX, chunkZ, searchRadius));
        if (centres.isEmpty()) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < centres.size(); i++) {
            long packed = centres.getLong(i);
            IslandData d = getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = baseX + lx;
                    int wz = baseZ + lz;

                    // precomputeXZ — та же функция что в fillChunk, без sqrt.
                    // Горячий путь: передаём profile + noiseIntensity из IslandData.
                    // computeNoiseIntensity() и computeProfile() не вызываются.
                    IslandShape.XZCache xz = shape.precomputeXZ(
                            wx, wz, d.cx, d.cz, d.radius, NOISE_DEFORM,
                            d.shapeNoiseIntensity, d.shapeProfile);
                    // Быстрый XZ-reject: если точка вне острова — пропускаем.
                    if (!shape.isSolid(d.topY, d.bottomY, d.topY, xz)) continue;

                    // Деревья только у края острова (см. TREE_EDGE_BAND_START).
                    if (!isInTreeEdgeBand(xz.distSq, d.radius)) continue;

                    // Пересчитываем surfaceY (детерминированная математика, нет side-эффектов)
                    int surfaceY = -1;
                    for (int wy = d.topY; wy >= d.bottomY; wy--) {
                        if (shape.isSolid(wy, d.bottomY, d.topY, xz)
                                && !shape.isSolid(wy + 1, d.bottomY, d.topY, xz)) {
                            surfaceY = wy;
                            break;
                        }
                    }
                    if (surfaceY < 0) continue;

                    double tn = treeNoise.noise2D(wx * 0.18, wz * 0.18);
                    if (tn < 0.55) continue;

                    double typeSample = treeNoise.noise2D(wx * 0.07 + 500, wz * 0.07 + 500);
                    boolean isBirch = typeSample > 0.3;
                    int trunkHeight = 4 + (int)((treeNoise.noise2D(wx * 0.31, wz * 0.31) + 1.0) * 0.5 * 3);

                    BlockState leaves = isBirch
                            ? BS_BIRCH_LEAVES
                            : BS_OAK_LEAVES;
                    int topLog = surfaceY + trunkHeight;
                    int leafRadius = 2;
                    for (int dlx = -leafRadius; dlx <= leafRadius; dlx++) {
                        for (int dlz = -leafRadius; dlz <= leafRadius; dlz++) {
                            for (int dly = -1; dly <= leafRadius; dly++) {
                                if (Math.abs(dlx) == leafRadius && Math.abs(dlz) == leafRadius) continue;
                                pos.set(wx + dlx, topLog + dly, wz + dlz);
                                if (region.getBlockState(pos).isAir()) {
                                    region.setBlock(pos, leaves, 2);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Мосты ─────────────────────────────────────────────────────────────────

    private void fillBridges(ChunkWriter chunk, int wx, int wz,
                             IslandData src, List<BridgePair> pairs,
                             BlockPos.MutableBlockPos pos) {
        for (BridgePair bp : pairs) {
            if (bp.src().cx != src.cx || bp.src().cz != src.cz) continue;

            double t = projectPointOntoSegment(wx, wz, bp.src().cx, bp.src().cz, bp.other().cx, bp.other().cz);
            if (t < 0.05 || t > 0.95) continue;

            double lineX = bp.src().cx + t * (bp.other().cx - bp.src().cx);
            double lineZ = bp.src().cz + t * (bp.other().cz - bp.src().cz);
            double perpDistSq = (wx - lineX) * (wx - lineX) + (wz - lineZ) * (wz - lineZ);

            double bridgeWidth = 2.5 + bridgeNoise.noise2D(wx * 0.1, wz * 0.1) * 1.0;
            if (perpDistSq > bridgeWidth * bridgeWidth) continue;

            for (int dy = 0; dy <= 1; dy++) {
                int wy = bp.bridgeY() + dy;
                BlockState bridgeBlock;
                if (bp.amethyst()) {
                    bridgeBlock = (dy == 0) ? BS_AMETHYST_BLOCK : BS_AMETHYST_CLUSTER;
                } else {
                    bridgeBlock = (dy == 0) ? BS_OAK_LOG : BS_MANGROVE;
                }
                if (chunk.getBlockState(wx, wy, wz).isAir()) chunk.setBlockState(wx, wy, wz, bridgeBlock);
            }
        }
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private double projectPointOntoSegment(int px, int pz, int ax, int az, int bx, int bz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 == 0) return 0;
        return ((px - ax) * dx + (pz - az) * dz) / len2;
    }

    private long hash(int ax, int az, int bx, int bz) {
        long h = seed
                ^ ((long)(ax + bx) * 341873128712L)
                ^ ((long)(az + bz) * 132897987541L)
                ^ ((long)(ax * bz) * 998244353L);
        h = h ^ (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 31);
    }

    private record BridgePair(IslandData src, IslandData other, int bridgeY, boolean amethyst) {}
    /**
     * Возвращает реальный Y верхней поверхности острова в точке (wx, wz)
     * с учётом шумовой деформации — для корректного LOD-рендеринга.
     *
     * <p>Используется в {@code AeroWorldDhWorldGenerator} вместо плоского
     * {@code d.topY}. Это даёт деформированный край острова вместо цилиндра.
     *
     * <p>Алгоритм: precomputeXZ один раз (кэш для всего Y-диапазона), затем
     * бинарный поиск по Y — O(log(topY-bottomY)) вместо O(topY-bottomY).
     *
     * @return Y верхней поверхности, или {@code d.bottomY - 1} если точка вне острова
     */
    public int getDeformedTopY(int wx, int wz, IslandData d) {
        IslandShape.XZCache xz = shape.precomputeXZ(
                wx, wz, d.cx, d.cz, d.radius, NOISE_DEFORM,
                d.shapeNoiseIntensity, d.shapeProfile);

        // Быстрая XZ-проверка: если точка вне острова даже на topY — выходим
        if (!shape.isSolid(d.topY, d.bottomY, d.topY, xz)) return d.bottomY - 1;

        // Бинарный поиск верхней поверхности: ищем наибольший Y где solid=true и solid(Y+1)=false
        int lo = d.bottomY, hi = d.topY;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (shape.isSolid(mid, d.bottomY, d.topY, xz)) lo = mid;
            else hi = mid - 1;
        }
        // lo = наивысший solid Y. Проверяем что это реально поверхность (над ним воздух)
        if (shape.isSolid(lo + 1, d.bottomY, d.topY, xz)) return d.bottomY - 1; // внутри
        return lo;
    }

    /**
     * Возвращает нижний Y острова в точке (wx, wz) с учётом деформации.
     * Бинарный поиск снизу вверх.
     *
     * @return Y нижней поверхности, или {@code d.topY + 1} если точка вне острова
     */
    public int getDeformedBottomY(int wx, int wz, IslandData d) {
        IslandShape.XZCache xz = shape.precomputeXZ(
                wx, wz, d.cx, d.cz, d.radius, NOISE_DEFORM,
                d.shapeNoiseIntensity, d.shapeProfile);

        if (!shape.isSolid(d.bottomY, d.bottomY, d.topY, xz)) return d.topY + 1;

        int lo = d.bottomY, hi = d.topY;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (shape.isSolid(mid, d.bottomY, d.topY, xz)) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

}