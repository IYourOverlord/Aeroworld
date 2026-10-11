package org.example.aeroworld.worldgen.dh;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Эксперимент (по умолчанию выключен): не гнать данные грубой секции вниз в листья (детализация 6) далеко от игрока.
 * Сейчас DH после каждой сгенерированной секции каскадом пишет данные во все листья (задачи «down» пропагатора, у родителя
 * детализации 7), а регенерация листьев работает только в радиусе R; вне R листья остаются размытой копией родителя.
 * Флаг {@code -Daeroworld.dhCascadeLeafRadius=N} (блоки, 0 = выключено): пропускать каскад в листья, если родитель
 * дальше N блоков от игрока (Chebyshev, от края секции). Без MC/DH, проверка: {@link AeroCascadeLeafSelfCheck}.
 * Пропущенные родители запоминаются в памяти и «возвращаются» (флаг {@code ApplyToChildren} ставится снова), когда игрок
 * приближается (сдвиг цели на {@code RADIUS/4}): иначе у места, куда игрок прилетел позже, не было бы дешёвых листьев, и DH
 * генерировал бы их по одному через очередь выборки (прогон 5: хвост вместо 15 с минуты и ETA в десятки минут).
 * ponytail: список пропущенных живёт только в памяти (после перезапуска игры у уже пропущенных родителей каскада в листья
 * не будет, их тогда закроет очередь выборки) и растёт до числа родителей детализации 7 в зоне генерации (порядка 10^5).
 * Ограничен только последний шаг (родитель детализации 7 -> листья); следующий шаг (8 -> 7) тоже мог бы
 * ограничиваться по радиусу, но это второе изменение для отдельного замера. Без игрока (dedicated-сервер) DH подставляет
 * (0,0), и радиус считался бы от начала координат; на таком сервере флаг не использовать.
 */
public final class AeroCascadeLeaf {

    public static final int RADIUS = Math.max(0, Integer.getInteger("aeroworld.dhCascadeLeafRadius", 0));
    /** Детализация родителя, чьи дети листья (SECTION_MINIMUM_DETAIL_LEVEL + 1). */
    static final int LEAF_PARENT_DETAIL = 7;

    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicBoolean LOGGED = new AtomicBoolean();
    private static volatile int targetX, targetZ;
    /** Только поток очереди пропагатора (один), потому без синхронизации. */
    private static final Set<Long> SKIPPED_POS = new HashSet<>();
    private static final AtomicLong REARMED = new AtomicLong();
    private static boolean rearmPending;
    private static int lastRearmX = Integer.MIN_VALUE, lastRearmZ;

    private AeroCascadeLeaf() { }

    /** Позиция игрока (или (0,0)), с которой DH начал текущий проход {@code runChildUpdates}. */
    public static void setTarget(int x, int z) {
        targetX = x;
        targetZ = z;
    }

    /** Нужно ли пропустить каскад в листья для родителя {@code pos}: считает по запомненной цели. */
    public static boolean skip(int detail, int minCornerX, int minCornerZ, int widthBlocks) {
        boolean s = isFar(detail, minCornerX, minCornerZ, widthBlocks);
        if (s) SKIPPED.incrementAndGet();
        return s;
    }

    /** То же без счётчика (для проверки при возврате пропущенных). */
    public static boolean isFar(int detail, int minCornerX, int minCornerZ, int widthBlocks) {
        return shouldSkip(RADIUS, detail, minCornerX, minCornerZ, widthBlocks, targetX, targetZ);
    }

    /** Запоминает пропущенного родителя, чтобы вернуть его, когда игрок приблизится. */
    public static void remember(long pos) {
        SKIPPED_POS.add(pos);
    }

    /** Итератор по пропущенным (удаление через {@code Iterator.remove}); только с потока очереди пропагатора. */
    public static Iterator<Long> skippedIterator() {
        return SKIPPED_POS.iterator();
    }

    /**
     * Пора ли просматривать пропущенных: после сдвига цели на {@code RADIUS/4} включается «ожидание», которое держится,
     * пока {@link #rearmDone} не сообщит, что просмотр закончен (за проход возвращается не больше лимита).
     */
    public static boolean rearmDue(int px, int pz) {
        if (RADIUS <= 0 || SKIPPED_POS.isEmpty()) return false;
        if (!rearmPending && (lastRearmX == Integer.MIN_VALUE
                || Math.max(Math.abs((long) px - lastRearmX), Math.abs((long) pz - lastRearmZ)) >= RADIUS / 4)) {
            rearmPending = true;
            lastRearmX = px;
            lastRearmZ = pz;
        }
        return rearmPending;
    }

    /** Результат прохода: вернули {@code n} из лимита {@code cap}; меньше лимита значит просмотр закончен. */
    public static void rearmDone(int n, int cap) {
        REARMED.addAndGet(n);
        if (n < cap) rearmPending = false;
    }

    public static long drainRearmed() {
        return REARMED.getAndSet(0);
    }

    static boolean shouldSkip(int radius, int detail, int minCornerX, int minCornerZ, int widthBlocks, int px, int pz) {
        if (radius <= 0 || detail != LEAF_PARENT_DETAIL) return false;
        int half = widthBlocks / 2;
        long dx = Math.abs((long) minCornerX + half - px) - half;
        long dz = Math.abs((long) minCornerZ + half - pz) - half;
        return Math.max(dx, dz) > radius;
    }

    /** Один раз пишет строку о том, что инъекция сработала (нет строки значит миксин не применился). */
    public static boolean firstUse() {
        return LOGGED.compareAndSet(false, true);
    }

    /** Сколько каскадов в листья пропущено с прошлого вызова. */
    public static long drainSkipped() {
        return SKIPPED.getAndSet(0);
    }
}
