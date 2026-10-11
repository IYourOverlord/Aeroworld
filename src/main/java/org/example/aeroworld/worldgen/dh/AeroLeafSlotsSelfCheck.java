package org.example.aeroworld.worldgen.dh;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Self-check для {@link AeroLeafSlots}: лимит не превышается, release возвращает слот, cap=0 без лимита, гонка потоков не пробивает лимит.
 * Запуск: {@code javac -d /tmp/ls AeroLeafSlots.java AeroLeafSlotsSelfCheck.java && java -ea -cp /tmp/ls org.example.aeroworld.worldgen.dh.AeroLeafSlotsSelfCheck}
 */
public final class AeroLeafSlotsSelfCheck {

    public static void main(String[] args) {
        AeroLeafSlots s = new AeroLeafSlots();
        assert s.tryAcquire(2) && s.tryAcquire(2) && !s.tryAcquire(2) && s.get() == 2; // отказ слот не занимает
        s.release();
        assert s.tryAcquire(2) && s.get() == 2;

        AeroLeafSlots free = new AeroLeafSlots();
        for (int i = 0; i < 1000; i++) assert free.tryAcquire(0); // 0 = выключено
        assert free.get() == 1000;

        AeroLeafSlots race = new AeroLeafSlots();
        AtomicInteger granted = new AtomicInteger();
        IntStream.range(0, 10_000).parallel().forEach(i -> { if (race.tryAcquire(14)) granted.incrementAndGet(); });
        assert granted.get() == 14 && race.get() == 14 : granted.get();

        System.out.println("AeroLeafSlotsSelfCheck: all checks passed");
    }
}
