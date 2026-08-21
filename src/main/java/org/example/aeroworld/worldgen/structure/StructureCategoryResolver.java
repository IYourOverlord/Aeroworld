package org.example.aeroworld.worldgen.structure;

import net.minecraft.resources.ResourceLocation;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.Layer1FlatGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;

import java.util.Locale;
import java.util.Set;

/**
 * Определяет категорию структуры по её {@link ResourceLocation}.
 *
 * <h3>Приоритет</h3>
 * <ol>
 *   <li>Явный deny-список — никогда не размещать в AeroWorld.</li>
 *   <li>Явный whitelist по точному id — всегда принимать без проверок.</li>
 *   <li>Подземные структуры (по id или по токену в пути).</li>
 *   <li>Водные структуры (ocean, monument, shipwreck…).</li>
 *   <li>Небесные парящие (airship, floating, sky, aerial, cloud…).</li>
 *   <li>Всё остальное → {@link StructureCategory#SURFACE} (наземные).</li>
 * </ol>
 *
 * Метод {@link #resolveForY(ResourceLocation, int)} дополнительно учитывает
 * диапазон Y: структуры в зонах небесных островов классифицируются как
 * {@link StructureCategory#ISLAND}.
 */
public final class StructureCategoryResolver {

    // ── Жёсткий deny-список ───────────────────────────────────────────────────
    // Эти структуры полностью несовместимы с кастомной генерацией AeroWorld.
    private static final Set<ResourceLocation> DENIED = Set.of(
            ResourceLocation.parse("minecraft:end_city"),
            ResourceLocation.parse("minecraft:nether_fossil"),
            ResourceLocation.parse("minecraft:bastion_remnant"),
            ResourceLocation.parse("minecraft:fortress")
    );

    // ── Whitelist: принимать без валидации ────────────────────────────────────
    // Структуры, специально спроектированные для воздушных/кастомных миров.
    private static final Set<ResourceLocation> ALWAYS_ALLOW = Set.of();

    // ── Токены для определения категорий по имени пути ────────────────────────
    private static final Set<String> UNDERGROUND_TOKENS = Set.of(
            "mineshaft", "trial_chambers", "ancient_city", "stronghold",
            "underground", "cave", "dungeon"
    );

    private static final Set<String> WATER_TOKENS = Set.of(
            "ocean", "monument", "shipwreck", "ruins", "underwater"
    );

    private static final Set<String> SKY_FLOATING_TOKENS = Set.of(
            "sky", "airship", "floating", "aerial", "cloud", "flying"
    );

    // ── Явные id подземных структур Minecraft ────────────────────────────────
    private static final Set<ResourceLocation> KNOWN_UNDERGROUND = Set.of(
            ResourceLocation.parse("minecraft:mineshaft"),
            ResourceLocation.parse("minecraft:trial_chambers"),
            ResourceLocation.parse("minecraft:ancient_city"),
            ResourceLocation.parse("minecraft:stronghold")
    );

    private StructureCategoryResolver() {}

    /**
     * Определяет категорию структуры только по её id.
     * Используется когда Y неизвестен (например, на этапе createStructures).
     */
    public static StructureCategory resolve(ResourceLocation structureId) {
        if (structureId == null) return StructureCategory.SURFACE;
        if (DENIED.contains(structureId)) return StructureCategory.DENY;
        if (ALWAYS_ALLOW.contains(structureId)) return StructureCategory.SURFACE;
        if (KNOWN_UNDERGROUND.contains(structureId)) return StructureCategory.UNDERGROUND;

        String path = structureId.getPath().toLowerCase(Locale.ROOT);

        for (String token : UNDERGROUND_TOKENS) {
            if (path.contains(token)) return StructureCategory.UNDERGROUND;
        }
        for (String token : WATER_TOKENS) {
            if (path.contains(token)) return StructureCategory.WATER;
        }
        for (String token : SKY_FLOATING_TOKENS) {
            if (path.contains(token)) return StructureCategory.SKY_FLOATING;
        }

        return StructureCategory.SURFACE;
    }

    /**
     * Определяет категорию с учётом Y-позиции структуры.
     * Структуры в диапазонах небесных слоёв → {@link StructureCategory#ISLAND}.
     *
     * @deprecated Категория по «сырому» {@code baseY} без учёта реального XZ-рельефа
     * может ошибочно классифицировать структуру Layer 1 как ISLAND, если ванильный
     * heightmap в данной точке случайно попал в Y-диапазон Layer 2/3/4 (см.
     * {@link #resolveForActualLayer(ResourceLocation, int)}). Оставлено для обратной
     * совместимости/фолбэка на случай, когда фактический слой определить не удалось
     * (например, {@code TerrainColumnSampler} недоступен).
     */
    @Deprecated
    public static StructureCategory resolveForY(ResourceLocation structureId, int baseY) {
        StructureCategory base = resolve(structureId);

        // Подземные/водные/deny — Y не меняет категорию
        if (base == StructureCategory.UNDERGROUND
                || base == StructureCategory.WATER
                || base == StructureCategory.DENY) {
            return base;
        }

        // Если структура попала в диапазон небесных островов → ISLAND
        if (isIslandLayerY(baseY)) {
            // SKY_FLOATING остаётся SKY_FLOATING — они парят, не стоят на островах
            if (base == StructureCategory.SKY_FLOATING) return StructureCategory.SKY_FLOATING;
            return StructureCategory.ISLAND;
        }

        return base;
    }

    /**
     * Определяет категорию по РЕАЛЬНОМУ слою в XZ-точке структуры, а не по
     * сырому {@code baseY}.
     *
     * <h3>Проблема, которую решает этот метод</h3>
     * {@link #resolveForY(ResourceLocation, int)} доверял только {@code baseY}:
     * если ванильный алгоритм определял высоту структуры в диапазоне Y,
     * который geometрически относится к Layer 2–4 (даже случайно, например
     * из-за heightmap-артефакта или близости границ слоёв), структура,
     * предназначенная для Layer 1 (деревня, аванпост и т.д.), получала
     * категорию ISLAND и валидировалась против рельефа острова — который
     * в этой XZ-точке физически существует, — из-за чего структура реально
     * отстраивалась на острове чужого слоя.
     *
     * <p>Этот метод вместо диапазона Y использует {@code actualLayer} —
     * результат {@link TerrainColumnSampler#resolveActualLayer(int, int)},
     * который смотрит, покрывает ли остров Layer 2/3/4 (или сам Layer 1)
     * данную XZ-точку в кэшированных данных генераторов — то есть определяет
     * слой по факту наличия тверди, а не по совпадению чисел.
     *
     * @param structureId  id структуры
     * @param actualLayer  результат {@code TerrainColumnSampler.resolveActualLayer(cx, cz)}:
     *                     1 — Layer 1 (поверхность), 2/3/4 — острова, -1 — ничего не найдено
     *                     (пустота между слоями либо точка вне любого острова)
     */
    public static StructureCategory resolveForActualLayer(ResourceLocation structureId, int actualLayer) {
        StructureCategory base = resolve(structureId);

        // Подземные/водные/deny — фактический слой не меняет категорию
        if (base == StructureCategory.UNDERGROUND
                || base == StructureCategory.WATER
                || base == StructureCategory.DENY) {
            return base;
        }

        // SKY_FLOATING остаётся SKY_FLOATING независимо от слоя — они парят,
        // а не стоят на конкретном острове/поверхности.
        if (base == StructureCategory.SKY_FLOATING) {
            return StructureCategory.SKY_FLOATING;
        }

        if (actualLayer == 2 || actualLayer == 3 || actualLayer == 4) {
            return StructureCategory.ISLAND;
        }
        if (actualLayer == 1) {
            return StructureCategory.SURFACE;
        }

        // actualLayer == -1: в этой XZ-точке нет тверди ни на одном слое —
        // структура гарантированно висит в пустоте между слоями.
        return StructureCategory.SURFACE; // дальше отсеется через isVoidGapY/сэмплинг
    }

    // Запас (в блоках) вокруг фактического диапазона острова, в пределах
    // которого структура ещё считается "относящейся к этому слою".
    private static final int LAYER_MARGIN = 20;

    /**
     * Попадает ли Y в диапазон одного из небесных слоёв (2, 3, 4)?
     *
     * <p>Границы взяты НАПРЯМУЮ из констант генераторов островов (не
     * магические числа), с запасом {@link #LAYER_MARGIN} блоков с каждой
     * стороны — так это не разъедется при изменении высоты слоёв.
     */
    public static boolean isIslandLayerY(int y) {
        return (y >= LowerIslandGenerator.LAYER_MIN_Y - LAYER_MARGIN
                && y <= LowerIslandGenerator.LAYER_MAX_Y + LAYER_MARGIN)   // Layer 2 ± запас
            || (y >= HighIslandGenerator.LAYER_MIN_Y - LAYER_MARGIN
                && y <= HighIslandGenerator.LAYER_MAX_Y + LAYER_MARGIN)   // Layer 3 ± запас
            || (y >= UpperIslandGenerator.LAYER_MIN_Y - LAYER_MARGIN
                && y <= UpperIslandGenerator.LAYER_MAX_Y + LAYER_MARGIN); // Layer 4 ± запас
    }

    /**
     * Попадает ли Y в пустое пространство между слоями?
     * Если да — структура гарантированно висит в воздухе.
     *
     * <p>Нижняя граница первого промежутка теперь {@link Layer1FlatGenerator#LAYER_MAX_Y}
     * (300, с горами), а не устаревшая константа 50 — иначе после появления
     * гор их вершины (до Y≈270) ошибочно считались бы "пустотой между слоями".
     */
    public static boolean isVoidGapY(int y) {
        return (y > Layer1FlatGenerator.LAYER_MAX_Y
                && y < LowerIslandGenerator.LAYER_MIN_Y - LAYER_MARGIN)    // между Layer1 и Layer2
            || (y > LowerIslandGenerator.LAYER_MAX_Y + LAYER_MARGIN
                && y < HighIslandGenerator.LAYER_MIN_Y - LAYER_MARGIN)    // между Layer2 и Layer3
            || (y > HighIslandGenerator.LAYER_MAX_Y + LAYER_MARGIN
                && y < UpperIslandGenerator.LAYER_MIN_Y - LAYER_MARGIN);  // между Layer3 и Layer4
    }
}
