package org.example.aeroworld.structure;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.example.aeroworld.AeroWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кеш размеров NBT-структур.
 *
 * <p>{@link StructurePlacementHelper#getStructureSize} выполняет IO-чтение и парсинг NBT.
 * Вызывать его на каждый retry-тик расточительно — структур немного, их размер не меняется
 * без перезагрузки датапаков. Этот класс хранит результаты в памяти и пересчитывает
 * их только при явном вызове {@link #invalidate()}.</p>
 *
 * <h3>Инвалидация</h3>
 * Вызывать {@link #invalidate()} при:
 * <ul>
 *   <li>событии {@code AddReloadListenerEvent} (смена датапаков / {@code /reload})</li>
 *   <li>остановке сервера</li>
 * </ul>
 */
public final class StructureSizeCache {

    /** Значение-sentinel для записей, которые не удалось прочитать — чтобы не повторять IO. */
    private static final Vec3i MISSING = new Vec3i(Integer.MIN_VALUE, 0, 0);

    private static final Map<ResourceLocation, Vec3i> CACHE = new ConcurrentHashMap<>();

    private StructureSizeCache() {}

    /**
     * Вернуть размер структуры, читая NBT только при первом обращении.
     *
     * @return размер, или {@code null} если NBT не удалось прочитать
     */
    public static Vec3i getSize(ServerLevel level, ResourceLocation nbtLocation) {
        Vec3i cached = CACHE.get(nbtLocation);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }

        Vec3i size = StructurePlacementHelper.getStructureSize(level, nbtLocation);
        CACHE.put(nbtLocation, size != null ? size : MISSING);

        if (size != null) {
        } else {
        }

        return size;
    }

    /**
     * Принудительно сбросить кеш.
     * После вызова следующее обращение снова выполнит IO-чтение.
     */
    public static void invalidate() {
        int size = CACHE.size();
        CACHE.clear();
    }
}
