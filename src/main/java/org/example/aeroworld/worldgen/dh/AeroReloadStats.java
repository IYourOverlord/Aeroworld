package org.example.aeroworld.worldgen.dh;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Диагностика этапа 5б (только измерение): как часто DH перестраивает уже показанные секции. Каждое завершение задачи
 * генерации ставит в очередь перезагрузки секцию и четырёх соседей ({@code LodQuadTree.queuePosToReload}); здесь для каждой
 * такой постановки считается интервал с прошлой постановки той же позиции, а для реально начатых перестроек
 * ({@code uploadRenderDataToGpuAsync} из {@code reloadQueuedSections}) интервал с прошлой перестройки.
 * Доля постановок с интервалом меньше 2.5 с = верхняя оценка того, что убрала бы склейка (тишина 2.5 с).
 * Разбивка по уровню детализации секции. Без MC/DH, проверка: {@link AeroReloadStatsSelfCheck}.
 * Включается {@code -Daeroworld.dhPoolStats=true}.
 */
public final class AeroReloadStats {

    public static final boolean ENABLED = Boolean.getBoolean("aeroworld.dhPoolStats");

    private static final int MAX_DETAIL = 16;
    private static final int MAX_KEYS = 1_000_000;
    /** Границы интервалов, нс: 0.1 с, 1 с, 2.5 с, 8 с. */
    private static final long[] BOUND_NS = {100_000_000L, 1_000_000_000L, 2_500_000_000L, 8_000_000_000L};

    private static final AtomicLongArray TRIG = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray TRIG_FIRST = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray TRIG_LT25 = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray TRIG_BUCKET = new AtomicLongArray(BOUND_NS.length);
    private static final AtomicLongArray BUILD = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray BUILD_FAIL = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray BUILD_LT25 = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray BUILD_BUCKET = new AtomicLongArray(BOUND_NS.length);
    private static final ConcurrentHashMap<Long, Long> LAST_TRIG = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> LAST_BUILD = new ConcurrentHashMap<>();

    private AeroReloadStats() { }

    private static int clampDetail(int detail) { return Math.max(0, Math.min(detail, MAX_DETAIL - 1)); }

    /** Индекс первой границы, которую интервал не превышает; BOUND_NS.length, если интервал больше всех. */
    private static int bucket(long gapNs) {
        for (int i = 0; i < BOUND_NS.length; i++) if (gapNs < BOUND_NS[i]) return i;
        return BOUND_NS.length;
    }

    private static void bump(AtomicLongArray buckets, long gapNs) {
        int b = bucket(gapNs);
        if (b < BOUND_NS.length) buckets.incrementAndGet(b);
    }

    /** Постановка позиции в очередь перезагрузки (центр или сосед). */
    public static void noteTrigger(int detail, long pos, long nowNs) {
        int d = clampDetail(detail);
        // ponytail: при переполнении карта сбрасывается, интервалы сразу после сброса считаются «первыми»; потолок 1 млн позиций
        if (LAST_TRIG.size() > MAX_KEYS) LAST_TRIG.clear();
        Long prev = LAST_TRIG.put(pos, nowNs);
        TRIG.incrementAndGet(d);
        if (prev == null) { TRIG_FIRST.incrementAndGet(d); return; }
        long gap = nowNs - prev;
        bump(TRIG_BUCKET, gap);
        if (gap < BOUND_NS[2]) TRIG_LT25.incrementAndGet(d);
    }

    /** Попытка начать перестройку секции из очереди перезагрузки; {@code started} = false: DH не смог начать (вернёт в очередь). */
    public static void noteRebuild(int detail, long pos, long nowNs, boolean started) {
        int d = clampDetail(detail);
        if (!started) { BUILD_FAIL.incrementAndGet(d); return; }
        if (LAST_BUILD.size() > MAX_KEYS) LAST_BUILD.clear();
        Long prev = LAST_BUILD.put(pos, nowNs);
        BUILD.incrementAndGet(d);
        if (prev == null) return;
        long gap = nowNs - prev;
        bump(BUILD_BUCKET, gap);
        if (gap < BOUND_NS[2]) BUILD_LT25.incrementAndGet(d);
    }

    /** Итог окна и обнуление счётчиков; {@code null}, если событий не было. Карты «последних» позиций не сбрасываются. */
    public static String drain(double elapsedSec) {
        double sec = Math.max(elapsedSec, 1e-9);
        long trig = 0, first = 0, build = 0, fail = 0;
        StringBuilder per = new StringBuilder();
        for (int d = 0; d < MAX_DETAIL; d++) {
            long t = TRIG.getAndSet(d, 0), f = TRIG_FIRST.getAndSet(d, 0), l = TRIG_LT25.getAndSet(d, 0),
                    b = BUILD.getAndSet(d, 0), bf = BUILD_FAIL.getAndSet(d, 0), bl = BUILD_LT25.getAndSet(d, 0);
            if (t == 0 && b == 0 && bf == 0) continue;
            trig += t; first += f; build += b; fail += bf;
            per.append(String.format(Locale.ROOT, " d%d trig %d/%.0f%% build %d", d, t, t == 0 ? 0.0 : 100.0 * l / t, b));
        }
        long[] tb = takeBuckets(TRIG_BUCKET), bb = takeBuckets(BUILD_BUCKET);
        if (trig == 0 && build == 0 && fail == 0) return null;
        double tp = Math.max(trig, 1), bp = Math.max(build, 1);
        return String.format(Locale.ROOT,
                "trig n=%d (%.1f/s) first %.0f%% gap<0.1s %.0f%% <1s %.0f%% <2.5s %.0f%% <8s %.0f%% | rebuild n=%d (%.1f/s) failed %d gap<0.1s %.0f%% <1s %.0f%% <2.5s %.0f%% <8s %.0f%% | by detail (trig n/gap<2.5s, build n):%s",
                trig, trig / sec, 100.0 * first / tp, 100.0 * tb[0] / tp, 100.0 * tb[1] / tp, 100.0 * tb[2] / tp, 100.0 * tb[3] / tp,
                build, build / sec, fail, 100.0 * bb[0] / bp, 100.0 * bb[1] / bp, 100.0 * bb[2] / bp, 100.0 * bb[3] / bp, per);
    }

    /** Забирает корзины и возвращает накопленные суммы (интервал меньше границы i). */
    private static long[] takeBuckets(AtomicLongArray b) {
        long[] out = new long[BOUND_NS.length];
        long s = 0;
        for (int i = 0; i < BOUND_NS.length; i++) { s += b.getAndSet(i, 0); out[i] = s; }
        return out;
    }
}
