package org.example.aeroworld.worldgen.layer;

/**
 * Self-check для {@link PlateauTerrace}: {@code javac -d /tmp/pt AeroNoise.java PlateauTerrace.java PlateauTerraceSelfCheck.java
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

        // 5б. Каньон: чем больше core, тем ниже (монотонно), дно не ниже min(h, FLOOR_Y) - шаг, кайма не выше h + RISE.
        for (double h = -10.0; h <= 200.0; h += 1.7) {
            double prevV = Double.MAX_VALUE;
            for (double c = 0.0; c <= 1.0; c += 0.02) {
                double v = PlateauTerrace.apply(h, 1.0, c, 0.0);
                assert v <= prevV + 1e-9 : "каньон не монотонен: h=" + h + " c=" + c;
                assert v >= Math.min(h, PlateauTerrace.FLOOR_Y) - step - 1e-9 : "дно каньона ниже допуска: h=" + h;
                assert v <= h + PlateauTerrace.RISE + 1e-9 : "кайма выше RISE: h=" + h;
                prevV = v;
            }
            // Сдвиг фазы не уводит высоту больше чем на шаг + |jitter|.
            double j = PlateauTerrace.apply(h, 1.0, 0.0, PlateauTerrace.JITTER);
            assert Math.abs(j - PlateauTerrace.apply(h, 1.0, 0.0, 0.0)) <= step + PlateauTerrace.JITTER + 1e-9 : "jitter слишком велик";
        }

        // 5в. core: 0..1, максимум на нулевом контуре шума, симметричен по знаку, гаснет у каймы (малая маска) и вне стен.
        assert PlateauTerrace.core(0.0, 1.0) == 1.0 : "нет каньона на нулевом контуре";
        assert PlateauTerrace.core(PlateauTerrace.CANYON_W1, 1.0) == 0.0 : "каньон шире стены";
        assert PlateauTerrace.core(0.0, PlateauTerrace.CANYON_MASK_LO) == 0.0 : "каньон прорезает кайму";
        for (double cn = -0.3; cn <= 0.3; cn += 0.01) {
            double c = PlateauTerrace.core(cn, 1.0);
            assert c >= 0.0 && c <= 1.0 : "core вне 0..1";
            assert c == PlateauTerrace.core(-cn, 1.0) : "core несимметричен";
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

        // 8. Овраги, останцы, мосты: диапазоны, гейт по глубине плато, монотонность apply по останцу.
        assert PlateauTerrace.gully(0.0, 1.0) == PlateauTerrace.GULLY_DEPTH : "овраг не на контуре";
        assert PlateauTerrace.gully(0.0, PlateauTerrace.CANYON_MASK_LO) == 0.0 : "овраг режет кайму";
        assert PlateauTerrace.gully(PlateauTerrace.GULLY_W1, 1.0) == 0.0 : "овраг шире стены";
        assert PlateauTerrace.pillar(1.0, 1.0) == 1.0 && PlateauTerrace.pillar(0.0, 1.0) == 0.0 : "останец: границы";
        assert PlateauTerrace.pillar(1.0, PlateauTerrace.CANYON_MASK_LO) == 0.0 : "останец на кайме";
        assert PlateauTerrace.bridge(0.0, 1.0) == 1.0 && PlateauTerrace.bridge(0.0, -1.0) == 0.0 : "мост: отбор";
        assert PlateauTerrace.bridge(PlateauTerrace.BRIDGE_W1, 1.0) == 0.0 : "мост шире полосы";
        for (double h = -10.0; h <= 120.0; h += 3.1) {
            double prevP = -Double.MAX_VALUE;
            for (double pl = 0.0; pl <= 1.0; pl += 0.05) {
                double v = PlateauTerrace.apply(h, 1.0, 0.0, 0.0, pl);
                assert v >= prevP - 1e-9 : "останец опускает землю: h=" + h;
                assert v <= PlateauTerrace.apply(h, 1.0) + PlateauTerrace.PILLAR_H + step : "останец выше PILLAR_H";
                prevP = v;
            }
        }
        assert !PlateauTerrace.hasBridgeVoid(0.4, 6, 48) : "пустота при слабом мосте";
        assert !PlateauTerrace.hasBridgeVoid(1.0, 42, 48) : "пустота без просвета";
        assert PlateauTerrace.hasBridgeVoid(1.0, 6, 48) : "нет пустоты под полным мостом";

        // 9. Реальные шумы, сид 12345, база Y=20, весь регион внутри плато (маска 1): мост никогда не опускает землю,
        // под ним всегда есть просвет, мосты не исчезли, останцы и овраги есть, каньон не заполнен.
        org.example.aeroworld.worldgen.noise.AeroNoise noise =
                new org.example.aeroworld.worldgen.noise.AeroNoise(12345L ^ 0x6A3F52D1L);
        int voids = 0, pillars = 0, gullies = 0, floors = 0;
        for (int z = -16500; z < -16000; z += 2) {
            for (int x = -8700; x < -7300; x += 2) {
                double with = PlateauTerrace.column(noise, 20.0, x, z, 1.0, true);
                double without = PlateauTerrace.column(noise, 20.0, x, z, 1.0, false);
                assert with >= without - 1e-9 : "мост опустил землю: " + x + "," + z;
                int sy = (int) Math.round(with), fy = (int) Math.round(without);
                if (PlateauTerrace.hasBridgeVoid(PlateauTerrace.bridgeAt(noise, x, z), fy, sy)) {
                    voids++;
                    assert fy + PlateauTerrace.CLEARANCE <= sy - PlateauTerrace.DECK : "нет просвета под мостом";
                }
                if (sy > 54) pillars++;
                if (sy >= 20 && sy <= 40) gullies++;
                if (sy <= 8) floors++;
            }
        }
        assert voids > 0 : "мосты исчезли";
        assert pillars > 0 : "останцы исчезли";
        assert gullies > 0 : "овраги исчезли";
        assert floors > 0 : "каньон исчез";

        System.out.println("PlateauTerraceSelfCheck: all checks passed (maxJump=" + maxJump + ")");
    }
}
