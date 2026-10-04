package org.example.aeroworld.worldgen.dh;

import org.example.aeroworld.config.AeroWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Статистика и мониторинг пропускной способности (throughput) аналитического генератора DH SeedGen.
 * Логирует количество сгенерированных чанков и секций/сек с интервалом, настраиваемым в AeroWorldConfig.
 */
public class AeroThroughputLimits {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroThroughputLimits.class);

    /** Радиус реальной генерации R в блоках (0 = гибрид выключен или конфиг ещё не загружен). */
    public static int realRadiusBlocks() {
        try {
            return AeroWorldConfig.DH_REAL_CHUNK_RADIUS.get() << 4;
        } catch (IllegalStateException | NullPointerException e) {
            return 0;
        }
    }

    /** Количество вертикальных 16-блоковых секций в высоте мира AeroWorld (-64..2031 = 2096 блоков = 131 секция). */
    public static final int SECTIONS_PER_CHUNK = 131;

    /**
     * Граница региона реального chunk-gen DH (BatchGenerationEnvironmentNeoforgeMixin). По умолчанию 0 (замер этапа 0: в ~4 раза быстрее, без ошибок; было 4);
     * {@code -Daeroworld.dhBorder=N} (0..8) — для A/B-замера этапа 0. Читается один раз при загрузке класса, чтобы все
     * три редиректа миксина видели одно значение. Без границы возможны отказы вида «нет ключа в fallbackChunkGetterFunc».
     */
    /**
     * {@code -Daeroworld.dhTwoPhase=true} — вернуть двухфазный режим (аналитика SURFACE, затем реальные чанки через регенерацию DH).
     * По умолчанию однофазный: по логам этапа 0 регенерация голодает, пока очередь забита листьями вне R (38% листьев R не дошли до фазы 2).
     */
    public static final boolean TWO_PHASE = Boolean.getBoolean("aeroworld.dhTwoPhase");

    public static final int WORLD_GEN_BORDER = Math.max(0, Math.min(8, Integer.getInteger("aeroworld.dhBorder", 0)));

    public static final int QUEUE_SCALE = 4;
    public static final int IN_FLIGHT_SCALE = 4;
    /** Порог по умолчанию, пока конфиг не загружен; сам порог задаёт {@code renderYieldQueue} в конфиге мода. */
    public static final int DEFAULT_RENDER_YIELD_QUEUE = 2500;

    /** Длина очереди рендера, выше которой генерация ждёт (ключ {@code renderYieldQueue}, меняется без пересборки). */
    public static int renderYieldQueue() {
        try {
            return AeroWorldConfig.DH_RENDER_YIELD_QUEUE.get();
        } catch (IllegalStateException | NullPointerException e) {
            return DEFAULT_RENDER_YIELD_QUEUE;
        }
    }
    public static final int SAVE_DELAY_MS = 1000;
    public static final String SQLITE_SYNC = "NORMAL";

    public static int distantHorizonsThreadCount() {
        try {
            var configs = com.seibel.distanthorizons.api.DhApi.Delayed.configs;
            if (configs != null) {
                Object val = configs.multiThreading().threadCount().getValue();
                if (val instanceof Integer i && i > 0) return i;
            }
        } catch (Throwable ignored) {}
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private final AtomicLong totalChunksGenerated = new AtomicLong(0);
    private final AtomicLong windowChunksGenerated = new AtomicLong(0);

    private volatile long windowStartTime = System.currentTimeMillis();

    // Реальные листья (делегат DH chunk-gen) не входят в chunks выше: считаем их отдельно, чтобы в логе было видно,
    // чем заняты слоты, когда аналитика молчит. Учёт на генератор (измерение), как и основной отчёт.
    private final AtomicInteger realInFlight = new AtomicInteger();
    private final AtomicLong totalRealDone = new AtomicLong();
    private final AtomicLong windowRealDone = new AtomicLong();
    private final AtomicLong windowRealNs = new AtomicLong();

    /** Оборачивает фьючу реального листа (то же значение возвращается DH); время включает ожидание в пуле. */
    public CompletableFuture<Void> trackRealLeaf(CompletableFuture<Void> future) {
        long t0 = System.nanoTime();
        realInFlight.incrementAndGet();
        future.whenComplete((v, err) -> {
            realInFlight.decrementAndGet();
            totalRealDone.incrementAndGet();
            windowRealDone.incrementAndGet();
            windowRealNs.addAndGet(System.nanoTime() - t0);
            checkReport(); // иначе в окне без аналитики строка не появилась бы
        });
        return future;
    }

    // Аналитические задачи по уровню детализации (окно между строками Throughput): сколько завершилось и сколько времени
    // ушло на сам расчёт (без ожидания в очереди и пуле). Отделяет «мало задач» от «задачи медленные»: chunks/с в отчёте
    // это площадь секций, и к концу прогрузки падает просто потому, что остаются мелкие уровни.
    private static final int MAX_DETAIL_TRACKED = 16;
    private final AtomicLongArray windowDetailTasks = new AtomicLongArray(MAX_DETAIL_TRACKED);
    private final AtomicLongArray windowDetailNs = new AtomicLongArray(MAX_DETAIL_TRACKED);

    /** Завершена аналитическая задача уровня {@code detailLevel}; {@code computeNanos} — время работы самого генератора. */
    public void recordAnalyticTask(int detailLevel, long computeNanos) {
        int d = Math.max(0, Math.min(detailLevel, MAX_DETAIL_TRACKED - 1));
        windowDetailTasks.incrementAndGet(d);
        windowDetailNs.addAndGet(d, computeNanos);
    }

    /** Строка вида {@code d0 412 (13.7/s, 38 ms) d3 20 (0.7/s, 410 ms)} за окно; обнуляет счётчики окна. */
    private String drainAnalyticSummary(double elapsedSec) {
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < MAX_DETAIL_TRACKED; d++) {
            long n = windowDetailTasks.getAndSet(d, 0);
            long ns = windowDetailNs.getAndSet(d, 0);
            if (n == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append('d').append(d).append(' ').append(n).append(" (")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", elapsedSec > 0 ? n / elapsedSec : 0.0))
                    .append("/s, ").append(ns / 1_000_000L / n).append(" ms)");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public void recordChunksGenerated(int chunks) {
        totalChunksGenerated.addAndGet(chunks);
        windowChunksGenerated.addAndGet(chunks);
        checkReport();
    }

    public void onTaskStart() {
        checkReport();
    }

    private void checkReport() {
        long now = System.currentTimeMillis();
        long intervalSec = AeroWorldConfig.DH_THROUGHPUT_LOG_INTERVAL_SEC != null
                ? AeroWorldConfig.DH_THROUGHPUT_LOG_INTERVAL_SEC.get()
                : 30;
        long intervalMs = Math.max(5000L, intervalSec * 1000L);

        if (now - windowStartTime >= intervalMs) {
            synchronized (this) {
                if (now - windowStartTime >= intervalMs) {
                    long elapsedMs = now - windowStartTime;
                    long chunks = windowChunksGenerated.getAndSet(0);
                    windowStartTime = now;

                    double elapsedSec = elapsedMs / 1000.0;
                    long sections = chunks * SECTIONS_PER_CHUNK;
                    double sectionsPerSec = elapsedSec > 0 ? (sections / elapsedSec) : 0.0;
                    double chunksPerSec = elapsedSec > 0 ? (chunks / elapsedSec) : 0.0;

                    long realDone = windowRealDone.getAndSet(0);
                    long realNs = windowRealNs.getAndSet(0);
                    LOGGER.info("[AeroWorld DH SeedGen] Throughput: {} chunks ({} chunks/s, {} sections/s) over last {}s | Total chunks: {} | real leaves: {} done ({}/s, avg {} ms), inFlight={}, total={} | analytic tasks by detail: {}",
                            chunks,
                            String.format(java.util.Locale.ROOT, "%.1f", chunksPerSec),
                            String.format(java.util.Locale.ROOT, "%.1f", sectionsPerSec),
                            String.format(java.util.Locale.ROOT, "%.1f", elapsedSec),
                            totalChunksGenerated.get(),
                            realDone,
                            String.format(java.util.Locale.ROOT, "%.1f", elapsedSec > 0 ? realDone / elapsedSec : 0.0),
                            realDone == 0 ? 0 : realNs / 1_000_000L / realDone,
                            realInFlight.get(), totalRealDone.get(),
                            drainAnalyticSummary(elapsedSec));
                }
            }
        }
    }

    public long getTotalChunksGenerated() {
        return totalChunksGenerated.get();
    }

    // ── Диагностика гейтов троттлинга (WorldGenSpeedGateMixin, GeneratorBusyMixin) ──
    // Статические счётчики: сами гейты — static-миксины в DH-классы без доступа к
    // конкретному инстансу генератора уровня, поэтому статистика общая на JVM.
    // Цель: увидеть, КАКОЙ гейт и как часто режет генерацию, а не гадать по всплескам
    // chunks/s из основного отчёта выше.

    private static final Logger GATE_LOGGER = LoggerFactory.getLogger("AeroWorld-GateDiagnostics");
    private static final long GATE_REPORT_INTERVAL_MS = 5000L;

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int dhThreads = distantHorizonsThreadCount();
        GATE_LOGGER.info("[Environment] availableProcessors={} distantHorizonsThreadCount={} " +
                        "(DH runs ~5-6 pools at this size each: WorldGen/UpdatePropagator/RenderLoader/IO/LODBuilder)",
                cores, dhThreads);
    }

    private static final AtomicLong renderGateChecks = new AtomicLong(0);
    private static final AtomicLong renderGateBlocks = new AtomicLong(0);
    private static final AtomicInteger renderGateLastQueueSize = new AtomicInteger(0);
    private static final AtomicInteger renderGateMaxQueueSize = new AtomicInteger(0);

    private static final AtomicLong backlogGateChecks = new AtomicLong(0);
    private static final AtomicLong backlogGateBlocks = new AtomicLong(0);
    private static final AtomicInteger backlogGateLastInProgress = new AtomicInteger(0);
    private static final AtomicInteger backlogGateMaxInProgress = new AtomicInteger(0);
    private static volatile int backlogGateLastAllowed = 0;

    private static volatile long gateReportWindowStart = System.currentTimeMillis();

    /** Вызывается из {@code WorldGenSpeedGateMixin} на каждой проверке worldGenThreadsCanRun. */
    public static void recordRenderGateCheck(boolean blocked, int queueSize) {
        renderGateChecks.incrementAndGet();
        if (blocked) renderGateBlocks.incrementAndGet();
        renderGateLastQueueSize.set(queueSize);
        renderGateMaxQueueSize.updateAndGet(prev -> Math.max(prev, queueSize));
        reportGatesIfDue();
    }

    /** Вызывается из {@code GeneratorBusyMixin} на каждой проверке isGeneratorBusy. */
    public static void recordBacklogGateCheck(boolean blocked, int inProgress, int allowed) {
        backlogGateChecks.incrementAndGet();
        if (blocked) backlogGateBlocks.incrementAndGet();
        backlogGateLastInProgress.set(inProgress);
        backlogGateMaxInProgress.updateAndGet(prev -> Math.max(prev, inProgress));
        backlogGateLastAllowed = allowed;
        reportGatesIfDue();
    }

    private static void reportGatesIfDue() {
        long now = System.currentTimeMillis();
        if (now - gateReportWindowStart < GATE_REPORT_INTERVAL_MS) return;

        synchronized (AeroThroughputLimits.class) {
            if (now - gateReportWindowStart < GATE_REPORT_INTERVAL_MS) return;
            gateReportWindowStart = now;

            long rChecks = renderGateChecks.getAndSet(0);
            long rBlocks = renderGateBlocks.getAndSet(0);
            long bChecks = backlogGateChecks.getAndSet(0);
            long bBlocks = backlogGateBlocks.getAndSet(0);

            double rBlockedPct = rChecks > 0 ? (100.0 * rBlocks / rChecks) : 0.0;
            double bBlockedPct = bChecks > 0 ? (100.0 * bBlocks / bChecks) : 0.0;

            GATE_LOGGER.info("[RenderYieldGate] blocked {}% of {} checks | queue size last={} max={} (limit={})",
                    String.format(java.util.Locale.ROOT, "%.1f", rBlockedPct), rChecks,
                    renderGateLastQueueSize.get(), renderGateMaxQueueSize.getAndSet(0),
                    renderYieldQueue());

            GATE_LOGGER.info("[BacklogGate] blocked {}% of {} checks | inProgress last={} max={} allowed={}",
                    String.format(java.util.Locale.ROOT, "%.1f", bBlockedPct), bChecks,
                    backlogGateLastInProgress.get(), backlogGateMaxInProgress.getAndSet(0),
                    backlogGateLastAllowed);
        }
    }
}