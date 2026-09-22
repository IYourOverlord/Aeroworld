package org.example.aeroworld.worldgen.column;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.example.aeroworld.worldgen.biome.AeroBiomeSource;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Единое аналитическое ядро генерации вертикальных столбцов мира AeroWorld.
 *
 * Объединяет все 4 слоя:
 * <ul>
 *   <li>Layer 1: Y -64..300 (поверхность, пещера, океан)</li>
 *   <li>Layer 2: Y 400..500 (нижние острова)</li>
 *   <li>Layer 3: Y 1000..1100 (средние острова / метеориты / планеты)</li>
 *   <li>Layer 4: Y 1900..2031 (верхние острова / медузы)</li>
 * </ul>
 *
 * Используется как реализацией {@code ChunkGenerator.getBaseColumn/getBaseHeight},
 * так и оверрайдом Distant Horizons {@code AeroSeedWorldGenerator}.
 */
public final class AeroColumnModel {

    private static final BlockState BS_STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BS_WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState BS_GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BS_DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState BS_ICE = Blocks.ICE.defaultBlockState();
    private static final BlockState BS_KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();
    private static final BlockState BS_SEAGRASS = Blocks.SEAGRASS.defaultBlockState();

    /** Толщина подповерхностного слоя под верхним блоком (как в реальной генерации). */
    private static final int SUBSURFACE_DEPTH = 3;
    /** Запас по XZ для деформации края островов слоя 2 (LowerIslandGenerator.NOISE_DEFORM). */
    private static final double LAYER2_NOISE_MARGIN = 18.0;
    /** Запас по XZ для края острова слоя 3 (шум деформации эллипсоида). */
    private static final double LAYER3_NOISE_MARGIN = 32.0;
    /** Запас по XZ для купола слоя 4 (UpperIslandGenerator.CAP_NOISE_DEF). */
    private static final double LAYER4_NOISE_MARGIN = 8.0;

    private AeroColumnModel() {}

    /**
     * Непрерывный диапазон блоков по вертикали [bottomY..topY] с одинаковым материалом и биомом.
     */
    public record Span(
            int bottomY,
            int topY,
            BlockState state,
            @Nullable String biomeName,
            @Nullable Holder<Biome> biomeHolder
    ) {
        public Span(int bottomY, int topY, BlockState state) {
            this(bottomY, topY, state, null, null);
        }

        public Span(int bottomY, int topY, BlockState state, @Nullable String biomeName) {
            this(bottomY, topY, state, biomeName, null);
        }
    }

    /**
     * Аналитически вычисляет список span'ов для колонки (x, z) по всем 4 слоям.
     * Возвращает отсортированный по возрастанию bottomY список непересекающихся span'ов.
     */
    public static List<Span> buildSpans(
            int x, int z,
            int minY, int levelMax,
            @Nullable Layer1TerrainGenerator layer1Terrain,
            @Nullable LowerIslandGenerator lowerIslands,
            @Nullable HighIslandGenerator highIslands,
            @Nullable UpperIslandGenerator upperIslands,
            @Nullable AeroBiomeSource aeroBiomeSource,
            boolean sampleBiomes
    ) {
        List<Span> spans = new ArrayList<>(8);

        String layer1BiomeName = null;
        Holder<Biome> layer1BiomeHolder = null;
        String islandBiomeName = null;
        Holder<Biome> islandBiomeHolder = null;

        if (sampleBiomes && aeroBiomeSource != null) {
            String l1Name = aeroBiomeSource.getLayer1BiomeName(x, z);
            layer1BiomeName = "aeroworld:" + l1Name;
            layer1BiomeHolder = aeroBiomeSource.findAeroBiome(l1Name).orElse(null);

            String isName = aeroBiomeSource.getIslandBiomeName(x, z);
            islandBiomeName = "aeroworld:" + isName;
            islandBiomeHolder = aeroBiomeSource.findAeroBiome(isName).orElse(null);
        }

        // ── 1. Layer 1 (поверхность + океан + пещера) ─────────────────────────
        if (layer1Terrain != null && minY <= Layer1TerrainGenerator.MAX_Y) {
            int surfaceY = layer1Terrain.getHeight(x, z);
            int seaLevel = Layer1TerrainGenerator.SEA_LEVEL;
            boolean hasCave = surfaceY >= seaLevel;
            int caveTop = hasCave ? layer1Terrain.computeCaveTop(x, z) : Integer.MIN_VALUE;
            int caveBottom = hasCave ? layer1Terrain.computeCaveBottom(x, z) : Integer.MAX_VALUE;

            int stoneTop = Math.min(surfaceY, levelMax);
            Layer1TerrainGenerator.BiomeSurfaceInfo surfaceInfo =
                    Layer1TerrainGenerator.surfaceInfoForBiomeName(layer1BiomeName);
            Layer1TerrainGenerator.SurfaceBlocks surface = null;
            if (stoneTop >= minY && stoneTop == surfaceY) {
                surface = Layer1TerrainGenerator.surfaceBlocks(surfaceInfo, surfaceY);
            }
            if (stoneTop >= minY) {
                if (hasCave && caveBottom <= caveTop) {
                    int lowerTop = Math.min(caveBottom - 1, stoneTop);
                    if (lowerTop >= minY) {
                        // Проверка Deep Dark для нижней части пещеры
                        String lowerBiome = layer1BiomeName;
                        Holder<Biome> lowerHolder = layer1BiomeHolder;
                        if (sampleBiomes && aeroBiomeSource != null && aeroBiomeSource.isDeepDark(x, z)) {
                            lowerBiome = "aeroworld:deep_dark";
                            lowerHolder = aeroBiomeSource.findAeroBiome("deep_dark").orElse(layer1BiomeHolder);
                        }
                        spans.add(new Span(minY, lowerTop, BS_STONE, lowerBiome, lowerHolder));
                    }
                    int upperBottom = Math.max(caveTop + 1, minY);
                    if (stoneTop >= upperBottom) {
                        addSurfaceColumn(spans, upperBottom, stoneTop, BS_STONE, surface, surfaceInfo, layer1BiomeName, layer1BiomeHolder);
                    }
                } else {
                    addSurfaceColumn(spans, minY, stoneTop, BS_STONE, surface, surfaceInfo, layer1BiomeName, layer1BiomeHolder);
                }

                // Деревья Layer 1 на LOD
                if (sampleBiomes && stoneTop == surfaceY && surfaceY >= seaLevel) {
                    AeroTreeCover.TreeSpans tree = AeroTreeCover.sampleLayer1(
                            layer1Terrain.getSeed(), x, z, surfaceY, layer1BiomeName, layer1Terrain);
                    if (tree != null) {
                        if (tree.hasTrunk()) {
                            int tb = Math.max(minY, tree.trunkBottom());
                            int tt = Math.min(levelMax, tree.trunkTop());
                            if (tt >= tb) {
                                spans.add(new Span(tb, tt, tree.log(), layer1BiomeName, layer1BiomeHolder));
                            }
                        }
                        if (tree.hasCanopy()) {
                            int cb = Math.max(minY, tree.canopyBottom());
                            int ct = Math.min(levelMax, tree.canopyTop());
                            if (ct >= cb) {
                                spans.add(new Span(cb, ct, tree.leaves(), layer1BiomeName, layer1BiomeHolder));
                            }
                        }
                    }
                }
            }

            if (surfaceY < seaLevel) {
                int waterBottom = Math.max(surfaceY + 1, minY);
                int waterTop = Math.min(seaLevel, levelMax);
                if (waterTop >= waterBottom) {
                    boolean frozen = surfaceInfo.isFrozenOcean();

                    // Подводная растительность (kelp/seagrass)
                    int vegHeight = layer1Terrain.sampleKelpHeight(x, z, surfaceY, frozen);
                    int vegBottom = waterBottom;
                    int vegTop = Math.min(surfaceY + vegHeight, waterTop - 1);
                    if (vegHeight > 0 && vegTop >= vegBottom) {
                        BlockState vegBlock = vegHeight == 1 ? BS_SEAGRASS : BS_KELP_PLANT;
                        spans.add(new Span(vegBottom, vegTop, vegBlock, layer1BiomeName, layer1BiomeHolder));
                        waterBottom = vegTop + 1; // вода начинается выше растительности
                    }

                    if (frozen && waterTop == seaLevel) {
                        if (waterTop > waterBottom) {
                            spans.add(new Span(waterBottom, waterTop - 1, BS_WATER, layer1BiomeName, layer1BiomeHolder));
                        }
                        spans.add(new Span(waterTop, waterTop, BS_ICE, layer1BiomeName, layer1BiomeHolder));
                    } else if (waterTop >= waterBottom) {
                        spans.add(new Span(waterBottom, waterTop, BS_WATER, layer1BiomeName, layer1BiomeHolder));
                    }
                }
            }
        }

        // ── 2. Layer 2 (Y 400..500) ──────────────────────────────────────────
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = lowerIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = lowerIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double reach = d.radius + LAYER2_NOISE_MARGIN;
                if (dx * dx + dz * dz >= reach * reach) continue;

                // Остров — перевёрнутый конус с плоским верхом: solid-диапазон колонки [bottom..d.topY].
                int isBottom = lowerIslands.getDeformedBottomY(x, z, d);
                if (isBottom > d.topY) continue; // колонка вне острова
                int isTop = d.topY;

                int bY = Math.max(isBottom, minY);
                int tY = Math.min(isTop, levelMax);
                if (tY >= bY) {
                    Layer1TerrainGenerator.SurfaceBlocks grass = isTop <= levelMax
                            ? new Layer1TerrainGenerator.SurfaceBlocks(BS_GRASS, BS_DIRT) : null;
                    addSurfaceColumn(spans, bY, tY, BS_STONE, grass, islandBiomeName, islandBiomeHolder);

                    // Структуры Layer 2 на LOD: Tank21 на RICH-островах (6.3)
                    AeroStructureCover.StructureColumn tank = AeroStructureCover.sampleLayer2RichStructure(
                            lowerIslands.worldSeed(), x, z, d, lowerIslands);
                    if (tank != null) {
                        int sb = Math.max(minY, tank.bottomY());
                        int st = Math.min(levelMax, tank.topY());
                        if (st >= sb) {
                            spans.add(new Span(sb, st, tank.state(), islandBiomeName, islandBiomeHolder));
                        }
                    }
                    // Деревья Layer 2 на LOD
                    if (sampleBiomes && isTop <= levelMax) {
                        AeroTreeCover.TreeSpans tree = lowerIslands.sampleTreeColumn(x, z, d, isTop);
                        if (tree != null) {
                            if (tree.hasTrunk()) {
                                int tb = Math.max(minY, tree.trunkBottom());
                                int tt = Math.min(levelMax, tree.trunkTop());
                                if (tt >= tb) {
                                    spans.add(new Span(tb, tt, tree.log(), islandBiomeName, islandBiomeHolder));
                                }
                            }
                            if (tree.hasCanopy()) {
                                int cb = Math.max(minY, tree.canopyBottom());
                                int ct = Math.min(levelMax, tree.canopyTop());
                                if (ct >= cb) {
                                    spans.add(new Span(cb, ct, tree.leaves(), islandBiomeName, islandBiomeHolder));
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 3. Layer 3 (Y 1000..1100) ────────────────────────────────────────
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = highIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = highIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double reach = d.getEffectiveRadius() + LAYER3_NOISE_MARGIN;
                if (dx * dx + dz * dz >= reach * reach) continue;

                int bodyTop = highIslands.getEllipsoidTopY(x, z, d);
                int bodyBottom = highIslands.getEllipsoidBottomY(x, z, d);
                if (bodyTop < bodyBottom) continue; // колонка вне тела

                int bY = Math.max(bodyBottom, minY);
                int tY = Math.min(bodyTop, levelMax);
                if (tY >= bY) {
                    spans.add(new Span(bY, tY, BS_STONE, islandBiomeName, islandBiomeHolder));
                }
                // Структуры Layer 3 на LOD: End City на планетах (6.3).
                // Город стоит в 9×9-силуэте по центру планеты — такие колонки
                // всегда лежат внутри тела, поэтому семплим после отсечения
                // колонок вне эллипсоида; вне силуэта вернётся null.
                AeroStructureCover.StructureColumn city = AeroStructureCover.sampleLayer3PlanetStructure(
                        x, z, d, highIslands);
                if (city != null) {
                    int sb = Math.max(minY, city.bottomY());
                    int st = Math.min(levelMax, city.topY());
                    if (st >= sb) {
                        spans.add(new Span(sb, st, city.state(), islandBiomeName, islandBiomeHolder));
                    }
                }
            }
        }


        // ── 4. Layer 4 (Y 1900..2031) ────────────────────────────────────────
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = upperIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = upperIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double reach = d.radius + LAYER4_NOISE_MARGIN;
                if (dx * dx + dz * dz >= reach * reach) continue;

                int capTop = upperIslands.getCapTopY(x, z, d);
                int capBottom = upperIslands.getCapBottomY(x, z, d);
                if (capTop < capBottom) continue; // колонка вне купола

                int bY = Math.max(capBottom, minY);
                int tY = Math.min(capTop, levelMax);
                if (tY >= bY) {
                    spans.add(new Span(bY, tY, BS_STONE, islandBiomeName, islandBiomeHolder));
                }
            }
        }

        return mergeSpans(spans);
    }

    /**
     * Добавляет вертикальный сегмент [bottom..top] с поверхностным покрытием: верхний блок
     * {@code surface.top()} и подповерхностный слой под ним (3 блока по умолчанию,
     * 15 для бэдлендов с elevation bands, 8 для stony/karst с прожилками),
     * ниже основной материал. При {@code surface == null} добавляется сплошной сегмент.
     */
    private static void addSurfaceColumn(List<Span> spans, int bottom, int top, BlockState base,
                                         @Nullable Layer1TerrainGenerator.SurfaceBlocks surface,
                                         @Nullable String biomeName, @Nullable Holder<Biome> biomeHolder) {
        addSurfaceColumn(spans, bottom, top, base, surface, null, biomeName, biomeHolder);
    }

    private static void addSurfaceColumn(List<Span> spans, int bottom, int top, BlockState base,
                                         @Nullable Layer1TerrainGenerator.SurfaceBlocks surface,
                                         @Nullable Layer1TerrainGenerator.BiomeSurfaceInfo info,
                                         @Nullable String biomeName, @Nullable Holder<Biome> biomeHolder) {
        if (surface == null) {
            spans.add(new Span(bottom, top, base, biomeName, biomeHolder));
            return;
        }
        boolean isBadlands = info != null && info.type() == Layer1TerrainGenerator.SurfaceType.BADLANDS;
        boolean isStoneBands = info != null && (info.type() == Layer1TerrainGenerator.SurfaceType.STONY
                || info.type() == Layer1TerrainGenerator.SurfaceType.KARST);
        int depth = isBadlands ? 15 : (isStoneBands ? 8 : SUBSURFACE_DEPTH);
        int underBottom = Math.max(bottom, top - depth);

        if (underBottom - 1 >= bottom) {
            spans.add(new Span(bottom, underBottom - 1, base, biomeName, biomeHolder));
        }
        // Для бэдлендов/горных прожилок LOD использует один репрезентативный блок бэнда
        // ponytail: LOD не эмитит per-Y бэнды — один блок surface.under() (= band at surfaceY-1)
        if (top - 1 >= underBottom) {
            spans.add(new Span(underBottom, top - 1, surface.under(), biomeName, biomeHolder));
        }
        spans.add(new Span(top, top, surface.top(), biomeName, biomeHolder));
    }

    /**
     * Сортирует span'ы по bottomY и объединяет перекрывающиеся / смежные диапазоны
     * с одинаковым BlockState и биомом.
     */
    public static List<Span> mergeSpans(List<Span> spans) {
        if (spans.size() <= 1) return spans;

        spans.sort((a, b) -> Integer.compare(a.bottomY(), b.bottomY()));
        List<Span> merged = new ArrayList<>(spans.size());
        Span cur = spans.get(0);

        for (int i = 1; i < spans.size(); i++) {
            Span next = spans.get(i);
            boolean sameState = cur.state().equals(next.state());
            boolean sameBiome = java.util.Objects.equals(cur.biomeName(), next.biomeName());

            if (next.bottomY() <= cur.topY() + 1 && sameState && sameBiome) {
                cur = new Span(
                        cur.bottomY(),
                        Math.max(cur.topY(), next.topY()),
                        cur.state(),
                        cur.biomeName(),
                        cur.biomeHolder()
                );
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        return merged;
    }

    /**
     * Возвращает высоту верхней точки колонки (x, z), полностью воспроизводя логику getBaseHeight.
     */
    public static int getBaseHeight(
            int x, int z,
            Heightmap.Types type,
            int minY, int levelMax,
            @Nullable Layer1TerrainGenerator layer1Terrain,
            @Nullable LowerIslandGenerator lowerIslands,
            @Nullable HighIslandGenerator highIslands,
            @Nullable UpperIslandGenerator upperIslands
    ) {
        int chunkX = x >> 4, chunkZ = z >> 4;

        // Layer 4 (Y 1900..2031)
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            LongArrayList centres = upperIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = upperIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz <= d.radius * d.radius) return d.topY + 1;
            }
        }

        // Layer 3 (Y 1000..1100)
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            LongArrayList centres = highIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = highIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double effR = d.getEffectiveRadius();
                if (dx * dx + dz * dz <= effR * effR) return d.topY + 1;
            }
        }

        // Layer 2 (Y 400..500)
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            LongArrayList centres = lowerIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
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
}