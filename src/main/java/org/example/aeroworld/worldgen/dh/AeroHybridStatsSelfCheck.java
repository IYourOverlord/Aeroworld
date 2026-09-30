package org.example.aeroworld.worldgen.dh;

import java.util.concurrent.CompletableFuture;

import org.example.aeroworld.worldgen.dh.AeroHybridStats.Branch;

/**
 * Self-check для {@link AeroHybridStats}: повторы классифицируются по предыдущей ветке, detail и знак координат
 * не смешиваются в ключе, {@code timeReal} возвращает ту же фьючу. Запуск: javac обоих файлов с slf4j на classpath, затем {@code java -ea}.
 */
public final class AeroHybridStatsSelfCheck {

    public static void main(String[] args) {
        assert AeroHybridStats.key(0, 1, 2) != AeroHybridStats.key(1, 1, 2);
        assert AeroHybridStats.key(0, -1, 2) != AeroHybridStats.key(0, 1, 2);
        assert AeroHybridStats.key(0, 1, -2) != AeroHybridStats.key(0, 1, 2);

        AeroHybridStats.onSection(0, -5, 7, Branch.PHASE1);       // первый ответ
        AeroHybridStats.onSection(0, -5, 7, Branch.PHASE2_REAL);  // штатный переход
        AeroHybridStats.onSection(0, -5, 7, Branch.PHASE2_REAL);  // спин после реальных чанков
        AeroHybridStats.onSection(0, 9, 9, Branch.OUTSIDE_FEATURES);
        AeroHybridStats.onSection(0, 9, 9, Branch.OUTSIDE_FEATURES); // спин после FEATURES
        AeroHybridStats.onSection(3, -5, 7, Branch.COARSE);       // другой detail — не повтор

        assert AeroHybridStats.firstCount(Branch.PHASE1) == 1 && AeroHybridStats.firstCount(Branch.COARSE) == 1;
        assert AeroHybridStats.repeatCount(Branch.PHASE1, Branch.PHASE2_REAL) == 1;
        assert AeroHybridStats.repeatCount(Branch.PHASE2_REAL, Branch.PHASE2_REAL) == 1;
        assert AeroHybridStats.repeatCount(Branch.OUTSIDE_FEATURES, Branch.OUTSIDE_FEATURES) == 1;
        assert AeroHybridStats.repeatCount(Branch.COARSE, Branch.COARSE) == 0;

        // застрявшие листья: PHASE1 без повтора считается ожидающим, любой следующий ответ снимает его
        AeroHybridStats.onSection(0, -3, 4, Branch.PHASE1);
        AeroHybridStats.onSection(0, 6, -2, Branch.PHASE1);
        AeroHybridStats.onSection(0, 6, -2, Branch.PHASE2_REAL);
        assert AeroHybridStats.phase1Waiting() == 1;                                   // остался только (-3,4)
        assert AeroHybridStats.stuckCount(System.currentTimeMillis()) == 0;            // ещё не «старый»
        assert AeroHybridStats.stuckCount(System.currentTimeMillis() + 61_000L) == 1;  // через минуту застрял
        assert AeroHybridStats.decodeCoord(AeroHybridStats.key(0, -3, 4) >> 29) == -3 && AeroHybridStats.decodeCoord(AeroHybridStats.key(0, -3, 4)) == 4;
        assert AeroHybridStats.decodeCoord(AeroHybridStats.key(0, 7, -9)) == -9;

        CompletableFuture<Void> f = new CompletableFuture<>();
        assert AeroHybridStats.timeReal(f) == f;
        f.complete(null);

        System.out.println("AeroHybridStatsSelfCheck: all checks passed");
    }
}