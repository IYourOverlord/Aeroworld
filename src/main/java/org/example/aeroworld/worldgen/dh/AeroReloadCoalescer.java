package org.example.aeroworld.worldgen.dh;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Этап 5б: склейка перезагрузок секций (аналог {@code ReloadCoalescer} другого мода). Постановка позиции в очередь
 * перезагрузки DH откладывается: позиция уходит в очередь, только когда на неё не было новых постановок
 * {@code quietNs} (тишина) или когда с первой отложенной постановки прошло {@code holdNs} (потолок удержания).
 * Серия постановок одной позиции даёт одну перестройку вместо нескольких. Постановка во время идущей перестройки не
 * теряется: позиция снова откладывается и после тишины перестроится по свежим данным.
 * Без MC/DH, проверка: {@link AeroReloadCoalescerSelfCheck}. Включена по умолчанию (этап 5б принят 2026-10-14: тишина 2500 мс, листья d6 без задержки); {@code -Daeroworld.dhReloadQuietMs=0} выключает. Значения по умолчанию держать в синхроне с {@code DhWorldGenBorderMixinPlugin}.
 * Потолок: обход всех отложенных позиций за такт (их единицы тысяч), при десятках тысяч понадобится очередь по времени.
 */
public final class AeroReloadCoalescer {

    public static final long QUIET_MS = Math.max(0, Long.getLong("aeroworld.dhReloadQuietMs", 2500L));
    public static final long HOLD_MS = Math.max(QUIET_MS, Long.getLong("aeroworld.dhReloadHoldMs", 8000L));
    /** Секции с уровнем детализации ниже этого значения не откладываются (7 = листья d6 идут сразу; 0 = откладываются все). */
    public static final int EXEMPT_BELOW_DETAIL = Integer.getInteger("aeroworld.dhReloadExemptBelowDetail", 7);
    public static final boolean ENABLED = QUIET_MS > 0;

    private static final AtomicLong HELD = new AtomicLong();
    private static final AtomicLong ABSORBED = new AtomicLong();
    private static final AtomicLong RELEASED = new AtomicLong();
    private static final AtomicLong FORCED = new AtomicLong();
    private static final AtomicLong BYPASSED = new AtomicLong();

    private final long quietNs;
    private final long holdNs;
    /** pos -> {время первой отложенной постановки, время последней}. */
    private final ConcurrentHashMap<Long, long[]> pending = new ConcurrentHashMap<>();

    public AeroReloadCoalescer(long quietMs, long holdMs) {
        this.quietNs = quietMs * 1_000_000L;
        this.holdNs = holdMs * 1_000_000L;
    }

    /** Откладывает позицию; повторная постановка продлевает тишину, но не потолок. */
    public void trigger(long pos, long nowNs) {
        HELD.incrementAndGet();
        pending.compute(pos, (k, v) -> {
            if (v == null) return new long[]{nowNs, nowNs};
            ABSORBED.incrementAndGet();
            v[1] = nowNs;
            return v;
        });
    }

    /** Счётчик обхода секций, которые не откладываются (уровень ниже порога). */
    public static void noteBypass() { BYPASSED.incrementAndGet(); }

    /** Отдаёт в {@code sink} все позиции, у которых вышла тишина или потолок удержания. Вызывать каждый такт дерева. */
    public void releaseReady(long nowNs, LongConsumer sink) {
        if (pending.isEmpty()) return;
        for (Long key : pending.keySet()) {
            pending.computeIfPresent(key, (k, v) -> {
                boolean forced = nowNs - v[0] >= holdNs;
                if (!forced && nowNs - v[1] < quietNs) return v;
                RELEASED.incrementAndGet();
                if (forced && nowNs - v[1] < quietNs) FORCED.incrementAndGet();
                sink.accept(k);
                return null;
            });
        }
    }

    public int pendingCount() { return pending.size(); }

    /** Итог окна и обнуление счётчиков; {@code null}, если событий не было. */
    public static String drainStats(double elapsedSec) {
        long held = HELD.getAndSet(0), abs = ABSORBED.getAndSet(0), rel = RELEASED.getAndSet(0),
                forced = FORCED.getAndSet(0), byp = BYPASSED.getAndSet(0);
        if (held == 0 && rel == 0 && byp == 0) return null;
        return String.format(Locale.ROOT,
                "quiet=%dms hold=%dms exempt<d%d | held %d (%.1f/s) absorbed %d (%.0f%%) released %d (%.1f/s) forced-by-hold %d bypassed %d",
                QUIET_MS, HOLD_MS, EXEMPT_BELOW_DETAIL, held, held / Math.max(elapsedSec, 1e-9), abs,
                held == 0 ? 0.0 : 100.0 * abs / held, rel, rel / Math.max(elapsedSec, 1e-9), forced, byp);
    }
}
