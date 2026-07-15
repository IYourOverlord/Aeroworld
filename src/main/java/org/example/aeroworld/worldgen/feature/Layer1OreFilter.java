package org.example.aeroworld.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Пост-генерационный фильтр руд.
 *
 * ВЫЗЫВАЕТСЯ ОДИН РАЗ на чанк — сразу после layer*Ores.generateOres()
 * в applyBiomeDecoration, только для центрального (текущего) чанка.
 *
 * Прежняя схема «сканируем 3×3 соседей + Set для дедупликации» порождала
 * race condition: сосед добавлялся в Set когда был соседом, а когда позже
 * становился центром — его собственные руды уже лежали необработанными,
 * но Set говорил «уже обработан». Итог — запрещённые руды оставались.
 *
 * Новая схема проще и надёжнее:
 *   • layer*Ores.generateOres() пишут ТОЛЬКО в пределах своего чанка
 *     (OreVeinHelper уже обрезает выход за границу XZ).
 *   • Поэтому фильтровать соседей не нужно — каждый чанк фильтрует сам себя.
 *
 * Правила фильтрации:
 *   Слой 1  (Y  -64 ..   50): удалить алмаз / лазурит / изумруд / редстоун
 *   Слой 2  (Y  300 ..  400): удалить алмаз / лазурит / изумруд
 *   Слой 3  (Y 1000 .. 1100): удалить алмаз / лазурит / изумруд
 *   Слой 4  (Y 1900 .. 2031): удалить всё КРОМЕ алмаза / лазурита / изумруда
 *
 * Каждая LevelChunkSection проверяется за O(1) через hasOnlyAir() —
 * пустые секции пропускаются мгновенно.
 */
public final class Layer1OreFilter {

    private static final BlockState BS_STONE     = Blocks.STONE    .defaultBlockState();
    private static final BlockState BS_DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    // ── Диапазоны где запрещены алмаз/лазурит/изумруд И редстоун (слой 1) ──
    private record BannedRange(int minY, int maxY, boolean banRedstone) {}

    private static final BannedRange[] BANNED_RANGES = {
        new BannedRange( -64,   50, true ),   // Слой 1 — редстоун тоже запрещён
        new BannedRange( 300,  400, false),   // Слой 2
        new BannedRange(1000, 1100, false),   // Слой 3
    };

    // Слой 4 — разрешены ТОЛЬКО алмаз/лазурит/изумруд
    private static final int LAYER4_MIN_Y = 1900;
    private static final int LAYER4_MAX_Y = 2031;

    private Layer1OreFilter() {}

    /**
     * Фильтрует один чанк (центральный) без каких-либо Set'ов.
     * Потокобезопасен — не читает и не пишет никакое разделяемое состояние.
     */
    public static void applyToChunk(ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;

        for (BannedRange range : BANNED_RANGES) {
            scanBannedRange(chunk, pos, baseX, baseZ, range);
        }
        scanLayer4(chunk, pos, baseX, baseZ);
    }

    // ── Внутренние методы ─────────────────────────────────────────────────────

    private static void scanBannedRange(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                         int baseX, int baseZ, BannedRange range) {
        int minSec = sectionIndex(chunk, range.minY);
        int maxSec = sectionIndex(chunk, range.maxY);

        for (int si = minSec; si <= maxSec; si++) {
            LevelChunkSection section = chunk.getSection(si);
            if (section == null || section.hasOnlyAir()) continue;

            int secBaseY = chunk.getSectionYFromSectionIndex(si) << 4;
            int yFrom    = Math.max(range.minY, secBaseY);
            int yTo      = Math.min(range.maxY, secBaseY + 15);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = yFrom; y <= yTo; y++) {
                        pos.set(baseX + x, y, baseZ + z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) continue;

                        if (isPrecious(state) || (range.banRedstone && isRedstone(state))) {
                            chunk.setBlockState(pos, stoneReplacement(y), false);
                        }
                    }
                }
            }
        }
    }

    private static void scanLayer4(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                    int baseX, int baseZ) {
        int minSec = sectionIndex(chunk, LAYER4_MIN_Y);
        int maxSec = sectionIndex(chunk, LAYER4_MAX_Y);

        for (int si = minSec; si <= maxSec; si++) {
            LevelChunkSection section = chunk.getSection(si);
            if (section == null || section.hasOnlyAir()) continue;

            int secBaseY = chunk.getSectionYFromSectionIndex(si) << 4;
            int yFrom    = Math.max(LAYER4_MIN_Y, secBaseY);
            int yTo      = Math.min(LAYER4_MAX_Y, secBaseY + 15);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = yFrom; y <= yTo; y++) {
                        pos.set(baseX + x, y, baseZ + z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) continue;

                        // В слое 4 удаляем всё кроме алмаза/лазурита/изумруда
                        if (isOre(state) && !isPrecious(state)) {
                            chunk.setBlockState(pos, BS_STONE, false);
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int sectionIndex(ChunkAccess chunk, int y) {
        int idx = chunk.getSectionIndex(y);
        if (idx < 0) return 0;
        if (idx >= chunk.getSectionsCount()) return chunk.getSectionsCount() - 1;
        return idx;
    }

    private static boolean isPrecious(BlockState s) {
        return s.is(Blocks.DIAMOND_ORE)    || s.is(Blocks.DEEPSLATE_DIAMOND_ORE)
            || s.is(Blocks.LAPIS_ORE)      || s.is(Blocks.DEEPSLATE_LAPIS_ORE)
            || s.is(Blocks.EMERALD_ORE)    || s.is(Blocks.DEEPSLATE_EMERALD_ORE);
    }

    private static boolean isRedstone(BlockState s) {
        return s.is(Blocks.REDSTONE_ORE) || s.is(Blocks.DEEPSLATE_REDSTONE_ORE);
    }

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
