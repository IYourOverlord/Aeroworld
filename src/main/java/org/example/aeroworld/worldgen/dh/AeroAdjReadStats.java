package org.example.aeroworld.worldgen.dh;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Диагностика этапа 5 (только измерение): сколько потоко-времени уходит на чтения соседних секций
 * {@code FullDataSourceProviderV2.getAdjForDirection} (поток Render Loader). Для каждого чтения: время всего метода,
 * время самого запроса к БД ({@code repo.getAdjByPosAndDirection}), «строки нет» (запрос вернул {@code null}), повтор
 * ключа {@code (pos, направление)} в окне и за прогон. Разбивка по уровню детализации секции.
 * Решающее правило этапа: доля = потоки {@code adj} / потоки {@code render} (строка {@code Pool:}); см. TASK_STAGE5.md.
 * «Строка есть, но боковые данные пустые» не различается: у декодированного источника {@code isEmpty} всегда false.
 * Без MC/DH, проверка: {@link AeroAdjReadStatsSelfCheck}. Включается {@code -Daeroworld.dhPoolStats=true}.
 */
public final class AeroAdjReadStats {

    public static final boolean ENABLED = Boolean.getBoolean("aeroworld.dhPoolStats");

    private static final int MAX_DETAIL = 16;
    private static final int DIRS = 4;
    private static final int MAX_SEEN = 500_000;

    private static final AtomicLongArray COUNT = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray NO_ROW = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray REP_WIN = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray REP_RUN = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray TOTAL_NS = new AtomicLongArray(MAX_DETAIL);
    private static final AtomicLongArray REPO_NS = new AtomicLongArray(MAX_DETAIL);
    @SuppressWarnings("unchecked")
    private static final Set<Long>[] SEEN_RUN = newSets();
    @SuppressWarnings("unchecked")
    private static final Set<Long>[] SEEN_WIN = newSets();
    private static final AtomicBoolean[] HOOK_SEEN = {new AtomicBoolean(), new AtomicBoolean()};

    private AeroAdjReadStats() { }

    @SuppressWarnings("unchecked")
    private static Set<Long>[] newSets() {
        Set<Long>[] s = new Set[DIRS];
        for (int i = 0; i < DIRS; i++) s[i] = ConcurrentHashMap.newKeySet();
        return s;
    }

    /** true только при первом срабатывании инъекции {@code hook} (для отдельной строки подтверждения в логе). */
    public static boolean firstHit(int hook) { return HOOK_SEEN[hook].compareAndSet(false, true); }

    /** Одно чтение: {@code dir} 0..3 (горизонтальное направление), времена в наносекундах. */
    public static void record(int detail, int dir, long pos, long totalNs, long repoNs, boolean noRow) {
        int d = Math.max(0, Math.min(detail, MAX_DETAIL - 1));
        int k = dir & (DIRS - 1);
        Set<Long> run = SEEN_RUN[k];
        // ponytail: при переполнении множество за прогон сбрасывается, доля повторов за прогон после сброса занижена; потолок 500 тыс. ключей на направление
        if (run.size() > MAX_SEEN) run.clear();
        if (!run.add(pos)) REP_RUN.incrementAndGet(d);
        if (!SEEN_WIN[k].add(pos)) REP_WIN.incrementAndGet(d);
        COUNT.incrementAndGet(d);
        if (noRow) NO_ROW.incrementAndGet(d);
        TOTAL_NS.addAndGet(d, totalNs);
        REPO_NS.addAndGet(d, repoNs);
    }

    /** Итог окна и обнуление счётчиков и оконных ключей; {@code null}, если чтений не было. Ключи за прогон не сбрасываются. */
    public static String drain(double elapsedSec) {
        double sec = Math.max(elapsedSec, 1e-9);
        long n = 0, noRow = 0, repWin = 0, repRun = 0, total = 0, repo = 0;
        StringBuilder per = new StringBuilder();
        for (int d = 0; d < MAX_DETAIL; d++) {
            long c = COUNT.getAndSet(d, 0), nr = NO_ROW.getAndSet(d, 0), rw = REP_WIN.getAndSet(d, 0),
                    rr = REP_RUN.getAndSet(d, 0), t = TOTAL_NS.getAndSet(d, 0), r = REPO_NS.getAndSet(d, 0);
            if (c == 0) continue;
            n += c; noRow += nr; repWin += rw; repRun += rr; total += t; repo += r;
            per.append(String.format(Locale.ROOT, " d%d %d/%.0f%%/%.0f%%", d, c, 100.0 * nr / c, 100.0 * rr / c));
        }
        for (Set<Long> s : SEEN_WIN) s.clear();
        if (n == 0) return null;
        return String.format(Locale.ROOT,
                "n=%d (%.1f/s) threads=%.2f (repo %.2f) avg %.2f ms (repo %.2f) norow %.0f%% repeat win %.0f%% run %.0f%% | by detail (n/norow/repeat-run):%s",
                n, n / sec, total / 1e9 / sec, repo / 1e9 / sec, total / 1e6 / n, repo / 1e6 / n,
                100.0 * noRow / n, 100.0 * repWin / n, 100.0 * repRun / n, per);
    }
}
