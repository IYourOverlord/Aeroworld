package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.noise.AeroNoise;
import org.example.aeroworld.worldgen.util.SectionDirectChunkWriter;

import java.util.function.BiFunction;

/**
 * Layer1TerrainGenerator — кастомная генерация рельефа и поверхности для Layer 1 (-64..300).
 * Заменяет ванильный NoiseBasedChunkGenerator для рельефа, сохраняя детерминированность от seed.
 */
public class Layer1TerrainGenerator {

    public static final int SEA_LEVEL = 63;
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 300;
    public static final int BEDROCK_LAYERS = 5;

    private static final BlockState BS_BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState BS_DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BS_STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BS_WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState BS_GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BS_DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState BS_SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState BS_SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState BS_RED_SAND = Blocks.RED_SAND.defaultBlockState();
    private static final BlockState BS_TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState BS_GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState BS_SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState BS_PODZOL = Blocks.PODZOL.defaultBlockState();

    private final long seed;
    private final AeroNoise continentNoise;
    private final AeroNoise erosionNoise;
    private final AeroNoise heightNoise;
    private final AeroNoise detailNoise;

    public Layer1TerrainGenerator(long seed) {
        this.seed = seed;
        this.continentNoise = new AeroNoise(seed ^ 0x1A2B3C4DL);
        this.erosionNoise   = new AeroNoise(seed ^ 0x5E6F7A8BL);
        this.heightNoise    = new AeroNoise(seed ^ 0x9C0D1E2FL);
        this.detailNoise    = new AeroNoise(seed ^ 0x33445566L);
    }

    public long getSeed() {
        return seed;
    }

    /**
     * Континентальность: от -1.0 до +1.0.
     * < -0.15 = океан, -0.15..0.0 = побережье/пляж, > 0.0 = суша.
     */
    public double getContinentality(double wx, double wz) {
        return continentNoise.fbm2D(wx * 0.0008, wz * 0.0008, 4, 2.0, 0.5);
    }

    /**
     * Эрозия: от -1.0 до +1.0.
     * < -0.3 = горы/пики, -0.3..0.2 = холмы, > 0.2 = равнины/плато.
     */
    public double getErosion(double wx, double wz) {
        return erosionNoise.fbm2D(wx * 0.0015, wz * 0.0015, 3, 2.0, 0.5);
    }

    /**
     * Вычисляет высоту поверхности (твёрдого грунта) в координатах (wx, wz).
     */
    public int getHeight(int wx, int wz) {
        double cont = getContinentality(wx, wz);
        double eros = getErosion(wx, wz);
        double fbm  = heightNoise.fbm2D(wx * 0.003, wz * 0.003, 5, 2.0, 0.5);
        double detail = detailNoise.noise2D(wx * 0.02, wz * 0.02) * 3.0;

        double baseHeight;
        if (cont < -0.35) {
            // Глубокий океан
            double t = (cont - (-1.0)) / 0.65; // 0..1
            baseHeight = 25.0 + t * 25.0; // 25..50
        } else if (cont < -0.15) {
            // Мелкий океан / склон шельфа
            double t = (cont - (-0.35)) / 0.20; // 0..1
            baseHeight = 50.0 + t * 11.0; // 50..61
        } else if (cont < 0.0) {
            // Побережье / пляжи
            double t = (cont - (-0.15)) / 0.15; // 0..1
            baseHeight = 61.0 + t * 5.0; // 61..66
        } else {
            // Внутренняя суша
            double t = Math.min(1.0, cont / 0.7);
            baseHeight = 66.0 + t * 20.0; // 66..86

            // Влияние эрозии на суше: низкая эрозия -> горы
            if (eros < 0.0) {
                double mountainFactor = -eros; // 0..1
                baseHeight += mountainFactor * 80.0 * (0.5 + 0.5 * fbm);
            }
        }

        // Детализация рельефа
        double h = baseHeight + (fbm * 12.0) + detail;

        int finalH = (int) Math.round(h);
        if (finalH < MIN_Y + BEDROCK_LAYERS + 1) finalH = MIN_Y + BEDROCK_LAYERS + 1;
        if (finalH > MAX_Y - 5) finalH = MAX_Y - 5;
        return finalH;
    }

    /**
     * Верхняя граница (включая воду) для WORLD_SURFACE_WG.
     */
    public int getTopmostHeight(int wx, int wz) {
        int solid = getHeight(wx, wz);
        return Math.max(solid, SEA_LEVEL);
    }

    /**
     * Заполняет твёрдую толщу Layer 1 камнем/глубинным сланцем, бедроком и водой до sea_level.
     */
    public void fillTerrain(SectionDirectChunkWriter writer, int chunkX, int chunkZ) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;

                int surfaceY = getHeight(wx, wz);

                // Бедрок на дне (0..4 слоя от MIN_Y)
                for (int y = MIN_Y; y < MIN_Y + BEDROCK_LAYERS; y++) {
                    if (y == MIN_Y) {
                        writer.setBlockState(wx, y, wz, BS_BEDROCK);
                    } else {
                        // Плавный переход бедрока
                        double r = detailNoise.noise2D(wx * 0.5, wz * 0.5 + y * 13.0);
                        writer.setBlockState(wx, y, wz, (r > 0.0) ? BS_BEDROCK : BS_DEEPSLATE);
                    }
                }

                // Каменная толща: deepslate ниже Y=0, stone выше Y=0
                for (int y = MIN_Y + BEDROCK_LAYERS; y <= surfaceY; y++) {
                    if (y < -8) {
                        writer.setBlockState(wx, y, wz, BS_DEEPSLATE);
                    } else if (y <= 0) {
                        double r = detailNoise.noise2D(wx * 0.1, wz * 0.1 + y * 0.2);
                        writer.setBlockState(wx, y, wz, (r > 0.0) ? BS_DEEPSLATE : BS_STONE);
                    } else {
                        writer.setBlockState(wx, y, wz, BS_STONE);
                    }
                }

                // Вода до SEA_LEVEL
                if (surfaceY < SEA_LEVEL) {
                    for (int y = surfaceY + 1; y <= SEA_LEVEL; y++) {
                        writer.setBlockState(wx, y, wz, BS_WATER);
                    }
                }
            }
        }
    }

    /**
     * Накладывает слой поверхности в зависимости от биома (песок, трава, снег, терракота и т.д.).
     */
    public void buildSurface(ChunkAccess chunk, BiFunction<Integer, Integer, Holder<Biome>> biomeGetter) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;

                int surfaceY = getHeight(wx, wz);
                Holder<Biome> biomeHolder = biomeGetter.apply(wx, wz);
                String biomePath = biomeHolder.unwrapKey().map(k -> k.location().getPath()).orElse("plains");

                boolean underWater = surfaceY < SEA_LEVEL;

                BlockState topBlock = BS_GRASS;
                BlockState underBlock = BS_DIRT;

                if (underWater) {
                    if (surfaceY <= SEA_LEVEL - 8) {
                        // Глубокое дно — гравий или песок
                        topBlock = BS_GRAVEL;
                        underBlock = BS_GRAVEL;
                    } else {
                        // Мелководье — песок
                        topBlock = BS_SAND;
                        underBlock = BS_SANDSTONE;
                    }
                } else if (biomePath.contains("desert")) {
                    topBlock = BS_SAND;
                    underBlock = BS_SANDSTONE;
                } else if (biomePath.contains("badlands")) {
                    topBlock = BS_RED_SAND;
                    underBlock = BS_TERRACOTTA;
                } else if (biomePath.contains("beach")) {
                    topBlock = BS_SAND;
                    underBlock = BS_SANDSTONE;
                } else if (biomePath.contains("stony")) {
                    topBlock = BS_STONE;
                    underBlock = BS_STONE;
                } else if (biomePath.contains("snowy") || biomePath.contains("frozen") || surfaceY >= 140) {
                    if (surfaceY >= 150) {
                        topBlock = BS_SNOW_BLOCK;
                        underBlock = BS_STONE;
                    } else {
                        topBlock = BS_GRASS;
                        underBlock = BS_DIRT;
                    }
                } else if (biomePath.contains("old_growth") || biomePath.contains("taiga")) {
                    topBlock = BS_PODZOL;
                    underBlock = BS_DIRT;
                }

                // Заменяем верхние 3-4 блока
                int depth = 3;
                pos.set(wx, surfaceY, wz);
                chunk.setBlockState(pos, topBlock, false);

                for (int d = 1; d <= depth; d++) {
                    int y = surfaceY - d;
                    if (y > MIN_Y + BEDROCK_LAYERS) {
                        pos.set(wx, y, wz);
                        chunk.setBlockState(pos, underBlock, false);
                    }
                }
            }
        }
    }
}
