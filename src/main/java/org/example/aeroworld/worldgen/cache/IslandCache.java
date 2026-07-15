package org.example.aeroworld.worldgen.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Потокобезопасный LRU-кэш свойств островов.
 *
 * <h3>Зачем это нужно</h3>
 * В {@code fillChunk} каждого генератора слоёв методы
 * {@code getIslandYBounds(cx,cz)}, {@code getIslandRadius(cx,cz)} и
 * {@code getEllipsoidAxes(cx,cz,...)} вызываются внутри двойного цикла
 * 16×16 блоков, то есть <b>256 раз на один остров на один чанк</b>.
 * Каждый вызов запускает {@code noise2D} / {@code fbm2D} с вычислением
 * перлиновского шума. С кэшом — вычисление происходит ровно один раз.
 *
 * <h3>Архитектура</h3>
 * <ul>
 *   <li>{@link LinkedHashMap} с {@code accessOrder=true} — настоящий LRU:
 *       при каждом {@code get} элемент перемещается в хвост, старейший
 *       ({@code eldest}) вытесняется автоматически через
 *       {@code removeEldestEntry}.</li>
 *   <li>Обёрнут в {@code Collections.synchronizedMap} для потокобезопасности.
 *       Все операции атомарны через единый монитор.</li>
 * </ul>
 *
 * <h3>Ключ</h3>
 * Координаты центра острова в блоках упакованы в {@code long} через
 * {@link ChunkKey#of(int, int)} — нет боксинга, нет аллокаций на хит.
 */
public final class IslandCache {

    /**
     * Максимальное количество островов в кэше.
     * При радиусе просмотра 8 ячеек (LowerIslandGenerator) в зоне одного игрока
     * одновременно могут быть видны ~25–30 островов. 512 — большой запас,
     * позволяющий серверу со многими игроками не вымывать кэш постоянно.
     */
    private static final int DEFAULT_MAX_SIZE = 512;

    private final Map<Long, IslandData> map;
    private final int maxSize;

    public IslandCache() {
        this(DEFAULT_MAX_SIZE);
    }

    public IslandCache(int maxSize) {
        this.maxSize = maxSize;
        this.map = Collections.synchronizedMap(
            new LinkedHashMap<>(maxSize * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, IslandData> eldest) {
                    return size() > maxSize;
                }
            }
        );
    }

    /**
     * Возвращает закэшированные данные острова или вычисляет их через {@code factory}.
     *
     * @param cx      X-координата центра острова (блоки)
     * @param cz      Z-координата центра острова (блоки)
     * @param factory функция, вычисляющая {@link IslandData} по ключу
     *                (ключ = {@code ChunkKey.of(cx, cz)})
     * @return закэшированный {@link IslandData}
     */
    public IslandData get(int cx, int cz, LongFunction<IslandData> factory) {
        long key = ChunkKey.of(cx, cz);
        // synchronizedMap требует явной синхронизации для составных операций
        synchronized (map) {
            IslandData existing = map.get(key); // обновляет LRU-порядок
            if (existing != null) return existing;
            IslandData computed = factory.apply(key);
            map.put(key, computed); // removeEldestEntry срабатывает здесь
            return computed;
        }
    }

    /**
     * Сбрасывает кэш. Вызывается при смене seed мира (пересоздание генераторов).
     */
    public void invalidate() {
        synchronized (map) {
            map.clear();
        }
    }

    /** Текущее количество закэшированных островов. Для отладки. */
    public int size() {
        synchronized (map) {
            return map.size();
        }
    }
}
