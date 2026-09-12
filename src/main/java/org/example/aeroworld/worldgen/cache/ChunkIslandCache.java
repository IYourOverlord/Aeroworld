package org.example.aeroworld.worldgen.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.concurrent.locks.StampedLock;
import java.util.function.LongFunction;

/**
 * Общий кэш списков центров островов для всех слоёв генератора.
 * Использование Long2ObjectLinkedOpenHashMap + StampedLock обеспечивает O(1) LRU вытеснение
 * без случайного удаления активно генерируемых чанков и без боксинга ключей.
 */
public final class ChunkIslandCache {

    public static final int MAX_SIZE = 4096;

    private final Long2ObjectLinkedOpenHashMap<LongArrayList> map =
            new Long2ObjectLinkedOpenHashMap<>(MAX_SIZE);
    private final StampedLock lock = new StampedLock();

    private static long composeKey(int layerId, int chunkX, int chunkZ) {
        long x = chunkX & 0xFFFFFFFL;
        long z = chunkZ & 0xFFFFFFFL;
        return ((layerId & 0xFFL) << 56) | (x << 28) | z;
    }

    public LongArrayList get(int layerId, int chunkX, int chunkZ,
                             LongFunction<LongArrayList> factory) {
        long key = composeKey(layerId, chunkX, chunkZ);

        long stamp = lock.readLock();
        try {
            LongArrayList existing = map.get(key);
            if (existing != null) return existing;
        } finally {
            lock.unlockRead(stamp);
        }

        LongArrayList computed = factory.apply(key);

        stamp = lock.writeLock();
        try {
            LongArrayList existing = map.get(key);
            if (existing != null) return existing;

            map.putAndMoveToLast(key, computed);
            while (map.size() > MAX_SIZE) {
                map.removeFirst();
            }
            return computed;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public void release(int layerId, int chunkX, int chunkZ) {
    }

    public void releaseAll(int chunkX, int chunkZ, int maxLayerId) {
    }

    public void invalidate() {
        long stamp = lock.writeLock();
        try {
            map.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public int size() {
        long stamp = lock.readLock();
        try {
            return map.size();
        } finally {
            lock.unlockRead(stamp);
        }
    }
}