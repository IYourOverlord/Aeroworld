package org.example.aeroworld.worldgen.dh;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Журнал листовых секций DH (detail 0), которым аналитика уже отдала картинку (шаг SURFACE).
 * Повторный запрос такой секции = фаза 2 (реальный chunk-gen). Хранится на диске уровня как
 * последовательность 8-байтовых ключей, чтобы фаза не терялась после перезапуска.
 * <p>
 * ponytail: набор целиком в памяти (≈50 байт/секция) и append-only файл без компактации;
 * потолок — сотни тысяч секций. Апгрейд: SQLite-таблица рядом с БД DH.
 */
public final class AeroLeafLedger {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroLeafLedger.class);
    private static final int FLUSH_EVERY = 64;

    private final Set<Long> seen = ConcurrentHashMap.newKeySet();
    private final DataOutputStream out;
    private int pendingFlush;

    public AeroLeafLedger(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                while (true) seen.add(in.readLong());
            } catch (EOFException endOfFile) {
                // норма: конец файла (возможный оборванный хвост игнорируется)
            }
        }
        this.out = new DataOutputStream(new java.io.BufferedOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
        LOGGER.info("[AeroWorld DH] Leaf ledger {}: {} entries", file.getFileName(), seen.size());
    }

    public static long key(int lodPosX, int lodPosZ) {
        return ((long) lodPosX << 32) | (lodPosZ & 0xFFFFFFFFL);
    }

    public boolean contains(long key) { return seen.contains(key); }

    public synchronized void add(long key) {
        if (!seen.add(key)) return;
        try {
            out.writeLong(key);
            if (++pendingFlush >= FLUSH_EVERY) {
                out.flush();
                pendingFlush = 0;
            }
        } catch (IOException e) {
            LOGGER.warn("[AeroWorld DH] Leaf ledger write failed (in-memory state kept): {}", e.toString());
        }
    }

    public synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            LOGGER.warn("[AeroWorld DH] Leaf ledger close failed: {}", e.toString());
        }
    }
}
