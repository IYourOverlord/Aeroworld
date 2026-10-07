package org.example.aeroworld.worldgen.layer;

import org.example.aeroworld.worldgen.layer.AeroRiverNetwork.Cell;

/**
 * Self-check для {@link AeroRiverNetwork}: {@code javac -d /tmp/rn ../noise/AeroNoise.java AeroRiverNetwork.java AeroRiverNetworkSelfCheck.java
 * && java -ea -cp /tmp/rn org.example.aeroworld.worldgen.layer.AeroRiverNetworkSelfCheck}
 * (пакет noise лежит рядом, MC не нужен).
 */
public final class AeroRiverNetworkSelfCheck {

    public static void main(String[] args) {
        // Синтетический материк: океан при x < ~-1500, суша вправо, волнистый берег и пара холмов (замкнутые впадины).
        java.util.function.DoubleBinaryOperator cont = (x, z) ->
                x / 5000.0 - 0.05 + 0.12 * Math.sin(z / 1100.0) + 0.10 * Math.sin(x / 1300.0) * Math.cos(z / 1700.0);

        AeroRiverNetwork net = new AeroRiverNetwork(12345L, cont, 0.0); // без искажения: рёбра прямые
        int shown = 0, lakes = 0, ends = 0;
        for (int i = -8; i <= 8; i++) {
            for (int j = -8; j <= 8; j++) {
                Cell c = net.cell(i, j);
                if (c.lakeR() > 0) lakes++;
                if (!c.shown()) continue;
                shown++;
                // Путь вниз по течению: континентальность строго убывает, путь не рвётся и кончается в воде или озере.
                Cell cur = c;
                for (int step = 0; ; step++) {
                    assert step < 60 : "путь не кончается от " + i + "," + j;
                    if (!cur.hasTarget()) {
                        assert cur.node().cont() < AeroRiverNetwork.WET || cur.lakeR() > 0 : "обрыв на суше без озера";
                        ends++;
                        break;
                    }
                    Cell next = net.cell(cur.ti(), cur.tj());
                    assert next.node().cont() < cur.node().cont() : "континентальность не убывает";
                    assert !next.hasTarget() || next.shown() : "цель показанного ребра не показана";
                    cur = next;
                }
                // Середина ребра в прямом пространстве: русло в самом центре (сила 1) и там нет озёр случайно.
                Cell b = net.cell(c.ti(), c.tj());
                int mx = (int) Math.round((c.node().x() + b.node().x()) / 2), mz = (int) Math.round((c.node().z() + b.node().z()) / 2);
                assert net.sample(mx, mz, cont.applyAsDouble(mx, mz)).river() > 0.99 : "нет русла на середине ребра";
            }
        }
        assert shown > 20 && lakes > 3 && ends > 5 : shown + " " + lakes + " " + ends;

        // Значения всегда в 0..1 и детерминированы.
        AeroRiverNetwork warped = new AeroRiverNetwork(12345L, cont);
        for (int x = -3000; x <= 12000; x += 7) {
            for (int z = -3000; z <= 3000; z += 211) {
                AeroRiverNetwork.Sample s = warped.sample(x, z, cont.applyAsDouble(x, z));
                assert s.river() >= 0 && s.river() <= 1 && s.lake() >= 0 && s.lake() <= 1;
                assert s.deep() >= 0 && s.deep() <= s.lake() : "deep выходит за lake";
                assert s.equals(warped.sample(x, z, cont.applyAsDouble(x, z))) : "недетерминировано";
            }
        }
        assert warped.sample(0, 0, 0.0).river() == new AeroRiverNetwork(12345L, cont).sample(0, 0, 0.0).river() : "seed -> один результат";

        // Глубокие озёра: только сток-озёра, доля около DEEP_LAKE_SHARE, у каждого ровно один центр в чанке,
        // и в центре чаша на полную силу (плоское дно).
        // Материк с холмами-впадинами (на плавном уклоне замкнутых впадин нет, а значит и сток-озёр).
        java.util.function.DoubleBinaryOperator hilly = (x, z) -> 0.30 + 0.25 * Math.sin(x / 900.0) * Math.sin(z / 1000.0);
        AeroRiverNetwork basins = new AeroRiverNetwork(777L, hilly);
        int sinks = 0, deepLakes = 0, centres = 0;
        for (int i = -40; i <= 40; i++) {
            for (int j = -40; j <= 40; j++) {
                Cell c = basins.cell(i, j);
                if (c.lakeR() > 0 && !c.hasTarget()) sinks++;
                if (!c.deep()) continue;
                assert !c.hasTarget() && c.lakeR() >= 130 : "глубокое озеро не сток-озеро";
                deepLakes++;
                int ncx = (int) Math.floor(c.node().x() / 16), ncz = (int) Math.floor(c.node().z() / 16);
                int found = 0;
                for (int cx = ncx - 20; cx <= ncx + 20; cx++) {
                    for (int cz = ncz - 20; cz <= ncz + 20; cz++) {
                        int[] centre = basins.deepLakeCentre(cx, cz);
                        if (centre == null) continue;
                        found++;
                        AeroRiverNetwork.Sample at = basins.sample(centre[0], centre[1], hilly.applyAsDouble(centre[0], centre[1]));
                        assert at.deep() > 0.99 : "центр озера не на дне чаши: " + at.deep();
                        centres += 1;
                    }
                }
                assert found >= 1 : "у глубокого озера нет центра в окне чанков";
            }
        }
        assert sinks > 20 && deepLakes >= 1 && deepLakes <= sinks / 2 : sinks + " " + deepLakes;

        System.out.println("AeroRiverNetworkSelfCheck: all checks passed (shown=" + shown + ", lakes=" + lakes + ", ends=" + ends
                + ", sinks=" + sinks + ", deepLakes=" + deepLakes + ", centres=" + centres + ")");
    }
}
