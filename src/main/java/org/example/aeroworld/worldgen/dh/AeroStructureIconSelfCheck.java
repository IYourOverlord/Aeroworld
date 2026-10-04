package org.example.aeroworld.worldgen.dh;

import java.util.List;

import org.example.aeroworld.worldgen.dh.AeroStructureIcon.Seg;

/**
 * Self-check для {@link AeroStructureIcon}: {@code javac -d /tmp/ic AeroStructureIcon.java AeroStructureIconSelfCheck.java && java -ea -cp /tmp/ic org.example.aeroworld.worldgen.dh.AeroStructureIconSelfCheck}
 */
public final class AeroStructureIconSelfCheck {

    public static void main(String[] args) {
        // Повтор слоя ";n" склеивает одинаковые ячейки в один отрезок; разные символы дают отдельные.
        AeroStructureIcon v = AeroStructureIcon.forPath("village_plains");
        assert v.w == 5 && v.d == 5;
        assert v.column(2, 2).equals(List.of(new Seg(0, 8, 'p'), new Seg(8, 10, 'c'))) : v.column(2, 2);
        assert v.column(0, 0).equals(List.of(new Seg(0, 8, 'p'), new Seg(8, 9, 'c'))) : v.column(0, 0);
        // Аванпост: башня высотой 13 + площадка 1 + зубцы 1 одним отрезком
        assert AeroStructureIcon.forPath("pillager_outpost").column(2, 2).equals(List.of(new Seg(0, 15, 'd')));

        // Пустоты внутри колонки сохраняются (рама портала), вне габаритов — пусто.
        AeroStructureIcon p = AeroStructureIcon.forPath("ruined_portal_desert");
        assert p.w == 4 && p.d == 1;
        assert p.column(0, 0).equals(List.of(new Seg(0, 5, 'o')));
        assert p.column(1, 0).equals(List.of(new Seg(0, 1, 'o'), new Seg(4, 5, 'o'))) : p.column(1, 0);
        assert p.column(4, 0).isEmpty() && p.column(0, 1).isEmpty() && p.column(-1, 0).isEmpty();

        // Прямоугольный значок: ширина = длина строки, глубина = число строк; мачта в середине.
        AeroStructureIcon s = AeroStructureIcon.forPath("shipwreck");
        assert s.w == 3 && s.d == 5;
        assert s.column(1, 2).equals(List.of(new Seg(0, 2, 'p'), new Seg(2, 6, 'l'))) : s.column(1, 2);

        // Неизвестная структура — шпиль; каждая известная даёт хотя бы одну непустую колонку.
        assert AeroStructureIcon.forPath("modded:x".substring(6)).column(0, 0).equals(List.of(new Seg(0, 8, '*')));
        for (String id : new String[]{"ocean_monument", "pillager_outpost", "woodland_mansion", "igloo", "desert_pyramid",
                "jungle_pyramid", "swamp_hut", "bastion_remnant", "ocean_ruin_cold", "trail_ruins", "village_desert"}) {
            AeroStructureIcon i = AeroStructureIcon.forPath(id);
            assert !i.column(i.w / 2, i.d / 2).isEmpty() : id;
        }

        // Подземные и End City не рисуются, наземные и подводные рисуются.
        for (String id : new String[]{"mineshaft", "mineshaft_mesa", "ancient_city", "trial_chambers", "buried_treasure",
                "fortress", "stronghold", "end_city"}) assert !AeroStructureIcon.shownOnLod(id) : id;
        for (String id : new String[]{"village_plains", "pillager_outpost", "ocean_monument", "bastion_remnant", "shipwreck", "ruined_portal", "modded_thing"})
            assert AeroStructureIcon.shownOnLod(id) : id;

        System.out.println("AeroStructureIconSelfCheck: all checks passed");
    }
}
