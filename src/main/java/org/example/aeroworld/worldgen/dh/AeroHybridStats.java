package org.example.aeroworld.worldgen.dh;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Этап 0 гибридной прогрузки: измерения до правки логики. Только счётчики и лог раз в 30 с
 * (логгер {@code AeroWorld-HybridStats}); отключается {@code -Daeroworld.dhstats=false}.
 * <ol>
 *   <li><b>Повторные запросы</b>: {@link #onSection} помнит, какой веткой ответили на секцию в прошлый раз.
 *       Повтор после {@code OUTSIDE_FEATURES}/{@code PHASE2_REAL} = спин перезапроса (шаг SURFACE &lt; FEATURES
 *       не должен возникать), повтор после {@code PHASE1} = штатный переход в фазу 2.</li>
 *   <li><b>Порядок очереди</b>: {@link #onLeafStart} — при старте листа сколько грубых задач ещё ждёт
 *       в очереди и сколько из них накрывают этот лист (лист обогнал собственного предка).</li>
 *   <li><b>Цена реальных чанков в R</b>: {@link #timeReal} — время от вызова делегата до завершения фьючи
 *       (включая ожидание в пуле), для сравнения — время аналитики по detail.</li>
 * </ol>
 * После этапа 0 класс и его вызовы удалить.
 * <p>
 * ponytail: карта «секция -> последняя ветка» без вытеснения, лимит {@link #TRACK_CAP} записей (≈60 байт каждая),
 * дальше секции считаются в {@code untracked}. Счётчики общие на JVM (как гейты в {@link AeroThroughputLimits}).
 */
public final class AeroHybridStats {

    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("aeroworld.dhstats", "true"));

    /** Чем ответил генератор на запрос секции. */
    public enum Branch { COARSE, PHASE1, PHASE2_REAL, OUTSIDE_FEATURES, REAL_UNAVAILABLE }

    private static final Logger LOG = LoggerFactory.getLogger("AeroWorld-HybridStats");
    private static final Branch[] BRANCHES = Branch.values();
    private static final int B = BRANCHES.length;
    private static final int TRACK_CAP = 500_000;
    private static final int MAX_DETAIL = 16;
    private static final long REPORT_INTERVAL_MS = 30_000L;
    private static final int SAMPLE_LIMIT = 10;

    private static final ConcurrentHashMap<Long, Integer> LAST_BRANCH = new ConcurrentHashMap<>();
    private static final AtomicLongArray FIRST = new AtomicLongArray(B);
    private static final AtomicLongArray REPEAT = new AtomicLongArray(B * B); // [prev * B + cur]
    private static final AtomicLong UNTRACKED = new AtomicLong();

    private static final AtomicLongArray ANALYTIC_CALLS = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray ANALYTIC_NS = new AtomicLongArray(MAX_DETAIL);

    private static final AtomicLong REAL_CALLS = new AtomicLong();
    private static final AtomicLong REAL_FAILED = new AtomicLong();
    private static final AtomicLong REAL_NS = new AtomicLong();
    private static final AtomicLong REAL_MAX_NS = new AtomicLong();
    private static final AtomicInteger REAL_IN_FLIGHT = new AtomicInteger();

    private static final AtomicLong LEAF_STARTS = new AtomicLong();
    private static final AtomicLong LEAF_WITH_COARSE = new AtomicLong();
    private static final AtomicLong LEAF_WITH_ANCESTOR = new AtomicLong();
    private static final AtomicLong COARSE_WAITING_SUM = new AtomicLong();
    private static final AtomicInteger SAMPLES = new AtomicInteger();

    /** Листья, ответ на которые был PHASE1 и повторного запроса ещё не было: ключ -> время (мс) первого ответа. */
    private static final ConcurrentHashMap<Long, Long> PHASE1_AT = new ConcurrentHashMap<>();
    private static final long STUCK_AFTER_MS = 60_000L;
    private static final int STUCK_SAMPLES = 5;

    /**
     * Описание застрявшего листа для отчёта (lodX, lodZ) -> строка; задаёт генератор, у которого есть доступ к DH/игрокам.
     * null — только координаты.
     */
    public static volatile java.util.function.BiFunction<Integer, Integer, String> stuckProbe;

    private static final AtomicLong NEXT_REPORT_MS = new AtomicLong(System.currentTimeMillis() + REPORT_INTERVAL_MS);

    private AeroHybridStats() {}

    /** Ключ секции: detail в старших битах, x/z по 29 бит (знак отбрасывается маской — коллизии только за ±2^28 секций). */
    static long key(int detailLevel, int lodX, int lodZ) {
        return ((long) detailLevel << 58) | ((long) (lodX & 0x1FFFFFFF) << 29) | (lodZ & 0x1FFFFFFFL);
    }

    public static void onSection(int detailLevel, int lodX, int lodZ, Branch branch) {
        if (!ENABLED) return;
        if (LAST_BRANCH.size() >= TRACK_CAP) {
            UNTRACKED.incrementAndGet();
            return;
        }
        long k = key(detailLevel, lodX, lodZ);
        if (branch == Branch.PHASE1) PHASE1_AT.putIfAbsent(k, System.currentTimeMillis());
        else PHASE1_AT.remove(k);
        Integer prev = LAST_BRANCH.put(k, branch.ordinal());
        if (prev == null) FIRST.incrementAndGet(branch.ordinal());
        else REPEAT.incrementAndGet(prev * B + branch.ordinal());
        maybeReport();
    }

    public static void onAnalytic(int detailLevel, long nanos) {
        if (!ENABLED) return;
        int d = Math.min(detailLevel, MAX_DETAIL - 1);
        ANALYTIC_CALLS.incrementAndGet(d);
        ANALYTIC_NS.addAndGet(d, nanos);
    }

    /** Оборачивает фьючу делегата реального chunk-gen; возвращает ту же фьючу (поведение DH не меняется). */
    public static CompletableFuture<Void> timeReal(CompletableFuture<Void> future) {
        if (!ENABLED) return future;
        long t0 = System.nanoTime();
        REAL_IN_FLIGHT.incrementAndGet();
        future.whenComplete((v, err) -> {
            long ns = System.nanoTime() - t0;
            REAL_IN_FLIGHT.decrementAndGet();
            REAL_CALLS.incrementAndGet();
            if (err != null) REAL_FAILED.incrementAndGet();
            REAL_NS.addAndGet(ns);
            REAL_MAX_NS.accumulateAndGet(ns, Math::max);
        });
        return future;
    }

    /** Старт листовой (detail 6) задачи очереди: {@code coarseWaiting} — ждущих грубее листа, {@code ancestorWaiting} — из них накрывающих лист. */
    public static void onLeafStart(int coarseWaiting, int ancestorWaiting, int sectionX, int sectionZ) {
        if (!ENABLED) return;
        LEAF_STARTS.incrementAndGet();
        COARSE_WAITING_SUM.addAndGet(coarseWaiting);
        if (coarseWaiting > 0) LEAF_WITH_COARSE.incrementAndGet();
        if (ancestorWaiting > 0) {
            LEAF_WITH_ANCESTOR.incrementAndGet();
            if (SAMPLES.getAndIncrement() < SAMPLE_LIMIT) {
                LOG.info("[Order] leaf 6*{},{} started while {} covering coarse task(s) still waiting ({} coarse waiting total)",
                        sectionX, sectionZ, ancestorWaiting, coarseWaiting);
            }
        }
        maybeReport();
    }

    private static void maybeReport() {
        long now = System.currentTimeMillis();
        long next = NEXT_REPORT_MS.get();
        if (now < next || !NEXT_REPORT_MS.compareAndSet(next, now + REPORT_INTERVAL_MS)) return;
        report();
    }

    private static void report() {
        StringBuilder rep = new StringBuilder();
        for (int p = 0; p < B; p++) {
            for (int c = 0; c < B; c++) {
                long n = REPEAT.get(p * B + c);
                if (n > 0) rep.append(' ').append(BRANCHES[p]).append("->").append(BRANCHES[c]).append('=').append(n);
            }
        }
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < B; i++) first.append(' ').append(BRANCHES[i]).append('=').append(FIRST.get(i));
        LOG.info("[Repeats] tracked={} untracked={} | first:{} | repeat(prev->now):{}",
                LAST_BRANCH.size(), UNTRACKED.get(), first, rep.length() == 0 ? " none" : rep);

        reportStuck(System.currentTimeMillis());

        long starts = LEAF_STARTS.get();
        LOG.info("[Order] leaf starts={} | with any coarse waiting={}% (avg {} waiting) | with own coarse ancestor waiting={}%",
                starts, pct(LEAF_WITH_COARSE.get(), starts),
                starts == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.1f", COARSE_WAITING_SUM.get() / (double) starts),
                pct(LEAF_WITH_ANCESTOR.get(), starts));

        long calls = REAL_CALLS.get();
        double avgMs = calls == 0 ? 0 : REAL_NS.get() / 1e6 / calls;
        StringBuilder an = new StringBuilder();
        for (int d = 0; d < MAX_DETAIL; d++) {
            long n = ANALYTIC_CALLS.get(d);
            if (n > 0) an.append(String.format(java.util.Locale.ROOT, " d%d=%.1fms(n=%d)", d, ANALYTIC_NS.get(d) / 1e6 / n, n));
        }
        Runtime rt = Runtime.getRuntime();
        LOG.info("[Real] leaves={} failed={} inFlight={} avg={}ms/leaf (~{}ms/chunk, 16 chunks per leaf) max={}ms | analytic avg:{} | heap {}MB/{}MB",
                calls, REAL_FAILED.get(), REAL_IN_FLIGHT.get(),
                String.format(java.util.Locale.ROOT, "%.0f", avgMs), String.format(java.util.Locale.ROOT, "%.0f", avgMs / 16),
                REAL_MAX_NS.get() / 1_000_000L, an.length() == 0 ? " none" : an,
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
    }

    /** Листья, оставшиеся в фазе 1 дольше {@link #STUCK_AFTER_MS}: сколько их и 5 самых старых с описанием от {@link #stuckProbe}. */
    private static void reportStuck(long nowMs) {
        int stuck = 0;
        long[] oldestKey = new long[STUCK_SAMPLES];
        long[] oldestAt = new long[STUCK_SAMPLES];
        int filled = 0;
        for (var e : PHASE1_AT.entrySet()) {
            long at = e.getValue();
            if (nowMs - at < STUCK_AFTER_MS) continue;
            stuck++;
            // вставка в короткий список самых старых (STUCK_SAMPLES мал, O(n*5) на отчёт раз в 30 с)
            int i = filled;
            if (filled < STUCK_SAMPLES) filled++;
            else if (at >= oldestAt[STUCK_SAMPLES - 1]) continue;
            else i = STUCK_SAMPLES - 1;
            while (i > 0 && oldestAt[i - 1] > at) { oldestAt[i] = oldestAt[i - 1]; oldestKey[i] = oldestKey[i - 1]; i--; }
            oldestAt[i] = at;
            oldestKey[i] = e.getKey();
        }
        if (stuck == 0 && PHASE1_AT.isEmpty()) return;
        LOG.info("[Stuck] phase1 waiting={} (older than {}s: {})", PHASE1_AT.size(), STUCK_AFTER_MS / 1000, stuck);
        var probe = stuckProbe;
        for (int i = 0; i < filled; i++) {
            int x = decodeCoord(oldestKey[i] >> 29), z = decodeCoord(oldestKey[i]);
            String extra = "";
            try { if (probe != null) extra = " " + probe.apply(x, z); } catch (Throwable t) { extra = " probe failed: " + t; }
            LOG.info("[Stuck]   leaf 6*{},{} waiting {}s{}", x, z, (nowMs - oldestAt[i]) / 1000, extra);
        }
    }

    /** Обратно к {@link #key}: 29 бит со знаком. */
    static int decodeCoord(long packed) {
        return (int) ((packed & 0x1FFFFFFFL) << 3) >> 3;
    }

    private static String pct(long part, long total) {
        return total == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.1f", 100.0 * part / total);
    }

    // для self-check
    static int stuckCount(long nowMs) {
        int n = 0;
        for (long at : PHASE1_AT.values()) if (nowMs - at >= STUCK_AFTER_MS) n++;
        return n;
    }
    static int phase1Waiting() { return PHASE1_AT.size(); }
    static long firstCount(Branch b) { return FIRST.get(b.ordinal()); }
    static long repeatCount(Branch prev, Branch now) { return REPEAT.get(prev.ordinal() * B + now.ordinal()); }
}