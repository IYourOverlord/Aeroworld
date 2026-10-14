package org.example.aeroworld.worldgen.dh;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-check для {@link AeroReloadCoalescer}: тишина продлевается повторной постановкой, потолок удержания форсирует выдачу,
 * позиции независимы, после выдачи постановка начинает новый цикл, счётчики окна.
 * Запуск: {@code javac -d /tmp/co AeroReloadCoalescer.java AeroReloadCoalescerSelfCheck.java && java -ea -cp /tmp/co org.example.aeroworld.worldgen.dh.AeroReloadCoalescerSelfCheck}
 */
public final class AeroReloadCoalescerSelfCheck {

    private static final long MS = 1_000_000L;

    private static List<Long> release(AeroReloadCoalescer c, long nowMs) {
        List<Long> out = new ArrayList<>();
        c.releaseReady(nowMs * MS, out::add);
        return out;
    }

    public static void main(String[] args) {
        AeroReloadCoalescer c = new AeroReloadCoalescer(2500, 8000);
        c.trigger(1L, 0);
        c.trigger(2L, 1000 * MS);
        assert release(c, 2000).isEmpty() : "рано: тишина 2.5 с не прошла";
        c.trigger(1L, 2000 * MS); // продлевает тишину позиции 1 до 4.5 с
        List<Long> r = release(c, 3600); // позиция 2: тишина с 1 с, 2.6 с прошло; позиция 1: 1.6 с
        assert r.equals(List.of(2L)) : r;
        assert release(c, 4400).isEmpty() : "позиция 1 ещё в тишине";
        assert release(c, 4500).equals(List.of(1L)) : "позиция 1 после тишины";
        assert c.pendingCount() == 0 : "после выдачи пусто";

        // потолок удержания: постановки каждые 2 с не дают тишины, но позиция выходит на 8 с от первой
        AeroReloadCoalescer h = new AeroReloadCoalescer(2500, 8000);
        for (int t = 0; t <= 6; t += 2) h.trigger(7L, t * 1000L * MS);
        assert release(h, 7000).isEmpty() : "до потолка";
        assert release(h, 8000).equals(List.of(7L)) : "форсировано потолком";

        // после выдачи постановка начинает новый цикл
        h.trigger(7L, 9000 * MS);
        assert release(h, 10000).isEmpty() && release(h, 11500).equals(List.of(7L)) : "новый цикл";

        // счётчики окна: у c и h вместе 8 постановок, 4 из них на уже отложенную позицию, 4 выдачи, 1 форсирована потолком
        String s = AeroReloadCoalescer.drainStats(10.0);
        assert s != null && s.contains("held 8 (0.8/s) absorbed 4 (50%) released 4 (0.4/s) forced-by-hold 1 bypassed 0") : s;
        assert AeroReloadCoalescer.drainStats(10.0) == null : "окно не обнулено";
        System.out.println("AeroReloadCoalescerSelfCheck OK");
    }
}
