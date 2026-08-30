package org.example.aeroworld.worldgen.structure;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.cache.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Реестр стартов структур Layer 1 и производный от него граф "пещерных
 * тоннелей", соединяющих каждую новую структуру с ближайшей уже известной.
 *
 * <p><b>Почему не через SavedData/ServerLevel:</b> регистрация происходит
 * из {@code createStructures}, вызываемого worldgen-потоками (в т.ч. C2ME)
 * до и независимо от готовности {@code ServerLevel}/{@code DataStorage}.
 * Реестр — чисто in-memory, per-{@code ChunkGenerator}-инстанс состояние,
 * пересоздаваемое при смене seed (см. {@code AeroWorldChunkGenerator}),
 * по тому же паттерну, что {@code ChunkIslandCache}.</p>
 *
 * <p><b>Производительность:</b> регистрация структуры (редкое событие —
 * разы на структуру, не на чанк) делает линейный скан уже известных узлов
 * (их количество на Layer 1 ограничено — деревни/крепости/etc.), считает
 * ближайшего ещё не соединённого соседа и строит путь ОДИН раз, раскладывая
 * его по chunk-bucket индексу. Carve-путь ({@link #segmentsForChunk}) —
 * lock-free O(1) поиск в {@code ConcurrentHashMap}, без пересчёта геометрии —
 * подавляющее большинство чанков (без тоннеля) выходит мгновенно.</p>
 */
public final class StructureLinkRegistry {

    /** Не соединяем структуры дальше этого расстояния — иначе тоннель через полмира. */
    private static final double MAX_LINK_DISTANCE = 3000.0;

    /** Диаметр тоннеля (по заданию — 12 блоков). */
    public static final int TUNNEL_DIAMETER = 12;
    public static final double TUNNEL_RADIUS = TUNNEL_DIAMETER / 2.0;

    public record StructureNode(long id, double x, double z, int surfaceY, long startChunkKey) {}

    /**
     * Путевая точка тоннеля с заранее посчитанным шумовым смещением
     * (детерминированным по паре узлов, не зависящим от порядка обхода чанков).
     */
    public record TunnelSegment(double ax, double ay, double az,
                                double bx, double by, double bz,
                                long linkSeed) {}

    private final List<StructureNode> nodes = new ArrayList<>();
    private final ConcurrentHashMap<Long, Boolean> knownStarts = new ConcurrentHashMap<>();
    /** Lock-free чтение на частом carve-пути; запись — редко, при построении тоннеля. */
    private final ConcurrentHashMap<Long, List<TunnelSegment>> segmentsByChunk = new ConcurrentHashMap<>();
    /** Чанки, для которых applyCarvers уже отработал — см. {@link #markChunkCarved}.
     *  Отдельная блокировка от {@link #lock}: вызывается на КАЖДЫЙ чанк (часто),
     *  тогда как lock защищает редкую операцию регистрации структуры — раздельные
     *  локи убирают contention между частым путём (carve) и редким (register). */
    private final LongOpenHashSet carvedChunks = new LongOpenHashSet();
    private final Object carvedChunksLock = new Object();
    private final ReentrantLock lock = new ReentrantLock();

    private long nextId = 0L;

    /** Гарантирует восстановление из SavedData ровно один раз за сессию. */
    private final java.util.concurrent.atomic.AtomicBoolean restoredFromPersistence =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Счётчики для периодической диагностики — см. {@link #logStats()}. */
    private final AtomicInteger linksBuilt = new AtomicInteger();
    private final AtomicInteger linksMissedAfterCarve = new AtomicInteger();

    /**
     * Регистрирует структуру (если ещё не зарегистрирована по её уникальному
     * стартовому ключу) и, если найден подходящий сосед, прокладывает к нему
     * тоннель. Потокобезопасно (C2ME может вызывать параллельно из разных
     * worker-потоков createStructures разных чанков).
     *
     * @param startChunkX chunk X старта структуры (часть ключа дедупликации)
     * @param startChunkZ chunk Z старта структуры
     * @param worldX      мировая X координата центра структуры (bounding box)
     * @param worldZ      мировая Z координата центра структуры
     * @param surfaceY    высота поверхности в центре структуры (Layer 1)
     */
    public void registerStructure(int startChunkX, int startChunkZ,
                                  double worldX, double worldZ, int surfaceY) {
        long startKey = ChunkKey.of(startChunkX, startChunkZ);
        if (knownStarts.putIfAbsent(startKey, Boolean.TRUE) != null) {
            return; // уже зарегистрирована этим стартовым чанком
        }

        lock.lock();
        try {
            StructureNode nearest = null;
            double bestDist2 = Double.MAX_VALUE;
            for (StructureNode candidate : nodes) {
                double dx = candidate.x() - worldX;
                double dz = candidate.z() - worldZ;
                double d2 = dx * dx + dz * dz;
                if (d2 < bestDist2) {
                    bestDist2 = d2;
                    nearest = candidate;
                }
            }

            StructureNode self = new StructureNode(nextId++, worldX, worldZ, surfaceY, startKey);
            nodes.add(self);

            AeroWorld.LOGGER.debug(
                    "[AeroWorld][StructureLink] Узел #{} зарегистрирован @ ({}, Y={}, {}). Известных узлов: {}.",
                    self.id(), (int) worldX, surfaceY, (int) worldZ, nodes.size());

            if (nearest == null) {
                AeroWorld.LOGGER.debug(
                        "[AeroWorld][StructureLink] Узел #{} — первый в реестре, соседей ещё нет, тоннель не строится.",
                        self.id());
                return;
            }

            double dist = Math.sqrt(bestDist2);
            if (bestDist2 > MAX_LINK_DISTANCE * MAX_LINK_DISTANCE) {
                AeroWorld.LOGGER.debug(
                        "[AeroWorld][StructureLink] Узел #{} — ближайший сосед #{} на расстоянии {} блоков > MAX_LINK_DISTANCE={}, тоннель не строится.",
                        self.id(), nearest.id(), (int) dist, (int) MAX_LINK_DISTANCE);
                return;
            }

            buildAndIndexTunnel(nearest, self, dist);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Помечает чанк как уже прошедший applyCarvers — вызывается безусловно
     * из {@code AeroWorldChunkGenerator.applyCarvers} для КАЖДОГО чанка,
     * независимо от того, есть в нём тоннель или нет. Нужно исключительно
     * для диагностики race «структура открыта после того, как чанк по пути
     * к ней уже сгенерирован» — см. {@link #indexSegmentByChunk}.
     */
    public void markChunkCarved(int chunkX, int chunkZ) {
        synchronized (carvedChunksLock) {
            carvedChunks.add(ChunkKey.of(chunkX, chunkZ));
        }
    }

    /**
     * Строит путь между двумя узлами (ломаная с шумовым прогибом по вертикали,
     * применяемым карвером во время резки — здесь фиксируются только опорные
     * точки и per-link seed) и индексирует каждый отрезок по чанкам, которые
     * он пересекает, добавляя запас в {@code TUNNEL_RADIUS} блоков на неровности.
     */
    private void buildAndIndexTunnel(StructureNode a, StructureNode b, double dist) {
        long linkSeed = mixSeed(a.id(), b.id());

        // Средняя высота между двумя точками — тоннель идёт примерно по
        // высоте пола между структурами, с запасом ниже нижней поверхности,
        // чтобы не выходить наружу на неровном рельефе.
        double midY = Math.min(a.surfaceY(), b.surfaceY()) - 6.0;

        TunnelSegment segment = new TunnelSegment(
                a.x(), midY, a.z(),
                b.x(), midY, b.z(),
                linkSeed);

        int alreadyCarved = indexSegmentByChunk(segment);
        linksBuilt.incrementAndGet();

        AeroWorld.LOGGER.info(
                "[AeroWorld][StructureLink] Тоннель построен: #{} <-> #{}, длина={} блоков, ось Y~{}.{}",
                a.id(), b.id(), (int) dist, (int) midY,
                alreadyCarved > 0
                        ? " ВНИМАНИЕ: " + alreadyCarved + " из чанков вдоль пути уже прошли applyCarvers"
                        + " ДО регистрации этого линка — там тоннель НЕ появится (см. StructureLinkTunnelCarver)."
                        : "");

        if (alreadyCarved > 0) {
            linksMissedAfterCarve.incrementAndGet();
        }
    }

    /**
     * @return количество уже carve'нутых чанков среди задетых сегментом
     *         (диагностика race, см. {@link #markChunkCarved}).
     */
    private int indexSegmentByChunk(TunnelSegment seg) {
        double dx = seg.bx() - seg.ax();
        double dz = seg.bz() - seg.az();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3) return 0;

        // Шагаем вдоль линии с шагом в пол-чанка, чтобы не пропустить
        // диагональные пересечения — O(длина / 8) итераций, разово.
        int steps = (int) Math.ceil(length / 8.0) + 1;
        double margin = TUNNEL_RADIUS + 8.0; // запас на шумовой прогиб оси

        int alreadyCarvedCount = 0;
        LongOpenHashSet touchedChunks = new LongOpenHashSet();

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double wx = seg.ax() + dx * t;
            double wz = seg.az() + dz * t;

            int minCx = (int) Math.floor((wx - margin)) >> 4;
            int maxCx = (int) Math.floor((wx + margin)) >> 4;
            int minCz = (int) Math.floor((wz - margin)) >> 4;
            int maxCz = (int) Math.floor((wz + margin)) >> 4;

            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    long key = ChunkKey.of(cx, cz);
                    if (!touchedChunks.add(key)) continue;

                    List<TunnelSegment> list = segmentsByChunk.computeIfAbsent(
                            key, k -> new CopyOnWriteArrayList<>());
                    if (!list.contains(seg)) {
                        list.add(seg);
                    }

                    if (isChunkCarved(key)) {
                        alreadyCarvedCount++;
                    }
                }
            }
        }
        return alreadyCarvedCount;
    }

    private boolean isChunkCarved(long chunkKey) {
        synchronized (carvedChunksLock) {
            return carvedChunks.contains(chunkKey);
        }
    }

    /**
     * O(1)-lookup: список сегментов тоннелей, потенциально пересекающих
     * данный чанк. Пустой список (без аллокации) для подавляющего
     * большинства чанков.
     */
    public List<TunnelSegment> segmentsForChunk(int chunkX, int chunkZ) {
        List<TunnelSegment> list = segmentsByChunk.get(ChunkKey.of(chunkX, chunkZ));
        return list == null ? List.of() : list;
    }

    private static long mixSeed(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    // ── Персистентность (StructureLinkData) ─────────────────────────────────

    /**
     * Снимок всех известных узлов — для сохранения в {@code StructureLinkData}.
     * Вызывается со server-thread (тик-хендлер), не из worldgen-потоков.
     */
    public List<StructureNode> snapshotNodes() {
        lock.lock();
        try {
            return List.copyOf(nodes);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Восстанавливает узлы из {@code StructureLinkData} при старте мира —
     * БЕЗ повторного построения тоннелей (они либо уже вырезаны в
     * сохранённых чанках, либо будут восстановлены естественным образом
     * при повторном посещении негенерированных чанков в новой сессии).
     * Нужно только чтобы {@code nextId}/{@code knownStarts}/{@code nodes}
     * не начинались с нуля и новые структуры продолжали корректно искать
     * ближайшего соседа среди уже открытых в прошлых сессиях.
     */
    public void restoreNodes(List<StructureNode> restored) {
        if (!restoredFromPersistence.compareAndSet(false, true)) return; // уже восстановлено ранее в этой сессии
        lock.lock();
        try {
            nodes.addAll(restored);
            for (StructureNode n : restored) {
                nextId = Math.max(nextId, n.id() + 1);
                knownStarts.putIfAbsent(n.startChunkKey(), Boolean.TRUE);
            }
            AeroWorld.LOGGER.info(
                    "[AeroWorld][StructureLink] Восстановлено {} узлов из StructureLinkData (предыдущая сессия).",
                    restored.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Периодическая диагностика — вызывать раз в N тиков из тик-хендлера.
     * Печатает агрегированную статистику сети тоннелей одной строкой,
     * без построчного дампа (который был бы слишком многословным на
     * больших мирах).
     */
    public void logStats() {
        int nodeCount;
        lock.lock();
        try {
            nodeCount = nodes.size();
        } finally {
            lock.unlock();
        }
        AeroWorld.LOGGER.info(
                "[AeroWorld][StructureLink] Статистика сети: узлов={}, тоннелей построено={}, "
                        + "тоннелей с потерянными сегментами (чанк был carve'нут раньше открытия структуры)={}.",
                nodeCount, linksBuilt.get(), linksMissedAfterCarve.get());
    }
}