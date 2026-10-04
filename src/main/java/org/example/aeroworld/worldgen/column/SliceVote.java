package org.example.aeroworld.worldgen.column;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Слияние нескольких вертикальных колонок в одну по Y-срезам — та же схема, что в DH
 * ({@code FullDataSourceV2.mergeInputTwoByTwoDataColumn}): воздух при голосовании игнорируется
 * (твёрдое побеждает пустоту), среди твёрдых побеждает самое частое значение по ключу.
 * Не зависит от Minecraft/DH-типов, поэтому проверяется {@link SliceVoteSelfCheck}.
 */
public final class SliceVote {

    /** Сегмент колонки [bottom, top), top эксклюзивный. */
    public record Seg<T>(int bottom, int top, T value) {}

    private SliceVote() {}

    /**
     * Вырезает [lo, hi) из колонки и вставляет на это место {@code fill}; остальное не меняется.
     * @param segs колонка по возрастанию без пересечений (как в {@link #vote})
     */
    public static <T> List<Seg<T>> carve(List<Seg<T>> segs, int lo, int hi, T fill) {
        List<Seg<T>> out = new ArrayList<>(segs.size() + 2);
        for (Seg<T> s : segs) if (s.bottom() < lo) out.add(new Seg<>(s.bottom(), Math.min(s.top(), lo), s.value()));
        out.add(new Seg<>(lo, hi, fill));
        for (Seg<T> s : segs) if (s.top() > hi) out.add(new Seg<>(Math.max(s.bottom(), hi), s.top(), s.value()));
        return out;
    }

    /**
     * @param cols  колонки; сегменты каждой отсортированы по возрастанию и не пересекаются
     * @param key   ключ голосования (одинаковый ключ = один и тот же материал)
     * @param empty сегмент, считающийся пустотой
     * @return сегменты по возрастанию без пустот; ничья решается в пользу первого набравшего счёт
     */
    public static <T> List<Seg<T>> vote(List<List<Seg<T>>> cols, Function<T, Object> key, Predicate<T> empty) {
        int[] ys = cols.stream().flatMap(List::stream).flatMapToInt(s -> java.util.stream.IntStream.of(s.bottom(), s.top()))
                .sorted().distinct().toArray();
        int[] idx = new int[cols.size()];
        List<Seg<T>> out = new ArrayList<>();
        Seg<T> run = null;
        Object runKey = null;

        for (int k = 0; k + 1 < ys.length; k++) {
            int a = ys[k];
            Map<Object, Integer> counts = new HashMap<>();
            Map<Object, T> rep = new HashMap<>();
            Object best = null;
            int bestCount = 0;
            for (int c = 0; c < idx.length; c++) {
                List<Seg<T>> col = cols.get(c);
                while (idx[c] < col.size() && col.get(idx[c]).top() <= a) idx[c]++;
                if (idx[c] >= col.size()) continue;
                Seg<T> s = col.get(idx[c]);
                if (s.bottom() > a || empty.test(s.value())) continue;
                Object kk = key.apply(s.value());
                rep.putIfAbsent(kk, s.value());
                int n = counts.merge(kk, 1, Integer::sum);
                if (n > bestCount) {
                    bestCount = n;
                    best = kk;
                }
            }

            if (best == null) {
                if (run != null) out.add(run);
                run = null;
                continue;
            }
            if (run != null && run.top() == a && Objects.equals(runKey, best)) {
                run = new Seg<>(run.bottom(), ys[k + 1], run.value());
            } else {
                if (run != null) out.add(run);
                run = new Seg<>(a, ys[k + 1], rep.get(best));
                runKey = best;
            }
        }
        if (run != null) out.add(run);
        return out;
    }
}
