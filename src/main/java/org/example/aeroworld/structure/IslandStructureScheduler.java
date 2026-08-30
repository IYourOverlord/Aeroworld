package org.example.aeroworld.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.example.aeroworld.AeroWorld;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Поток-безопасная очередь структур, ожидающих размещения на островах Layer 2.
 *
 * <h3>Жизненный цикл</h3>
 * <ol>
 *   <li>Worldgen-поток (C2ME) вызывает {@link #enqueue(int, int, ResourceLocation)}
 *       при генерации чанка. Y <b>не передаётся</b> — реальный Y острова
 *       определяется позже, в server-thread, по живому миру.</li>
 *   <li>Server-thread вызывает {@link #flushToPersistence(ServerLevel)} каждый тик,
 *       перекладывая записи в {@link PendingStructureData} (SavedData).</li>
 *   <li>{@link org.example.aeroworld.event.ProximityTriggerHandler} читает SavedData
 *       и выполняет фактический спавн по расписанию с backoff.</li>
 * </ol>
 *
 * <h3>Дедупликация</h3>
 * {@code seenChunks} — ConcurrentHashSet чанковых ключей (XZ). Гарантирует что
 * даже при параллельной генерации C2ME один остров попадёт в очередь один раз.
 * Дополнительная дедупликация — в {@link PendingStructureData#addEntry}.
 */
public class IslandStructureScheduler {

    private final ConcurrentLinkedQueue<PendingStructureData.Entry> queue =
            new ConcurrentLinkedQueue<>();

    /** Чанки (XZ), уже добавленные в очередь. Атомарный, C2ME-безопасный. */
    private final Set<Long> seenChunks = ConcurrentHashMap.newKeySet();

    // ── Worldgen-thread safe ──────────────────────────────────────────────────

    /**
     * Поставить структуру в очередь по XZ-центру острова.
     *
     * <p>Y не принимается намеренно: в worldgen-потоке C2ME heightmap может быть
     * ещё не финален. Реальный Y ищется в {@link
     * org.example.aeroworld.event.ProximityTriggerHandler#findRealSurface} уже
     * на server-thread по полностью загруженному чанку.</p>
     *
     * <p>Безопасно вызывать из нескольких worldgen-потоков одновременно.</p>
     *
     * @param islandBlockX X-координата центра острова (блоки, не чанки)
     * @param islandBlockZ Z-координата центра острова (блоки, не чанки)
     * @param id           ResourceLocation структуры в PhysicalStructures
     */
    public void enqueue(int islandBlockX, int islandBlockZ, ResourceLocation id) {
        // Пакуем чанк XZ в long для O(1) set-операции
        long chunkKey = (long)(islandBlockX >> 4) & 0xFFFFFFFFL
                | ((long)(islandBlockZ >> 4) & 0xFFFFFFFFL) << 32;

        if (!seenChunks.add(chunkKey)) {
            return;
        }

        // Y=0 — placeholder; реальный Y вычисляется в ProximityTriggerHandler
        BlockPos pos = new BlockPos(islandBlockX, 0, islandBlockZ);
        queue.add(new PendingStructureData.Entry(pos, id));

    }

    // ── Server-thread ─────────────────────────────────────────────────────────

    /**
     * Переложить всё накопленное из worldgen-потоков в SavedData.
     * Вызывать каждый тик из {@link org.example.aeroworld.event.ProximityTriggerHandler}.
     */
    public void flushToPersistence(ServerLevel level) {
        if (queue.isEmpty()) return;

        PendingStructureData data = PendingStructureData.getOrCreate(level);
        PendingStructureData.Entry entry;
        int count = 0;

        while ((entry = queue.poll()) != null) {
            // addEntry() дополнительно дедуплицирует на случай пересоздания scheduler
            data.addEntry(entry.pos(), entry.id());
            count++;
        }

        if (count > 0) {
        }
    }

    /**
     * Сбросить кеш виденных чанков.
     * Вызывать при смене мира или /reload, чтобы структуры в новом мире были обработаны заново.
     */
    public void resetSeenChunks() {
        seenChunks.clear();
    }
}