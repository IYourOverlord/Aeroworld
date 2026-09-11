package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.noise.AeroNoise;
import org.example.aeroworld.worldgen.util.ChunkWriter;

import java.util.function.BiFunction;

/**
 * Layer1TerrainGenerator — кастомная генерация рельефа и поверхности для Layer 1 (-64..300).
 * Заменяет ванильный NoiseBasedChunkGenerator для рельефа, сохраняя детерминированность от seed.
 */
public class Layer1TerrainGenerator {

    public static final int SEA_LEVEL = 0;
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
    private static final BlockState BS_CALCITE = Blocks.CALCITE.defaultBlockState();
    private static final BlockState BS_BASALT = Blocks.BASALT.defaultBlockState();
    private static final BlockState BS_BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();

    private static final BlockState BS_SEAGRASS = Blocks.SEAGRASS.defaultBlockState();
    private static final BlockState BS_KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();
    private static final BlockState BS_KELP = Blocks.KELP.defaultBlockState();

    private static final BlockState[] BS_CORAL_BLOCKS = {
            Blocks.TUBE_CORAL_BLOCK.defaultBlockState(),
            Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(),
            Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState(),
            Blocks.FIRE_CORAL_BLOCK.defaultBlockState(),
            Blocks.HORN_CORAL_BLOCK.defaultBlockState()
    };

    private static final BlockState[] BS_CORALS = {
            Blocks.TUBE_CORAL.defaultBlockState(),
            Blocks.BRAIN_CORAL.defaultBlockState(),
            Blocks.BUBBLE_CORAL.defaultBlockState(),
            Blocks.FIRE_CORAL.defaultBlockState(),
            Blocks.HORN_CORAL.defaultBlockState()
    };

    private static final BlockState[] BS_CORAL_FANS = {
            Blocks.TUBE_CORAL_FAN.defaultBlockState(),
            Blocks.BRAIN_CORAL_FAN.defaultBlockState(),
            Blocks.BUBBLE_CORAL_FAN.defaultBlockState(),
            Blocks.FIRE_CORAL_FAN.defaultBlockState(),
            Blocks.HORN_CORAL_FAN.defaultBlockState()
    };

    public static final int CAVE_BOTTOM_Y = -50;
    public static final int CAVE_TOP_Y = -25;
    private static final double CAVE_MID_Y = (CAVE_TOP_Y + CAVE_BOTTOM_Y) / 2.0; // -37.5

    private final long seed;
    private final AeroNoise continentNoise;
    private final AeroNoise erosionNoise;
    private final AeroNoise heightNoise;
    private final AeroNoise detailNoise;
    private final AeroNoise caveCeilingNoise;
    private final AeroNoise caveFloorNoise;
    private final AeroNoise caveLushNoise;
    private final AeroNoise oceanVegNoise;
    private final AeroNoise coralNoise;
    private final AeroNoise ridgeNoise;
    private final AeroNoise riverNoise;
    private final AeroNoise riverWarpNoise;

    public Layer1TerrainGenerator(long seed) {
        this.seed = seed;
        this.continentNoise   = new AeroNoise(seed ^ 0x1A2B3C4DL);
        this.erosionNoise     = new AeroNoise(seed ^ 0x5E6F7A8BL);
        this.heightNoise      = new AeroNoise(seed ^ 0x9C0D1E2FL);
        this.detailNoise      = new AeroNoise(seed ^ 0x33445566L);
        this.caveCeilingNoise = new AeroNoise(seed ^ 0xBBCCDDEEL);
        this.caveFloorNoise   = new AeroNoise(seed ^ 0x13579BDFL);
        this.caveLushNoise    = new AeroNoise(seed ^ 0x2468ACE0L);
        this.oceanVegNoise    = new AeroNoise(seed ^ 0x44556677L);
        this.coralNoise       = new AeroNoise(seed ^ 0x8899AABBL);
        this.ridgeNoise       = new AeroNoise(seed ^ 0x71D63A4BL);
        this.riverNoise       = new AeroNoise(seed ^ 0x4E9B17C3L);
        this.riverWarpNoise   = new AeroNoise(seed ^ 0xC3815F29L);
    }

    public long getSeed() {
        return seed;
    }

    /**
     * Проверяет, находится ли точка внутри полости гигантской пещеры (не под водой).
     */
    public boolean isCaveAir(int wx, int y, int wz, int surfaceY) {
        // Пещера генерируется только на суше (surfaceY >= SEA_LEVEL)
        if (surfaceY < SEA_LEVEL) return false;

        // Границы пещеры с легкой неровностью сводов
        double ceilingOffset = caveCeilingNoise.noise2D(wx * 0.03, wz * 0.03) * 2.0;
        int top = (int) Math.round(CAVE_TOP_Y + ceilingOffset);

        // Рельеф пола: крупные холмы/впадины (fbm, низкая частота) + мелкая деталь
        double floorHills = caveFloorNoise.fbm2D(wx * 0.015, wz * 0.015, 4, 2.0, 0.5) * 14.0;
        double floorDetail = caveFloorNoise.noise2D(wx * 0.08, wz * 0.08) * 3.0;
        int bottom = (int) Math.round(CAVE_BOTTOM_Y + floorHills + floorDetail);
        // Не даём полу подняться выше середины пещеры, сохраняя проходимость объёма
        bottom = Math.min(bottom, (int) Math.floor(CAVE_MID_Y - 3));

        return y >= bottom && y <= top;
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
     * Стандартная суша: в диапазоне Y от 2 до 18..20.
     * Океаны: от -50 до -1.
     * Горы (низкая эрозия): могут подниматься выше Y=20 (до 60..120+).
     */
    public int getHeight(int wx, int wz) {
        double continentality = getContinentality(wx, wz);
        double erosion = getErosion(wx, wz);
        double macro = heightNoise.fbm2D(wx * 0.003, wz * 0.003, 5, 2.0, 0.5);
        double detail = detailNoise.noise2D(wx * 0.02, wz * 0.02);

        double deepOcean = -47.0 + macro * 3.0 + detail * 0.75;
        double shelf = -8.0 + macro * 2.5 + detail;
        double beach = 2.0 + macro * 1.5 + detail;

        double continentalRise = smoothstep(0.0, 0.70, continentality) * 11.0;
        double mountainness = smoothstep(0.0, 0.55, -erosion);
        double mountainShape = 0.35 + 0.65 * ((macro + 1.0) * 0.5);
        double land = 3.0 + continentalRise + mountainness * 80.0 * mountainShape
                + macro * 4.0 + detail * 1.5;

        // Rare continent-scale ridge systems. The ridge field is sampled in world
        // coordinates and blended by continentality, so it cannot create chunk seams.
        double ridge = getRidgeStrength(wx, wz);
        double ridgeLandMask = smoothstep(0.04, 0.22, continentality);
        land += ridgeLandMask * ridge * (92.0 + 34.0 * mountainShape);

        double height = lerp(deepOcean, shelf, smoothstep(-0.42, -0.16, continentality));
        height = lerp(height, beach, smoothstep(-0.20, -0.02, continentality));
        height = lerp(height, land, smoothstep(-0.06, 0.10, continentality));

        // Domain-warped river valleys form continuous, naturally curved channels.
        // They are only cut through land; the centre is five to six blocks below sea level.
        double river = getRiverStrength(wx, wz);
        double riverLandMask = smoothstep(-0.01, 0.10, continentality);
        double riverBed = SEA_LEVEL - (5.0 + riverNoise.noise2D(wx * 0.018, wz * 0.018) * 0.5);
        height = lerp(height, Math.min(height, riverBed), river * riverLandMask);

        int finalHeight = (int) Math.round(height);
        return Math.max(MIN_Y + BEDROCK_LAYERS + 1, Math.min(220, finalHeight));
    }

    /** Returns the continuous ridge contribution used by terrain and biome selection. */
    public double getRidgeStrength(int wx, int wz) {
        double field = 1.0 - Math.abs(ridgeNoise.fbm2D(wx * 0.00125, wz * 0.00125, 4, 2.0, 0.5));
        return smoothstep(0.72, 0.90, field);
    }

    /** Returns the continuous river-channel mask; one equals the channel centre. */
    public double getRiverStrength(int wx, int wz) {
        double warpX = riverWarpNoise.fbm2D(wx * 0.0018 + 91.0, wz * 0.0018 - 37.0, 3, 2.0, 0.5) * 130.0;
        double warpZ = riverWarpNoise.fbm2D(wx * 0.0018 - 53.0, wz * 0.0018 + 71.0, 3, 2.0, 0.5) * 130.0;
        double channel = Math.abs(riverNoise.fbm2D((wx + warpX) * 0.0042, (wz + warpZ) * 0.0042, 3, 2.0, 0.5));
        return 1.0 - smoothstep(0.035, 0.075, channel);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
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
    public void fillTerrain(ChunkWriter writer, int chunkX, int chunkZ) {
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
                    // Проверяем полость гигантской пещеры:
                    // если это суша и точка попадает в пещеру (и не является столбом), то блок не ставим (оставляем воздух)
                    if (isCaveAir(wx, y, wz, surfaceY)) {
                        continue;
                    }

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

        decorateCaveCeiling(writer, chunkX, chunkZ);
        decorateCaveFloor(writer, chunkX, chunkZ);
    }

    /**
     * Декорирует свод гигантской пещеры под стилистику "пышных пещер":
     * мох на потолочном камне, светящийся лишайник и свисающие пещерные лианы
     * (с шансом светящихся ягод на конце) в полости под потолком.
     */
    private void decorateCaveCeiling(ChunkWriter writer, int chunkX, int chunkZ) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        BlockState mossBlock   = Blocks.MOSS_BLOCK.defaultBlockState();
        BlockState glowLichen  = Blocks.GLOW_LICHEN.defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        BlockState vineBerries = Blocks.CAVE_VINES.defaultBlockState()
                .setValue(BlockStateProperties.BERRIES, true);
        BlockState vineNoBerry = Blocks.CAVE_VINES.defaultBlockState()
                .setValue(BlockStateProperties.BERRIES, false);

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;

                int surfaceY = getHeight(wx, wz);
                if (surfaceY < SEA_LEVEL) continue; // пещера только на суше

                double ceilingOffset = caveCeilingNoise.noise2D(wx * 0.03, wz * 0.03) * 2.0;
                int top = (int) Math.round(CAVE_TOP_Y + ceilingOffset);

                // Потолок пещеры: блок камня прямо над полостью (top+1), только если сама
                // точка top действительно воздух пещеры (не столб).
                if (!isCaveAir(wx, top, wz, surfaceY)) continue;
                int ceilingBlockY = top + 1;
                if (ceilingBlockY > surfaceY) continue;

                double mossNoise = caveLushNoise.fbm2D(wx * 0.05, wz * 0.05, 2, 2.0, 0.5);
                if (mossNoise <= 0.05) continue; // пятнами, не сплошным ковром

                writer.setBlockState(wx, ceilingBlockY, wz, mossBlock);

                double lichenRoll = caveLushNoise.noise2D(wx * 0.11 + 100.0, wz * 0.11);
                if (lichenRoll > -0.2) {
                    writer.setBlockState(wx, top, wz, glowLichen);
                }

                double vineRoll = caveLushNoise.noise2D(wx * 0.17 + 500.0, wz * 0.17 + 500.0);
                if (vineRoll > 0.55) {
                    int vineLen = 1 + ((int) (Math.abs(vineRoll) * 100.0) % 4); // 1..4 сегмента
                    for (int i = 0; i < vineLen; i++) {
                        int vy = top - 1 - i;
                        if (!isCaveAir(wx, vy, wz, surfaceY)) break;
                        boolean isLast = i == vineLen - 1;
                        writer.setBlockState(wx, vy, wz, isLast ? vineBerries : vineNoBerry);
                    }
                }
            }
        }
    }

    /**
     * Декорирует пол гигантской пещеры светящимся лишайником (грань UP) поверх блока пола.
     */
    private void decorateCaveFloor(ChunkWriter writer, int chunkX, int chunkZ) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        BlockState glowLichenUp = Blocks.GLOW_LICHEN.defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;

                int surfaceY = getHeight(wx, wz);
                if (surfaceY < SEA_LEVEL) continue; // пещера только на суше

                double floorHills = caveFloorNoise.fbm2D(wx * 0.015, wz * 0.015, 4, 2.0, 0.5) * 14.0;
                double floorDetail = caveFloorNoise.noise2D(wx * 0.08, wz * 0.08) * 3.0;
                int bottom = (int) Math.round(CAVE_BOTTOM_Y + floorHills + floorDetail);
                bottom = Math.min(bottom, (int) Math.floor(CAVE_MID_Y - 3));

                if (!isCaveAir(wx, bottom, wz, surfaceY)) continue;
                if (bottom - 1 < MIN_Y + BEDROCK_LAYERS) continue;

                double lichenRoll = caveLushNoise.noise2D(wx * 0.11 + 900.0, wz * 0.11 + 900.0);
                if (lichenRoll > -0.2) {
                    writer.setBlockState(wx, bottom, wz, glowLichenUp);
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
                } else if (biomePath.contains("snowy") || biomePath.contains("frozen") || surfaceY >= 50) {
                    if (surfaceY >= 60) {
                        topBlock = BS_SNOW_BLOCK;
                        underBlock = BS_STONE;
                    } else {
                        topBlock = BS_GRASS;
                        underBlock = BS_DIRT;
                    }
                } else if (biomePath.contains("volcanic")) {
                    topBlock = BS_BASALT;
                    underBlock = BS_BLACKSTONE;
                } else if (biomePath.contains("karst")) {
                    topBlock = BS_CALCITE;
                    underBlock = BS_STONE;
                } else if (biomePath.contains("heather") || biomePath.contains("old_growth") || biomePath.contains("taiga")) {
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

                // Подводная растительность и коралловые рифы
                if (underWater && surfaceY < SEA_LEVEL - 1) {
                    placeUnderwaterFeatures(chunk, wx, surfaceY, wz, biomePath);
                }
            }
        }
    }

    private void placeUnderwaterFeatures(ChunkAccess chunk, int wx, int surfaceY, int wz, String biomePath) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int waterDepth = SEA_LEVEL - surfaceY;

        // 1. Коралловые рифы (в теплых океанах, warm_ocean или lukwarm_ocean, на глубине от 4 до 35 блоков)
        boolean isWarmOcean = biomePath.contains("warm");
        if (isWarmOcean && waterDepth >= 4) {
            double cNoise = coralNoise.fbm2D(wx * 0.05, wz * 0.05, 3, 2.0, 0.5);
            if (cNoise > 0.40) {
                int coralTypeIdx = Math.abs((int) (coralNoise.noise2D(wx * 0.2, wz * 0.2) * 10)) % BS_CORAL_BLOCKS.length;
                int reefHeight = 1 + (int) ((cNoise - 0.40) * 10.0);
                reefHeight = Math.min(reefHeight, waterDepth - 2);

                for (int h = 1; h <= reefHeight; h++) {
                    pos.set(wx, surfaceY + h, wz);
                    chunk.setBlockState(pos, BS_CORAL_BLOCKS[coralTypeIdx], false);
                }

                int topY = surfaceY + reefHeight + 1;
                if (topY < SEA_LEVEL) {
                    pos.set(wx, topY, wz);
                    BlockState decor = (cNoise > 0.55) ? BS_CORALS[coralTypeIdx] : BS_CORAL_FANS[coralTypeIdx];
                    chunk.setBlockState(pos, decor, false);
                }
                return;
            }
        }

        // 2. Ламинарии (Kelp) — растут высокими стеблями на глубинах от 6 блоков
        double veg = oceanVegNoise.fbm2D(wx * 0.03, wz * 0.03, 3, 2.0, 0.5);
        if (veg > 0.35 && waterDepth >= 6 && !biomePath.contains("frozen")) {
            int kelpHeight = 3 + (int) (detailNoise.noise2D(wx * 0.1, wz * 0.1) * 8.0);
            kelpHeight = Math.max(2, Math.min(kelpHeight, waterDepth - 2));

            for (int k = 1; k < kelpHeight; k++) {
                pos.set(wx, surfaceY + k, wz);
                chunk.setBlockState(pos, BS_KELP_PLANT, false);
            }
            pos.set(wx, surfaceY + kelpHeight, wz);
            chunk.setBlockState(pos, BS_KELP, false);
            return;
        }

        // 3. Морская трава / водоросли (Seagrass) — на умеренных и мелких глубинах
        if (veg > 0.10 && surfaceY + 1 < SEA_LEVEL) {
            pos.set(wx, surfaceY + 1, wz);
            chunk.setBlockState(pos, BS_SEAGRASS, false);
        }
    }
}