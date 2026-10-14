package org.example.aeroworld.worldgen.dh;

/**
 * Self-check для {@link AeroReloadStats}: интервалы постановок и перестроек, накопленные доли, неудачные старты, обнуление окна.
 * Запуск: {@code javac -d /tmp/rl AeroReloadStats.java AeroReloadStatsSelfCheck.java && java -ea -cp /tmp/rl org.example.aeroworld.worldgen.dh.AeroReloadStatsSelfCheck}
 */
public final class AeroReloadStatsSelfCheck {

    private static final long MS = 1_000_000L, S = 1_000_000_000L;

    public static void main(String[] args) {
        // позиция 1 (d6): t=0 первая, +50 мс, +1.95 с, +10 с; позиция 2 (d7): первая
        AeroReloadStats.noteTrigger(6, 1L, 0);
        AeroReloadStats.noteTrigger(6, 1L, 50 * MS);
        AeroReloadStats.noteTrigger(6, 1L, 50 * MS + 1_950 * MS);
        AeroReloadStats.noteTrigger(6, 1L, 50 * MS + 1_950 * MS + 10 * S);
        AeroReloadStats.noteTrigger(7, 2L, 0);
        // перестройки позиции 1: t=0, +1 с (граница 1 с не «меньше»), неудачный старт, +20 с
        AeroReloadStats.noteRebuild(6, 1L, 0, true);
        AeroReloadStats.noteRebuild(6, 1L, 1 * S, true);
        AeroReloadStats.noteRebuild(6, 1L, 2 * S, false);
        AeroReloadStats.noteRebuild(6, 1L, 21 * S, true);
        String out = AeroReloadStats.drain(10.0);
        assert out != null : "null";
        assert out.contains("trig n=5 (0.5/s) first 40% gap<0.1s 20% <1s 20% <2.5s 40% <8s 40%") : out;
        assert out.contains("rebuild n=3 (0.3/s) failed 1 gap<0.1s 0% <1s 0% <2.5s 33% <8s 33%") : out;
        assert out.contains("d6 trig 4/50% build 3 d7 trig 1/0% build 0") : out;
        assert AeroReloadStats.drain(10.0) == null : "окно не обнулено";

        // новое окно: последняя постановка позиции 1 помнится между окнами (интервал 3 с: <8 с, но не <2.5 с)
        AeroReloadStats.noteTrigger(6, 1L, 50 * MS + 1_950 * MS + 13 * S);
        String out2 = AeroReloadStats.drain(1.0);
        assert out2.contains("trig n=1 (1.0/s) first 0% gap<0.1s 0% <1s 0% <2.5s 0% <8s 100%") : out2;
        System.out.println("AeroReloadStatsSelfCheck OK");
    }
}
