package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
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
 * <h3>Почему "рядом с поверхностью острова, а не сверху на ней"</h3>
 * Блок "врыт" в землю на {@link #BURY_DEPTH} блок — верхняя грань стоит на
 * уровне поверхности острова, а над ней и вокруг расчищена небольшая площадка
 * воздуха (см. {@link #clearAboveBlock}). Это не декорация поверх острова
 * (блок не "парит" над землёй) и не замуровка в толще камня — он врыт вровень
 * с землёй и виден сверху, как и природные Vault/Trial Spawner в trial chambers.
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

    /** Насколько глубоко "врыт" блок: 1 — верх блока на уровне земли, сам блок в предыдущем слое почвы. */
    private static final int BURY_DEPTH = 1;

    /** Сколько блоков воздуха расчищаем над поставленным блоком (высота самого блока + запас). */
    private static final int OPEN_ABOVE_BLOCKS = 3;

    /** Радиус расчищаемой площадки вокруг блока по X/Z — нужен для спавна мобов Trial Spawner. */
    private static final int CLEAR_RADIUS = 2;

    /** Сколько раз пытаемся найти валидную точку на острове, прежде чем отказаться от одной структуры. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 48;

    /** Минимальное расстояние (в блоках) между уже поставленными Vault/Trial на одном острове. */
    private static final double MIN_SPACING = 6.0;

    /**
     * Безопасный отступ (в блоках) от границы чанка, вызвавшего декорацию.
     * Кандидатная точка обязана лежать строго внутри {@code [chunkMinX + margin, chunkMaxX - margin]}
     * (аналогично по Z), где margin учитывает {@link #CLEAR_RADIUS} расчистки площадки
     * вокруг блока — иначе {@link #clearAboveBlock} и последующая запись blockEntity
     * могут задеть соседний чанк, который на момент вызова {@code applyBiomeDecoration}
     * ещё не гарантированно декорирован. Запись/чтение чужого недекорированного чанка
     * через {@link WorldGenLevel#getChunk} — основная причина, по которой Trial Spawner
     * теряет свой NBT-конфиг (спавнится без {@code spawn_potentials}) или структура
     * пропадает вовсе при последующей генерации того чанка.
     */
    private static final int CHUNK_SAFETY_MARGIN = CLEAR_RADIUS + 1;

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
     * @param chunkX     координата чанка (в чанках), вызвавшего decoration — точка размещения
     *                   обязана остаться внутри него (см. {@link #CHUNK_SAFETY_MARGIN})
     * @param chunkZ     см. {@code chunkX}
     */
    public static void placeForIsland(WorldGenLevel region,
                                       IslandShape shape,
                                       IslandData island,
                                       double noiseDeform,
                                       VaultTrialSpawnTier tier,
                                       VaultTrialLootConfig loot,
                                       RandomSource rng,
                                       int chunkX,
                                       int chunkZ) {

        List<BlockPos> placed = new ArrayList<>(tier.vaultCount() + tier.trialSpawnerCount());

        for (int i = 0; i < tier.vaultCount(); i++) {
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, placed, chunkX, chunkZ);
            if (pos == null) continue;
            placeVault(region, pos, loot);
            clearAboveBlock(region, pos);
            placed.add(pos);
        }

        for (int i = 0; i < tier.trialSpawnerCount(); i++) {
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, placed, chunkX, chunkZ);
            if (pos == null) continue;
            placeTrialSpawner(region, pos, loot);
            clearAboveBlock(region, pos);
            placed.add(pos);
        }

        AeroWorld.LOGGER.debug(
                "[AeroWorld] IslandVaultTrialGenerator: island ({},{}) tier={} placed {} structure(s) in chunk ({},{}).",
                island.cx, island.cz, tier, placed.size(), chunkX, chunkZ);
    }

    // ── Поиск точки внутри тела острова ────────────────────────────────────────

    /**
     * Ищет случайную точку на поверхности острова, куда можно "врыть" блок
     * на 1 блок вглубь земли — верхняя грань блока на уровне поверхности,
     * а над ней открытый воздух (блок торчит из земли, видимый сверху).
     *
     * <p>Использует ту же {@link IslandShape#isSolid} математику, что и fillChunk
     * слоя, поэтому гарантированно попадает на реальную поверхность острова
     * (с учётом деформации края), а не в пустоту рядом с ним.</p>
     *
     * <h3>Почему точка обязана лежать внутри вызвавшего чанка</h3>
     * Остров ({@code island.radius}) обычно значительно больше 16×16 блоков
     * одного чанка, а {@link Layer2VaultTrialPlacer#placeForChunk} вызывается
     * из {@code applyBiomeDecoration} для одного конкретного чанка. Если бы
     * кандидатная точка могла оказаться в соседнем чанке, то запись блока
     * и его {@code blockEntity} (см. {@link #placeBlockWithEntity}) шла бы
     * через {@code region.getChunk(pos)} в чанк, который на этот момент
     * не гарантированно декорирован — такая запись либо не переживает
     * последующую генерацию того чанка (NBT конфига Trial Spawner теряется,
     * из-за чего он спавнится без {@code spawn_potentials} и никогда не
     * запускает испытание), либо структура пропадает целиком. Поэтому поиск
     * жёстко ограничен текущим чанком (с отступом {@link #CHUNK_SAFETY_MARGIN}
     * от его границ) — независимо от того, насколько большой остров.
     */
    private static BlockPos findBuriedSpot(WorldGenLevel region,
                                            IslandShape shape,
                                            IslandData island,
                                            double noiseDeform,
                                            RandomSource rng,
                                            List<BlockPos> alreadyPlaced,
                                            int chunkX,
                                            int chunkZ) {

        // Ограничиваем поиск внутренними ~70% радиуса, чтобы не задевать тонкий
        // деформированный край острова (там мало толщи почвы под поверхностью).
        double innerRadius = island.radius * 0.7;

        // Безопасные границы текущего чанка (включительно), с отступом от
        // краёв на CHUNK_SAFETY_MARGIN — см. javadoc метода и константы.
        int minX = (chunkX << 4) + CHUNK_SAFETY_MARGIN;
        int maxX = (chunkX << 4) + 15 - CHUNK_SAFETY_MARGIN;
        int minZ = (chunkZ << 4) + CHUNK_SAFETY_MARGIN;
        int maxZ = (chunkZ << 4) + 15 - CHUNK_SAFETY_MARGIN;

        // island.cx/cz всегда внутри этого чанка (вызывающая сторона гарантирует
        // это — см. Layer2VaultTrialPlacer.placeForChunk), но остров сам по себе
        // часто больше одного чанка. Подрезаем радиус поиска расстоянием до
        // ближайшей безопасной границы чанка, иначе большая часть попыток из
        // MAX_PLACEMENT_ATTEMPTS уйдёт впустую на точки, заведомо отбракованные
        // проверкой границ чанка ниже.
        double maxDistToChunkEdge = Math.min(
                Math.min(island.cx - minX, maxX - island.cx),
                Math.min(island.cz - minZ, maxZ - island.cz));
        if (maxDistToChunkEdge < 0) return null; // остров вплотную к недекорируемому краю чанка
        innerRadius = Math.min(innerRadius, maxDistToChunkEdge);

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dist  = rng.nextDouble() * innerRadius;
            int wx = island.cx + (int) Math.round(Math.cos(angle) * dist);
            int wz = island.cz + (int) Math.round(Math.sin(angle) * dist);

            // Точка обязана остаться в пределах вызвавшего чанка (с запасом) —
            // иначе пропускаем кандидата, не тратя время на isSolid-математику.
            if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;

            IslandShape.XZCache xz = shape.precomputeXZ(
                    wx, wz, island.cx, island.cz, island.radius, noiseDeform,
                    island.shapeNoiseIntensity, island.shapeProfile);

            // Верхняя поверхность в этой XZ-колонке — тот же grass-блок, который
            // ставит LowerIslandGenerator.fillChunk (идентичная формула isSolid).
            if (!shape.isSolid(island.topY, island.bottomY, island.topY, xz)) continue;
            int surfaceY = binarySearchTopSurface(shape, island, xz);
            if (surfaceY < island.bottomY) continue;

            // Блок "врыт" на BURY_DEPTH ниже поверхности — верхняя часть
            // остаётся на уровне земли, открытая сверху (не в толще камня).
            int wy = surfaceY - BURY_DEPTH;
            if (wy <= island.bottomY) continue;

            // Под точкой должна быть твёрдая почва (не пустота/обрыв края острова).
            if (!shape.isSolid(wy, island.bottomY, island.topY, xz)) continue;
            if (!shape.isSolid(wy - 1, island.bottomY, island.topY, xz)) continue;

            BlockPos candidate = new BlockPos(wx, wy, wz);
            if (tooClose(candidate, alreadyPlaced)) continue;

            // Вторичная защита: если по каким-то причинам регион не считает
            // чанк этой точки доступным для записи (ещё не сгенерирован до
            // нужной стадии), пропускаем — лучше не поставить структуру,
            // чем поставить её с потерянным при последующей генерации NBT.
            if (!region.hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) continue;

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

    /**
     * Расчищает небольшую воздушную площадку над и вокруг только что
     * поставленного блока — {@link #CLEAR_RADIUS} блоков в стороны,
     * {@link #OPEN_ABOVE_BLOCKS} блоков вверх.
     *
     * <p>fillChunk слоя уже поставил grass/dirt поверх этой колонки до вызова
     * генератора (см. {@code applyBiomeDecoration}), поэтому без явной расчистки
     * поставленный блок останется погребён под травой, а не "торчащим из земли".</p>
     *
     * <p>Для Trial Spawner расчистка площадки, а не только столба, важна и
     * функционально: спавнер проверяет line-of-sight и требует свободное место
     * в радиусе {@code spawn_range} вокруг себя, иначе попытки спавна мобов
     * проваливаются одна за другой и он никогда не переходит в фазу наград.</p>
     */
    private static void clearAboveBlock(WorldGenLevel region, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int dy = 1; dy <= OPEN_ABOVE_BLOCKS; dy++) {
            for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) {
                for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!region.getBlockState(cursor).isAir()) {
                        region.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    // ── Постановка блоков ────────────────────────────────────────────────────

    private static void placeVault(WorldGenLevel region, BlockPos pos, VaultTrialLootConfig loot) {
        CompoundTag config = new CompoundTag();
        config.putString("loot_table", loot.vaultLootNormal().toString());
        // key_item по умолчанию и так "minecraft:trial_key" — задаём явно, чтобы
        // не зависеть от дефолтов ванили при возможных будущих изменениях.
        CompoundTag keyItem = new CompoundTag();
        keyItem.putString("id", "minecraft:trial_key");
        keyItem.putInt("count", 1);
        config.put("key_item", keyItem);

        CompoundTag root = new CompoundTag();
        root.put("config", config);
        // "server_data"/"shared_data" намеренно не заполняем — ваниль инициализирует
        // их дефолтными значениями (VaultServerData/VaultSharedData) при первом тике
        // блок-сущности, до этого поля не нужны для базовой функциональности.

        placeBlockWithEntity(region, pos, BS_VAULT, root);
    }

    private static void placeTrialSpawner(WorldGenLevel region, BlockPos pos, VaultTrialLootConfig loot) {
        CompoundTag root = new CompoundTag();

        CompoundTag normalConfig = buildSpawnerConfig(loot.trialLootNormal(), loot.spawnPotentials(), false);
        CompoundTag ominousConfig = buildSpawnerConfig(loot.trialLootOminous(), loot.spawnPotentials(), true);

        root.put("normal_config", normalConfig);
        root.put("ominous_config", ominousConfig);

        placeBlockWithEntity(region, pos, BS_TRIAL_SPAWNER, root);
    }

    /**
     * Строит полный trial spawner configuration compound (не просто loot_tables_to_eject).
     *
     * <p>Без {@code spawn_potentials} список мобов пуст, и спавнер никогда никого
     * не призывает — тогда его невозможно "победить", и он никогда не переходит
     * в фазу выдачи наград. Это обязательное поле, а не декоративное.</p>
     *
     * @param lootTable        loot table для {@code loot_tables_to_eject}
     * @param spawnPotentials  список мобов (переиспользуется из {@link VaultTrialLootConfig})
     * @param ominous          зловещий конфиг спавнит немного больше мобов одновременно
     */
    private static CompoundTag buildSpawnerConfig(ResourceLocation lootTable,
                                                    List<VaultTrialLootConfig.SpawnPotential> spawnPotentials,
                                                    boolean ominous) {
        CompoundTag cfg = new CompoundTag();

        ListTag potentials = new ListTag();
        for (VaultTrialLootConfig.SpawnPotential sp : spawnPotentials) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("weight", sp.weight());
            CompoundTag data = new CompoundTag();
            CompoundTag entity = new CompoundTag();
            entity.putString("id", sp.entityId().toString());
            data.put("entity", entity);
            entry.put("data", data);
            potentials.add(entry);
        }
        cfg.put("spawn_potentials", potentials);

        ListTag ejectList = new ListTag();
        CompoundTag ejectEntry = new CompoundTag();
        ejectEntry.putString("data", lootTable.toString());
        ejectEntry.putInt("weight", 1);
        ejectList.add(ejectEntry);
        cfg.put("loot_tables_to_eject", ejectList);

        // Явно задаём остальные боевые параметры, а не полагаемся только на
        // дефолты ванили — чтобы поведение было предсказуемо одинаковым
        // независимо от версии/дефолтов конкретного билда игры.
        cfg.putInt("spawn_range", 4);
        cfg.putFloat("total_mobs", ominous ? 9.0f : 6.0f);
        cfg.putFloat("simultaneous_mobs", ominous ? 3.0f : 2.0f);
        cfg.putFloat("total_mobs_added_per_player", 2.0f);
        cfg.putFloat("simultaneous_mobs_added_per_player", 1.0f);
        cfg.putInt("ticks_between_spawn", 40);

        return cfg;
    }

    /**
     * Ставит блок и заполняет его blockEntity NBT-тегом, создавая blockEntity
     * явно через {@link EntityBlock#newBlockEntity} вместо чтения его обратно
     * из региона после {@code setBlock}.
     *
     * <p>{@link WorldGenLevel} не имеет собственного {@code setBlockEntity}
     * (это метод {@link net.minecraft.world.level.chunk.ChunkAccess}/{@code Level}),
     * поэтому получаем {@code ChunkAccess} через {@code region.getChunk(pos)}
     * и ставим blockEntity туда напрямую — так же, как это делает ванильный
     * код при генерации структур.</p>
     *
     * <p>Построение blockEntity вручную (а не чтение его обратно из региона
     * через {@code getBlockEntity} сразу после {@code setBlock}) — надёжный
     * способ гарантировать, что NBT (loot table, spawn_potentials и т.д.)
     * переживёт генерацию чанка.</p>
     */
    private static void placeBlockWithEntity(WorldGenLevel region, BlockPos pos,
                                              BlockState state, CompoundTag tag) {
        region.setBlock(pos, state, 3);

        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld] IslandVaultTrialGenerator: block at {} is not an EntityBlock — skipped NBT.",
                    pos);
            return;
        }

        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be == null) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld] IslandVaultTrialGenerator: newBlockEntity returned null at {} — skipped NBT.",
                    pos);
            return;
        }

        be.loadWithComponents(tag, region.registryAccess());

        net.minecraft.world.level.chunk.ChunkAccess chunk = region.getChunk(pos);
        chunk.setBlockEntity(be);
        be.setChanged();
    }
}
