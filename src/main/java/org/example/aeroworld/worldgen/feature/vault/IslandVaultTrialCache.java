package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Общий (per-layer) кэш прогресса размещения Vault/Trial Spawner по острову.
 *
 * <h3>Проблема, которую решает</h3>
 * {@code IslandVaultTrialGenerator.placeForIsland}/{@code placeForEllipsoidIsland}/
 * {@code placeForJellyfishIsland} ищут точки постановки строго внутри ОДНОГО
 * чанка ({@code chunkX}/{@code chunkZ} вызова) — единственного, для которого
 * {@code WorldGenLevel.setBlock} в decoration-фазе гарантированно не отбрасывает
 * запись (см. javadoc {@code findBuriedSpot}). Раньше все три Placer'а вызывали
 * генератор только один раз — для чанка, где лежит центр острова — и требовали
 * набрать ВСЕ структуры тира (до 3 vault + 5 trial spawner у RICH) внутри одной
 * площади 16×16 с {@code MIN_SPACING=5.0} между ними. На тирах MEDIUM/RICH это
 * физически не всегда умещается — часть структур находила {@code null}-позицию
 * и тихо пропускалась.
 *
 * <h3>Решение</h3>
 * Остров кэширует свой прогресс ({@link Progress}) по ключу {@code (cx, cz)}.
 * Каждый Placer теперь вызывается для КАЖДОГО чанка, пересекающего
 * {@code island.radius} от центра острова (не только чанка центра), и на
 * каждый такой вызов пытается доразместить недостающие структуры, читая уже
 * поставленные позиции из общего {@code Progress.placed} — атомарные счётчики
 * {@code vaultsRemaining}/{@code trialSpawnersRemaining} гарантируют, что
 * параллельные вызовы для разных чанков того же острова (C2ME) в сумме не
 * превысят лимит тира, даже без внешней синхронизации по острову.
 *
 * <h3>Почему без выселения (в отличие от {@code ChunkIslandCache})</h3>
 * Число одновременно генерируемых ОСТРОВОВ (не чанков) на порядок меньше числа
 * чанков в работе, а каждая запись занимает лишь {@code List<BlockPos>} на
 * максимум 8 элементов (RICH: 3+5) и два {@code AtomicInteger}. Остров либо
 * успевает набрать полный тир (после чего дальнейшие вызовы — no-op, счётчики
 * на нуле), либо игра остановлена/выгружена вместе с генератором (и вместе с
 * ним — {@code AeroWorldChunkGenerator}, которому принадлежит этот кэш).
 *
 * <h3>Персистентность</h3>
 * Кэш живёт только в памяти на время существования {@code ChunkGenerator}
 * (аналогично {@code ChunkIslandCache}). Это осознанно: недостающие структуры
 * острова добираются по мере генерации ЛЮБОГО из его чанков в рамках одной
 * серверной сессии, а полный набор чанков острова (диаметр {@code radius*2})
 * генерируется практически всегда (игрок так или иначе облетает остров
 * целиком, если он вообще посещается/прегенерируется). NBT-персистентность
 * (аналог {@code PendingStructureData}) не требуется: здесь нет отложенной
 * операции, требующей выполниться ПОЗЖЕ за пределами generation-фазы — каждый
 * вызов placeForChunk либо ставит структуру немедленно в текущем чанке, либо
 * не ставит ничего (в отличие от {@code IslandStructureScheduler}, где
 * блюпринт годами ждёт приближения игрока).
 */
public final class IslandVaultTrialCache {

    private final ConcurrentHashMap<Long, Progress> map = new ConcurrentHashMap<>();

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * Возвращает (создавая при первом обращении) прогресс острова.
     * {@code vaultCount}/{@code trialSpawnerCount} используются ТОЛЬКО при
     * первом создании записи — тир острова детерминирован по его координатам,
     * поэтому повторные вызовы с теми же аргументами для того же острова
     * идемпотентны и не меняют уже созданный {@link Progress}.
     */
    public Progress getOrCreate(int cx, int cz, int vaultCount, int trialSpawnerCount) {
        return map.computeIfAbsent(key(cx, cz), k -> new Progress(vaultCount, trialSpawnerCount));
    }

    /** Освобождает запись острова (используется опционально при полном наборе, чтобы не раздувать карту бесконечно). */
    public void release(int cx, int cz) {
        map.remove(key(cx, cz));
    }

    public int size() {
        return map.size();
    }

    /**
     * Накопительный прогресс размещения структур одного острова.
     *
     * <p>{@code vaultsRemaining}/{@code trialSpawnersRemaining} декрементируются
     * атомарно и НЕМЕДЛЕННО перед попыткой постановки (compare-and-decrement
     * через {@link AtomicInteger#getAndUpdate}) — чтобы параллельные вызовы
     * {@code placeForChunk} для разных чанков одного острова не могли вдвоём
     * прочитать одинаковый "остаток > 0" и совместно превысить лимит тира.
     * Если попытка постановки в итоге проваливается (нет валидной точки в этом
     * чанке, {@code setBlock} отклонён движком), счётчик возвращается обратно
     * ({@link #vaultsRemaining}{@code .incrementAndGet()}), чтобы бюджет не
     * терялся безвозвратно — другой чанк того же острова получит шанс.</p>
     *
     * <p>{@code placed} — {@link CopyOnWriteArrayList}: чтения (проверка
     * {@code MIN_SPACING} в {@code tooClose}) многократно чаще записей
     * (одна на каждую реально поставленную структуру), и потокобезопасность
     * без внешних блокировок здесь важнее, чем цена копирования на запись.</p>
     */
    public static final class Progress {
        public final AtomicInteger vaultsRemaining;
        public final AtomicInteger trialSpawnersRemaining;
        public final List<BlockPos> placed = new CopyOnWriteArrayList<>();

        private Progress(int vaultCount, int trialSpawnerCount) {
            this.vaultsRemaining = new AtomicInteger(vaultCount);
            this.trialSpawnersRemaining = new AtomicInteger(trialSpawnerCount);
        }

        /** {@code true}, если весь тир острова уже набран (дальнейшие вызовы — no-op). */
        public boolean isComplete() {
            return vaultsRemaining.get() <= 0 && trialSpawnersRemaining.get() <= 0;
        }

        public List<BlockPos> placedView() {
            return Collections.unmodifiableList(placed);
        }
    }
}
