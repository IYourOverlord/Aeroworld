package org.example.aeroworld.worldgen.feature;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Генератор руд для 1-го слоя (Y -64..50).
 *
 * Полностью заменяет ванильную генерацию — вызывается из applyBiomeDecoration
 * ПОСЛЕ super.applyBiomeDecoration() и ПЕРЕД Layer1OreFilter.applyToChunk().
 *
 * Разрешённые руды в слое 1:
 *   уголь, железо, медь, золото
 *
 * Запрещены (не генерируются здесь, Layer1OreFilter подчищает ванильный остаток):
 *   алмаз, лазурит, изумруд, редстоун
 *
 * Все veinSize > 0 — при veinSize=0 OreVeinHelper всё равно ставит центральный
 * блок (radius≈0, петля с r=0 выполняется один раз), что неверно.
 */
public class Layer1OreGenerator {

    private static final BlockState BS_COAL_ORE   = Blocks.COAL_ORE          .defaultBlockState();
    private static final BlockState BS_IRON_ORE   = Blocks.IRON_ORE          .defaultBlockState();
    private static final BlockState BS_DS_IRON    = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
    private static final BlockState BS_COPPER_ORE = Blocks.COPPER_ORE        .defaultBlockState();
    private static final BlockState BS_GOLD_ORE   = Blocks.GOLD_ORE          .defaultBlockState();
    private static final BlockState BS_DS_GOLD    = Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();

    // ── Границы слоя ─────────────────────────────────────────────────────────
    public static final int BASE_Y    = -40;
    public static final int TOP_Y     = 10;
    private static final int DS_TOP_Y = 0;   // граница stone / deepslate

    // ── УГОЛЬ  (только stone-зона, Y 0..TOP_Y) ───────────────────────────────
    // Ванила: vein=17, attempts≈20; сужаем диапазон → поднимаем attempts
    private static final int COAL_VEIN     = 9;
    private static final int COAL_ATTEMPTS = 24;
    private static final int COAL_MIN_Y    = 0;
    private static final int COAL_MAX_Y    = TOP_Y;

    // ── ЖЕЛЕЗО (2 зоны) ───────────────────────────────────────────────────────
    private static final int IRON_VEIN            = 4;
    private static final int IRON_UPPER_ATTEMPTS  = 4;   // stone-зона
    private static final int IRON_UPPER_MIN_Y     = -16;
    private static final int IRON_UPPER_MAX_Y     = TOP_Y;

    private static final int IRON_LOWER_ATTEMPTS  = 3;   // deepslate-зона
    private static final int IRON_LOWER_MIN_Y     = BASE_Y;
    private static final int IRON_LOWER_MAX_Y     = -32;

    // ── МЕДЬ  (stone-зона, Y 0..TOP_Y) ───────────────────────────────────────
    private static final int COPPER_VEIN     = 4;
    private static final int COPPER_ATTEMPTS = 3;
    private static final int COPPER_MIN_Y    = 0;
    private static final int COPPER_MAX_Y    = TOP_Y;

    // ── ЗОЛОТО (2 зоны) ───────────────────────────────────────────────────────
    private static final int GOLD_VEIN           = 3;
    private static final int GOLD_UPPER_ATTEMPTS = 2;
    private static final int GOLD_UPPER_MIN_Y    = -16;
    private static final int GOLD_UPPER_MAX_Y    = TOP_Y;

    private static final int GOLD_LOWER_ATTEMPTS = 2;
    private static final int GOLD_LOWER_MIN_Y    = BASE_Y;
    private static final int GOLD_LOWER_MAX_Y    = -32;

    // ─────────────────────────────────────────────────────────────────────────

    public void generateOres(ChunkAccess chunk, RandomSource random, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Уголь
        placeVeins(chunk, random, baseX, baseZ,
                BS_COAL_ORE,
                COAL_ATTEMPTS, COAL_VEIN, COAL_MIN_Y, COAL_MAX_Y);

        // Железо (2 зоны)
        placeVeins(chunk, random, baseX, baseZ,
                BS_IRON_ORE,
                IRON_UPPER_ATTEMPTS, IRON_VEIN, IRON_UPPER_MIN_Y, IRON_UPPER_MAX_Y);
        placeVeins(chunk, random, baseX, baseZ,
                BS_DS_IRON,
                IRON_LOWER_ATTEMPTS, IRON_VEIN, IRON_LOWER_MIN_Y, IRON_LOWER_MAX_Y);

        // Медь
        placeVeins(chunk, random, baseX, baseZ,
                BS_COPPER_ORE,
                COPPER_ATTEMPTS, COPPER_VEIN, COPPER_MIN_Y, COPPER_MAX_Y);

        // Золото (2 зоны)
        placeVeins(chunk, random, baseX, baseZ,
                BS_GOLD_ORE,
                GOLD_UPPER_ATTEMPTS, GOLD_VEIN, GOLD_UPPER_MIN_Y, GOLD_UPPER_MAX_Y);
        placeVeins(chunk, random, baseX, baseZ,
                BS_DS_GOLD,
                GOLD_LOWER_ATTEMPTS, GOLD_VEIN, GOLD_LOWER_MIN_Y, GOLD_LOWER_MAX_Y);

        // Алмаз, лазурит, изумруд, редстоун — НЕ генерируем в слое 1.
        // Ванильный остаток (от super.applyBiomeDecoration) будет удалён
        // Layer1OreFilter.applyToChunk() который вызывается сразу после нас.
    }

    private void placeVeins(ChunkAccess chunk, RandomSource random,
                             int baseX, int baseZ,
                             BlockState ore, int attempts, int veinSize,
                             int minY, int maxY) {
        int range = Math.max(1, maxY - minY);
        for (int i = 0; i < attempts; i++) {
            int x = baseX + random.nextInt(16);
            int y = minY  + random.nextInt(range);
            int z = baseZ + random.nextInt(16);
            OreVeinHelper.placeVein(chunk, random, x, y, z, ore, veinSize);
        }
    }
}
