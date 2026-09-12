package org.example.aeroworld.worldgen.cache;

/**
 * Тип небесного тела Layer 3 (High Sky Islands).
 *
 * <p>Детерминированно назначается по центру острова в
 * {@code HighIslandGenerator.computeIslandData}: 50% METEORITE, 50% PLANET.
 */
public enum BodyType {
    /** Полый эллипсоид со стенкой и воронками в верхнем своде. */
    METEORITE,
    /** Сплошная сфера с концентрическими кольцами астероидов. */
    PLANET
}
