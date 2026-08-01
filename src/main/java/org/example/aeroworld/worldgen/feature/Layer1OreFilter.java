package org.example.aeroworld.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Пост-генерационный фильтр руд.
 *
 * ВЫЗЫВАЕТСЯ ОДИН РАЗ на чанк — сразу после кастомной генерации в
 * applyBiomeDecoration, только для центрального (текущего) чанка.
 *
 * Генерация руды отключена во ВСЕХ кастомных слоях (Layer1..4 OreGenerator,
 * Layer1FlatGenerator), поэтому единственный источник руды, который теперь
 * может встретиться в чанке — это ванильные фичи Minecraft, размещаемые
 * внутри super.applyBiomeDecoration() (обычная генерация руды ванильного
 * оверворлда/этого измерения через biome features).
 *
 * Правило фильтрации теперь одно и простое:
 *   По ВСЕЙ высоте чанка (все 4 слоя измерения) удалить ЛЮБУЮ руду
 *   (включая ancient debris в незеровских диапазонах, если такие есть)
 *   и заменить её на камень/дипслейт в зависимости от Y.
 *
 * Каждая LevelChunkSection проверяется за O(1) через hasOnlyAir() —
 * пустые секции пропускаются мгновенно.
 */
public final class Layer1OreFilter {

    private static final BlockState BS_STONE     = Blocks.STONE    .defaultBlockState();
    private static final BlockState BS_DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    private Layer1OreFilter() {}

    /**
     * Фильтрует один чанк (центральный) по всей его высоте, удаляя любую руду.
     * Потокобезопасен — не читает и не пишет никакое разделяемое состояние.
     */
    public static void applyToChunk(ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;

        int sectionsCount = chunk.getSectionsCount();
        for (int si = 0; si < sectionsCount; si++) {
            LevelChunkSection section = chunk.getSection(si);
            if (section == null || section.hasOnlyAir()) continue;

            int secBaseY = chunk.getSectionYFromSectionIndex(si) << 4;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = secBaseY; y < secBaseY + 16; y++) {
                        pos.set(baseX + x, y, baseZ + z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) continue;

                        if (isOre(state)) {
                            chunk.setBlockState(pos, stoneReplacement(y), false);
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isOre(BlockState s) {
        return s.is(Blocks.COAL_ORE)          || s.is(Blocks.DEEPSLATE_COAL_ORE)
            || s.is(Blocks.IRON_ORE)           || s.is(Blocks.DEEPSLATE_IRON_ORE)
            || s.is(Blocks.COPPER_ORE)         || s.is(Blocks.DEEPSLATE_COPPER_ORE)
            || s.is(Blocks.GOLD_ORE)           || s.is(Blocks.DEEPSLATE_GOLD_ORE)
            || s.is(Blocks.REDSTONE_ORE)       || s.is(Blocks.DEEPSLATE_REDSTONE_ORE)
            || s.is(Blocks.DIAMOND_ORE)        || s.is(Blocks.DEEPSLATE_DIAMOND_ORE)
            || s.is(Blocks.LAPIS_ORE)          || s.is(Blocks.DEEPSLATE_LAPIS_ORE)
            || s.is(Blocks.EMERALD_ORE)        || s.is(Blocks.DEEPSLATE_EMERALD_ORE)
            || s.is(Blocks.ANCIENT_DEBRIS)
            || s.is(Blocks.NETHER_QUARTZ_ORE)  || s.is(Blocks.NETHER_GOLD_ORE);
    }

    private static BlockState stoneReplacement(int y) {
        return y < 0 ? BS_DEEPSLATE : BS_STONE;
    }
}
