package org.example.aeroworld.worldgen.layer;

/**
 * Провал-сенот в джунглях: шар с отрезанной верхушкой (отверстие наверх), вся внутренняя поверхность - глина, вода
 * до центра шара. Чистая геометрия без Minecraft: центр, радиус шара и уровень воды (= высота центра шара) задаёт
 * {@code Layer1TerrainGenerator.cenoteAt}, профиль колонки считает {@link #column}. Общая для реальных чанков и
 * LOD-модели {@code AeroColumnModel}.
 */
public record AeroCenote(int cx, int cz, int radius, int waterY) {

    /** В ячейке сенота нет (радиус 0). */
    public static final AeroCenote ABSENT = new AeroCenote(0, 0, 0, 0);

    /** Верх открытой колонки (отверстие): больше любой высоты мира. */
    public static final int OPEN = Short.MAX_VALUE;

    /** Толщина глиняной оболочки (блоков) от внутренней поверхности шара. */
    private static final double SHELL = 1.5;

    /**
     * Колонка сенота снизу вверх: глина [clayLo..airLo-1], пустота (вода до waterY, выше воздух) [airLo..airHi],
     * глина [airHi+1..clayHi]. Пустой диапазон - нижняя граница больше верхней. {@code airHi == OPEN}: колонка в
     * отверстии, потолка нет.
     */
    public record Column(int clayLo, int airLo, int airHi, int clayHi, int waterY) {}

    /** Высота среза над центром шара: отверстие радиуса {@code sqrt(R² - h²)} = 0.6 R. */
    public int cutHeight() {
        return radius * 4 / 5;
    }

    /** Радиус отверстия в квадрате. */
    public int openingRadius2() {
        int h = cutHeight();
        return radius * radius - h * h;
    }

    /** Наибольшее |dy|, для которого dy² < m (-1, если m <= 0). */
    private static int reach(int m) {
        return m > 0 ? (int) Math.sqrt(m - 1) : -1;
    }

    /** Профиль колонки или null, если колонка вне шара и его оболочки. */
    public Column column(int wx, int wz) {
        if (radius == 0) return null;
        int dx = wx - cx, dz = wz - cz;
        int d2 = dx * dx + dz * dz;
        // оболочка: dist < R + SHELL, целочисленно (R+1.5)² = R² + 3R + 2.25
        int kout = reach(radius * radius + 3 * radius + 2 - d2);
        if (kout < 0) return null;
        int kin = reach(radius * radius - d2);
        int cy = waterY;
        if (kin < 0) return new Column(cy - kout, cy + kout + 1, cy + kout, cy + kout, cy);
        if (d2 < openingRadius2()) return new Column(cy - kout, cy - kin, OPEN, OPEN, cy);
        return new Column(cy - kout, cy - kin, cy + kin, cy + kout, cy);
    }
}
