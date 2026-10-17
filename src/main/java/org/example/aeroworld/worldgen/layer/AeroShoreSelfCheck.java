package org.example.aeroworld.worldgen.layer;

/**
 * Self-check кромки берега: запуск {@code java AeroShoreSelfCheck.java} (классы Minecraft не нужны).
 * Показывает, что при cont < -0.05 (океанский биом {@code AeroBiomeSource.resolveLayer1Biome}) рельеф
 * {@link Layer1TerrainGenerator#getHeight} (при нулевых шумах) ещё выше уровня моря, то есть под биомом
 * океана есть сухая полоса, и она должна краситься песком, а не травой ({@code classifyPath}: "ocean" -> BEACH).
 * Формулы продублированы из Layer1TerrainGenerator.height (ponytail: при смене констант обновить вручную).
 */
public final class AeroShoreSelfCheck {
    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Высота при macro = detail = 0 для cont < 0.1 (участок land берём равным базе 3.0). */
    static int height(double cont) {
        double deep = -47.0, shelf = -8.0, beach = 2.0, land = 3.0;
        double h = deep + (shelf - deep) * smooth(-0.42, -0.16, cont);
        h = h + (beach - h) * smooth(-0.20, -0.02, cont);
        h = h + (land - h) * smooth(-0.06, 0.10, cont);
        return (int) Math.round(h);
    }

    public static void main(String[] args) {
        final double OCEAN_BELOW = -0.05; // порог океана в resolveLayer1Biome
        double lastDry = Double.NaN;
        for (double c = OCEAN_BELOW - 1e-6; c > -0.2; c -= 0.0005) {
            if (height(c) >= 0) lastDry = c;
        }
        System.out.printf("сухая суша под биомом океана до cont = %.4f (ширина %.4f)%n", lastDry, OCEAN_BELOW - lastDry);
        if (!(height(-0.051) >= 0)) throw new AssertionError("ожидалась сухая кромка при cont=-0.051");
        if (!(height(-0.09) < 0)) throw new AssertionError("ожидалась вода при cont=-0.09");
        System.out.println("OK");
    }
}
