package org.example.aeroworld.worldgen.dh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Self-check для {@link AeroLeafLedger}: запись переживает переоткрытие, оборванный хвост файла не ломает загрузку.
 * Запуск: {@code javac} этого файла и {@link AeroLeafLedger} с slf4j на classpath, затем {@code java -ea}.
 */
public final class AeroLeafLedgerSelfCheck {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ledger");
        Path file = dir.resolve("data").resolve("leaves.bin");

        long a = AeroLeafLedger.key(-5, 7), b = AeroLeafLedger.key(123456, -987654);
        assert a != b && a != AeroLeafLedger.key(7, -5);

        AeroLeafLedger l = new AeroLeafLedger(file);
        assert !l.contains(a);
        l.add(a);
        l.add(a); // дубликат не пишется повторно
        l.add(b);
        l.close();
        assert Files.size(file) == 16 : Files.size(file);

        Files.write(file, new byte[]{1, 2, 3}, StandardOpenOption.APPEND); // оборванный хвост
        AeroLeafLedger r = new AeroLeafLedger(file);
        assert r.contains(a) && r.contains(b) && !r.contains(AeroLeafLedger.key(0, 0));
        r.close();

        System.out.println("AeroLeafLedgerSelfCheck: all checks passed");
    }
}
