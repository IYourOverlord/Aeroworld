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

    /**
     * Составной ключ {@code (layerId, cx, cz)}, а НЕ просто {@code (cx, cz)}.
     *
     * <p><b>Почему это критично:</b> экземпляр этого кэша один общий на все три
     * слоя ({@code AeroWorldChunkGenerator} передаёт один и тот же
     * {@code sharedVaultTrialCache} в {@code Layer2VaultTrialPlacer},
     * {@code Layer3VaultTrialPlacer} и {@code Layer4VaultTrialPlacer}). Каждый
     * слой использует свой {@code IslandPlacer} с собственной сеткой и солью
     * seed, но их острова распределены НЕЗАВИСИМО по одной и той же плоскости
     * XZ (различаются только диапазоном Y, который этот кэш не видит) — при
     * плотных пресетах (например {@code dense_archipelago}: grid=10/14/12)
     * остров одного слоя регулярно оказывается в той же (или очень близкой)
     * точке XZ, что и остров/спутник другого слоя, а спутники архипелага Layer2
     * (кольцо вокруг центра, до 6 штук) дополнительно увеличивают плотность
     * занятых XZ-точек. Без {@code layerId} в ключе {@code getOrCreate} для
     * острова слоя A мог вернуть уже существующую запись, ранее созданную для
     * острова слоя B с теми же {@code (cx,cz)} — оба слоя тогда делили ОДИН
     * общий счётчик {@code vaultsRemaining}/{@code trialSpawnersRemaining} и
     * список {@code placed}, из-за чего конкретный физический остров получал
     * структуры сверх своего тира (ровно баг со скриншота: POOR-спутник с
     * лимитом 1+1 получал 5 или 3 объекта — фактически сумму своих и чужих).</p>
     */
    private static long key(int layerId, int cx, int cz) {
        long h = ((long) layerId) * 0x9E3779B97F4A7C15L
                ^ ((long) cx * 341873128712L)
                ^ ((long) cz * 132897987541L);
        h = h ^ (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h = h ^ (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    /**
     * Возвращает (создавая при первом обращении) прогресс острова слоя
     * {@code layerId}. {@code vaultCount}/{@code trialSpawnerCount} используются
     * ТОЛЬКО при первом создании записи — тир острова детерминирован по его
     * координатам, поэтому повторные вызовы с теми же аргументами для того же
     * острова идемпотентны и не меняют уже созданный {@link Progress}.
     *
     * @param layerId идентификатор слоя (см. {@code LowerIslandGenerator.LAYER_ID},
     *                {@code HighIslandGenerator.LAYER_ID}, {@code UpperIslandGenerator.LAYER_ID}) —
     *                обязателен для различения островов разных слоёв с
     *                совпадающими XZ-координатами, см. {@link #key}.
     */
    public Progress getOrCreate(int layerId, int cx, int cz, int vaultCount, int trialSpawnerCount) {
        return map.computeIfAbsent(key(layerId, cx, cz), k -> new Progress(vaultCount, trialSpawnerCount));
    }

    /**
     * То же, что {@link #getOrCreate(int, int, int, int, int)}, но также сообщает
     * вызывающему, была ли запись только что создана ({@code true}) или уже
     * существовала под этим ключом ({@code true} == "new"). Нужно для
     * диагностики коллизий ключа между разными физическими островами — если для
     * одного и того же {@code (layerId,cx,cz)} два разных места кода передают
     * разные {@code vaultCount}/{@code trialSpawnerCount}, но получают одну и ту
     * же (уже существующую) запись, это явный симптом бага "остров получает
     * структуры соседа".
     */
    public Progress getOrCreate(int layerId, int cx, int cz, int vaultCount, int trialSpawnerCount,
                                java.util.function.Consumer<Boolean> wasCreatedCallback) {
        long k = key(layerId, cx, cz);
        boolean[] created = {false};
        Progress p = map.computeIfAbsent(k, kk -> { created[0] = true; return new Progress(vaultCount, trialSpawnerCount); });
        if (wasCreatedCallback != null) wasCreatedCallback.accept(created[0]);
        return p;
    }

    /** Освобождает запись острова (используется опционально при полном наборе, чтобы не раздувать карту бесконечно). */
    public void release(int layerId, int cx, int cz) {
        map.remove(key(layerId, cx, cz));
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