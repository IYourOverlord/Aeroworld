package org.example.aeroworld.worldgen.structure;

/**
 * Категория структуры — определяет, какой тип валидации применяется.
 *
 * <ul>
 *   <li>{@link #SURFACE} — структуры поверхности слоя 1 (деревни, аванпосты).
 *       Требуют твёрдую землю прямо под подошвой.</li>
 *   <li>{@link #ISLAND} — структуры на небесных островах слоёв 2–4.
 *       Принимаются только если под ними есть остров с достаточной площадью.</li>
 *   <li>{@link #UNDERGROUND} — подземные структуры (шахты, крепость, древний город).
 *       Должны располагаться внутри твёрдого рельефа, не в пустоте.</li>
 *   <li>{@link #WATER} — океанские структуры (руины, монумент).
 *       Не размещаются в кастомном измерении — нет океана.</li>
 *   <li>{@link #SKY_FLOATING} — структуры, парящие в воздухе (airship и подобные).
 *       Принимаются если рядом есть хотя бы один остров и нет коллизии с рельефом.</li>
 *   <li>{@link #DENY} — структуры, которые никогда не размещаются в AeroWorld.</li>
 * </ul>
 */
public enum StructureCategory {

    SURFACE,
    ISLAND,
    UNDERGROUND,
    WATER,
    SKY_FLOATING,
    DENY;

    /** Нужна ли для этой категории проверка наличия твёрдого основания? */
    public boolean requiresSolidGround() {
        return this == SURFACE || this == ISLAND;
    }

    /** Нужна ли проверка на пустоту между слоями? */
    public boolean requiresLayerBoundsCheck() {
        return this == ISLAND || this == SKY_FLOATING;
    }
}
