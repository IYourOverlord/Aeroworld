package org.example.aeroworld.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import org.exampl.physical_structures.api.event.PhysicalStructurePlacedEvent;
import org.example.aeroworld.AeroWorld;

/**
 * Слушатель события успешного размещения физической структуры.
 *
 * <p>Регистрируется в конструкторе {@link AeroWorld}:</p>
 * <pre>{@code
 * NeoForge.EVENT_BUS.register(AeroStructureListener.class);
 * }</pre>
 *
 * <p>Срабатывает после того как Sable собрал sub-level — т.е. структура
 * уже физически существует в мире и готова к взаимодействию.</p>
 */
public final class AeroStructureListener {

    @SubscribeEvent
    public static void onStructurePlaced(PhysicalStructurePlacedEvent event) {
        ServerLevel level  = event.level();
        BlockPos    origin = event.origin();


        // Звуковой эффект появления структуры
        level.playSound(
                null,                        // null = слышат все игроки в радиусе
                origin,
                SoundEvents.ANVIL_LAND,
                SoundSource.BLOCKS,
                1.5f,                        // громкость
                0.6f                         // pitch (низкий — тяжёлый металл)
        );

        // Здесь можно добавить:
        //   • партиклы через level.sendParticles(...)
        //   • запись в статистику
        //   • отправку пакета клиенту для кастомного эффекта
    }
}