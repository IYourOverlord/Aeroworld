package org.example.aeroworld.worldgen.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.concurrent.locks.StampedLock;
import java.util.function.LongFunction;

/**
 * Потокобезопасный кэш свойств островов.
 * Использование Long2ObjectLinkedOpenHashMap + StampedLock обеспечивает O(1) LRU вытеснение
 * без случайного удаления данных активных островов.
 */
public final class IslandCache {

    private static final int DEFAULT_MAX_SIZE = 512;

    private final Long2ObjectLinkedOpenHashMap<IslandData> map;
    private final int maxSize;
    private final StampedLock lock = new StampedLock();

    public IslandCache() {
        this(DEFAULT_MAX_SIZE);
    }

    public IslandCache(int maxSize) {
        this.maxSize = maxSize;
        this.map = new Long2ObjectLinkedOpenHashMap<>(maxSize);
    }

    public IslandData get(int cx, int cz, LongFunction<IslandData> factory) {
        long key = ChunkKey.of(cx, cz);

        long stamp = lock.readLock();
        try {
            IslandData existing = map.get(key);
            if (existing != null) return existing;
        } finally {
            lock.unlockRead(stamp);
        }

        IslandData computed = factory.apply(key);

        stamp = lock.writeLock();
        try {
            IslandData existing = map.get(key);
            if (existing != null) return existing;

            map.putAndMoveToLast(key, computed);
            while (map.size() > maxSize) {
                map.removeFirst();
            }
            return computed;
        } finally {
            lock.unlockWrite(stamp);
        }
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
