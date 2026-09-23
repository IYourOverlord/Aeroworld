package org.example.aeroworld.worldgen.dh;

import org.example.aeroworld.config.AeroWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Статистика и мониторинг пропускной способности (throughput) аналитического генератора DH SeedGen.
 * Логирует количество сгенерированных чанков и секций/сек с интервалом, настраиваемым в AeroWorldConfig.
 */
public class AeroThroughputLimits {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroThroughputLimits.class);

    /** Количество вертикальных 16-блоковых секций в высоте мира AeroWorld (-64..2031 = 2096 блоков = 131 секция). */
    public static final int SECTIONS_PER_CHUNK = 131;

    public static final int QUEUE_SCALE = 4;
    public static final int IN_FLIGHT_SCALE = 16;
    public static final int RENDER_YIELD_QUEUE = 200;
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

                    LOGGER.info("[AeroWorld DH SeedGen] Throughput: {} chunks ({} chunks/s, {} sections/s) over last {}s | Total chunks: {}",
                            chunks,
                            String.format(java.util.Locale.ROOT, "%.1f", chunksPerSec),
                            String.format(java.util.Locale.ROOT, "%.1f", sectionsPerSec),
                            String.format(java.util.Locale.ROOT, "%.1f", elapsedSec),
                            totalChunksGenerated.get());
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
                    RENDER_YIELD_QUEUE);

            GATE_LOGGER.info("[BacklogGate] blocked {}% of {} checks | inProgress last={} max={} allowed={}",
                    String.format(java.util.Locale.ROOT, "%.1f", bBlockedPct), bChecks,
                    backlogGateLastInProgress.get(), backlogGateMaxInProgress.getAndSet(0),
                    backlogGateLastAllowed);
        }
    }
}