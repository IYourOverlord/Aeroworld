package org.example.aeroworld.worldgen.dh;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Диагностика (только измерение): куда уходит время потоков пула генерации DH при аналитических задачах.
 * Для каждой задачи считаются: ожидание в очереди пула (постановка, запуск), работа самого генератора (body) и время
 * «после тела» (after-body): всё, что DH выполняет на том же потоке сразу после завершения нашей задачи (цепочка
 * зависимых колбэков {@code CompletableFuture}: снятие с учёта, запись и обновление данных секции и т.п.).
 * Без MC/DH, проверка: {@link AeroPoolStatsSelfCheck}. Включается {@code -Daeroworld.dhPoolStats=true}.
 */
final class AeroPoolStats {

    /** Один раз на JVM читается из системного свойства и передаётся в конструктор. */
    static final boolean ENABLED_BY_FLAG = Boolean.getBoolean("aeroworld.dhPoolStats");

    private static final int MAX_DETAIL = 16;

    /** Имена пулов DH, которые делят потоки (порядок индексов в {@link #sample}). */
    static final String[] POOLS = {"wg", "prop", "render", "io", "lod"};

    // Состояние выборки занятости пулов общее для всех экземпляров (сэмплер один на JVM).
    private static final AtomicLong SAMPLES = new AtomicLong();
    private static final AtomicLong WG_PAUSED = new AtomicLong();
    private static final AtomicLongArray SUM_RUNNING = new AtomicLongArray(POOLS.length);
    private static final AtomicLongArray SUM_QUEUE = new AtomicLongArray(POOLS.length);

    private final boolean enabled;
    private final AtomicLongArray count = new AtomicLongArray(MAX_DETAIL);
    private final AtomicLongArray waitNs = new AtomicLongArray(MAX_DETAIL);
    private final AtomicLongArray totalNs = new AtomicLongArray(MAX_DETAIL);
    private final AtomicLongArray bodyNs = new AtomicLongArray(MAX_DETAIL);
    private final AtomicLong waitMaxNs = new AtomicLong();
    private final AtomicLong afterMaxNs = new AtomicLong();

    AeroPoolStats(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Оборачивает пул: измеряет ожидание {@code submitNs -> запуск} и полное время работы потока над задачей, включая цепочку
     * колбэков, которую {@code CompletableFuture} выполняет внутри {@code Runnable.run()}. {@code body[0]} задача заполняет сама.
     * При выключенном флаге возвращает {@code ex} без изменений.
     */
    Executor wrap(Executor ex, int detailLevel, long submitNs, long[] body) {
        if (!enabled) return ex;
        return r -> ex.execute(() -> {
            long start = System.nanoTime();
            try {
                r.run();
            } finally {
                record(detailLevel, start - submitNs, System.nanoTime() - start, body[0]);
            }
        });
    }

    /**
     * Один срез состояния пулов DH (раз в секунду): {@code worldGenCanRun}, сколько задач выполняется и ждёт в очереди у каждого
     * пула из {@link #POOLS}. Показывает, кто занимает общие потоки и не стоит ли пул генерации на паузе.
     */
    static void sample(boolean worldGenCanRun, int[] running, int[] queue) {
        SAMPLES.incrementAndGet();
        if (!worldGenCanRun) WG_PAUSED.incrementAndGet();
        for (int i = 0; i < POOLS.length; i++) {
            SUM_RUNNING.addAndGet(i, running[i]);
            SUM_QUEUE.addAndGet(i, queue[i]);
        }
    }

    void record(int detailLevel, long wait, long total, long body) {
        int d = Math.max(0, Math.min(detailLevel, MAX_DETAIL - 1));
        count.incrementAndGet(d);
        waitNs.addAndGet(d, wait);
        totalNs.addAndGet(d, total);
        bodyNs.addAndGet(d, body);
        waitMaxNs.accumulateAndGet(wait, Math::max);
        afterMaxNs.accumulateAndGet(Math.max(0L, total - body), Math::max);
    }

    /** Итог окна и обнуление счётчиков; {@code null}, если задач не было. */
    String drain(double elapsedSec, int threads) {
        long tn = 0, tw = 0, tt = 0, tb = 0;
        StringBuilder per = new StringBuilder();
        for (int d = 0; d < MAX_DETAIL; d++) {
            long c = count.getAndSet(d, 0), w = waitNs.getAndSet(d, 0), t = totalNs.getAndSet(d, 0), b = bodyNs.getAndSet(d, 0);
            if (c == 0) continue;
            tn += c; tw += w; tt += t; tb += b;
            per.append(" d").append(d).append(" n=").append(c).append(" wait=").append(ms(w, c))
                    .append(" body=").append(ms(b, c)).append(" after=").append(ms(Math.max(0L, t - b), c));
        }
        long waitMax = waitMaxNs.getAndSet(0), afterMax = afterMaxNs.getAndSet(0);
        if (tn == 0) return null;
        double capacityNs = elapsedSec * 1e9 * Math.max(1, threads);
        String line = String.format(Locale.ROOT,
                "tasks=%d threads=%d busy=%.0f%% (body %.0f%% + after-body %.0f%%) | queue wait avg %s ms max %d ms | after-body max %d ms | per detail (avg ms):%s",
                tn, threads, 100.0 * tt / capacityNs, 100.0 * tb / capacityNs, 100.0 * Math.max(0L, tt - tb) / capacityNs,
                ms(tw, tn), waitMax / 1_000_000L, afterMax / 1_000_000L, per);
        return line + drainSamples(threads);
    }

    private static String drainSamples(int threads) {
        long n = SAMPLES.getAndSet(0), paused = WG_PAUSED.getAndSet(0);
        long[] run = new long[POOLS.length], que = new long[POOLS.length];
        for (int i = 0; i < POOLS.length; i++) {
            run[i] = SUM_RUNNING.getAndSet(i, 0);
            que[i] = SUM_QUEUE.getAndSet(i, 0);
        }
        if (n == 0) return "";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, " | samples=%d wg paused %.0f%% | avg running", n, 100.0 * paused / n));
        double all = 0;
        for (int i = 0; i < POOLS.length; i++) {
            all += (double) run[i] / n;
            sb.append(String.format(Locale.ROOT, " %s=%.1f", POOLS[i], (double) run[i] / n));
        }
        sb.append(String.format(Locale.ROOT, " (all=%.1f of %d) | avg queue", all, threads));
        for (int i = 0; i < POOLS.length; i++) sb.append(String.format(Locale.ROOT, " %s=%.0f", POOLS[i], (double) que[i] / n));
        return sb.toString();
    }

    private static String ms(long sumNs, long n) {
        return String.format(Locale.ROOT, "%.0f", sumNs / 1e6 / n);
    }
}
