package org.example.aeroworld.worldgen.column;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.example.aeroworld.worldgen.cache.BodyType;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.feature.vault.Layer2VaultTrialPlacer;
import org.example.aeroworld.worldgen.feature.vault.VaultTrialSpawnTier;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGeneratorAccess;
import org.example.aeroworld.worldgen.noise.IslandPlacer;

import javax.annotation.Nullable;

/**
 * Аналитическая модель покрытия структурами для LOD (Distant Horizons).
 *
 * <p>Аналог SeedGen {@code StructureCover.solve}: детерминированно по данным
 * острова (тип небесного тела / тир вольтов) проверяет наличие структуры в
 * чанке и накладывает упрощённый вертикальный шаблон блоков поверх рельефа
 * спанов {@link AeroColumnModel}. Никакого RNG-состояния, никакого обращения
 * к StructureManager — только чистые предикаты от (seed, координаты).</p>
 *
 * <p><b>6.1. Оценка необходимости:</b> из всех структур AeroWorld видимость на
 * дальнем top-down LOD имеют только:</p>
 * <ul>
 *   <li><b>End City</b> (Layer 3) — стоит строго по центру каждой планеты
 *       {@link BodyType#PLANET} ({@code EndCityStructureMixin}) в открытом
 *       небе: башня высотой до 36 блоков над сферой — ключевой визуальный
 *       ориентир на дистанциях 32–128 чанков;</li>
 *   <li><b>Tank21</b> (Layer 2) — стоит по центру обычного (не архипелажного)
 *       острова тира RICH ({@code Layer2StructurePlacer}), габариты ~13×13×12.</li>
 * </ul>
 * <p>Ancient City, Nether Fortress и Bastion находятся под сплошным каменным
 * массивом Layer 1 и на top-down LOD не видны — оверлей для них не строится.
 * Vault/Trial Spawner — одиночные блоки внутри островов, меньше вокселя LOD.</p>
 */
public final class AeroStructureCover {

    /** Вертикальный шаблон структуры в одной колонке: непрерывный диапазон [bottomY..topY] одного материала. */
    public record StructureColumn(int bottomY, int topY, BlockState state) {}

    /** Тип структуры, обнаруженной чекером {@link #solve}. */
    public enum Kind {
        /** Структуры в чанке нет. */
        NONE,
        /** End City на планете Layer 3. */
        END_CITY,
        /** Tank21 на RICH-острове Layer 2. */
        TANK21
    }

    // ── End City (Layer 3, планеты) ─────────────────────────────────────────
    /** Запас над макушкой сферы планеты. Должен совпадать с EndCityStructureMixin.END_CITY_CLEARANCE. */
    public static final int END_CITY_CLEARANCE = 16;
    /** Полуразмер основания города по XZ (9×9 блоков). */
    public static final int END_CITY_BASE_RADIUS = 4;
    /** Основание: startY .. startY + END_CITY_BASE_HEIGHT - 1. */
    public static final int END_CITY_BASE_HEIGHT = 5;
    /** Полуразмер ствола башни (3×3 блоков). */
    public static final int END_CITY_TOWER_RADIUS = 1;
    /** Верх ствола/карниза башни относительно startY. */
    public static final int END_CITY_TOP_OFFSET = 36;

    private static final BlockState BS_END_STONE_BRICKS = Blocks.END_STONE_BRICKS.defaultBlockState();
    private static final BlockState BS_PURPUR = Blocks.PURPUR_BLOCK.defaultBlockState();

    // ── Tank21 (Layer 2, RICH-острова) ──────────────────────────────────────
    /** Полуразмер корпуса танка по XZ (13×13 блоков — габарит NBT tank21). */
    public static final int TANK21_RADIUS = 6;
    /** Полуразмер центрального ядра (5×5 блоков, железо). */
    public static final int TANK21_CORE_RADIUS = 2;
    /** Высота корпуса над плоской вершиной острова (NBT ~12 блоков). */
    public static final int TANK21_HEIGHT = 12;

    private static final BlockState BS_IRON = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState BS_GRAY_CONCRETE = Blocks.GRAY_CONCRETE.defaultBlockState();

    private AeroStructureCover() {}

    // ── 6.2. Легковесный детерминированный чекер ────────────────────────────

    /**
     * Детерминированно решает, есть ли в чанке (chunkX, chunkZ) структура,
     * видимая на дальнем LOD. Полностью повторяет предикаты реального спавна:
     * <ul>
     *   <li>End City — центр острова Layer 3 лежит в чанке и тело имеет тип
     *       {@link BodyType#PLANET} (тот же guard, что в {@code EndCityStructureMixin});</li>
     *   <li>Tank21 — центр острова Layer 2 лежит в чанке, остров не является
     *       архипелагом (центром или спутником) и тир вольтов — RICH
     *       (тот же guard, что в {@code Layer2StructurePlacer}).</li>
     * </ul>
     * Не зависит от порядка генерации чанков; O(число центров в окрестности чанка).
     */
    public static Kind solve(int chunkX, int chunkZ,
                             @Nullable HighIslandGenerator highIslands,
                             @Nullable LowerIslandGenerator lowerIslands) {
        if (highIslands != null) {
            LongArrayList centres = highIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                int bx = ChunkKey.x(packed);
                int bz = ChunkKey.z(packed);
                if ((bx >> 4) != chunkX || (bz >> 4) != chunkZ) continue;
                IslandData d = highIslands.getIslandData(bx, bz);
                if (d.bodyType == BodyType.PLANET) return Kind.END_CITY;
            }
        }

        if (lowerIslands != null) {
            LongArrayList centres = lowerIslands.getCachedIslandCentresForChunk(chunkX, chunkZ);
            for (int i = 0; i < centres.size(); i++) {
                long packed = centres.getLong(i);
                int bx = ChunkKey.x(packed);
                int bz = ChunkKey.z(packed);
                if ((bx >> 4) != chunkX || (bz >> 4) != chunkZ) continue;
                if (isTank21Island(lowerIslands, packed, bx, bz)) return Kind.TANK21;
            }
        }

        return Kind.NONE;
    }

    /**
     * Предикат Tank21: не-архипелажный остров Layer 2 с тиром RICH.
     * Используется и чекером {@link #solve}, и семплером {@link #sampleLayer2RichStructure}.
     */
    private static boolean isTank21Island(LowerIslandGenerator lowerIslands, long packed, int cx, int cz) {
        IslandPlacer placer = lowerIslands.getPlacer();
        // RICH недостижим для островов архипелага (центр/спутник) — отсекаем
        // без вычисления тира, как в Layer2StructurePlacer.
        if (placer.isArchipelagoCentre(packed)
                || placer.findArchipelagoCentreFor(cx, cz, lowerIslands.getSearchRadius())
                != IslandPlacer.NO_ISLAND) {
            return false;
        }
        return Layer2VaultTrialPlacer.pickTierStatic(lowerIslands.worldSeed(), cx, cz)
                == VaultTrialSpawnTier.RICH;
    }

    // ── 6.3. Колоночные шаблоны ─────────────────────────────────────────────

    /**
     * Возвращает вертикальный шаблон End City для колонки (x, z) на планете
     * Layer 3 или {@code null}, если колонка вне силуэта города.
     *
     * <p>Геометрия (упрощённый столбцовый силуэт реальной башни):</p>
     * <ul>
     *   <li>основание 9×9: {@code END_STONE_BRICKS}, [startY .. startY+4], где
     *       startY = ellipsoidTopY(центра) + {@link #END_CITY_CLEARANCE};</li>
     *   <li>ствол+карниз 3×3: {@code PURPUR_BLOCK}, [startY+5 .. startY+36].</li>
     * </ul>
     *
     * <p>{@code planet} — {@link IslandData} планеты, полученная от
     * {@code highIslands.getIslandData}; высота базы считается той же функцией
     * {@code getEllipsoidTopY(cx, cz, d)}, что использует {@code EndCityStructureMixin}.</p>
     */
    @Nullable
    public static StructureColumn sampleLayer3PlanetStructure(
            int x, int z, @Nullable IslandData planet, @Nullable HighIslandGenerator highIslands) {
        if (planet == null || highIslands == null || planet.bodyType != BodyType.PLANET) return null;

        int adx = Math.abs(x - planet.cx);
        int adz = Math.abs(z - planet.cz);
        if (adx > END_CITY_BASE_RADIUS || adz > END_CITY_BASE_RADIUS) return null;

        int surfaceY = highIslands.getEllipsoidTopY(planet.cx, planet.cz, planet);
        int startY = surfaceY + END_CITY_CLEARANCE;

        if (adx <= END_CITY_TOWER_RADIUS && adz <= END_CITY_TOWER_RADIUS) {
            return new StructureColumn(startY + END_CITY_BASE_HEIGHT,
                    startY + END_CITY_TOP_OFFSET, BS_PURPUR);
        }
        return new StructureColumn(startY, startY + END_CITY_BASE_HEIGHT - 1, BS_END_STONE_BRICKS);
    }

    /**
     * Возвращает вертикальный шаблон Tank21 для колонки (x, z) на острове
     * Layer 2 или {@code null}, если остров не RICH / архипелажный / колонка
     * вне силуэта танка.
     *
     * <p>Геометрия (плоская вершина {@code d.topY}, как в реальной генерации):</p>
     * <ul>
     *   <li>ядро 5×5: {@code IRON_BLOCK}, [topY+1 .. topY+12];</li>
     *   <li>корпус 13×13: {@code GRAY_CONCRETE}, [topY+7 .. topY+12].</li>
     * </ul>
     */
    @Nullable
    public static StructureColumn sampleLayer2RichStructure(
            long worldSeed, int x, int z, @Nullable IslandData island, @Nullable LowerIslandGenerator lowerIslands) {
        if (island == null || lowerIslands == null) return null;

        int adx = Math.abs(x - island.cx);
        int adz = Math.abs(z - island.cz);
        if (adx > TANK21_RADIUS || adz > TANK21_RADIUS) return null;

        if (!isTank21Island(lowerIslands, ChunkKey.of(island.cx, island.cz), island.cx, island.cz)) {
            return null;
        }

        int baseY = island.topY + 1;
        if (adx <= TANK21_CORE_RADIUS && adz <= TANK21_CORE_RADIUS) {
            return new StructureColumn(baseY, island.topY + TANK21_HEIGHT, BS_IRON);
        }
        return new StructureColumn(island.topY + TANK21_HEIGHT / 2, island.topY + TANK21_HEIGHT, BS_GRAY_CONCRETE);
    }
}