package org.example.aeroworld.worldgen.layer;

/**
 * Self-check для {@link PlateauTerrace}: {@code javac -d /tmp/pt PlateauTerrace.java PlateauTerraceSelfCheck.java
 * && java -ea -cp /tmp/pt org.example.aeroworld.worldgen.layer.PlateauTerraceSelfCheck} (MC не нужен).
 */
public final class PlateauTerraceSelfCheck {

    public static void main(String[] args) {
        final double step = PlateauTerrace.STEP;

        // 1. Монотонность и непрерывность на мелкой сетке, включая отрицательные высоты.
        double prev = PlateauTerrace.terrace(-60.0, step);
        double maxJump = 0.0;
        for (double h = -60.0; h <= 240.0; h += 0.01) {
            double v = PlateauTerrace.terrace(h, step);
            assert v >= prev - 1e-9 : "terrace не монотонна на h=" + h;
            maxJump = Math.max(maxJump, v - prev);
            prev = v;
        }
        assert maxJump < 0.2 : "разрыв в terrace: " + maxJump;

        // 2. На площадке (доля шага 0..0.75) значение постоянно и равно floor(t)*step.
        for (int level = -5; level <= 40; level++) {
            double base = level * step;
            for (double f = 0.0; f <= 0.75; f += 0.05) {
                double v = PlateauTerrace.terrace(base + f * step, step);
                assert Math.abs(v - base) < 1e-9 : "площадка не плоская: level=" + level + " f=" + f + " v=" + v;
            }
            // 3. Уступ: на последней четверти шага значение растёт ровно на шаг, не больше.
            double top = PlateauTerrace.terrace(base + step - 1e-9, step);
            assert Math.abs(top - (base + step)) < 1e-6 : "уступ не доходит до следующего уровня";
            assert top - base <= step + 1e-9 : "уступ больше шага";
        }

        // 4. Отклонение от исходной высоты строго меньше шага.
        for (double h = -60.0; h <= 240.0; h += 0.37) {
            assert Math.abs(PlateauTerrace.terrace(h, step) - h) < step : "terrace ушла от h больше шага: " + h;
        }

        // 5. apply: нулевая маска не меняет высоту, полная даёт подъём около RISE и ступень.
        for (double h = -10.0; h <= 120.0; h += 1.3) {
            assert PlateauTerrace.apply(h, 0.0) == h : "apply с маской 0 меняет высоту";
            double full = PlateauTerrace.apply(h, 1.0);
            assert Math.abs(full - PlateauTerrace.terrace(h + PlateauTerrace.RISE, step)) < 1e-9 : "apply(mask=1) != terrace(h+RISE)";
            assert full > h + PlateauTerrace.RISE - step - 1e-9 && full < h + PlateauTerrace.RISE + step : "подъём вне допуска";
        }

        // 6. Маска: 0..1, гаснет на хребтах и в океане, детерминирована.
        for (double n = -1.0; n <= 1.0; n += 0.05) {
            for (double c = -0.5; c <= 1.0; c += 0.1) {
                for (double r = 0.0; r <= 1.0; r += 0.1) {
                    double m = PlateauTerrace.mask(n, c, r);
                    assert m >= 0.0 && m <= 1.0 : "маска вне 0..1";
                    assert m == PlateauTerrace.mask(n, c, r) : "маска недетерминирована";
                }
            }
        }
        assert PlateauTerrace.mask(0.9, 0.5, 1.0) == 0.0 : "маска не гаснет на хребте";
        assert PlateauTerrace.mask(0.9, -0.1, 0.0) == 0.0 : "маска в океане";
        assert PlateauTerrace.mask(0.1, 0.5, 0.0) == 0.0 : "маска без шума";
        assert PlateauTerrace.mask(0.9, 0.5, 0.0) == 1.0 : "полная маска";

        // 7. Биом: холод, побережье и хребет отключают плато.
        assert PlateauTerrace.isPlateauBiome(1.0, 0.3, 0.0, 0.1);
        assert !PlateauTerrace.isPlateauBiome(1.0, 0.3, 0.0, -0.1) : "плато в холоде";
        assert !PlateauTerrace.isPlateauBiome(1.0, 0.05, 0.0, 0.1) : "плато у берега";
        assert !PlateauTerrace.isPlateauBiome(1.0, 0.3, 0.4, 0.1) : "плато на хребте";
        assert !PlateauTerrace.isPlateauBiome(0.5, 0.3, 0.0, 0.1) : "порог маски строгий";

        System.out.println("PlateauTerraceSelfCheck: all checks passed (maxJump=" + maxJump + ")");
    }
}
