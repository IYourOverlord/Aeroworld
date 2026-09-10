package org.example.aeroworld.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;

import java.util.Map;

/**
 * AncientCityIslandSupportPlacer — создаёт каменную ступенчатую островную платформу-опору
 * под древним городом (Ancient City), чтобы структура не висела в пустоте гигантской пещеры.
 */
public final class AncientCityIslandSupportPlacer {

    private static final BlockState BS_DEEPSLATE_BRICKS = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
    private static final BlockState BS_POLISHED_DEEPSLATE = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    private static final BlockState BS_COBBLED_DEEPSLATE = Blocks.COBBLED_DEEPSLATE.defaultBlockState();

    private static final int STEP_SIZE = 3; // каждые 3 блока вбок Y понижается на 1 (ступенчатый склон)
    private static final int BASE_EXTENSION = 4; // выступ платформы за пределы структуры

    public static void placeSupportForChunk(WorldGenLevel level, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minChunkX = chunkPos.getMinBlockX();
        int maxChunkX = chunkPos.getMaxBlockX();
        int minChunkZ = chunkPos.getMinBlockZ();
        int maxChunkZ = chunkPos.getMaxBlockZ();

        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (!starts.isEmpty()) {
            for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                handleStructureStart(level, chunk, entry.getKey(), entry.getValue(), minChunkX, maxChunkX, minChunkZ, maxChunkZ);
            }
        }

        Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> refs = chunk.getAllReferences();
        if (!refs.isEmpty()) {
            for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> entry : refs.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                StructureStart start = level.getLevel().structureManager().getStartForStructure(
                        SectionPos.of(chunk.getPos(), chunk.getMinSection()), entry.getKey(), chunk);
                if (start != null && start != StructureStart.INVALID_START && start.isValid()) {
                    handleStructureStart(level, chunk, entry.getKey(), start, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
                }
            }
        }
    }

    private static void handleStructureStart(WorldGenLevel level, ChunkAccess chunk,
                                             Structure structure, StructureStart start,
                                             int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        if (start == null || !start.isValid()) return;
        ResourceLocation id = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .getKey(structure);
        if (id == null || !id.getPath().contains("ancient_city")) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (StructurePiece piece : start.getPieces()) {
            BoundingBox pieceBox = piece.getBoundingBox();
            int pieceMinY = pieceBox.minY();

            // Проверяем пересечение с текущим чанком с учётом запаса на ступени
            int scanMinX = Math.max(minChunkX, pieceBox.minX() - 15);
            int scanMaxX = Math.min(maxChunkX, pieceBox.maxX() + 15);
            int scanMinZ = Math.max(minChunkZ, pieceBox.minZ() - 15);
            int scanMaxZ = Math.min(maxChunkZ, pieceBox.maxZ() + 15);

            if (scanMinX > scanMaxX || scanMinZ > scanMaxZ) continue;

            for (int x = scanMinX; x <= scanMaxX; x++) {
                for (int z = scanMinZ; z <= scanMaxZ; z++) {
                    // Расстояние по горизонтали (Чебышёвское / манхэттенское) до границы pieceBox
                    int dx = 0;
                    if (x < pieceBox.minX()) dx = pieceBox.minX() - x;
                    else if (x > pieceBox.maxX()) dx = x - pieceBox.maxX();

                    int dz = 0;
                    if (z < pieceBox.minZ()) dz = pieceBox.minZ() - z;
                    else if (z > pieceBox.maxZ()) dz = z - pieceBox.maxZ();

                    int dist = Math.max(dx, dz); // Расстояние до прямоугольника

                    // Высота верха ступени в данной точке
                    int topY;
                    if (dist <= BASE_EXTENSION) {
                        topY = pieceMinY; // Плоская площадка прямо под структурой
                    } else {
                        int extra = dist - BASE_EXTENSION;
                        topY = pieceMinY - (extra / STEP_SIZE); // Ступенчатый спуск к полу
                    }

                    if (topY < Layer1TerrainGenerator.CAVE_BOTTOM_Y) {
                        continue;
                    }

                    // Заполняем столб вниз до пола пещеры
                    for (int y = topY; y >= Layer1TerrainGenerator.CAVE_BOTTOM_Y; y--) {
                        pos.set(x, y, z);
                        BlockState current = chunk.getBlockState(pos);
                        // Если уже стоит твёрдый блок не из воздуха/воды/растений — не перезаписываем
                        if (!current.isAir() && current.isSolid()) {
                            break;
                        }

                        if (y == topY) {
                            chunk.setBlockState(pos, BS_DEEPSLATE_BRICKS, false);
                        } else if (y >= topY - 2) {
                            chunk.setBlockState(pos, BS_POLISHED_DEEPSLATE, false);
                        } else {
                            chunk.setBlockState(pos, BS_COBBLED_DEEPSLATE, false);
                        }
                    }
                }
            }
        }
    }
}
