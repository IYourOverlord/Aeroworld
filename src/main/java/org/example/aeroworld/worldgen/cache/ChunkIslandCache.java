package org.example.aeroworld.worldgen.cache;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * Общий кэш списков центров островов для всех слоёв генератора.
 *
 * <h3>Зачем это нужно</h3>
 * {@code IslandPlacer.getIslandCentresForChunk(chunkX, chunkZ, searchRadius)}
 * обходит сетку ячеек в радиусе {@code searchRadius} и для каждой ячейки
 * вычисляет хэш + проверку вероятности. При searchRadius=8 это
 * <b>(2*8+1)² = 289 хэш-вычислений</b> на один вызов.
 *
 * В {@code fillChunk} этот метод вызывается один раз — хорошо.
 * Но в {@code restoreIslandsInChunk} (вызов из {@code applyCarvers})
 * он вызывается снова для того же чанка. С кэшом — второй вызов мгновенный.
 *
 * <h3>Общий кэш вместо трёх отдельных</h3>
 * Ранее каждый из трёх генераторов (Lower, High, Upper) держал свой экземпляр
 * {@code ChunkIslandCache} с {@code MAX_SIZE=1024} — итого 3072 слота и три
 * {@code ConcurrentHashMap}. После объединения — один {@code ConcurrentHashMap}
 * на 1024 слота, ключ расширен {@code layerId}.
 *
 * <h3>Кодирование ключа</h3>
 * {@code key = (layerId & 0xFFL) << 56 | (chunkX & 0xFFFFFFFL) << 28 | (chunkZ & 0xFFFFFFFL)}
 * <ul>
 *   <li>8 бит под {@code layerId} — достаточно для любого числа слоёв.</li>
 *   <li>28 бит под {@code chunkX} и 28 бит под {@code chunkZ} отдельно — каждая ось
 *       перекрывает ±134M чанков, с огромным запасом покрывает границу мира
 *       Minecraft (±30M блоков = ±1.875M чанков, нужно 21 бит).</li>
 *   <li>Нет коллизий: разные (layerId, chunkX, chunkZ) → разные ключи.</li>
 * </ul>
 *
 * <h3>Данные — LongArrayList</h3>
 * <p>Центры островов упакованы в {@code long} через {@link ChunkKey#of(int,int)},
 * хранятся в {@link LongArrayList} (fastutil, primitive long[]) — нулевые аллокации
 * на горячем пути (ни {@code int[]}, ни боксинг {@code Long}).</p>
 *
 * <h3>Размер кэша</h3>
 * Одновременно в генерации находится не более ~128–256 чанков (ванильный
 * движок + C2ME). Три слоя × 256 = 768. 1024 — запас с хорошим запасом.
 *
 * <h3>Потокобезопасность</h3>
 * {@link ConcurrentHashMap} + {@code computeIfAbsent} — без явных блокировок.
 */
public final class ChunkIslandCache {

    private static final int MAX_SIZE = 1024;

    private final ConcurrentHashMap<Long, LongArrayList> map =
            new ConcurrentHashMap<>(MAX_SIZE * 2, 0.75f, 8);

    // ── Строим составной ключ ──────────────────────────────────────────────────

    /**
     * Кодирует тройку (layerId, chunkX, chunkZ) в один {@code long} без коллизий.
     *
     * <pre>
     * bits 63..56: layerId  (8 бит, значения 0-255)
     * bits 55..28: chunkX & 0xFFFFFFF (28 бит, знаковая обрезка — диапазон ±134M)
     * bits 27.. 0: chunkZ & 0xFFFFFFF (28 бит)
     * </pre>
     *
     * Каждая координата пакуется в свои собственные 28 бит независимо от другой,
     * поэтому ни X, ни Z не обрезаются на практически достижимых позициях
     * Minecraft (граница мира ±30M блоков = ±1.875M чанков, нужно лишь 21 бит).
     */
    private static long composeKey(int layerId, int chunkX, int chunkZ) {
        long x = chunkX & 0xFFFFFFFL; // младшие 28 бит chunkX
        long z = chunkZ & 0xFFFFFFFL; // младшие 28 бит chunkZ
        return ((layerId & 0xFFL) << 56) | (x << 28) | z;
    }

    // ── Публичный API ──────────────────────────────────────────────────────────

    /**
     * Возвращает список центров островов для заданного слоя и чанка,
     * либо вычисляет его через {@code factory}.
     *
     * @param layerId идентификатор слоя (0 = Lower, 1 = High, 2 = Upper, и т.д.)
     * @param chunkX  координата чанка X
     * @param chunkZ  координата чанка Z
     * @param factory функция-вычислитель (вызывается минимум раз на (layerId, chunkX, chunkZ))
     */
    public LongArrayList get(int layerId, int chunkX, int chunkZ,
                           LongFunction<LongArrayList> factory) {
        long key = composeKey(layerId, chunkX, chunkZ);
        LongArrayList existing = map.get(key);
        if (existing != null) return existing;

        // Боксинг только на cache miss — computeIfAbsent принимает Function<Long,V>
        LongArrayList computed = map.computeIfAbsent(key, k -> factory.apply(k));

        if (map.size() > MAX_SIZE) {
            Long evict = map.keys().nextElement();
            if (evict != null && evict != key) map.remove(evict);
        }
        return computed;
    }

    /**
     * Освобождает запись для одного слоя конкретного чанка.
     * Вызывается каждым генератором через {@code releaseChunkCache(chunkX, chunkZ)}.
     *
     * @param layerId идентификатор слоя (0 = Lower, 1 = High, 2 = Upper)
     * @param chunkX  координата чанка X
     * @param chunkZ  координата чанка Z
     */
    public void release(int layerId, int chunkX, int chunkZ) {
        map.remove(composeKey(layerId, chunkX, chunkZ));
    }

    /**
     * Освобождает записи для всех слоёв от 0 до {@code maxLayerId - 1}
     * за один вызов. Используется в {@code AeroWorldChunkGenerator}
     * вместо трёх отдельных {@code releaseChunkCache}.
     *
     * @param chunkX     координата чанка X
     * @param chunkZ     координата чанка Z
     * @param maxLayerId количество слоёв (эксклюзивно); обычно = 3
     */
    public void releaseAll(int chunkX, int chunkZ, int maxLayerId) {
        for (int i = 0; i < maxLayerId; i++) {
            map.remove(composeKey(i, chunkX, chunkZ));
        }
    }

    public void invalidate() {
        map.clear();
    }

    public int size() {
        return map.size();
    }
}