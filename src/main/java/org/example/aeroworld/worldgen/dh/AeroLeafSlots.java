package org.example.aeroworld.worldgen.dh;

import java.util.concurrent.atomic.AtomicInteger;

/** Счётчик одновременных реальных листьев с атомарным резервированием под лимит (этап 1б). Без MC/DH, проверка: {@link AeroLeafSlotsSelfCheck}. */
final class AeroLeafSlots {

    private final AtomicInteger inFlight = new AtomicInteger();

    /** Занять слот; {@code cap <= 0} — без лимита. {@code false}, если лимит исчерпан (слот не занят). */
    boolean tryAcquire(int cap) {
        int n;
        do {
            n = inFlight.get();
            if (cap > 0 && n >= cap) return false;
        } while (!inFlight.compareAndSet(n, n + 1));
        return true;
    }

    void release() {
        inFlight.decrementAndGet();
    }

    int get() {
        return inFlight.get();
    }
}
