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
 * <h3>Почему "на поверхности острова, заменяя верхний слой земли"</h3>
 * Блок ставится на {@code surfaceY} — том же Y, на котором fillChunk слоя
 * поставил верхний grass/dirt блок острова — и тем самым заменяет его
 * (см. {@link #placeVault}/{@link #placeTrialSpawner}, которые вызывают
 * {@code region.setBlock} на этой позиции). Сверху расчищена небольшая
 * площадка воздуха (см. {@link #clearAboveBlock}). Так блок не "парит" над
 * землёй и не оказывается погребён под нетронутым верхним слоем почвы —
 * последнее раньше блокировало spawn_range Trial Spawner твёрдым блоком
 * прямо сверху, из-за чего мобы не могли заспавниться.
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

    /** Сколько блоков воздуха расчищаем над поставленным блоком (высота самого блока + запас). */
    private static final int OPEN_ABOVE_BLOCKS = 3;

    /** Радиус расчищаемой площадки вокруг блока по X/Z — нужен для спавна мобов Trial Spawner. */
    private static final int CLEAR_RADIUS = 2;

    /** Сколько раз пытаемся найти валидную точку на острове, прежде чем отказаться от одной структуры. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 48;

    /** Минимальное расстояние (в блоках) между уже поставленными Vault/Trial на одном острове. */
    private static final double MIN_SPACING = 6.0;

    /**
     * Радиус (в блоках) вокруг центра острова, зарезервированный под
     * {@code physical_structures:tank21} (см. {@link
     * org.example.aeroworld.worldgen.layer.Layer2StructurePlacer}), который
     * планируется отдельным, полностью независимым проходом (server-thread,
     * уже после decoration) на origin, совпадающем с {@code island.cx}/
     * {@code island.cz} — самым центром острова. Известный размер блюпринта
     * tank21 — 11×12×7 (см. лог "StructureSizeCache: cached ... → 11x12x7"),
     * то есть его наибольший горизонтальный габарит — 12 блоков.
     *
     * <p>ВАЖНО: радиус здесь НЕ может превышать половину диагонали одного
     * чанка (16×16 ≈ 22.6 блока по диагонали), потому что кандидатные точки
     * Vault/Trial Spawner сэмплируются строго внутри чанка-инициатора
     * decoration (см. {@link #findBuriedSpot}) — этот же чанк почти всегда
     * содержит и центр острова (island.cx/cz), вокруг которого построена
     * exclusion-зона. Слишком большой радиус (например, 14 — было раньше)
     * покрывает круг диаметром 28 блоков, что физически больше самого чанка
     * целиком, и оставляет НОЛЬ валидных точек ни для одной структуры —
     * именно так и получился баг "ни ларец, ни спавнер не появляются вообще".
     * 7 блоков — компромисс: покрывает половину габарита tank21 (не
     * идеальная гарантия для самых больших/асимметрично расположенных
     * блюпринтов, но реалистичный компромисс при жёстком ограничении
     * одним чанком) и оставляет достаточно площади чанка для поиска.
     */
    private static final double STRUCTURE_EXCLUSION_RADIUS = 7.0;

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
     * @param chunkX     координата чанка (в чанках), вызвавшего decoration — используется
     *                    только для диагностического лога, поиск точки им не ограничен
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
            if (!placeVault(region, pos, loot)) continue;
            clearAboveBlock(region, pos);
            placed.add(pos);
        }

        for (int i = 0; i < tier.trialSpawnerCount(); i++) {
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, placed, chunkX, chunkZ);
            if (pos == null) continue;
            if (!placeTrialSpawner(region, pos, loot)) continue;
            clearAboveBlock(region, pos);
            placed.add(pos);
        }

        AeroWorld.LOGGER.debug(
                "[AeroWorld] IslandVaultTrialGenerator: island ({},{}) tier={} placed {} structure(s) in chunk ({},{}).",
                island.cx, island.cz, tier, placed.size(), chunkX, chunkZ);
    }

    // ── Поиск точки внутри тела острова ────────────────────────────────────────

    /**
     * Ищет случайную точку на поверхности острова, куда можно поставить блок
     * вровень с землёй — сама точка (surfaceY) станет позицией Vault/Trial
     * Spawner, заменив исходный верхний grass/dirt блок острова.
     *
     * <p>Использует ту же {@link IslandShape#isSolid} математику, что и fillChunk
     * слоя, поэтому гарантированно попадает на реальную поверхность острова
     * (с учётом деформации края), а не в пустоту рядом с ним.</p>
     *
     * <h3>Почему точка ограничена ровно одним чанком</h3>
     * Ванильный {@code WorldGenLevel.setBlock} тихо отбрасывает запись за
     * пределами узкого safe-radius decoration-фазы, даже если
     * {@code region.hasChunk} для более широкой области возвращает
     * {@code true} (см. "Detected setBlock in a far chunk" в логах ядра —
     * на практике отказ наблюдался уже на дистанции 2 чанков от
     * chunkX/chunkZ). Единственный чанк, для которого движок гарантированно
     * не отбрасывает запись — тот самый, для которого прямо сейчас вызван
     * {@code applyBiomeDecoration}. Поэтому кандидатные точки сэмплируются
     * непосредственно внутри {@code chunkX}/{@code chunkZ}, а не по всему
     * {@code island.radius}.
     */
    private static BlockPos findBuriedSpot(WorldGenLevel region,
                                            IslandShape shape,
                                            IslandData island,
                                            double noiseDeform,
                                            RandomSource rng,
                                            List<BlockPos> alreadyPlaced,
                                            int chunkX,
                                            int chunkZ) {

        double innerRadius = island.radius * 0.7;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            // Сэмплируем точку СРАЗУ внутри чанка-инициатора decoration
            // (единственный чанк, где запись гарантированно разрешена — см.
            // javadoc выше), а не по всему радиусу острова со случайным
            // отбросом — так почти все MAX_PLACEMENT_ATTEMPTS попыток
            // действительно попадают в валидный чанк, вместо того чтобы
            // тратиться впустую на кандидатов, которые заведомо будут
            // отброшены проверкой chunkX/chunkZ ниже.
            int wx = (chunkX << 4) + rng.nextInt(16);
            int wz = (chunkZ << 4) + rng.nextInt(16);

            // Кандидат обязан лежать в ТОМ ЖЕ чанке, для которого прямо сейчас
            // вызван applyBiomeDecoration (chunkX/chunkZ) — это единственный
            // чанк, для которого ванильный WorldGenLevel.setBlock гарантированно
            // НЕ отбрасывает запись как "far chunk write". region.hasChunk()
            // может вернуть true для куда более широкой физически загруженной
            // области региона, но фактическая запись за пределами
            // чанка-инициатора всё равно тихо отбрасывается движком (см. лог
            // "setBlock ... was rejected by the engine" — это наблюдалось даже
            // на дистанции всего в 2 чанка). Радиус 0 — единственная граница,
            // которая не требует угадывания реального safe-radius движка.
            //
            // ВАЖНО: остров может не пересекаться своим телом с этим конкретным
            // чанком вообще (island.radius может быть меньше расстояния от
            // центра до этого чанка) — тогда isSolid-проверки ниже просто не
            // найдут валидную колонку и findBuriedSpot вернёт null для этого
            // острова. Это ожидаемо: Layer2VaultTrialPlacer вызывает
            // placeForIsland только для чанка, где лежит island.cx/cz, так что
            // для большинства островов их центр (и какая-то площадь вокруг)
            // гарантированно лежит в этом самом чанке.

            // Не ставим Vault/Trial Spawner в зоне, где почти наверняка позже
            // будет собран physical_structures:tank21 (origin = island.cx/cz).
            // См. javadoc STRUCTURE_EXCLUSION_RADIUS.
            double distFromCentreSq = (double)(wx - island.cx) * (wx - island.cx)
                    + (double)(wz - island.cz) * (wz - island.cz);
            if (distFromCentreSq < STRUCTURE_EXCLUSION_RADIUS * STRUCTURE_EXCLUSION_RADIUS) continue;

            // Не выходим за пределы разумного тела острова (0.7 радиуса) —
            // страховка от случая, когда чанк частично лежит далеко за
            // пределами формы острова.
            if (distFromCentreSq > innerRadius * innerRadius) continue;

            IslandShape.XZCache xz = shape.precomputeXZ(
                    wx, wz, island.cx, island.cz, island.radius, noiseDeform,
                    island.shapeNoiseIntensity, island.shapeProfile);

            // Верхняя поверхность в этой XZ-колонке — тот же grass-блок, который
            // ставит LowerIslandGenerator.fillChunk (идентичная формула isSolid).
            if (!shape.isSolid(island.topY, island.bottomY, island.topY, xz)) continue;
            int surfaceY = binarySearchTopSurface(shape, island, xz);
            if (surfaceY < island.bottomY) continue;

            // Блок ставится ВРОВЕНЬ с поверхностью острова (на surfaceY, том же
            // Y, где fillChunk слоя поставил верхний grass/dirt блок) — этот
            // блок далее заменяется постановкой Vault/Trial Spawner, а не
            // остаётся нетронутым НАД структурой. Раньше здесь стоял
            // surfaceY - BURY_DEPTH: спавнер оказывался на блок НИЖЕ земли,
            // а верхний слой почвы (surfaceY) не расчищался вообще —
            // структура была буквально погребена под этим слоем земли, и
            // spawn_range Trial Spawner был перекрыт твёрдым блоком сверху,
            // из-за чего мобы физически не могли заспавниться (Cooldown
            // оставался 0s бесконечно).
            int wy = surfaceY;
            if (wy <= island.bottomY) continue;

            // Под точкой должна быть твёрдая почва (не пустота/обрыв края острова).
            if (!shape.isSolid(wy - 1, island.bottomY, island.topY, xz)) continue;
            if (!shape.isSolid(wy - 2, island.bottomY, island.topY, xz)) continue;

            BlockPos candidate = new BlockPos(wx, wy, wz);
            if (tooClose(candidate, alreadyPlaced)) continue;

            // Дешёвая ранняя отсечка — не тратим дальнейшие проверки на точки,
            // чей чанк region вообще не считает загруженным. Финальная и
            // единственно надёжная гарантия успешной записи — проверка
            // возвращаемого значения setBlock в placeBlockWithEntity: она
            // отбрасывает и те случаи, где hasChunk() вернул true, но фактическая
            // запись всё равно отклонена движком (far-chunk write).
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
     * {@link #OPEN_ABOVE_BLOCKS} блоков вверх, начиная с {@code pos.y + 1}
     * (сам {@code pos} уже занят Vault/Trial Spawner, поставленным заранее
     * через {@link #placeBlockWithEntity} — он и заменил исходный grass/dirt
     * той колонки).
     *
     * <p>Критично не начинать расчистку с {@code pos} — тогда исходный
     * верхний слой почвы прямо НАД блоком остался бы нетронутым и перекрывал
     * бы {@code spawn_range} Trial Spawner сверху твёрдым блоком: спавнер
     * выглядел бы погребённым в земле, а попытки заспавнить моба
     * проваливались бы одна за другой, никогда не переходя в фазу наград.</p>
     *
     * <p>Для Trial Spawner расчистка площадки, а не только столба, важна и
     * функционально: спавнер проверяет line-of-sight и требует свободное место
     * в радиусе {@code spawn_range} вокруг себя.</p>
     */
    private static void clearAboveBlock(WorldGenLevel region, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int dy = 0; dy <= OPEN_ABOVE_BLOCKS; dy++) {
            for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) {
                for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
                    if (dy == 0 && dx == 0 && dz == 0) continue; // сам блок структуры — не трогаем
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!region.getBlockState(cursor).isAir()) {
                        region.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    // ── Постановка блоков ────────────────────────────────────────────────────

    private static boolean placeVault(WorldGenLevel region, BlockPos pos, VaultTrialLootConfig loot) {
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

        return placeBlockWithEntity(region, pos, BS_VAULT, root);
    }

    private static boolean placeTrialSpawner(WorldGenLevel region, BlockPos pos, VaultTrialLootConfig loot) {
        CompoundTag root = new CompoundTag();

        CompoundTag normalConfig = buildSpawnerConfig(loot.trialLootNormal(), loot.spawnPotentials(), false);
        CompoundTag ominousConfig = buildSpawnerConfig(loot.trialLootOminous(), loot.spawnPotentials(), true);

        root.put("normal_config", normalConfig);
        root.put("ominous_config", ominousConfig);

        return placeBlockWithEntity(region, pos, BS_TRIAL_SPAWNER, root);
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
     *
     * <h3>Почему проверяется результат {@code setBlock}</h3>
     * {@link WorldGenLevel#setBlock} тихо возвращает {@code false} и НЕ
     * записывает блок, если позиция выходит за пределы safe-radius записи
     * decoration-фазы ("Detected setBlock in a far chunk" в логах ядра —
     * ваниль это не выбрасывает как исключение, только пишет ERROR в лог).
     * Игнорирование этого возвращаемого значения — коренная причина бага,
     * из-за которого спавнер физически отсутствовал (на позиции оставался
     * исходный grass_block), а BlockEntity всё равно создавался и
     * прицеплялся к чанку через {@code chunk.setBlockEntity(be)} — тот
     * вызов не имеет подобной защиты и срабатывает независимо от того,
     * встал ли реально блок. В итоге получался "осиротевший" BlockEntity
     * без соответствующего блока, который не спавнил мобов.
     *
     * @return {@code true}, если блок реально встал и NBT применён;
     *         {@code false}, если запись была отброшена ванилью — в этом
     *         случае BlockEntity НЕ создаётся вовсе, чтобы не оставлять
     *         осиротевшие данные в чанке.
     */
    private static boolean placeBlockWithEntity(WorldGenLevel region, BlockPos pos,
                                                 BlockState state, CompoundTag tag) {
        boolean written = region.setBlock(pos, state, 3);
        if (!written || !region.getBlockState(pos).is(state.getBlock())) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld] IslandVaultTrialGenerator: setBlock at {} was rejected by the engine " +
                    "(likely far-chunk write outside decoration safe-radius) — skipping structure entirely, " +
                    "no orphaned BlockEntity will be created.",
                    pos);
            return false;
        }

        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld] IslandVaultTrialGenerator: block at {} is not an EntityBlock — skipped NBT.",
                    pos);
            return false;
        }

        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be == null) {
            AeroWorld.LOGGER.warn(
                    "[AeroWorld] IslandVaultTrialGenerator: newBlockEntity returned null at {} — skipped NBT.",
                    pos);
            return false;
        }

        be.loadWithComponents(tag, region.registryAccess());

        net.minecraft.world.level.chunk.ChunkAccess chunk = region.getChunk(pos);
        chunk.setBlockEntity(be);
        be.setChanged();
        return true;
    }
}
