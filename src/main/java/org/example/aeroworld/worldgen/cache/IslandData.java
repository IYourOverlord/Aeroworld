package org.example.aeroworld.worldgen.cache;

/**
 * Иммутабельный объект, хранящий все вычисленные свойства одного острова.
 *
 * Создаётся один раз при первом обращении к острову и кэшируется по ключу
 * (blockCX, blockCZ) острова. Все генераторы слоёв получают свойства
 * только через этот объект — повторных вычислений нет.
 *
 * <p>Поля зависят от слоя: {@code ellipsoidAxes} заполнено только для Layer 3,
 * {@code tentacleData} — только для Layer 4. Для неактуальных слоёв они null.
 */
public final class IslandData {

    // ── Координаты центра острова (блоковые) ──────────────────────────────
    public final int cx;
    public final int cz;

    // ── Высотные границы острова ──────────────────────────────────────────
    public final int bottomY;
    public final int topY;

    // ── Горизонтальный радиус ─────────────────────────────────────────────
    public final double radius;

    // ── Layer 2: профиль формы и интенсивность шума края ─────────────────
    // Для Layer 3 и 4 не используются (значения -1 / 0.0).
    // Вычисляются один раз в computeIslandData вместо пересчёта на каждую
    // из 256 XZ-колонок чанка (пункт R оптимизационного листа).
    public final int    shapeProfile;        // 0–3, индекс кривой профиля
    public final double shapeNoiseIntensity; // масштаб краевого шума для этого острова

    // ── Layer 3: полуоси эллипсоида [ax, ay, az] ─────────────────────────
    // null для других слоёв
    public final double[] ellipsoidAxes;

    // ── Layer 4: параметры щупалец [tentacleCount][offsetX, offsetZ, len, angle]
    // null для других слоёв
    public final double[][] tentacleData;

    // ── Конструктор для Layer 2 (профиль + интенсивность шума кэшируются) ───
    public IslandData(int cx, int cz, int bottomY, int topY, double radius,
                      int shapeProfile, double shapeNoiseIntensity) {
        this.cx                   = cx;
        this.cz                   = cz;
        this.bottomY              = bottomY;
        this.topY                 = topY;
        this.radius               = radius;
        this.shapeProfile         = shapeProfile;
        this.shapeNoiseIntensity  = shapeNoiseIntensity;
        this.ellipsoidAxes        = null;
        this.tentacleData         = null;
    }

    // ── Конструктор для Layer 2 без кэша профиля (обратная совместимость) ────
    // Используется в тестах; в продакшне всегда передавайте profile+noiseIntensity.
    public IslandData(int cx, int cz, int bottomY, int topY, double radius) {
        this(cx, cz, bottomY, topY, radius, -1, 0.0);
    }

    // ── Конструктор для Layer 3 (эллипсоид) ──────────────────────────────
    public IslandData(int cx, int cz, int bottomY, int topY, double radius,
                      double[] ellipsoidAxes) {
        this.cx                   = cx;
        this.cz                   = cz;
        this.bottomY              = bottomY;
        this.topY                 = topY;
        this.radius               = radius;
        this.shapeProfile         = -1;
        this.shapeNoiseIntensity  = 0.0;
        this.ellipsoidAxes        = ellipsoidAxes;
        this.tentacleData         = null;
    }

    // ── Конструктор для Layer 4 (медузы) ─────────────────────────────────
    public IslandData(int cx, int cz, int bottomY, int topY, double radius,
                      double[][] tentacleData) {
        this.cx                   = cx;
        this.cz                   = cz;
        this.bottomY              = bottomY;
        this.topY                 = topY;
        this.radius               = radius;
        this.shapeProfile         = -1;
        this.shapeNoiseIntensity  = 0.0;
        this.ellipsoidAxes        = null;
        this.tentacleData         = tentacleData;
    }

    /** Высота острова в блоках. */
    public int height() {
        return topY - bottomY;
    }

    /** Y центра острова (для эллипсоида/шара). */
    public int centerY() {
        return (bottomY + topY) / 2;
    }
}
