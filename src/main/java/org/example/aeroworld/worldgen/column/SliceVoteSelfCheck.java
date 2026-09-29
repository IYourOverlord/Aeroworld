package org.example.aeroworld.worldgen.column;

import java.util.List;

import org.example.aeroworld.worldgen.column.SliceVote.Seg;

/**
 * Self-check для {@link SliceVote}: {@code javac -d /tmp/sv SliceVote.java SliceVoteSelfCheck.java && java -ea -cp /tmp/sv org.example.aeroworld.worldgen.column.SliceVoteSelfCheck}
 */
public final class SliceVoteSelfCheck {

    private static Seg<String> s(int bottom, int top, String v) { return new Seg<>(bottom, top, v); }

    private static List<Seg<String>> vote(List<List<Seg<String>>> cols) {
        return SliceVote.vote(cols, v -> v, "air"::equals);
    }

    public static void main(String[] args) {
        // Остров в одном сэмпле из трёх не теряется: твёрдое побеждает пустоту.
        var r = vote(List.of(
                List.of(s(0, 10, "grass")),
                List.of(s(0, 10, "grass")),
                List.of(s(0, 10, "grass"), s(100, 110, "stone"))));
        assert r.equals(List.of(s(0, 10, "grass"), s(100, 110, "stone"))) : r;

        // Среди твёрдых в одном срезе побеждает большинство.
        r = vote(List.of(List.of(s(0, 10, "sand")), List.of(s(0, 10, "grass")), List.of(s(0, 10, "grass"))));
        assert r.equals(List.of(s(0, 10, "grass"))) : r;

        // Ничья: побеждает первый набравший счёт (детерминизм).
        r = vote(List.of(List.of(s(0, 10, "sand")), List.of(s(0, 10, "grass"))));
        assert r.equals(List.of(s(0, 10, "sand"))) : r;

        // Смежные срезы одного материала склеиваются в один сегмент.
        r = vote(List.of(List.of(s(0, 5, "stone"), s(5, 10, "stone")), List.of(s(0, 10, "stone"))));
        assert r.equals(List.of(s(0, 10, "stone"))) : r;

        // Сегмент-воздух не голосует и не перебивает твёрдое.
        r = vote(List.of(List.of(s(0, 10, "air")), List.of(s(0, 10, "stone"))));
        assert r.equals(List.of(s(0, 10, "stone"))) : r;

        // Пустота между слоями сохраняется, всё пусто -> пустой результат.
        r = vote(List.of(List.of(s(0, 4, "stone"), s(8, 12, "stone")), List.of(s(0, 4, "stone"), s(8, 12, "stone"))));
        assert r.equals(List.of(s(0, 4, "stone"), s(8, 12, "stone"))) : r;
        assert vote(List.of(List.<Seg<String>>of(), List.<Seg<String>>of())).isEmpty();

        System.out.println("SliceVoteSelfCheck: all checks passed (6/6)");
    }
}
