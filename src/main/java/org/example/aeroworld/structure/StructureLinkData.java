package org.example.aeroworld.structure;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.worldgen.structure.StructureLinkRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * SavedData — персистентная копия узлов {@link StructureLinkRegistry}
 * (сеть пещерных тоннелей между структурами Layer 1).
 *
 * <p><b>Зачем нужна отдельная персистентность:</b> {@link StructureLinkRegistry}
 * — чисто in-memory состояние ChunkGenerator'а, обнуляемое при каждом
 * перезапуске сервера. Без сохранения структур, открытых в прошлой сессии,
 * при следующем запуске новые структуры не будут "видеть" уже известные
 * старые как кандидатов на связывание — граф начинался бы с нуля каждую
 * сессию, оставляя изолированные острова структур на границах сессий.</p>
 *
 * <p><b>Что НЕ сохраняется:</b> уже проиндексированные {@code TunnelSegment}
 * и {@code carvedChunks} — они не нужны межсессионно, так как относятся
 * либо к уже вырезанным (записанным на диск) чанкам, либо будут
 * восстановлены естественным образом при повторной генерации чанков в
 * новой сессии через {@link StructureLinkRegistry#registerStructure}.</p>
 *
 * <p>Запись происходит на server-thread из тик-хендлера (см.
 * {@code AeroWorldChunkGenerator}/событие тика), а не из worldgen-потоков —
 * по тому же паттерну, что {@link IslandStructureScheduler#flushToPersistence}.</p>
 */
public class StructureLinkData extends SavedData {

    public static final String DATA_NAME = AeroWorld.MOD_ID + "_structure_links";

    private final List<StructureLinkRegistry.StructureNode> nodes = new ArrayList<>();

    private StructureLinkData() {}

    private static StructureLinkData load(CompoundTag tag, HolderLookup.Provider provider) {
        StructureLinkData data = new StructureLinkData();
        ListTag list = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            long id = e.getLong("id");
            double x = e.getDouble("x");
            double z = e.getDouble("z");
            int surfaceY = e.getInt("surfaceY");
            long startChunkKey = e.getLong("startChunkKey");
            data.nodes.add(new StructureLinkRegistry.StructureNode(id, x, z, surfaceY, startChunkKey));
        }
        AeroWorld.LOGGER.info(
                "[AeroWorld][StructureLink] StructureLinkData: загружено {} узлов из предыдущей сессии.",
                data.nodes.size());
        return data;
    }

    public static StructureLinkData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(StructureLinkData::new, StructureLinkData::load),
                DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (StructureLinkRegistry.StructureNode n : nodes) {
            CompoundTag c = new CompoundTag();
            c.putLong("id", n.id());
            c.putDouble("x", n.x());
            c.putDouble("z", n.z());
            c.putInt("surfaceY", n.surfaceY());
            c.putLong("startChunkKey", n.startChunkKey());
            list.add(c);
        }
        tag.put("nodes", list);
        return tag;
    }

    /**
     * Полностью заменяет сохранённый снимок текущим состоянием реестра и
     * помечает данные "грязными" для записи на диск. Вызывать периодически
     * (не каждый тик — снимок узлов растёт медленно) со server-thread.
     */
    public void replaceSnapshot(List<StructureLinkRegistry.StructureNode> current) {
        if (current.size() == nodes.size()) return; // ничего нового — не трогаем диск
        nodes.clear();
        nodes.addAll(current);
        setDirty();
    }

    public List<StructureLinkRegistry.StructureNode> getNodes() {
        return nodes;
    }
}
