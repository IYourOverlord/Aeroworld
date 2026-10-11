package org.example.aeroworld.worldgen.dh;

import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-check для {@link AeroPoolStats}: подсчёт долей и средних, обнуление окна, замер обёртки пула, выключенный режим.
 * Запуск: {@code javac -d /tmp/ps AeroPoolStats.java AeroPoolStatsSelfCheck.java && java -ea -cp /tmp/ps org.example.aeroworld.worldgen.dh.AeroPoolStatsSelfCheck}
 */
public final class AeroPoolStatsSelfCheck {

    public static void main(String[] args) throws Exception {
        AeroPoolStats s = new AeroPoolStats(true);
        s.record(3, 200_000_000L, 1_000_000_000L, 800_000_000L);
        s.record(3, 400_000_000L, 1_000_000_000L, 600_000_000L);
        String out = s.drain(10.0, 2); // ёмкость 10 с * 2 потока = 2e10 нс; занято 2e9 (10%), тело 1.4e9 (7%), после тела 6e8 (3%)
        assert out != null && out.contains("tasks=2 threads=2 busy=10% (body 7% + after-body 3%)") : out;
        assert out.contains("queue wait avg 300 ms max 400 ms | after-body max 400 ms") : out;
        assert out.contains("d3 n=2 wait=300 body=700 after=300") : out;
        assert s.drain(10.0, 2) == null; // окно обнулено

        Executor direct = Runnable::run;
        long[] body = {0};
        AeroPoolStats on = new AeroPoolStats(true);
        on.wrap(direct, 0, System.nanoTime() - 5_000_000L, body).execute(() -> {
            body[0] = 1_000_000L;
            try { Thread.sleep(3); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        String w = on.drain(1.0, 1);
        Matcher m = Pattern.compile("d0 n=1 wait=(\\d+) body=1 after=(\\d+)").matcher(w);
        assert m.find() : w;
        assert Integer.parseInt(m.group(1)) >= 5 && Integer.parseInt(m.group(2)) >= 1 : w; // ожидание не меньше заданных 5 мс, после тела не меньше ~2 мс

        assert new AeroPoolStats(false).wrap(direct, 0, 0L, body) == direct; // выключено: пул не оборачивается

        // срезы пулов: 4 среза, генерация на паузе в одном из них
        AeroPoolStats.sample(true,  new int[]{4, 2, 6, 1, 1}, new int[]{40, 3, 10, 5, 0});
        AeroPoolStats.sample(true,  new int[]{4, 2, 6, 1, 1}, new int[]{44, 3, 10, 5, 0});
        AeroPoolStats.sample(false, new int[]{0, 2, 6, 1, 1}, new int[]{48, 3, 10, 5, 0});
        AeroPoolStats.sample(true,  new int[]{4, 2, 6, 1, 1}, new int[]{52, 3, 10, 5, 0});
        AeroPoolStats p = new AeroPoolStats(true);
        p.record(3, 1_000_000L, 2_000_000L, 1_500_000L);
        String sm = p.drain(1.0, 14);
        assert sm.contains("samples=4 wg paused 25% | avg running wg=3.0 prop=2.0 render=6.0 io=1.0 lod=1.0 (all=13.0 of 14)") : sm;
        assert sm.contains("avg queue wg=46 prop=3 render=10 io=5 lod=0") : sm;
        p.record(3, 1_000_000L, 2_000_000L, 1_500_000L);
        assert !p.drain(1.0, 14).contains("samples="); // срезы обнулены

        System.out.println("AeroPoolStatsSelfCheck: all checks passed");
    }
}
