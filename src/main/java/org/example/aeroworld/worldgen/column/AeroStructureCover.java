package org.example.aeroworld.worldgen.column;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.example.aeroworld.worldgen.cache.BodyType;
import org.example.aeroworld.worldgen.cache.ChunkKey;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;

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
 * дальнем top-down LOD имеет только:</p>
 * <ul>
 *   <li><b>End City</b> (Layer 3) — стоит строго по центру каждой планеты
 *       {@link BodyType#PLANET} ({@code EndCityStructureMixin}) в открытом
 *       небе: башня высотой до 36 блоков над сферой — ключевой визуальный
 *       ориентир на дистанциях 32–128 чанков;</li>
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
        END_CITY
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

    private AeroStructureCover() {}

    // ── 6.2. Легковесный детерминированный чекер ────────────────────────────

    /**
     * Детерминированно решает, есть ли в чанке (chunkX, chunkZ) структура,
     * видимая на дальнем LOD. Повторяет предикат реального спавна: центр острова Layer 3
     * лежит в чанке и тело имеет тип {@link BodyType#PLANET} (тот же guard, что в
     * {@code EndCityStructureMixin}).
     * Не зависит от порядка генерации чанков; O(число центров в окрестности чанка).
     */
    public static Kind solve(int chunkX, int chunkZ,
                             @Nullable HighIslandGenerator highIslands) {
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

        return Kind.NONE;
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
}
