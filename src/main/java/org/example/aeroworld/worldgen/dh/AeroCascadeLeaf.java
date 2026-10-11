package org.example.aeroworld.worldgen.dh;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Эксперимент (по умолчанию выключен): не гнать данные грубой секции вниз в листья (детализация 6) далеко от игрока.
 * Сейчас DH после каждой сгенерированной секции каскадом пишет данные во все листья (задачи «down» пропагатора, у родителя
 * детализации 7), а регенерация листьев работает только в радиусе R; вне R листья остаются размытой копией родителя.
 * Флаг {@code -Daeroworld.dhCascadeLeafRadius=N} (блоки, 0 = выключено): пропускать каскад в листья, если родитель
 * дальше N блоков от игрока (Chebyshev, от края секции). Без MC/DH, проверка: {@link AeroCascadeLeafSelfCheck}.
 * ponytail: ограничен только последний шаг (родитель детализации 7 -> листья); следующий шаг (8 -> 7) тоже мог бы
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

    private AeroCascadeLeaf() { }

    /** Позиция игрока (или (0,0)), с которой DH начал текущий проход {@code runChildUpdates}. */
    public static void setTarget(int x, int z) {
        targetX = x;
        targetZ = z;
    }

    /** Нужно ли пропустить каскад в листья для родителя {@code pos}: считает по запомненной цели. */
    public static boolean skip(int detail, int minCornerX, int minCornerZ, int widthBlocks) {
        boolean s = shouldSkip(RADIUS, detail, minCornerX, minCornerZ, widthBlocks, targetX, targetZ);
        if (s) SKIPPED.incrementAndGet();
        return s;
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
