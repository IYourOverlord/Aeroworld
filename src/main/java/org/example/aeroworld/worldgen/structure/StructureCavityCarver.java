package org.example.aeroworld.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Вырезает воздушную полость вокруг структуры ПЕРЕД тем, как рельеф Layer 1
 * уже залил этот объём сплошным камнем.
 *
 * <h3>Проблема</h3>
 * В ваниле рельеф строится через density function + Beardifier, который
 * САМ оставляет пустоты вокруг структур (структура "ныряет" в уже пористый
 * ландшафт, плюс между её частями обычно естественная пещера). В AeroWorld
 * рельеф Layer 1 — императивный: {@code Layer1FlatGenerator.fillChunk}
 * заливает камнем весь объём колонки независимо от того, что там позже
 * встанет структура. Сама структура печатает блоки только в момент
 * {@code applyBiomeDecoration} (через {@code super.applyBiomeDecoration()} →
 * {@code StructureStart.placeInChunk}) — то есть ПОСЛЕ того, как рельеф уже
 * залил всё камнем. Печать структуры замещает камень только там, где
 * реально стоят её jigsaw-пьесы (комнаты/коридоры); пространство МЕЖДУ
 * пьесами (естественно открытое в оригинальной структуре, там всегда воздух
 * между city_center/entrance/ruin-пьесами) остаётся закрашенным камнем
 * рельефа — эффект «структура внутри сплошной горы», к каждой части
 * приходится прокапываться.
 *
 * <h3>Решение</h3>
 * После того, как {@code Layer1FlatGenerator.fillChunk} залил рельеф, но
 * ДО того как чанк дойдёт до {@code applyBiomeDecoration} (где печатаются
 * реальные блоки структуры), проходим по всем {@code StructureStart},
 * пересекающим этот чанк, и для каждого {@code StructurePiece} вырезаем
 * воздух в его bounding box (+ небольшой запас) — то есть даём структуре
 * ту же "уже пористую" почву, которую она получила бы в ванильном
 * density-рельефе. Печать самой структуры (позже, в decoration) кладёт
 * актуальные блоки внутрь этой полости как обычно — carve только убирает
 * заранее лишний камень, не подменяет саму структуру.
 *
 * <p>Работает только выше {@code minCarveY} (по умолчанию — ниже уровня
 * земли, только Layer 1) — чтобы не трогать острова слоёв 2-4 и не резать
 * поверхностный рельеф там, где структуры и так наземные (деревни и т.п.
 * не нуждаются в carve — они и так строятся на поверхности).
 */
public final class StructureCavityCarver {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // Запас вокруг bounding box каждой пьесы (блоков) — чтобы полость была
    // чуть просторнее самой структуры, а не впритык к стенам (даёт "пещерный"
    // вид, как в оригинале, а не идеально прямоугольную коробку).
    private static final int PIECE_MARGIN = 2;

    private StructureCavityCarver() {}

    /**
     * Вырезает воздух под все структуры, чей bounding box пересекает этот
     * чанк — но ТОЛЬКО для категорий {@link StructureCategory#UNDERGROUND}
     * и {@link StructureCategory#WATER} (по {@link StructureCategoryResolver}).
     * Вызывать из {@code fillFromNoise}, СРАЗУ ПОСЛЕ заливки рельефа Layer 1
     * для этого чанка (пока структура ещё не напечатана).
     *
     * <p><b>Важно:</b> {@code ChunkAccess.getAllStarts()} возвращает
     * {@link StructureStart} ТОЛЬКО в чанке, где структура физически
     * стартует (origin chunk) — крупная структура вроде ancient_city
     * (~220×220 блоков = ~14×14 чанков) имеет ровно один такой чанк из
     * ~196 пересекаемых. Поэтому здесь используется
     * {@code structureManager.getAllStructuresAt(pos)}, который находит
     * структуры, пересекающие произвольную позицию, независимо от того,
     * в каком чанке находится их origin — так же, как это делает ванильный
     * Beardifier при сглаживании рельефа вокруг структур в соседних чанках.
     *
     * <p>Наземные структуры ({@link StructureCategory#SURFACE}, например
     * деревни) сюда намеренно НЕ попадают: они и так строятся на уже
     * открытой поверхности через собственную terrain-адаптацию, и carve
     * под ними создал бы неестественные ямы под домами вместо пользы.
     * Небесные/островные категории также не затрагиваются — Layer 1 carve
     * их не касается.
     *
     * @param structureManager используется и для поиска структур в этой
     *                         точке ({@code getAllStructuresAt}), и для
     *                         {@code registryAccess()} — перевода
     *                         {@code Structure} → {@code ResourceLocation}.
     * @param minCarveY верхняя граница по Y (обычно
     *                  {@code Layer1FlatGenerator.LAYER_MAX_Y}) — просто
     *                  защита от выхода за пределы чанка, не заменяет
     *                  фильтрацию по категории.
     */
    public static void carveForChunk(ChunkAccess chunk,
                                      net.minecraft.world.level.StructureManager structureManager,
                                      int minCarveY) {
        var structureRegistry = structureManager.registryAccess().registryOrThrow(
                net.minecraft.core.registries.Registries.STRUCTURE);

        BoundingBox chunkBox = new BoundingBox(
                chunk.getPos().getMinBlockX(), chunk.getMinBuildHeight(), chunk.getPos().getMinBlockZ(),
                chunk.getPos().getMaxBlockX(), chunk.getMaxBuildHeight(),                chunk.getPos().getMaxBlockZ());

        // Пробуем несколько точек чанка (центр + 4 угла) — getAllStructuresAt
        // ищет структуры, пересекающие ИМЕННО эту точку, а не весь чанк;
        // структура может задевать чанк только краем bounding box без
        // пересечения центра, поэтому проверяем несколько точек по высоте
        // всего диапазона Layer 1, чтобы не пропустить структуру, которая
        // проходит только через угол или только на большой глубине.
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int[][] sampleXZ = {
                {baseX + 8,  baseZ + 8},  // центр
                {baseX,      baseZ},      // углы
                {baseX + 15, baseZ},
                {baseX,      baseZ + 15},
                {baseX + 15, baseZ + 15},
        };
        int minY = Math.max(chunk.getMinBuildHeight(), -80);
        int maxY = Math.min(chunk.getMaxBuildHeight(), minCarveY);
        int ySamples = 6; // шаг по Y крупный — структура большая, не нужна плотная сетка

        java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, BlockPos> found =
                new java.util.HashMap<>();
        for (int[] xz : sampleXZ) {
            for (int i = 0; i <= ySamples; i++) {
                int y = minY + (maxY - minY) * i / ySamples;
                BlockPos samplePos = new BlockPos(xz[0], y, xz[1]);
                var atPos = structureManager.getAllStructuresAt(samplePos);
                for (var structure : atPos.keySet()) {
                    found.putIfAbsent(structure, samplePos);
                }
            }
        }
        if (found.isEmpty()) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (var e : found.entrySet()) {
            var structure = e.getKey();
            BlockPos hitPos = e.getValue();
            ResourceLocation structureId = structureRegistry.getKey(structure);
            if (structureId == null) continue;

            // Только UNDERGROUND/WATER — деревни и прочие SURFACE-структуры
            // здесь намеренно пропускаются (см. javadoc класса/метода).
            StructureCategory category = StructureCategoryResolver.resolve(structureId);
            if (category != StructureCategory.UNDERGROUND && category != StructureCategory.WATER) {
                continue;
            }

            // Используем ИМЕННО ту точку, где getAllStructuresAt нашёл
            // структуру (hitPos), а не центр чанка — getStructureAt требует
            // позицию, реально пересекающую bounding box структуры;
            // структура может задевать чанк только в углу/на одной глубине.
            StructureStart start = structureManager.getStructureAt(hitPos, structure);
            if (start == null || start == StructureStart.INVALID_START || !start.isValid()) continue;
            if (!start.getBoundingBox().intersects(chunkBox)) continue;

            for (StructurePiece piece : start.getPieces()) {
                BoundingBox pb = piece.getBoundingBox();
                if (!pb.intersects(chunkBox)) continue;

                int minX = Math.max(chunkBox.minX(), pb.minX() - PIECE_MARGIN);
                int maxX = Math.min(chunkBox.maxX(), pb.maxX() + PIECE_MARGIN);
                int minZ = Math.max(chunkBox.minZ(), pb.minZ() - PIECE_MARGIN);
                int maxZ = Math.min(chunkBox.maxZ(), pb.maxZ() + PIECE_MARGIN);
                int pMinY = Math.max(chunk.getMinBuildHeight(),   pb.minY() - PIECE_MARGIN);
                int pMaxY = Math.min(Math.min(chunk.getMaxBuildHeight(), minCarveY), pb.maxY() + PIECE_MARGIN);

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = pMinY; y <= pMaxY; y++) {
                            pos.set(x, y, z);
                            if (!chunk.getBlockState(pos).isAir()) {
                                chunk.setBlockState(pos, AIR, false);
                            }
                        }
                    }
                }
            }
        }
    }
}
