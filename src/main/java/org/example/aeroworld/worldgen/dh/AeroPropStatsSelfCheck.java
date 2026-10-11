package org.example.aeroworld.worldgen.dh;

/**
 * Self-check для {@link AeroPropStats}: подсчёт, доля повторов, потоки, обнуление окна, оборачивание задачи.
 * Запуск: {@code javac -d /tmp/pr AeroPropStats.java AeroPropStatsSelfCheck.java && java -ea -cp /tmp/pr org.example.aeroworld.worldgen.dh.AeroPropStatsSelfCheck}
 */
public final class AeroPropStatsSelfCheck {

    public static void main(String[] args) {
        // up: позиция 7 дважды (один повтор), позиция 8 один раз; down: позиция 7 один раз (виды не смешиваются)
        AeroPropStats.record(0, 3, 7L, 100_000_000L, 20_000_000L);
        AeroPropStats.record(0, 3, 7L, 300_000_000L, 40_000_000L);
        AeroPropStats.record(0, 4, 8L, 0L, 30_000_000L);
        AeroPropStats.record(1, 3, 7L, 0L, 10_000_000L);
        String out = AeroPropStats.drain(10.0);
        assert out != null : "null";
        assert out.contains("up: n=3 (0.3/s) threads=0.0 avg 30.0 ms wait 133 ms repeat 33%") : out;
        assert out.contains("d3 2/50% d4 1/0%") : out;
        assert out.contains("|| down: n=1 (0.1/s) threads=0.0 avg 10.0 ms wait 0 ms repeat 0%") : out;
        assert AeroPropStats.drain(10.0) == null : "окно не обнулено";

        // wrap: без позиции задача не оборачивается, с позицией записывается один раз
        Runnable plain = () -> { };
        assert AeroPropStats.wrap(0, plain) == plain;
        AeroPropStats.notePending(99L, 2);
        Runnable wrapped = AeroPropStats.wrap(0, plain);
        assert wrapped != plain;
        wrapped.run();
        assert AeroPropStats.drain(1.0).contains("up: n=1") : "wrap не записал";
        assert AeroPropStats.wrap(0, plain) == plain : "позиция не сброшена";
        System.out.println("AeroPropStatsSelfCheck OK");
    }
}
