package org.example.aeroworld.worldgen.dh;

import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import org.example.aeroworld.config.AeroWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Потокобезопасный LRU-кэш подтверждённо полностью сгенерированных секций Distant Horizons
 * (п.8 PROGRESS.md, аналог {@code CompleteSectionCache} из Distant Horizons SeedGen).
 * <p>
 * {@code GeneratedFullDataSourceProvider.getPositionsToRetrieve(pos, generatorDetailLevel)}
 * вызывается на каждом тике {@code LodQuadTree} ({@code tryQueuePosForRetrieval}) для каждой
 * близкой к игроку world-gen секции — включая уже подтверждённо готовые. Каждый такой вызов
 * читает {@code repo.getColumnGenerationStepForPos} из SQLite и сканирует до 4096 байт
 * generation-step колонок, чтобы определить, что секция полностью сгенерирована и повторно
 * вернуть тот же пустой список отсутствующих позиций — чистый повторный обход дерева
 * квадрантов без результата.
 * <p>
 * Данный кэш запоминает пары (pos, generatorDetailLevel), для которых последний вызов
 * оригинального метода вернул пустой список, и позволяет {@code CompleteSectionCacheMixin}
 * закоротить повторный вызов немедленным пустым результатом, минуя чтение БД и скан колонок.
 * <p>
 * Инвалидация — по позиции секции при {@code AbstractDhRepo.save}/{@code deleteWithKey}
 * (данные секции перезаписаны, статус генерации мог измениться) и полная очистка при
 * {@code deleteAll}; хуки уже добавлены в {@code SqliteTuningMixin} (переиспользован тот же
 * механизм, что и для {@link AeroAdjacencyCache}, п.7).
 */
public final class AeroCompleteSectionCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("AeroWorld/CompleteSectionCache");
    public static final int DEFAULT_MAX_SIZE = 4096;
    private static final byte NO_ENTRY = -1;

    // Значение = generatorDetailLevel, на котором позиция была подтверждена полностью
    // сгенерированной. LRU-порядок поддерживается связностью Long2ByteLinkedOpenHashMap.
    private static final Long2ByteLinkedOpenHashMap MAP = createMap();
    private static final StampedLock LOCK = new StampedLock();

    private static final AtomicLong HITS = new AtomicLong(0);
    private static final AtomicLong MISSES = new AtomicLong(0);
    private static final AtomicLong CONFIRMATIONS = new AtomicLong(0);
    private static final AtomicLong EVICTIONS = new AtomicLong(0);

    private AeroCompleteSectionCache() {}

    private static Long2ByteLinkedOpenHashMap createMap() {
        Long2ByteLinkedOpenHashMap map = new Long2ByteLinkedOpenHashMap(DEFAULT_MAX_SIZE);
        map.defaultReturnValue(NO_ENTRY);
        return map;
    }

    private static boolean enabled() {
        return (AeroWorldConfig.DH_COMPLETE_SECTION_CACHE_ENABLED == null || AeroWorldConfig.DH_COMPLETE_SECTION_CACHE_ENABLED.get())
                && (AeroWorldConfig.DH_OVERRIDE_ENABLED == null || AeroWorldConfig.DH_OVERRIDE_ENABLED.get());
    }

    /**
     * @return {@code true}, если {@code pos} на данном {@code generatorDetailLevel} ранее был
     * подтверждён полностью сгенерированным и с тех пор не инвалидирован записью/удалением.
     */
    public static boolean isConfirmedComplete(long pos, byte generatorDetailLevel) {
        if (!enabled()) {
            return false;
        }
        long stamp = LOCK.readLock();
        try {
            byte stored = MAP.get(pos);
            return stored != NO_ENTRY && stored == generatorDetailLevel;
        } finally {
            LOCK.unlockRead(stamp);
        }
    }

    /**
     * Подтверждает, что {@code pos} на {@code generatorDetailLevel} полностью сгенерирован:
     * последний вызов оригинального {@code getPositionsToRetrieve} вернул пустой список.
     */
    public static void markComplete(long pos, byte generatorDetailLevel) {
        if (!enabled()) {
            return;
        }
        int maxSize = AeroWorldConfig.DH_COMPLETE_SECTION_CACHE_SIZE != null
                ? AeroWorldConfig.DH_COMPLETE_SECTION_CACHE_SIZE.get()
                : DEFAULT_MAX_SIZE;

        long stamp = LOCK.writeLock();
        try {
            MAP.putAndMoveToLast(pos, generatorDetailLevel);
            CONFIRMATIONS.incrementAndGet();
            while (MAP.size() > maxSize) {
                MAP.removeFirstByte();
                EVICTIONS.incrementAndGet();
            }
        } catch (Throwable t) {
            LOGGER.debug("[AeroWorld] Error recording complete section at pos {}: {}", pos, t.getMessage());
        } finally {
            LOCK.unlockWrite(stamp);
        }
    }

    /** Инвалидирует запись для конкретной позиции секции (вызывается при записи/удалении в БД). */
    public static void invalidate(long pos) {
        long stamp = LOCK.writeLock();
        try {
            MAP.remove(pos);
        } finally {
            LOCK.unlockWrite(stamp);
        }
    }

    public static void clear() {
        long stamp = LOCK.writeLock();
        try {
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

    public static void recordHit() {
        HITS.incrementAndGet();
    }

    public static void recordMiss() {
        MISSES.incrementAndGet();
    }

    public static long hits() {
        return HITS.get();
    }

    public static long misses() {
        return MISSES.get();
    }

    public static void logSummary() {
        long hits = HITS.get();
        long misses = MISSES.get();
        long total = hits + misses;
        if (total == 0) {
            return;
        }
        double hitPct = 100.0 * hits / total;
        LOGGER.info("[AeroWorld CompleteSectionCache Summary] {} lookups, {} hits ({}), {} misses, {} confirmations, {} evictions, size {}",
                total,
                hits,
                String.format(Locale.ROOT, "%.1f%%", hitPct),
                misses,
                CONFIRMATIONS.get(),
                EVICTIONS.get(),
                size());
    }
}
