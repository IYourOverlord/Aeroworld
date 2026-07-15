package org.example.aeroworld.worldgen.structure;

import net.minecraft.resources.ResourceLocation;

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
     */
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
     * Попадает ли Y в диапазон одного из небесных слоёв (2, 3, 4)?
     */
    public static boolean isIslandLayerY(int y) {
        return (y >= 280 && y <= 420)   // Layer 2 ± запас
            || (y >= 980 && y <= 1120)  // Layer 3 ± запас
            || (y >= 1880 && y <= 2031);// Layer 4
    }

    /**
     * Попадает ли Y в пустое пространство между слоями?
     * Если да — структура гарантированно висит в воздухе.
     */
    public static boolean isVoidGapY(int y) {
        return (y > 50  && y < 280)    // между Layer1 и Layer2
            || (y > 420 && y < 980)    // между Layer2 и Layer3
            || (y > 1120 && y < 1880); // между Layer3 и Layer4
    }
}
