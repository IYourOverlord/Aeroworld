package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.example.aeroworld.config.AeroWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;

/**
 * Потокобезопасный LRU-кэш декодированных секций {@link FullDataSourceV2} для границ чанков и
 * устранения дыр рендера (StandInTerrain).
 * <p>
 * Использование {@link Long2ObjectLinkedOpenHashMap} + {@link StampedLock} обеспечивает O(1)
 * LRU-вытеснение без боксинга ключей позиций секций.
 * Управление жизненным циклом через reference counting ({@link CacheRecord#refCount} +
 * арендуемый {@link Entry}) гарантирует, что кэшированный объект не закрывается во время
 * активного чтения в каскадном downsample и безопасно освобождает пуловые массивы
 * ({@code source.close()}) только при вытеснении из кэша и отсутствии активных читателей.
 */
public final class AeroAdjacencyCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("AeroWorld/AdjacencyCache");
    public static final int DEFAULT_MAX_SIZE = 512;

    private static final Long2ObjectLinkedOpenHashMap<CacheRecord> MAP =
            new Long2ObjectLinkedOpenHashMap<>(DEFAULT_MAX_SIZE);
    private static final StampedLock LOCK = new StampedLock();

    // ── Метрики и профилирование (7.1) ─────────────────────────────────────────
    private static final AtomicLong TOTAL_REQUESTS = new AtomicLong(0);
    private static final AtomicLong CACHE_HITS = new AtomicLong(0);
    private static final AtomicLong CACHE_MISSES = new AtomicLong(0);
    private static final AtomicLong CACHE_EVICTIONS = new AtomicLong(0);
    private static final AtomicLong TOTAL_LOAD_TIME_NANOS = new AtomicLong(0);

    private static final AtomicLong WINDOW_REQUESTS = new AtomicLong(0);
    private static final AtomicLong WINDOW_HITS = new AtomicLong(0);
    private static final AtomicLong WINDOW_MISSES = new AtomicLong(0);
    private static final AtomicLong WINDOW_EVICTIONS = new AtomicLong(0);
    private static final AtomicLong WINDOW_LOAD_TIME_NANOS = new AtomicLong(0);

    private static volatile long windowStartTime = System.currentTimeMillis();

    private AeroAdjacencyCache() {}

    /**
     * Запись в кэше с подсчётом ссылок.
     * {@code refCount} инициализируется 1 (ссылка самого кэша в {@link #MAP}).
     */
    private static final class CacheRecord {
        final long pos;
        final FullDataSourceV2 source;
        final long createdTimeMs;
        final AtomicInteger refCount = new AtomicInteger(1);
        final AtomicBoolean closed = new AtomicBoolean(false);

        CacheRecord(long pos, FullDataSourceV2 source) {
            this.pos = pos;
            this.source = source;
            this.createdTimeMs = System.currentTimeMillis();
        }

        boolean isExpired(long now, long ttlMs) {
            return ttlMs > 0 && (now - createdTimeMs) > ttlMs;
        }

        void retain() {
            refCount.incrementAndGet();
        }

        void release() {
            if (refCount.decrementAndGet() <= 0) {
                closeSource();
            }
        }

        private void closeSource() {
            if (closed.compareAndSet(false, true)) {
                if (source != null) {
                    try {
                        source.close();
                    } catch (Throwable t) {
                        LOGGER.debug("[AeroWorld] Error closing FullDataSourceV2 at pos {}: {}", pos, t.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Арендуемый дескриптор (lease handle) кэшированного {@link FullDataSourceV2}.
     * Вызывающая сторона обязана вызвать {@link #release()} или {@link #close()} по завершении работы.
     */
    public static final class Entry implements AutoCloseable {
        private final CacheRecord record;
        private final FullDataSourceV2 source;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Entry(CacheRecord record, FullDataSourceV2 source) {
            this.record = record;
            this.source = source;
        }

        public FullDataSourceV2 get() {
            return source;
        }

        public void release() {
            close();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (record != null) {
                    record.release();
                } else if (source != null) {
                    try {
                        source.close();
                    } catch (Throwable t) {
                        LOGGER.debug("[AeroWorld] Error closing un-cached FullDataSourceV2: {}", t.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Получает или загружает {@link FullDataSourceV2} для заданной позиции.
     *
     * @param pos    позиция секции {@code DhSectionPos}
     * @param loader поставщик данных (обращение к SQLite через FullDataSourceProviderV2)
     * @return дескриптор с объектом или {@code null} при ошибке/отсутствии
     */
    public static Entry get(long pos, Supplier<FullDataSourceV2> loader) {
        TOTAL_REQUESTS.incrementAndGet();
        WINDOW_REQUESTS.incrementAndGet();
        checkReport();

        boolean enabled = (AeroWorldConfig.DH_ADJACENCY_CACHE_ENABLED == null || AeroWorldConfig.DH_ADJACENCY_CACHE_ENABLED.get())
                && (AeroWorldConfig.DH_OVERRIDE_ENABLED == null || AeroWorldConfig.DH_OVERRIDE_ENABLED.get());

        if (!enabled) {
            long t0 = System.nanoTime();
            FullDataSourceV2 raw = loadSafely(loader, pos);
            long elapsed = System.nanoTime() - t0;
            CACHE_MISSES.incrementAndGet();
            WINDOW_MISSES.incrementAndGet();
            TOTAL_LOAD_TIME_NANOS.addAndGet(elapsed);
            WINDOW_LOAD_TIME_NANOS.addAndGet(elapsed);
            return raw != null ? new Entry(null, raw) : null;
        }

        // Поиск в кэше
        long stamp = LOCK.writeLock();
        try {
            CacheRecord record = MAP.get(pos);
            if (record != null) {
                long now = System.currentTimeMillis();
                long ttlSec = AeroWorldConfig.DH_ADJACENCY_CACHE_TTL_SEC != null
                        ? AeroWorldConfig.DH_ADJACENCY_CACHE_TTL_SEC.get()
                        : 10;
                long ttlMs = ttlSec * 1000L;
                if (record.isExpired(now, ttlMs)) {
                    MAP.remove(pos);
                    record.release();
                    CACHE_EVICTIONS.incrementAndGet();
                    WINDOW_EVICTIONS.incrementAndGet();
                } else {
                    MAP.putAndMoveToLast(pos, record);
                    record.retain();
                    CACHE_HITS.incrementAndGet();
                    WINDOW_HITS.incrementAndGet();
                    return new Entry(record, record.source);
                }
            }
        } finally {
            LOCK.unlockWrite(stamp);
        }

        // Промах кэша: загрузка вне блокировок
        CACHE_MISSES.incrementAndGet();
        WINDOW_MISSES.incrementAndGet();
        long startNanos = System.nanoTime();
        FullDataSourceV2 loaded = loadSafely(loader, pos);
        long elapsedNanos = System.nanoTime() - startNanos;
        TOTAL_LOAD_TIME_NANOS.addAndGet(elapsedNanos);
        WINDOW_LOAD_TIME_NANOS.addAndGet(elapsedNanos);

        if (loaded == null) {
            return null;
        }

        if (loaded.isEmpty) {
            // Пустые секции не кэшируем в LRU для экономии памяти пула массивов
            return new Entry(null, loaded);
        }

        int maxSize = AeroWorldConfig.DH_ADJACENCY_CACHE_SIZE != null
                ? AeroWorldConfig.DH_ADJACENCY_CACHE_SIZE.get()
                : DEFAULT_MAX_SIZE;

        stamp = LOCK.writeLock();
        try {
            CacheRecord existing = MAP.get(pos);
            if (existing != null) {
                long now = System.currentTimeMillis();
                long ttlSec = AeroWorldConfig.DH_ADJACENCY_CACHE_TTL_SEC != null
                        ? AeroWorldConfig.DH_ADJACENCY_CACHE_TTL_SEC.get()
                        : 10;
                long ttlMs = ttlSec * 1000L;
                if (!existing.isExpired(now, ttlMs)) {
                    // Другой поток уже загрузил актуальную секцию: закрываем дубликат и возвращаем кэшированную
                    closeDirectly(loaded, pos);
                    MAP.putAndMoveToLast(pos, existing);
                    existing.retain();
                    CACHE_HITS.incrementAndGet();
                    WINDOW_HITS.incrementAndGet();
                    return new Entry(existing, existing.source);
                } else {
                    MAP.remove(pos);
                    existing.release();
                }
            }

            CacheRecord record = new CacheRecord(pos, loaded);
            record.retain(); // 1 для кэша, 1 для возвращаемого Entry
            MAP.putAndMoveToLast(pos, record);

            while (MAP.size() > maxSize) {
                CacheRecord evicted = MAP.removeFirst();
                if (evicted != null) {
                    evicted.release();
                    CACHE_EVICTIONS.incrementAndGet();
                    WINDOW_EVICTIONS.incrementAndGet();
                }
            }

            return new Entry(record, record.source);
        } finally {
            LOCK.unlockWrite(stamp);
        }
    }

    private static FullDataSourceV2 loadSafely(Supplier<FullDataSourceV2> loader, long pos) {
        try {
            return loader.get();
        } catch (Throwable t) {
            LOGGER.warn("[AeroWorld] Error loading FullDataSourceV2 at pos {}: {}", pos, t.getMessage());
            return null;
        }
    }

    private static void closeDirectly(FullDataSourceV2 source, long pos) {
        if (source != null) {
            try {
                source.close();
            } catch (Throwable t) {
                LOGGER.debug("[AeroWorld] Error closing duplicate FullDataSourceV2 at pos {}: {}", pos, t.getMessage());
            }
        }
    }

    public static void invalidate(long pos) {
        long stamp = LOCK.writeLock();
        try {
            CacheRecord removed = MAP.remove(pos);
            if (removed != null) {
                removed.release();
            }
        } finally {
            LOCK.unlockWrite(stamp);
        }
    }

    public static void clear() {
        long stamp = LOCK.writeLock();
        try {
            for (CacheRecord record : MAP.values()) {
                if (record != null) {
                    record.release();
                }
            }
            MAP.clear();
        } finally {
            LOCK.unlockWrite(stamp);
        }
    }

    public static int size() {
        long stamp = LOCK.readLock();
        try {
            return MAP.size();
        } finally {
            LOCK.unlockRead(stamp);
        }
    }

    public static long hits() {
        return CACHE_HITS.get();
    }

    public static long misses() {
        return CACHE_MISSES.get();
    }

    public static long evictions() {
        return CACHE_EVICTIONS.get();
    }

    public static long totalRequests() {
        return TOTAL_REQUESTS.get();
    }

    private static void checkReport() {
        long now = System.currentTimeMillis();
        long intervalSec = AeroWorldConfig.DH_THROUGHPUT_LOG_INTERVAL_SEC != null
                ? AeroWorldConfig.DH_THROUGHPUT_LOG_INTERVAL_SEC.get()
                : 30;
        long intervalMs = Math.max(5000L, intervalSec * 1000L);

        if (now - windowStartTime >= intervalMs) {
            synchronized (AeroAdjacencyCache.class) {
                if (now - windowStartTime >= intervalMs) {
                    long elapsedMs = now - windowStartTime;
                    long reqs = WINDOW_REQUESTS.getAndSet(0);
                    long hits = WINDOW_HITS.getAndSet(0);
                    long misses = WINDOW_MISSES.getAndSet(0);
                    long evictions = WINDOW_EVICTIONS.getAndSet(0);
                    long loadNanos = WINDOW_LOAD_TIME_NANOS.getAndSet(0);
                    windowStartTime = now;

                    if (reqs == 0) return;

                    double elapsedSec = elapsedMs / 1000.0;
                    double hitPct = (reqs > 0) ? (100.0 * hits / reqs) : 0.0;
                    double avgLoadMs = (misses > 0) ? (loadNanos / (misses * 1_000_000.0)) : 0.0;
                    double totalLoadMs = loadNanos / 1_000_000.0;

                    int maxSize = AeroWorldConfig.DH_ADJACENCY_CACHE_SIZE != null
                            ? AeroWorldConfig.DH_ADJACENCY_CACHE_SIZE.get()
                            : DEFAULT_MAX_SIZE;

                    LOGGER.info("[AeroWorld AdjacencyCache] Window {}s: {} lookups, {} hits ({}), {} misses, {} evictions | Avg load: {} ms (total: {} ms) | Cache: {}/{}",
                            String.format(java.util.Locale.ROOT, "%.1f", elapsedSec),
                            reqs,
                            hits,
                            String.format(java.util.Locale.ROOT, "%.1f%%", hitPct),
                            misses,
                            evictions,
                            String.format(java.util.Locale.ROOT, "%.2f", avgLoadMs),
                            String.format(java.util.Locale.ROOT, "%.1f", totalLoadMs),
                            size(),
                            maxSize);
                }
            }
        }
    }

    public static void logSummary() {
        long total = TOTAL_REQUESTS.get();
        if (total == 0) return;
        long hits = CACHE_HITS.get();
        long misses = CACHE_MISSES.get();
        long evictions = CACHE_EVICTIONS.get();
        long totalNanos = TOTAL_LOAD_TIME_NANOS.get();
        double hitPct = (total > 0) ? (100.0 * hits / total) : 0.0;
        double avgLoadMs = (misses > 0) ? (totalNanos / (misses * 1_000_000.0)) : 0.0;

        LOGGER.info("[AeroWorld AdjacencyCache Summary] Total: {} lookups, {} hits ({}), {} misses, {} evictions | Avg load: {} ms | Cache size: {}",
                total,
                hits,
                String.format(java.util.Locale.ROOT, "%.1f%%", hitPct),
                misses,
                evictions,
                String.format(java.util.Locale.ROOT, "%.2f", avgLoadMs),
                size());
    }
}
