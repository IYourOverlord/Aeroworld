package org.example.aeroworld.worldgen.dh;

import org.example.aeroworld.config.AeroWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public static final int IN_FLIGHT_SCALE = 4;
    public static final int RENDER_YIELD_QUEUE = Integer.MAX_VALUE;
    public static final int RENDER_PRIORITY = 2;
    public static final int SAVE_DELAY_MS = 10000;
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
}