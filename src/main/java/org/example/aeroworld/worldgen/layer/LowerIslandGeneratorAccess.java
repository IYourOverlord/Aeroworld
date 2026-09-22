package org.example.aeroworld.worldgen.layer;

/**
 * Узкий интерфейс доступа к world seed нижних островов для кода, которому
 * нужен только сид (например, {@code AeroStructureCover} для детерминированного
 * предиката тира RICH), без зависимости от полного генератора.
 */
public interface LowerIslandGeneratorAccess {
    /** World seed, из которого построен генератор. */
    long worldSeed();
}
