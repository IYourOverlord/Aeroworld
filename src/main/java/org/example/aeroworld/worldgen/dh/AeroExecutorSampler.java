package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

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
        for (int i = 0; i < ex.length; i++) {
            if (ex[i] == null) continue;
            running[i] = ex[i].getRunningTaskCount();
            queue[i] = ex[i].getQueueSize();
        }
        AeroPoolStats.sample(ex[0] == null || ex[0].canRun(), running, queue);
    }
}
