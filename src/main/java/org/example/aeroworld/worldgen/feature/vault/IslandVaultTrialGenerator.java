package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.noise.IslandShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Универсальный генератор Vault/Trial Spawner внутри тела острова.
 *
 * <h3>Архитектурная цель</h3>
 * Класс не знает ничего про конкретный слой (Layer 2, 3, 4...). Всё, что нужно
 * для генерации на новом слое с другим дропом — это:
 * <ol>
 *   <li>новые loot table JSON (см. {@code data/aeroworld/loot_table/gameplay/...})</li>
 *   <li>новый {@link VaultTrialLootConfig} с их id;</li>
 *   <li>вызов {@link #placeForIsland} из точки декорации нужного слоя,
 *       передав {@link IslandShape} этого слоя и параметры тела острова.</li>
 * </ol>
 *
 * <h3>Почему "внутри самого верхнего слоя острова", а не на поверхности</h3>
 * Блоки замуровываются в каменном ядре острова, на 2–6 блоков ниже поверхности
 * (см. {@link #SURFACE_MIN_DEPTH}/{@link #SURFACE_MAX_DEPTH}) — то есть под травой/
 * землёй, внутри камня, а не поставлены сверху острова как декорация. Найти их
 * можно, только раскопав/прокопав остров.
 *
 * <h3>Почему запись через {@link WorldGenLevel}, а не {@link net.minecraft.world.level.chunk.ChunkAccess}</h3>
 * Vault и Trial Spawner — блоки с {@link BlockEntity} (NBT-конфиг loot table).
 * Корректная инициализация NBT блок-сущности требует {@code registryAccess()}
 * (для {@link BlockEntity#loadWithComponents}), которого нет на этапе
 * {@code fillChunk}/{@code ChunkAccess}. Поэтому размещение выполняется в фазе
 * decoration ({@code applyBiomeDecoration}), как и деревья ({@code placeTreesInRegion}).
 */
public final class IslandVaultTrialGenerator {

    private static final BlockState BS_VAULT         = Blocks.VAULT.defaultBlockState();
    private static final BlockState BS_TRIAL_SPAWNER  = Blocks.TRIAL_SPAWNER.defaultBlockState();

    /** Минимальная и максимальная глубина замуровки под верхней поверхностью острова. */
    private static final int SURFACE_MIN_DEPTH = 2;
    private static final int SURFACE_MAX_DEPTH = 6;

    /** Сколько раз пытаемся найти валидную точку на острове, прежде чем отказаться от одной структуры. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 48;

    /** Минимальное расстояние (в блоках) между уже поставленными Vault/Trial на одном острове. */
    private static final double MIN_SPACING = 6.0;

    private IslandVaultTrialGenerator() {
    }

    /**
     * Размещает Vault и Trial Spawner для одного острова согласно выбранному тиру.
     *
     * @param region     регион генерации (для записи blockEntity с NBT)
     * @param shape      форма острова текущего слоя (та же математика, что и в fillChunk слоя)
     * @param island     кэшированные данные острова (bounds, radius, форма)
     * @param noiseDeform деформация края острова (та же константа, что использует fillChunk слоя)
     * @param tier       категория богатства спавна для этого острова
     * @param loot       конфиг loot table (специфичен для слоя/дропа)
     * @param rng        детерминированный источник случайности (по острову, не по чанку!)
     */
    public static void placeForIsland(WorldGenLevel region,
                                       IslandShape shape,
                                       IslandData island,
                                       double noiseDeform,
                                       VaultTrialSpawnTier tier,
                                       VaultTrialLootConfig loot,
                                       RandomSource rng) {

        List<BlockPos> placed = new ArrayList<>(tier.vaultCount() + tier.trialSpawnerCount());

        for (int i = 0; i < tier.vaultCount(); i++) {
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, placed);
            if (pos == null) continue;
            placeVault(region, pos, loot.vaultLootNormal(), rng);
            placed.add(pos);
        }

        for (int i = 0; i < tier.trialSpawnerCount(); i++) {
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, placed);
            if (pos == null) continue;
            placeTrialSpawner(region, pos, loot, rng);
            placed.add(pos);
        }

        AeroWorld.LOGGER.debug(
                "[AeroWorld] IslandVaultTrialGenerator: island ({},{}) tier={} placed {} structure(s).",
                island.cx, island.cz, tier, placed.size());
    }

    // ── Поиск точки внутри тела острова ────────────────────────────────────────

    /**
     * Ищет случайную точку внутри каменного ядра острова, под поверхностью на
     * глубину {@link #SURFACE_MIN_DEPTH}..{@link #SURFACE_MAX_DEPTH}, достаточно
     * далеко от уже размещённых точек этого острова.
     *
     * <p>Использует ту же {@link IslandShape#isSolid} математику, что и fillChunk
     * слоя, поэтому гарантированно попадает внутрь реального тела острова
     * (с учётом деформации края), а не в пустоту рядом с ним.</p>
     */
    private static BlockPos findBuriedSpot(WorldGenLevel region,
                                            IslandShape shape,
                                            IslandData island,
                                            double noiseDeform,
                                            RandomSource rng,
                                            List<BlockPos> alreadyPlaced) {

        // Ограничиваем поиск внутренними ~70% радиуса, чтобы не задевать тонкий
        // деформированный край острова (там мало каменной толщи для замуровки).
        double innerRadius = island.radius * 0.7;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dist  = rng.nextDouble() * innerRadius;
            int wx = island.cx + (int) Math.round(Math.cos(angle) * dist);
            int wz = island.cz + (int) Math.round(Math.sin(angle) * dist);

            IslandShape.XZCache xz = shape.precomputeXZ(
                    wx, wz, island.cx, island.cz, island.radius, noiseDeform,
                    island.shapeNoiseIntensity, island.shapeProfile);

            // Верхняя поверхность в этой XZ-колонке (бинарный поиск, как getDeformedTopY).
            if (!shape.isSolid(island.topY, island.bottomY, island.topY, xz)) continue;
            int surfaceY = binarySearchTopSurface(shape, island, xz);
            if (surfaceY < island.bottomY) continue;

            int depth = SURFACE_MIN_DEPTH + rng.nextInt(SURFACE_MAX_DEPTH - SURFACE_MIN_DEPTH + 1);
            int wy = surfaceY - depth;
            if (wy <= island.bottomY) continue;

            // Точка должна быть твёрдой (внутри камня), не пустотой под тонким сводом.
            if (!shape.isSolid(wy, island.bottomY, island.topY, xz)) continue;
            if (!shape.isSolid(wy - 1, island.bottomY, island.topY, xz)) continue;
            if (!shape.isSolid(wy + 1, island.bottomY, island.topY, xz)) continue;

            BlockPos candidate = new BlockPos(wx, wy, wz);
            if (tooClose(candidate, alreadyPlaced)) continue;

            return candidate;
        }
        return null;
    }

    private static boolean tooClose(BlockPos candidate, List<BlockPos> placed) {
        for (BlockPos p : placed) {
            if (p.distSqr(candidate) < MIN_SPACING * MIN_SPACING) return true;
        }
        return false;
    }

    private static int binarySearchTopSurface(IslandShape shape, IslandData island, IslandShape.XZCache xz) {
        int lo = island.bottomY, hi = island.topY;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (shape.isSolid(mid, island.bottomY, island.topY, xz)) lo = mid;
            else hi = mid - 1;
        }
        if (shape.isSolid(lo + 1, island.bottomY, island.topY, xz)) return island.bottomY - 1; // не поверхность
        return lo;
    }

    // ── Постановка блоков ────────────────────────────────────────────────────

    private static void placeVault(WorldGenLevel region, BlockPos pos,
                                    ResourceLocation lootNormal, RandomSource rng) {
        region.setBlock(pos, BS_VAULT, 3);

        CompoundTag config = new CompoundTag();
        config.putString("loot_table", lootNormal.toString());

        CompoundTag root = new CompoundTag();
        root.put("config", config);
        // "server_data.loot_table_to_eject_when_ominous" не заполняем: чтение
        // произойдёт только когда рядом окажется игрок с Bad Omen — ванильная
        // логика Vault сама подставит ominous-лут при активации. Здесь достаточно
        // указать base loot_table; для ominous-варианта данный остров может
        // (опционально) переопределить loot через отдельный вызов placeVault
        // с другим ResourceLocation при более высоком тире — см. вызывающий код.

        applyBlockEntityTag(region, pos, root);
    }

    private static void placeTrialSpawner(WorldGenLevel region, BlockPos pos,
                                           VaultTrialLootConfig loot, RandomSource rng) {
        region.setBlock(pos, BS_TRIAL_SPAWNER, 3);

        CompoundTag root = new CompoundTag();

        CompoundTag normalConfig = new CompoundTag();
        normalConfig.put("loot_tables_to_eject", lootTablesToEjectList(loot.trialLootNormal()));

        CompoundTag ominousConfig = new CompoundTag();
        ominousConfig.put("loot_tables_to_eject", lootTablesToEjectList(loot.trialLootOminous()));

        root.put("normal_config", normalConfig);
        root.put("ominous_config", ominousConfig);

        applyBlockEntityTag(region, pos, root);
    }

    private static net.minecraft.nbt.ListTag lootTablesToEjectList(ResourceLocation lootTable) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("data", lootTable.toString());
        entry.putInt("weight", 1);
        list.add(entry);
        return list;
    }

    /**
     * Сливает NBT-тег в только что поставленный blockEntity.
     *
     * <p>{@code EntityBlock.newBlockEntity} + {@code loadWithComponents} — способ,
     * не зависящий от конкретного класса blockEntity (VaultBlockEntity/
     * TrialSpawnerBlockEntity), поэтому переиспользуем для обоих блоков и для
     * любых будущих блок-сущностей без изменения этого метода.</p>
     */
    private static void applyBlockEntityTag(WorldGenLevel region, BlockPos pos, CompoundTag tag) {
        BlockEntity be = region.getBlockEntity(pos);
        if (be == null) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld] IslandVaultTrialGenerator: blockEntity is null at {} after setBlock — skipped NBT.",
                    pos);
            return;
        }
        be.loadWithComponents(tag, region.registryAccess());
        be.setChanged();
    }
}
