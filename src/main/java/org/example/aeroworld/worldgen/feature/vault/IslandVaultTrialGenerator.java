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
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;
import org.example.aeroworld.worldgen.noise.IslandShape;

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

    /**
     * Радиус расчищаемой площадки вокруг блока по X/Z/Y — нужен для спавна
     * мобов Trial Spawner. СИНХРОНИЗИРОВАН с {@code spawn_range=4}, который
     * задаётся в {@link #buildSpawnerConfig} — ваниль пытается разместить
     * моба в сферическом радиусе {@code spawn_range} от блока спавнера и
     * требует line-of-sight до него (см. Minecraft Wiki: "Mobs spawn in
     * positions that have a line of sight to the trial spawner, in a
     * 4-block spherical radius"). Если это значение расходится с
     * {@code spawn_range}, часть кандидатных позиций внутри spawn_range
     * остаётся погребена в нерасчищенном грунте острова — спавн в них тихо
     * проваливается (без ошибок в логах), и при достаточно частых неудачах
     * складывается впечатление, что спавнер вообще не активируется, хотя
     * "Cooldown: 0s" продолжает показываться (это лишь означает "не на
     * кулдауне", а не "успешно спавнит").
     */
    private static final int CLEAR_RADIUS = 4;

    /**
     * Сколько блоков ниже {@code pos.y} тоже нужно расчистить. Ваниль
     * пробует спавнить моба в диапазоне Y от {@code spawner.y - 1} до
     * {@code spawner.y + 2} (см. Minecraft Wiki: "The random Y coordinate
     * will be from -1 to 2") — то есть в том числе на один блок НИЖЕ
     * самого блока спавнера (стандартная позиция для моба ростом 2 блока:
     * ноги на {@code y-1}). Без расчистки этого нижнего яруса там всегда
     * стоит нерасчищенный грунт острова, что дополнительно душит спавн и
     * ломает line-of-sight с этой стороны.
     */
    private static final int CLEAR_BELOW_BLOCKS = 1;

    /**
     * Горизонтальный радиус (по X/Z, в блоках), в пределах которого разрешён
     * снос яруса {@code pos.y - 1} (см. {@link #CLEAR_BELOW_BLOCKS}).
     *
     * <p>Снос нижнего яруса нужен только под самим спавнером и в его
     * непосредственной близости — там, где действительно может встать моб
     * ростом 2 блока. Если применять его по всей сфере {@code CLEAR_RADIUS=4},
     * получается кольцевая прорезь в полу острова на расстоянии до 4 блоков
     * от центра (на один Y ниже поверхности) — на тонких островах или ближе
     * к краю это может продырявить остров насквозь или оставить нависающие
     * "карманы" пустоты. Ограничение до ближней зоны сохраняет функциональный
     * эффект (нижний ярус спавна расчищен) без риска для целостности острова.
     */
    private static final int CLEAR_BELOW_RADIUS = 1;

    /** Сколько раз пытаемся найти валидную точку на острове, прежде чем отказаться от одной структуры. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 48;

    /**
     * Минимальное расстояние (в блоках) между уже поставленными Vault/Trial
     * на одном острове.
     *
     * <p>Раньше здесь стояло 9.0 (диаметр сферы расчистки {@code CLEAR_RADIUS*2}),
     * чтобы снос грунта ниже {@code pos.y} у одной структуры не подрезал пол
     * под соседней. Это исходило из того, что {@code CLEAR_BELOW_BLOCKS}
     * применялся по всей сфере радиуса {@code CLEAR_RADIUS=4}. После введения
     * {@link #CLEAR_BELOW_RADIUS} снос нижнего яруса ограничен ближней зоной
     * (радиус 1) — соседние структуры больше не могут подрезать друг другу
     * пол, даже если их верхние (y ≥ 0) сферы расчистки перекрываются: это
     * лишь означает перекрывающийся воздух, а не разрушение опоры.</p>
     *
     * <p>9.0 при этом оказалось СЛИШКОМ БОЛЬШИМ значением для площади поиска:
     * кандидаты сэмплируются строго внутри одного чанка 16×16 (см.
     * {@link #findBuriedSpot}), и уместить 5 точек (RICH-тир: 3 vault + 5
     * trial spawner) с попарным расстоянием ≥9 в этой площади практически
     * невозможно — симуляция (500 прогонов, {@code MAX_PLACEMENT_ATTEMPTS=48}
     * на структуру) показала в среднем лишь ~3.7 из 5 успешно размещённых
     * структур. Именно это и вызывало баг "тир MEDIUM, а trial spawner на
     * острове всего 1 вместо 3" — часть структур находила null-позицию и
     * просто пропускалась ({@code if (pos == null) continue;} в
     * {@link #placeForIsland}) без единой строки в логах.
     *
     * <p>5.0 — минимальное из проверенных значений, при котором та же
     * симуляция даёт гарантированные 5/5 в каждом из 500 прогонов.</p>
     */
    private static final double MIN_SPACING = 5.0;

    /**
     * Радиус (в блоках) вокруг центра острова, реально занятый (или в
     * ближайшем будущем занятый) блюпринтом {@code physical_structures:tank21}
     * (см. {@link org.example.aeroworld.worldgen.layer.Layer2StructurePlacer}),
     * который планируется отдельным, полностью независимым проходом
     * (server-thread, уже после decoration) на origin, совпадающем с
     * {@code island.cx}/{@code island.cz} — самым центром острова. Известный
     * размер блюпринта tank21 — 11×12×7 (см. лог "StructureSizeCache: cached
     * ... → 11x12x7"), причём origin — это УГОЛ блюпринта (см.
     * {@code Layer2StructurePlacer}: {@code origin.x = island.cx},
     * {@code origin.z = island.cz}), а не его центр — структура растёт от
     * (cx, cz) в положительном направлении X/Z.
     *
     * <p>ВАЖНО: это НЕ тот радиус, который напрямую сравнивается с координатами
     * кандидата в {@link #findBuriedSpot} — см. {@link #EFFECTIVE_EXCLUSION_RADIUS}
     * ниже и почему разница между ними существенна.</p>
     */
    private static final double STRUCTURE_EXCLUSION_RADIUS = 3.0;

    /**
     * Фактический радиус отсечки кандидатов в {@link #findBuriedSpot} —
     * {@link #STRUCTURE_EXCLUSION_RADIUS}, увеличенный на {@link #CLEAR_RADIUS}.
     *
     * <p>Раньше отсекались только кандидаты, чья ТОЧКА постановки Vault/Trial
     * лежала внутри {@code STRUCTURE_EXCLUSION_RADIUS} от центра острова. Но
     * сама точка — не единственное, что затрагивает зону tank21: после
     * постановки блока {@link #clearAboveBlock} расчищает сферу воздуха
     * радиусом {@link #CLEAR_RADIUS} вокруг неё. Кандидат мог легко пройти
     * проверку (например, оказаться в 3.5 блоках от центра — уже за пределами
     * exclusion-радиуса 3.0), но его сфера расчистки радиусом 4 всё равно
     * дотягивалась ДО и ЗА пределы зоны tank21, выбивая в ней воздушные
     * карманы прямо на той высоте, где позже (в отдельном проходе) собирается
     * блюпринт — из-за чего tank21 оказывался частично "подвешен" в пустоте
     * или пересекался с ямой Vault/Trial структуры.</p>
     *
     * <p>Отсекать нужно не по точке постановки, а по тому, может ли ЛЮБАЯ
     * точка сферы расчистки (радиус {@code CLEAR_RADIUS} от кандидата)
     * оказаться внутри {@code STRUCTURE_EXCLUSION_RADIUS} от центра острова.
     * Это эквивалентно требованию: расстояние от кандидата до центра ≥
     * {@code STRUCTURE_EXCLUSION_RADIUS + CLEAR_RADIUS}.</p>
     */
    private static final double EFFECTIVE_EXCLUSION_RADIUS = STRUCTURE_EXCLUSION_RADIUS + CLEAR_RADIUS;

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
     * @param excludeTankZone true — резервировать зону вокруг центра острова под
     *                    physical_structures:tank21 (обычные острова, где tank21
     *                    действительно может быть поставлен); false — не резервировать
     *                    (архипелажные острова, где tank21 никогда не появляется, см.
     *                    {@code Layer2StructurePlacer.isEligibleForTank}) — иначе на
     *                    маленьких островах-спутниках это исключение съедало всё
     *                    доступное пространство поиска, и часть Vault/Trial Spawner
     *                    молча не размещалась (см. javadoc {@link #findBuriedSpot}).
     * @param progress   накопительный прогресс острова ({@link IslandVaultTrialCache}) —
     *                    общий для всех чанков этого острова; цикл идёт, пока в нём
     *                    остаются недоразмещённые структуры тира, а не фиксированное
     *                    число раз, и уже поставленные позиции (в т.ч. из ДРУГИХ
     *                    чанков этого острова) участвуют в проверке {@code MIN_SPACING}.
     */
    public static void placeForIsland(WorldGenLevel region,
                                       IslandShape shape,
                                       IslandData island,
                                       double noiseDeform,
                                       VaultTrialSpawnTier tier,
                                       VaultTrialLootConfig loot,
                                       RandomSource rng,
                                       int chunkX,
                                       int chunkZ,
                                       boolean excludeTankZone,
                                       IslandVaultTrialCache.Progress progress) {

        while (progress.vaultsRemaining.get() > 0) {
            if (progress.vaultsRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) <= 0) break;
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, progress.placed, chunkX, chunkZ, excludeTankZone);
            if (pos == null || !placeVault(region, pos, loot)) {
                progress.vaultsRemaining.incrementAndGet();
                if (pos == null) break; // этот чанк исчерпан — дальнейшие попытки здесь тоже провалятся
                continue;
            }
            clearAboveBlock(region, pos);
            progress.placed.add(pos);
        }

        while (progress.trialSpawnersRemaining.get() > 0) {
            if (progress.trialSpawnersRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) <= 0) break;
            BlockPos pos = findBuriedSpot(region, shape, island, noiseDeform, rng, progress.placed, chunkX, chunkZ, excludeTankZone);
            if (pos == null || !placeTrialSpawner(region, pos, loot)) {
                progress.trialSpawnersRemaining.incrementAndGet();
                if (pos == null) break;
                continue;
            }
            clearAboveBlock(region, pos);
            progress.placed.add(pos);
        }
    }

    /**
     * Размещает Vault и Trial Spawner для одного острова Layer 3 (эллипсоид,
     * {@code HighIslandGenerator}) согласно выбранному тиру.
     *
     * <p>В отличие от {@link #placeForIsland} (Layer 2, {@link IslandShape}),
     * этот метод не использует {@code IslandShape.isSolid()}/{@code precomputeXZ()} —
     * у эллипсоидной геометрии Layer 3 их попросту нет. Вместо этого точка
     * поверхности ищется через {@link HighIslandGenerator#getEllipsoidTopY}/
     * {@link HighIslandGenerator#getEllipsoidBottomY} (см. {@link #findBuriedSpotEllipsoid}),
     * которые уже воспроизводят ту же формулу {@code xzSq + dyInv² ≤ 1} и тот
     * же {@code edgeNoise} (nx/nz), что и {@code HighIslandGenerator.fillChunk}
     * — иначе кандидат мог бы попасть в шумовой зазор на краю острова.</p>
     *
     * <p>Постановка блока/NBT ({@link #placeVault}/{@link #placeTrialSpawner}/
     * {@link #placeBlockWithEntity}) и расчистка воздуха ({@link #clearAboveBlock})
     * переиспользуются без изменений — они не знают о геометрии слоя.</p>
     *
     * @param region     регион генерации (для записи blockEntity с NBT)
     * @param generator  генератор Layer 3 (источник {@code getEllipsoidTopY}/{@code getEllipsoidBottomY}/{@code computeXZSq})
     * @param island     кэшированные данные острова (bounds, radius, {@code ellipsoidAxes})
     * @param tier       категория богатства спавна для этого острова
     * @param loot       конфиг loot table (специфичен для слоя/дропа)
     * @param rng        детерминированный источник случайности (по острову, не по чанку!)
     * @param chunkX     координата чанка (в чанках), вызвавшего decoration
     * @param chunkZ     см. {@code chunkX}
     * @param progress   накопительный прогресс острова, см. {@link #placeForIsland}.
     */
    public static void placeForEllipsoidIsland(WorldGenLevel region,
                                                HighIslandGenerator generator,
                                                IslandData island,
                                                VaultTrialSpawnTier tier,
                                                VaultTrialLootConfig loot,
                                                RandomSource rng,
                                                int chunkX,
                                                int chunkZ,
                                                IslandVaultTrialCache.Progress progress) {

        while (progress.vaultsRemaining.get() > 0) {
            if (progress.vaultsRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) <= 0) break;
            BlockPos pos = findBuriedSpotEllipsoid(region, generator, island, rng, progress.placed, chunkX, chunkZ);
            if (pos == null || !placeVault(region, pos, loot)) {
                progress.vaultsRemaining.incrementAndGet();
                if (pos == null) break;
                continue;
            }
            clearAboveBlock(region, pos);
            progress.placed.add(pos);
        }

        while (progress.trialSpawnersRemaining.get() > 0) {
            if (progress.trialSpawnersRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) <= 0) break;
            BlockPos pos = findBuriedSpotEllipsoid(region, generator, island, rng, progress.placed, chunkX, chunkZ);
            if (pos == null || !placeTrialSpawner(region, pos, loot)) {
                progress.trialSpawnersRemaining.incrementAndGet();
                if (pos == null) break;
                continue;
            }
            clearAboveBlock(region, pos);
            progress.placed.add(pos);
        }
    }

    /**
     * Размещает Vault и Trial Spawner для одного острова Layer 4 (медуза —
     * купол + щупальца, {@code UpperIslandGenerator}) согласно выбранному тиру.
     *
     * <p>В отличие от {@link #placeForIsland} (Layer 2) и
     * {@link #placeForEllipsoidIsland} (Layer 3), структуры Layer 4 ставятся
     * ТОЛЬКО на купол ({@code isCapSolid}), а не в произвольную точку тела
     * острова — щупальца слишком тонкие (радиус 5 → 1.5 блока на конце,
     * см. {@code UpperIslandGenerator.TENTACLE_BASE_R}/{@code TENTACLE_TIP_R})
     * и физически не способны вместить {@code CLEAR_RADIUS=4}-сферу расчистки
     * без разрушения формы щупальца. Купол (радиус острова, см.
     * {@code isCapSolid}) — единственная часть медузы с достаточным объёмом
     * тела, аналогично тому, как у Layer 2/3 структуры ставятся в "ядро"
     * острова (0.7 нормализованного радиуса), не у самого края.</p>
     *
     * <p>Точка поверхности ищется через {@link UpperIslandGenerator#getCapTopY}/
     * {@link UpperIslandGenerator#getCapBottomY} — те же методы, что использует
     * LOD/сэмплер колонки для купола, поэтому кандидат гарантированно попадает
     * на реальную верхнюю поверхность купола (с учётом {@code capEdgeNoise}),
     * а не в шумовой зазор на его краю.</p>
     *
     * <p>Постановка блока/NBT и расчистка воздуха переиспользуются без
     * изменений, как и у Layer 2/3 — они не знают о геометрии слоя. У Layer 4
     * нет своего "якорного" блюпринта (в отличие от tank21/HAUL-01 у Layer 2/3,
     * см. {@code Layer2StructurePlacer}/{@code Layer3StructurePlacer}), поэтому
     * зона вокруг {@code island.cx}/{@code island.cz} НЕ исключается —
     * см. {@link #findBuriedSpotCap}.</p>
     *
     * @param region     регион генерации (для записи blockEntity с NBT)
     * @param generator  генератор Layer 4 (источник {@code getCapTopY}/{@code getCapBottomY})
     * @param island     кэшированные данные острова (bounds, radius, {@code tentacleData})
     * @param tier       категория богатства спавна для этого острова
     * @param loot       конфиг loot table (специфичен для слоя/дропа)
     * @param rng        детерминированный источник случайности (по острову, не по чанку!)
     * @param chunkX     координата чанка (в чанках), вызвавшего decoration
     * @param chunkZ     см. {@code chunkX}
     * @param progress   накопительный прогресс острова, см. {@link #placeForIsland}.
     */
    public static void placeForJellyfishIsland(WorldGenLevel region,
                                                 UpperIslandGenerator generator,
                                                 IslandData island,
                                                 VaultTrialSpawnTier tier,
                                                 VaultTrialLootConfig loot,
                                                 RandomSource rng,
                                                 int chunkX,
                                                 int chunkZ,
                                                 IslandVaultTrialCache.Progress progress) {

        while (progress.vaultsRemaining.get() > 0) {
            if (progress.vaultsRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) <= 0) break;
            BlockPos pos = findBuriedSpotCap(region, generator, island, rng, progress.placed, chunkX, chunkZ);
            if (pos == null || !placeVault(region, pos, loot)) {
                progress.vaultsRemaining.incrementAndGet();
                if (pos == null) break;
                continue;
            }
            clearAboveBlock(region, pos);
            progress.placed.add(pos);
        }

        while (progress.trialSpawnersRemaining.get() > 0) {
            if (progress.trialSpawnersRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) <= 0) break;
            BlockPos pos = findBuriedSpotCap(region, generator, island, rng, progress.placed, chunkX, chunkZ);
            if (pos == null || !placeTrialSpawner(region, pos, loot)) {
                progress.trialSpawnersRemaining.incrementAndGet();
                if (pos == null) break;
                continue;
            }
            clearAboveBlock(region, pos);
            progress.placed.add(pos);
        }
    }

    /**
     * Консервативный запас от края эллипсоида по XZ — аналог {@code island.radius * 0.7}
     * из {@link #findBuriedSpot} (Layer 2), но выраженный через {@code xzSq}
     * (см. {@link HighIslandGenerator#computeXZSq}): {@code xzSq <= 0.49} эквивалентно
     * "внутри 0.7 нормализованного радиуса эллипсоида".
     */
    private static final double ELLIPSOID_INNER_XZ_SQ = 0.7 * 0.7;

    /**
     * Консервативный запас от края купола Layer 4 (медуза) по XZ — тот же
     * принцип "0.7 нормализованного радиуса", что и у Layer 2/3, чтобы не
     * ставить структуры у самой кромки купола, где его толщина минимальна.
     */
    private static final double CAP_INNER_RADIUS_FACTOR = 0.7;

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
     * {@code island.radius}. Финальная гарантия успешной записи — проверка
     * возвращаемого значения {@code setBlock} в {@link #placeBlockWithEntity}.
     */
    private static BlockPos findBuriedSpot(WorldGenLevel region,
                                            IslandShape shape,
                                            IslandData island,
                                            double noiseDeform,
                                            RandomSource rng,
                                            List<BlockPos> alreadyPlaced,
                                            int chunkX,
                                            int chunkZ,
                                            boolean excludeTankZone) {

        double innerRadius = island.radius * 0.7;
        // На маленьких островах (спутники архипелага, центры архипелага) фиксированный
        // EFFECTIVE_EXCLUSION_RADIUS=7.0 может оказаться БОЛЬШЕ innerRadius (0.7×island.radius),
        // делая кольцо кандидатов пустым — тогда все структуры, кроме первой (успевшей занять
        // единственную случайно найденную точку до истощения попыток), молча пропадают
        // (см. "if (pos == null) continue;" в placeForIsland). tank21 в принципе никогда не
        // ставится на архипелажных островах (см. Layer2StructurePlacer.isEligibleForTank),
        // поэтому исключать зону вокруг центра под него там не нужно — excludeTankZone=false
        // отключает эту проверку и возвращает исходное, куда более широкое пространство поиска.
        double exclusionRadius = excludeTankZone ? EFFECTIVE_EXCLUSION_RADIUS : 0.0;

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

            // Не ставим Vault/Trial Spawner там, где его сфера расчистки
            // (радиус CLEAR_RADIUS) может дотянуться до зоны, где почти
            // наверняка позже будет собран physical_structures:tank21
            // (origin = island.cx/cz). Сравниваем с EFFECTIVE_EXCLUSION_RADIUS
            // (= STRUCTURE_EXCLUSION_RADIUS + CLEAR_RADIUS), а не с "голым"
            // радиусом tank21 — см. javadoc EFFECTIVE_EXCLUSION_RADIUS.
            double distFromCentreSq = (double)(wx - island.cx) * (wx - island.cx)
                    + (double)(wz - island.cz) * (wz - island.cz);
            if (distFromCentreSq < exclusionRadius * exclusionRadius) continue;

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

            // Раньше здесь была дополнительная проверка region.hasChunk(...).
            // Она убрана как избыточная: кандидат по построению всегда лежит
            // внутри chunkX/chunkZ (см. семплирование wx/wz выше и javadoc
            // класса), а это тот самый чанк, для которого прямо сейчас вызван
            // applyBiomeDecoration — region.hasChunk() для него гарантированно
            // true, так что проверка ничего не отсеивала, а только тратила
            // вызов на каждую из MAX_PLACEMENT_ATTEMPTS попыток. Единственная
            // реальная гарантия успешной записи — проверка возвращаемого
            // значения setBlock в placeBlockWithEntity, которая остаётся.
            return candidate;
        }
        return null;
    }

    /**
     * Аналог {@link #findBuriedSpot}, но под эллипсоидную геометрию Layer 3
     * ({@code HighIslandGenerator}) — сплошной эллипсоид без {@link IslandShape}.
     *
     * <h3>Почему нельзя переиспользовать {@link #findBuriedSpot}</h3>
     * {@code findBuriedSpot} завязан на {@code IslandShape.isSolid()}/
     * {@code precomputeXZ()} — у Layer 3 этой геометрии нет вообще (см. javadoc
     * {@link #placeForEllipsoidIsland}). Вместо этого поверхность колонки
     * берётся из {@link HighIslandGenerator#getEllipsoidTopY} — тот же метод,
     * которым LOD получает реальный Y верхней границы острова, а значит он уже
     * учитывает {@code edgeNoise} (nx/nz) и формулу {@code xzSq + dyInv² ≤ 1}
     * идентично {@code HighIslandGenerator.fillChunk}. Без этого шума кандидат
     * мог бы попасть в шумовой зазор на краю острова, где реального блока нет.
     *
     * <p>Все остальные правила (ограничение точки одним чанком decoration,
     * {@code EFFECTIVE_EXCLUSION_RADIUS} от центра острова — здесь это будущий
     * excraft:HAUL-01, {@code MIN_SPACING} между уже поставленными структурами)
     * идентичны {@link #findBuriedSpot} — см. его javadoc за подробным
     * обоснованием каждой проверки.
     */
    private static BlockPos findBuriedSpotEllipsoid(WorldGenLevel region,
                                                      HighIslandGenerator generator,
                                                      IslandData island,
                                                      RandomSource rng,
                                                      List<BlockPos> alreadyPlaced,
                                                      int chunkX,
                                                      int chunkZ) {

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            // Сэмплируем точку строго внутри чанка-инициатора decoration —
            // та же причина, что и в findBuriedSpot (см. его javadoc):
            // WorldGenLevel.setBlock тихо отбрасывает запись за пределами
            // safe-radius decoration-фазы для любого другого чанка.
            int wx = (chunkX << 4) + rng.nextInt(16);
            int wz = (chunkZ << 4) + rng.nextInt(16);

            // Не ставим структуру там, где её сфера расчистки (CLEAR_RADIUS)
            // может дотянуться до зоны будущего excraft:HAUL-01 (origin =
            // island.cx/cz, см. Layer3StructurePlacer) — тот же принцип, что и
            // EFFECTIVE_EXCLUSION_RADIUS у Layer 2 (см. его javadoc).
            double distFromCentreSq = (double) (wx - island.cx) * (wx - island.cx)
                    + (double) (wz - island.cz) * (wz - island.cz);
            if (distFromCentreSq < EFFECTIVE_EXCLUSION_RADIUS * EFFECTIVE_EXCLUSION_RADIUS) continue;

            // XZ-проверка "не слишком близко к краю" — эллипсоидный аналог
            // island.radius * 0.7 у Layer 2 (см. ELLIPSOID_INNER_XZ_SQ). Заодно
            // отсекает xzSq > 1.0 (вне эллипсоида по XZ), т.к. 0.49 < 1.0.
            double xzSq = generator.computeXZSq(wx, wz, island);
            if (xzSq > ELLIPSOID_INNER_XZ_SQ) continue;

            // Верхняя поверхность колонки — та же формула (с тем же edgeNoise),
            // что fillChunk использовал при заливке острова сплошным камнем.
            int surfaceY = generator.getEllipsoidTopY(wx, wz, island);
            if (surfaceY < island.bottomY) continue; // колонка вне острова

            int wy = surfaceY;
            if (wy <= island.bottomY) continue;

            // Под точкой должна быть твёрдая почва минимум на 2 блока вниз.
            // Эллипсоид — СПЛОШНОЕ тело (fillChunk заливает камнем весь объём
            // от columnBottomY до columnTopY в этой XZ-колонке, без полостей),
            // поэтому достаточно убедиться, что (wy - 2) не ниже нижней границы
            // эллипсоида в этой колонке — тогда весь диапазон [wy-2, wy] заведомо
            // внутри сплошного тела и, значит, твёрдый.
            int columnBottomY = generator.getEllipsoidBottomY(wx, wz, island);
            if (wy - 2 < columnBottomY) continue;

            BlockPos candidate = new BlockPos(wx, wy, wz);
            if (tooClose(candidate, alreadyPlaced)) continue;

            return candidate;
        }
        return null;
    }

    /**
     * Аналог {@link #findBuriedSpotEllipsoid}, но под геометрию купола Layer 4
     * ({@code UpperIslandGenerator}) — медуза не сплошное тело: сплошной
     * объём есть только у купола ({@code capBaseY..topY}), ниже него до
     * {@code botY} тело представлено тонкими щупальцами, непригодными для
     * структур (см. javadoc {@link #placeForJellyfishIsland}).
     *
     * <h3>Почему нельзя переиспользовать {@link #findBuriedSpotEllipsoid}</h3>
     * У купола нет единой формулы {@code xzSq + dyInv² ≤ 1}, как у эллипсоида —
     * его радиус зависит от {@code t=(wy-capBaseY)/(topY-capBaseY)} нелинейно
     * (см. {@code UpperIslandGenerator.isCapSolid}: {@code base=(1-t²)},
     * дополнительная выпуклость {@code bulge} у основания купола). Вместо
     * попытки продублировать эту формулу здесь, точка поверхности берётся
     * напрямую через {@link UpperIslandGenerator#getCapTopY}/
     * {@link UpperIslandGenerator#getCapBottomY} — те же методы, которыми
     * пользуется остальной код мода для колонки купола, поэтому кандидат
     * гарантированно совпадает с реальной поверхностью (включая
     * {@code capEdgeNoise}).
     *
     * <p>XZ-отсечка "не у края купола" использует {@code island.radius},
     * умноженный на {@link #CAP_INNER_RADIUS_FACTOR} — тот же принцип
     * "0.7 нормализованного радиуса", что у {@link #findBuriedSpot}/
     * {@link #findBuriedSpotEllipsoid}, но купол сужается к вершине и
     * основанию (см. {@code base}/{@code bulge} в {@code isCapSolid}), так
     * что эта отсечка — лишь консервативная стартовая эвристика; финальная
     * проверка объёма всё равно выполняется через {@code getCapTopY}/
     * {@code getCapBottomY} ниже.</p>
     *
     * <p>В отличие от Layer 2/3, здесь НЕТ {@code EFFECTIVE_EXCLUSION_RADIUS}
     * от {@code island.cx}/{@code island.cz} — у Layer 4 нет собственного
     * блюпринта-структуры, привязанного к центру острова (см. javadoc
     * {@link #placeForJellyfishIsland}).</p>
     */
    private static BlockPos findBuriedSpotCap(WorldGenLevel region,
                                                UpperIslandGenerator generator,
                                                IslandData island,
                                                RandomSource rng,
                                                List<BlockPos> alreadyPlaced,
                                                int chunkX,
                                                int chunkZ) {

        double innerRadius = island.radius * CAP_INNER_RADIUS_FACTOR;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            // Сэмплируем точку строго внутри чанка-инициатора decoration —
            // та же причина, что и в findBuriedSpot/findBuriedSpotEllipsoid
            // (см. их javadoc): WorldGenLevel.setBlock тихо отбрасывает
            // запись за пределами safe-radius decoration-фазы для любого
            // другого чанка.
            int wx = (chunkX << 4) + rng.nextInt(16);
            int wz = (chunkZ << 4) + rng.nextInt(16);

            // Консервативная XZ-отсечка от края купола (см. javadoc метода).
            double distFromCentreSq = (double) (wx - island.cx) * (wx - island.cx)
                    + (double) (wz - island.cz) * (wz - island.cz);
            if (distFromCentreSq > innerRadius * innerRadius) continue;

            // Верхняя поверхность купола в этой XZ-колонке — тот же метод,
            // которым UpperIslandGenerator/остальной код мода получает
            // реальный Y верхней границы купола (учитывает capEdgeNoise).
            int surfaceY = generator.getCapTopY(wx, wz, island);
            if (surfaceY < island.bottomY) continue; // колонка вне купола (в щупальце или мимо острова)

            int wy = surfaceY;
            if (wy <= island.bottomY) continue;

            // Под точкой должен быть сплошной купол минимум на 2 блока вниз.
            // Купол — сплошное тело в диапазоне [capBottomY, capTopY] в этой
            // XZ-колонке (см. UpperIslandGenerator.isCapSolid: условие только
            // по XZ-расстоянию и Y-диапазону, без внутренних полостей),
            // поэтому достаточно убедиться, что (wy - 2) не ниже нижней
            // границы купола — тогда весь диапазон [wy-2, wy] заведомо внутри
            // сплошного тела.
            int capBottomY = generator.getCapBottomY(wx, wz, island);
            if (wy - 2 < capBottomY) continue;

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

    /**
     * Расчищает воздушную площадку сферической формы вокруг только что
     * поставленного блока — радиусом {@link #CLEAR_RADIUS} (синхронизирован
     * с {@code spawn_range} спавнера), от {@code pos.y - CLEAR_BELOW_BLOCKS}
     * до {@code pos.y + OPEN_ABOVE_BLOCKS} по высоте (сам {@code pos} уже
     * занят Vault/Trial Spawner, поставленным заранее через
     * {@link #placeBlockWithEntity} — он и заменил исходный grass/dirt той
     * колонки).
     *
     * <p>Форма — сфера, а не прямоугольный столб/площадка: ваниль спавнит
     * мобов Trial Spawner в сферическом радиусе {@code spawn_range} и требует
     * прямую line-of-sight до блока спавнера. Прямоугольная расчистка
     * оставляла нетронутые "углы" грунта острова на границе радиуса —
     * дальние по диагонали кандидатные позиции внутри {@code spawn_range}
     * либо оказывались физически погребены в грунте, либо не имели прямой
     * видимости до спавнера из-за стенки нерасчищенной ямы. Оба случая
     * приводят к тихому провалу попытки спавна без единой записи в логах.</p>
     *
     * <p>Нижний ярус ({@code CLEAR_BELOW_BLOCKS}) обязателен: ваниль пробует
     * Y от {@code pos.y - 1} до {@code pos.y + 2} — без расчистки этого яруса
     * там всегда стоит нерасчищенный грунт острова, что душит и позицию
     * спавна, и line-of-sight с этой стороны.</p>
     */
    private static void clearAboveBlock(WorldGenLevel region, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        int radiusSq = CLEAR_RADIUS * CLEAR_RADIUS;
        int belowRadiusSq = CLEAR_BELOW_RADIUS * CLEAR_BELOW_RADIUS;
        for (int dy = -CLEAR_BELOW_BLOCKS; dy <= OPEN_ABOVE_BLOCKS; dy++) {
            for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) {
                for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // сам блок структуры — не трогаем
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue; // только внутри сферы
                    // Ниже уровня спавнера снос грунта ограничен ближней зоной
                    // (CLEAR_BELOW_RADIUS), а не всей сферой — иначе получается
                    // кольцевая прорезь в полу острова у самой границы spawn_range.
                    // См. javadoc CLEAR_BELOW_RADIUS.
                    if (dy < 0 && dx * dx + dz * dz > belowRadiusSq) continue;
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
            return false;
        }

        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return false;
        }

        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be == null) {
            return false;
        }

        be.loadWithComponents(tag, region.registryAccess());

        net.minecraft.world.level.chunk.ChunkAccess chunk = region.getChunk(pos);
        chunk.setBlockEntity(be);
        be.setChanged();
        return true;
    }
}
