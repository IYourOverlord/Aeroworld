package org.example.aeroworld.worldgen.cache;

/**
 * Утилитный класс для упаковки пары (chunkX, chunkZ) или (blockX, blockZ)
 * в один long без аллокации объектов.
 *
 * Формат: старшие 32 бита — X, младшие 32 бита — Z.
 * Покрывает весь диапазон int, включая отрицательные координаты.
 */
public final class ChunkKey {
    private ChunkKey() {}

    public static long of(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }
}
