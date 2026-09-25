package org.example.aeroworld.worldgen.dh;

/**
 * Assert-based self-check (без фреймворков) для правила "смешанная территория на границе
 * биомов — углублять ли рекурсию subsamples в {@code AeroSeedWorldGenerator.buildDominantSpans}".
 * <p>
 * Почему не тестируем {@code buildDominantSpans} напрямую: он строит span'ы через
 * {@code AeroColumnModel.buildSpans}, которая тянет генераторы слоёв (Minecraft/DH classpath —
 * недоступен в этом контейнере офлайн, см. ниже). Поэтому здесь проверяется byte-for-byte копия
 * самой чистой логики решения — {@code AeroSeedWorldGenerator.shouldRefineForMixedArea}
 * (subStep <= 1 → стоп; depth >= MAX_MIXED_AREA_RECURSION_DEPTH → стоп; иначе доля победителя
 * < MIXED_AREA_VOTE_THRESHOLD → рекурсировать). Это НЕ прогон реального метода из
 * AeroSeedWorldGenerator.java, а проверка того же правила в изоляции — если оригинал и копия
 * разойдутся, этот self-check перестанет отражать реальность.
 * <p>
 * ВАЖНО (честно, без изображения того, чего не было): javac в контейнере есть (был доустановлен
 * через apt из archive.ubuntu.com/security.ubuntu.com), но полный classpath Minecraft/NeoForge/
 * Distant Horizons недоступен — build.gradle тянет зависимости с maven.parchmentmc.org и из
 * NeoForge/Mojang репозиториев, которые не входят в разрешённый список сетевых доменов песочницы.
 * Поэтому compile+run ниже — это компиляция и прогон ИМЕННО этого файла плайн javac/java, без
 * Gradle и без реального AeroSeedWorldGenerator.class на classpath.
 */
public final class BuildDominantSpansMixedAreaSelfCheck {

    // Копия констант из AeroSeedWorldGenerator — держать в синхроне вручную при изменении порога/глубины там.
    private static final double MIXED_AREA_VOTE_THRESHOLD = 0.6;
    private static final int MAX_MIXED_AREA_RECURSION_DEPTH = 3;

    // Копия тела AeroSeedWorldGenerator.shouldRefineForMixedArea.
    static boolean shouldRefineForMixedArea(int winnerVotes, int totalVotes, int subStep, int depth) {
        if (subStep <= 1) {
            return false;
        }
        if (depth >= MAX_MIXED_AREA_RECURSION_DEPTH) {
            return false;
        }
        double winnerFraction = (double) winnerVotes / totalVotes;
        return winnerFraction < MIXED_AREA_VOTE_THRESHOLD;
    }

    public static void main(String[] args) {
        // 1. Явно смешанная территория (50/50 на границе биомов), subStep ещё можно мельчить,
        //    предохранитель глубины не выбран -> должна углубляться.
        assert shouldRefineForMixedArea(18, 36, 4, 0)
                : "50/50 при subStep>1 и depth<max должно рекурсировать";

        // 2. Доля победителя ровно на пороге (0.6) не считается смешанной: строго "< порога",
        //    а не "<=" — доля 60% уже честное большинство.
        assert !shouldRefineForMixedArea(6, 10, 4, 0)
                : "доля ровно на MIXED_AREA_VOTE_THRESHOLD не должна рекурсировать";

        // 3. Явное большинство (глубина леса/океана) -> без рекурсии, дешёвый путь не должен дорожать.
        assert !shouldRefineForMixedArea(9, 10, 4, 0)
                : "явное большинство не должно рекурсировать";

        // 4. subStep уже дошёл до честного per-block уровня -> стоп независимо от доли,
        //    даже если голосование патовое.
        assert !shouldRefineForMixedArea(1, 4, 1, 0)
                : "subStep<=1 обязан остановить рекурсию независимо от доли победителя";

        // 5. Предохранитель глубины: даже при патовом голосовании на depth == max дальше не идём.
        assert !shouldRefineForMixedArea(1, 4, 4, MAX_MIXED_AREA_RECURSION_DEPTH)
                : "depth >= MAX_MIXED_AREA_RECURSION_DEPTH обязан остановить рекурсию";

        // 6. На предыдущем шаге (depth == max - 1) рекурсия ещё разрешена при смешанном голосовании —
        //    предохранитель не должен срабатывать на шаг раньше времени.
        assert shouldRefineForMixedArea(1, 4, 4, MAX_MIXED_AREA_RECURSION_DEPTH - 1)
                : "на depth == max-1 смешанное голосование всё ещё должно углубляться";

        System.out.println("BuildDominantSpansMixedAreaSelfCheck: all checks passed (6/6)");
    }
}
