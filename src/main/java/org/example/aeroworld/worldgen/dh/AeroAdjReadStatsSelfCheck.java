package org.example.aeroworld.worldgen.dh;

/**
 * Self-check для {@link AeroAdjReadStats}: подсчёт, потоки, «строки нет», повторы в окне и за прогон, независимость направлений.
 * Запуск: {@code javac -d /tmp/ar AeroAdjReadStats.java AeroAdjReadStatsSelfCheck.java && java -ea -cp /tmp/ar org.example.aeroworld.worldgen.dh.AeroAdjReadStatsSelfCheck}
 */
public final class AeroAdjReadStatsSelfCheck {

    private static final long S = 1_000_000_000L;

    public static void main(String[] args) {
        // окно 1: ключ (7, dir0) дважды (один повтор), (8, dir1) один раз, (7, dir1) один раз (другое направление не повтор)
        AeroAdjReadStats.record(3, 0, 7L, 2 * S, 1 * S, true);
        AeroAdjReadStats.record(3, 0, 7L, 4 * S, 3 * S, false);
        AeroAdjReadStats.record(4, 1, 8L, 3 * S, 2 * S, false);
        String out = AeroAdjReadStats.drain(10.0);
        assert out != null : "null";
        assert out.contains("n=3 (0.3/s) threads=0.90 (repo 0.60) avg 3000.00 ms (repo 2000.00) norow 33% repeat win 33% run 33%") : out;
        assert out.contains("d3 2/50%/50% d4 1/0%/0%") : out;
        assert AeroAdjReadStats.drain(10.0) == null : "окно не обнулено";

        // окно 2: тот же ключ (7, dir0) снова: в окне не повтор, за прогон повтор
        AeroAdjReadStats.record(3, 0, 7L, S, S, false);
        String out2 = AeroAdjReadStats.drain(1.0);
        assert out2.contains("n=1 (1.0/s) threads=1.00 (repo 1.00) avg 1000.00 ms (repo 1000.00) norow 0% repeat win 0% run 100%") : out2;

        // направление 5 сворачивается в 1 (& 3): ключ (8, dir1) уже виден за прогон
        AeroAdjReadStats.record(4, 5, 8L, S, S, false);
        assert AeroAdjReadStats.drain(1.0).contains("run 100%") : "dir & 3";

        assert AeroAdjReadStats.firstHit(0) && !AeroAdjReadStats.firstHit(0) && AeroAdjReadStats.firstHit(1) : "firstHit";
        System.out.println("AeroAdjReadStatsSelfCheck OK");
    }
}
