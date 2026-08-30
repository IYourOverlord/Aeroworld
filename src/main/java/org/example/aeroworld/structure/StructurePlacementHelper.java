package org.example.aeroworld.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.example.aeroworld.AeroWorld;

/**
 * Утилиты для безопасного спавна структур PhysicalStructures на островах AeroWorld.
 *
 * <ul>
 *   <li>{@link #getStructureSize}  — размер NBT из ResourceManager (без инстанциирования шаблона)</li>
 *   <li>{@link #areChunksReady}    — проверка что все чанки зоны структуры имеют статус FULL</li>
 *   <li>{@link #isSpaceClear}      — быстрая проверка свободного пространства над поверхностью</li>
 * </ul>
 *
 * Все методы вызываются на server-thread.
 */
public final class StructurePlacementHelper {

    private StructurePlacementHelper() {}

    // ── NBT size ─────────────────────────────────────────────────────────────

    /**
     * Читает размер структуры из NBT через ResourceManager.
     *
     * <p>Ванильный NBT формат структур хранит поле {@code "size"} как список из трёх
     * int-тегов [sizeX, sizeY, sizeZ]. Метод парсит только этот заголовок,
     * не загружая всю структуру в память.</p>
     *
     * <p>Результат стоит кешировать через {@link StructureSizeCache} — это IO-операция.</p>
     *
     * @param nbtLocation путь вида {@code physical_structures:structures/tank_11.nbt}
     * @return размер структуры, или {@code null} если NBT недоступен / не распознан
     */
    public static Vec3i getStructureSize(ServerLevel level, ResourceLocation nbtLocation) {
        try {
            var resource = level.getServer()
                    .getResourceManager()
                    .getResourceOrThrow(nbtLocation);
            try (var stream = resource.open()) {
                CompoundTag nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                if (nbt.contains("size")) {
                    // TAG_List of TAG_Int: [sizeX, sizeY, sizeZ]
                    var list = nbt.getList("size", 3);
                    if (list.size() == 3) {
                        return new Vec3i(list.getInt(0), list.getInt(1), list.getInt(2));
                    }
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    // ── Chunk readiness ───────────────────────────────────────────────────────

    /**
     * Проверяет что все чанки в прямоугольной зоне {@code [origin, origin+size)}
     * имеют статус {@link ChunkStatus#FULL}.
     *
     * <p>Только при статусе FULL гарантировано что {@code level.setBlock()} запишет
     * блок в чанк. На более ранних статусах (FEATURES, LIGHT и др.) вызов молча
     * отбрасывается Minecraft, из-за чего {@code placeInWorld()} возвращает 0 блоков.</p>
     *
     * @param origin левый-нижний угол зоны структуры
     * @param size   размер структуры (из {@link #getStructureSize})
     * @return {@code true} если все затронутые чанки полностью загружены
     */
    public static boolean areChunksReady(ServerLevel level, BlockPos origin, Vec3i size) {
        int minCX = origin.getX() >> 4;
        int minCZ = origin.getZ() >> 4;
        int maxCX = (origin.getX() + Math.max(size.getX() - 1, 0)) >> 4;
        int maxCZ = (origin.getZ() + Math.max(size.getZ() - 1, 0)) >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ── Space clearance ───────────────────────────────────────────────────────

    /**
     * Быстрая проверка что зона над {@code origin} размером {@code size} преимущественно пуста.
     *
     * <p>Сэмплирует сетку с шагом {@code step=3} блока для производительности.
     * Если хотя бы один сэмплированный блок не является воздухом — возвращает false.</p>
     *
     * <p>Намеренно не является 100% точной проверкой: гарантирует отсутствие
     * крупных препятствий (другие структуры, крупные деревья), но не одиночных блоков.</p>
     *
     * @param origin левый-нижний угол проверяемой зоны (совпадает с origin спавна)
     * @param size   размер структуры
     * @return {@code true} если зона свободна для размещения
     */
    public static boolean isSpaceClear(ServerLevel level, BlockPos origin, Vec3i size) {
        final int STEP = 3;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        // Начинаем с dy=1 чтобы пропустить поверхностный блок острова (origin.getY()).
        // Сам блок поверхности — часть острова, а не препятствие для структуры.
        for (int dx = 0; dx < size.getX(); dx += STEP) {
            for (int dy = 1; dy < size.getY(); dy += STEP) {
                for (int dz = 0; dz < size.getZ(); dz += STEP) {
                    mpos.set(origin.getX() + dx,
                             origin.getY() + dy,
                             origin.getZ() + dz);
                    if (!level.getBlockState(mpos).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
