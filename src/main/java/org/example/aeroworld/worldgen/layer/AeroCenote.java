package org.example.aeroworld.worldgen.layer;

/**
 * Провал-сенот в джунглях: круглая шахта с озером на дне. Чистая геометрия без Minecraft: центр, радиус и уровень воды
 * озера задаёт {@code Layer1TerrainGenerator.cenoteAt}, а пол озера в колонке считает {@link #floorY}.
 * Общая для реальных чанков и LOD-модели {@code AeroColumnModel}.
 */
public record AeroCenote(int cx, int cz, int radius, int waterY) {

    /** Колонка вне сенота (влезает в short для {@code Layer1ColumnCache}). */
    public static final int NONE = Short.MIN_VALUE;
    /** В ячейке сенота нет (радиус 0). */
    public static final AeroCenote ABSENT = new AeroCenote(0, 0, 0, 0);

    /** Глубина чаши: у края вода глубиной 2, в центре 2 + BOWL блоков. */
    static final int BOWL = 5;

    /**
     * Верхний блок дна озера в колонке или {@link #NONE}, если колонка вне шахты. {@code wobble} (блоки) добавляется
     * к радиусу, чтобы край не был идеальной окружностью.
     */
    public int floorY(int wx, int wz, double wobble) {
        if (radius == 0) return NONE;
        double r = radius + wobble;
        double dx = wx - cx, dz = wz - cz;
        double d2 = dx * dx + dz * dz;
        if (d2 >= r * r) return NONE;
        return waterY - 2 - (int) ((1.0 - d2 / (r * r)) * BOWL);
    }
}
