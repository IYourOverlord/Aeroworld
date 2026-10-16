package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Диагностика (только измерение, включается вместе с {@link AeroPoolStats} флагом {@code -Daeroworld.dhPoolStats=true}):
 * раз в секунду снимает у общего пула DH, сколько задач выполняется и ждёт у пулов генерации, пропагатора, загрузки рендера,
 * IO и LOD Builder, и можно ли пулу генерации сейчас работать ({@code worldGenThreadsCanRun}). Итоги идут в строку {@code Pool}.
 */
final class AeroExecutorSampler {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private AeroExecutorSampler() {}

    static void startOnce() {
        if (!STARTED.compareAndSet(false, true)) return;
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroWorld Pool Sampler");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleAtFixedRate(() -> {
            try {
                sample();
            } catch (Throwable ignored) {
                // измерение не должно влиять на игру: пулы DH могут быть ещё не созданы или пересозданы
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static void sample() {
        PriorityTaskPicker.Executor[] ex = {
                ThreadPoolUtil.getWorldGenExecutor(), ThreadPoolUtil.getUpdatePropagatorExecutor(),
                ThreadPoolUtil.getRenderLoadingExecutor(), ThreadPoolUtil.getFileHandlerExecutor(),
                ThreadPoolUtil.getChunkToLodBuilderExecutor()}; // порядок как в AeroPoolStats.POOLS
        int[] running = new int[ex.length];
        int[] queue = new int[ex.length];
        long[] completed = new long[ex.length];
        double[] avgMs = new double[ex.length];
        for (int i = 0; i < ex.length; i++) {
            if (ex[i] == null) continue;
            running[i] = ex[i].getRunningTaskCount();
            queue[i] = ex[i].getQueueSize();
            completed[i] = ex[i].getCompletedTaskCount();
            avgMs[i] = ex[i].getAverageRunTimeInMs();
        }
        boolean canRun = ex[0] == null || ex[0].canRun();
        AeroPoolStats.sample(canRun, running, queue, completed, avgMs);
        sampleReasons(!canRun, queue[4]);
    }

    /** Условия паузы генерации из {@code ThreadPoolUtil.worldGenThreadsCanRun} (DH 3.3.2), посчитанные заново только для диагностики. */
    private static void sampleReasons(boolean paused, int lodQueue) {
        double speed = 0;
        boolean camera = false, unhealthy = false;
        try {
            speed = ClientApi.INSTANCE.getAvgCameraSpeed();
            camera = Config.Common.WorldGenerator.pauseIfMovingQuickly.get() && speed > LodUtil.ROCKET_ELYTRA_SPEED_IN_BLOCKS_PER_SEC - 10.0;
        } catch (Throwable ignored) {
            // клиентского API может не быть (выделенный сервер) или DH другой версии: причина просто не считается
        }
        try {
            unhealthy = Config.Common.WorldGenerator.limitIfServerUnhealthy.get()
                    && !SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class).isServerThreadHealthy();
        } catch (Throwable ignored) {
            // см. выше
        }
        AeroPoolStats.sampleReasons(paused, camera, lodQueue > 1000, unhealthy, speed, lodQueue);
    }
}
