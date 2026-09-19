package org.example.aeroworld.worldgen.dh;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Объединение (coalescing) частых запросов перезагрузки секций LOD (ReloadCoalesceMixin).
 * Предотвращает лавинообразные повторные пересчёты квад-дерева при быстром обновлении чанков.
 */
public final class ReloadCoalescer {

    private static final long DEFAULT_QUIET_MS = 250L;
    private static final long DEFAULT_MAX_HOLD_MS = 2500L;

    private static final Long2LongOpenHashMap RELEASE_AT = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap DEADLINE = new Long2LongOpenHashMap();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong RELEASED = new AtomicLong();

    private ReloadCoalescer() {}

    public static void capture(long pos) {
        REQUESTS.incrementAndGet();
        long now = System.currentTimeMillis();
        synchronized (RELEASE_AT) {
            RELEASE_AT.put(pos, now + DEFAULT_QUIET_MS);
            if (!DEADLINE.containsKey(pos)) {
                DEADLINE.put(pos, now + DEFAULT_MAX_HOLD_MS);
            }
        }
    }

    public static void flush(LongConsumer queue) {
        LongOpenHashSet due = new LongOpenHashSet();
        long now = System.currentTimeMillis();

        synchronized (RELEASE_AT) {
            if (RELEASE_AT.isEmpty()) return;
            ObjectIterator<Long2LongMap.Entry> it = RELEASE_AT.long2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2LongMap.Entry entry = it.next();
                long pos = entry.getLongKey();
                long releaseAt = entry.getLongValue();
                long deadline = DEADLINE.get(pos);

                if (now >= releaseAt || now >= deadline) {
                    due.add(pos);
                    it.remove();
                    DEADLINE.remove(pos);
                }
            }
        }

        if (!due.isEmpty()) {
            RELEASED.addAndGet(due.size());
            LongIterator it = due.iterator();
            while (it.hasNext()) {
                queue.accept(it.nextLong());
            }
        }
    }

    public static void clear() {
        synchronized (RELEASE_AT) {
            RELEASE_AT.clear();
            DEADLINE.clear();
        }
        REQUESTS.set(0);
        RELEASED.set(0);
    }
}
