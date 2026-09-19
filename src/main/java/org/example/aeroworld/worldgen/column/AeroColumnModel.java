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
                        spans.add(new Span(upperBottom, stoneTop, BS_STONE, layer1BiomeName, layer1BiomeHolder));
                    }
                } else {
                    spans.add(new Span(minY, stoneTop, BS_STONE, layer1BiomeName, layer1BiomeHolder));
                }
            }

            if (surfaceY < seaLevel) {
                int waterBottom = Math.max(surfaceY + 1, minY);
                int waterTop = Math.min(seaLevel, levelMax);
                if (waterTop >= waterBottom) {
                    spans.add(new Span(waterBottom, waterTop, BS_WATER, layer1BiomeName, layer1BiomeHolder));
                }
            }
        }

        // ── 2. Layer 2 (Y 400..500) ──────────────────────────────────────────
        if (lowerIslands != null && levelMax >= LowerIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = lowerIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, lowerIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = lowerIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz > d.radius * d.radius) continue;

                int bY = Math.max(d.bottomY, minY);
                int tY = Math.min(d.topY, levelMax);
                if (tY >= bY) {
                    spans.add(new Span(bY, tY, BS_STONE, islandBiomeName, islandBiomeHolder));
                }
            }
        }

        // ── 3. Layer 3 (Y 1000..1100) ────────────────────────────────────────
        if (highIslands != null && levelMax >= HighIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = highIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, highIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = highIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                double effR = d.getEffectiveRadius();
                if (dx * dx + dz * dz > effR * effR) continue;

                int bY = Math.max(d.bottomY, minY);
                int tY = Math.min(d.topY, levelMax);
                if (tY >= bY) {
                    spans.add(new Span(bY, tY, BS_STONE, islandBiomeName, islandBiomeHolder));
                }
            }
        }

        // ── 4. Layer 4 (Y 1900..2031) ────────────────────────────────────────
        if (upperIslands != null && levelMax >= UpperIslandGenerator.LAYER_MIN_Y) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            LongArrayList centres = upperIslands.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, upperIslands.getSearchRadius());
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                IslandData d = upperIslands.getIslandData(ChunkKey.x(packed), ChunkKey.z(packed));
                double dx = x - d.cx, dz = z - d.cz;
                if (dx * dx + dz * dz > d.radius * d.radius) continue;

                int bY = Math.max(d.bottomY, minY);
                int tY = Math.min(d.topY, levelMax);
                if (tY >= bY) {
                    spans.add(new Span(bY, tY, BS_STONE, islandBiomeName, islandBiomeHolder));
                }
            }
        }

        return mergeSpans(spans);
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
                double effR = d.getEffectiveRadius();
                if (dx * dx + dz * dz <= effR * effR) return d.topY + 1;
            }
        }

        // Layer 2 (Y 400..500)
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
}
