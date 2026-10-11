package org.example.aeroworld.worldgen.dh;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Диагностика (только измерение): что делает пропагатор DH ({@code FullDataUpdatePropagatorV2}) в общем пуле потоков.
 * Задача вида 0 = «вверх» (дети обновляют родителя), вида 1 = «вниз» (родитель обновляет детей). Уровень = уровень детализации
 * секции-родителя. Для каждой задачи: время работы, ожидание в очереди пула, и повтор (та же позиция того же вида уже
 * обрабатывалась раньше в этом запуске): высокая доля повторов означает лавину избыточных обновлений.
 * Без MC/DH, проверка: {@link AeroPropStatsSelfCheck}. Включается {@code -Daeroworld.dhPoolStats=true}.
 */
public final class AeroPropStats {

    public static final boolean ENABLED = Boolean.getBoolean("aeroworld.dhPoolStats");

    private static final int MAX_DETAIL = 16;
    private static final int MAX_SEEN = 4_000_000;
    private static final String[] KIND = {"up", "down"};

    private static final AtomicLongArray COUNT = new AtomicLongArray(2 * MAX_DETAIL);
    private static final AtomicLongArray REPEAT = new AtomicLongArray(2 * MAX_DETAIL);
    private static final AtomicLongArray RUN_NS = new AtomicLongArray(2 * MAX_DETAIL);
    private static final AtomicLongArray WAIT_NS = new AtomicLongArray(2 * MAX_DETAIL);
    @SuppressWarnings("unchecked")
    private static final Set<Long>[] SEEN = new Set[]{ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet()};
    private static final ThreadLocal<long[]> PENDING = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE, 0});

    private AeroPropStats() { }

    /** Запоминает позицию (и её уровень), которую поток пропагатора только что взял в работу; сразу после неё идёт {@code execute}. */
    public static void notePending(long pos, int detail) {
        long[] p = PENDING.get();
        p[0] = pos;
        p[1] = detail;
    }

    /** Оборачивает задачу пула; без запомненной позиции возвращает задачу как есть. */
    public static Runnable wrap(int kind, Runnable task) {
        long[] p = PENDING.get();
        long pos = p[0];
        int detail = (int) p[1];
        p[0] = Long.MIN_VALUE;
        if (pos == Long.MIN_VALUE) return task;
        long submit = System.nanoTime();
        return () -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                record(kind, detail, pos, start - submit, System.nanoTime() - start);
            }
        };
    }

    static void record(int kind, int detail, long pos, long waitNs, long runNs) {
        int i = kind * MAX_DETAIL + Math.max(0, Math.min(detail, MAX_DETAIL - 1));
        Set<Long> seen = SEEN[kind];
        // ponytail: при переполнении множество просто сбрасывается, доля повторов после сброса занижена; потолок ~4 млн позиций
        if (seen.size() > MAX_SEEN) seen.clear();
        if (!seen.add(pos)) REPEAT.incrementAndGet(i);
        COUNT.incrementAndGet(i);
        RUN_NS.addAndGet(i, runNs);
        WAIT_NS.addAndGet(i, waitNs);
    }

    /** Итог окна и обнуление счётчиков; {@code null}, если задач не было. Множества «виденных» позиций не сбрасываются. */
    public static String drain(double elapsedSec) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int k = 0; k < 2; k++) {
            long n = 0, rep = 0, run = 0, wait = 0;
            StringBuilder per = new StringBuilder();
            for (int d = 0; d < MAX_DETAIL; d++) {
                int i = k * MAX_DETAIL + d;
                long c = COUNT.getAndSet(i, 0), r = REPEAT.getAndSet(i, 0), t = RUN_NS.getAndSet(i, 0), w = WAIT_NS.getAndSet(i, 0);
                if (c == 0) continue;
                n += c; rep += r; run += t; wait += w;
                per.append(String.format(Locale.ROOT, " d%d %d/%.0f%%", d, c, 100.0 * r / c));
            }
            if (k > 0) sb.append(" || ");
            if (n == 0) { sb.append(KIND[k]).append(": none"); continue; }
            any = true;
            sb.append(String.format(Locale.ROOT, "%s: n=%d (%.1f/s) threads=%.1f avg %.1f ms wait %.0f ms repeat %.0f%% | by detail (n/repeat):%s",
                    KIND[k], n, n / Math.max(elapsedSec, 1e-9), run / 1e9 / Math.max(elapsedSec, 1e-9),
                    run / 1e6 / n, wait / 1e6 / n, 100.0 * rep / n, per));
        }
        return any ? sb.toString() : null;
    }
}
