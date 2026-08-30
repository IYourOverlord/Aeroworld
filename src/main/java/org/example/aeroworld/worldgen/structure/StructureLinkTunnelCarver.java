package org.example.aeroworld.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.carver.SinkholeCarver;
import org.example.aeroworld.worldgen.structure.StructureLinkRegistry;

import java.util.List;

/**
 * Вырезает сеть пещерных тоннелей (диаметр {@value StructureLinkRegistry#TUNNEL_DIAMETER}
 * блока), соединяющих структуры Layer 1 между собой ниже уровня моря —
 * см. {@link StructureLinkRegistry}.
 *
 * <p>Вызывается из {@code AeroWorldChunkGenerator.applyCarvers} ПОСЛЕ
 * {@link SinkholeCarver}, тем же принципом детерминированного,
 * потокобезопасного carving напрямую по {@link ChunkAccess} — без
 * регистрации как {@code WorldCarver}.</p>
 *
 * <p><b>Производительность:</b> для подавляющего большинства чанков (без
 * тоннеля) {@link StructureLinkRegistry#segmentsForChunk} возвращает пустой
 * список за O(1) — carve-цикл не запускается вовсе. Для затронутых чанков
 * резка идёт только в bounding-box сегмента, ограниченном текущим чанком.</p>
 *
 * <p>Ось тоннеля получает детерминированный шумовой прогиб (не зависящий
 * от порядка обхода чанков — только от {@code linkSeed} и продольной
 * координаты вдоль сегмента), чтобы тоннель не выглядел идеально прямой
 * трубой. Сечение — эллипс с радиальным шумом ±1.5 блока для рваных стен.</p>
 */
public final class StructureLinkTunnelCarver {

    private StructureLinkTunnelCarver() {}

    private static final double RADIUS = StructureLinkRegistry.TUNNEL_RADIUS;

    /** Амплитуда синусоидального прогиба оси тоннеля по XZ и Y. */
    private static final double AXIS_WOBBLE_XZ = 6.0;
    private static final double AXIS_WOBBLE_Y  = 4.0;
    /** Период прогиба вдоль оси (в блоках). */
    private static final double WOBBLE_PERIOD = 40.0;

    /** Амплитуда неровности стенок (делает сечение не идеально круглым). */
    private static final double WALL_NOISE_AMPLITUDE = 1.5;

    /** Не режем выше этого Y относительно уровня моря — задание: "40 блоков вниз от моря". */
    private static final int BELOW_SEA_LEVEL_LIMIT = 40;

    private static final int BEDROCK_MARGIN = 5;

    private static final BlockState AIR       = Blocks.AIR.defaultBlockState();
    private static final BlockState STALACTITE = Blocks.DRIPSTONE_BLOCK.defaultBlockState();

    private static boolean isCarveTarget(BlockState state) {
        return !state.isAir() && !state.liquid();
    }

    /**
     * @param chunk     чанк, в котором идёт карвинг
     * @param registry  реестр тоннелей (per-generator instance)
     * @param seaLevel  фактический уровень моря ванильного noise-пресета
     *                  ({@code NoiseGeneratorSettings.seaLevel()})
     */
    public static void carveChunk(ChunkAccess chunk, StructureLinkRegistry registry, int seaLevel) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        List<StructureLinkRegistry.TunnelSegment> segments = registry.segmentsForChunk(chunkX, chunkZ);
        if (segments.isEmpty()) return;

        int minWX = chunkX << 4;
        int minWZ = chunkZ << 4;
        int minBuildHeight = chunk.getMinBuildHeight();

        // Задание: тоннели только в 40 блоках от уровня моря вниз.
        int hardCeilingY = seaLevel;
        int hardFloorY   = Math.max(seaLevel - BELOW_SEA_LEVEL_LIMIT, minBuildHeight + BEDROCK_MARGIN);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (StructureLinkRegistry.TunnelSegment seg : segments) {
            carveSegmentInChunk(chunk, seg, minWX, minWZ, hardCeilingY, hardFloorY, pos);
        }
    }

    private static void carveSegmentInChunk(ChunkAccess chunk, StructureLinkRegistry.TunnelSegment seg,
                                            int minWX, int minWZ,
                                            int hardCeilingY, int hardFloorY,
                                            BlockPos.MutableBlockPos pos) {
        double dx = seg.bx() - seg.ax();
        double dz = seg.bz() - seg.az();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3) return;

        double ux = dx / length;
        double uz = dz / length;
        // Перпендикуляр в плоскости XZ — для бокового прогиба оси.
        double px = -uz;
        double pz = ux;

        double margin = RADIUS + AXIS_WOBBLE_XZ + WALL_NOISE_AMPLITUDE + 2.0;

        // Шаг сэмплирования оси — половина радиуса, чтобы не пропустить пересечение чанка.
        int sampleCount = (int) Math.ceil(length / (RADIUS)) + 1;

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / (sampleCount - 1 == 0 ? 1 : sampleCount - 1);
            double alongDist = t * length;

            double baseX = seg.ax() + dx * t;
            double baseZ = seg.az() + dz * t;

            // Детерминированный шумовой прогиб — функция только от linkSeed
            // и позиции вдоль оси, не от чанка: соседние чанки видят
            // идентичную ось на границе.
            double phase = alongDist / WOBBLE_PERIOD * (Math.PI * 2.0);
            long wobbleHashXZ = mixSeed(seg.linkSeed(), Double.doubleToLongBits(Math.floor(alongDist / 4.0)));
            double wobbleN = ((wobbleHashXZ >>> 40) & 0xFFFF) / 65535.0 * 2.0 - 1.0;

            double axisX = baseX + px * (Math.sin(phase) * AXIS_WOBBLE_XZ * 0.5 + wobbleN * AXIS_WOBBLE_XZ * 0.5);
            double axisZ = baseZ + pz * (Math.sin(phase) * AXIS_WOBBLE_XZ * 0.5 + wobbleN * AXIS_WOBBLE_XZ * 0.5);
            double axisY = seg.ay() + Math.sin(phase * 0.7) * AXIS_WOBBLE_Y;

            // Быстрый bounding-check: центр сечения далеко от этого чанка — пропускаем сэмпл.
            double chunkCenterX = minWX + 8.0;
            double chunkCenterZ = minWZ + 8.0;
            double ddx = axisX - chunkCenterX;
            double ddz = axisZ - chunkCenterZ;
            if (ddx * ddx + ddz * ddz > (margin + 12.0) * (margin + 12.0)) continue;

            carveDisk(chunk, axisX, axisY, axisZ, seg.linkSeed(), minWX, minWZ,
                    hardCeilingY, hardFloorY, pos);
        }
    }

    /** Вырезает эллиптическое сечение с шумовыми стенками в текущем чанке вокруг (cx,cy,cz). */
    private static void carveDisk(ChunkAccess chunk, double cx, double cy, double cz,
                                  long linkSeed, int minWX, int minWZ,
                                  int hardCeilingY, int hardFloorY,
                                  BlockPos.MutableBlockPos pos) {
        int startX = Math.max(minWX, (int) Math.floor(cx - RADIUS - WALL_NOISE_AMPLITUDE - 1));
        int endX   = Math.min(minWX + 15, (int) Math.floor(cx + RADIUS + WALL_NOISE_AMPLITUDE + 1));
        int startZ = Math.max(minWZ, (int) Math.floor(cz - RADIUS - WALL_NOISE_AMPLITUDE - 1));
        int endZ   = Math.min(minWZ + 15, (int) Math.floor(cz + RADIUS + WALL_NOISE_AMPLITUDE + 1));
        if (startX > endX || startZ > endZ) return;

        int startY = Math.max(hardFloorY, (int) Math.floor(cy - RADIUS - WALL_NOISE_AMPLITUDE - 1));
        int endY   = Math.min(hardCeilingY, (int) Math.floor(cy + RADIUS + WALL_NOISE_AMPLITUDE + 1));
        if (startY > endY) return;

        for (int wx = startX; wx <= endX; wx++) {
            for (int wz = startZ; wz <= endZ; wz++) {
                double ddx = wx - cx;
                double ddz = wz - cz;
                double radial = Math.sqrt(ddx * ddx + ddz * ddz);

                // Шум стенки по азимуту — детерминирован по (linkSeed, направлению),
                // округлённому до крупных секторов, чтобы не мерцать блок-к-блоку.
                long wallHash = mixSeed(linkSeed, Double.doubleToLongBits(Math.atan2(ddz, ddx) * 32.0));
                double wallNoise = ((wallHash >>> 40) & 0xFFFF) / 65535.0 * WALL_NOISE_AMPLITUDE;
                double effRadiusXZ = RADIUS + wallNoise;

                if (radial > effRadiusXZ + 1.0) continue;

                for (int wy = startY; wy <= endY; wy++) {
                    double ddy = wy - cy;
                    // Эллипсоид: чуть приплюснут по Y (визуально шире, чем выше).
                    double norm = (ddx * ddx + ddz * ddz) / (effRadiusXZ * effRadiusXZ)
                            + (ddy * ddy) / (RADIUS * RADIUS);
                    if (norm > 1.0) continue;

                    pos.set(wx, wy, wz);
                    BlockState current = chunk.getBlockState(pos);
                    if (!isCarveTarget(current)) continue;
                    chunk.setBlockState(pos, AIR, false);
                }

                // Сталактиты/сталагниты на границе полости — сразу над/под потолком/полом.
                placeDripstoneAt(chunk, wx, wz, cy, effRadiusXZ, radial, wallHash, startY, endY, pos);
            }
        }
    }

    /**
     * Ставит сталагнит (снизу, растёт вверх) либо сталактит (сверху, растёт вниз)
     * в точке (wx,wz), если она внутри радиуса тоннеля по XZ — ищет верхнюю и
     * нижнюю границу вырезанной полости в этом столбце и with некоторой
     * вероятностью помещает dripstone block вплотную к камню (проверка вокруг
     * не выполняется — Dripstone Block достаточно как декоративный маркер,
     * не требует полноценной физики роста сталактитов).
     */
    private static void placeDripstoneAt(ChunkAccess chunk, int wx, int wz, double axisY,
                                         double effRadiusXZ, double radial, long wallHash,
                                         int startY, int endY, BlockPos.MutableBlockPos pos) {
        if (radial > effRadiusXZ * 0.85) return; // только ближе к центру полости, не у самых стен
        // Разреженность: не в каждой колонке.
        if (((wallHash >>> 8) & 0xF) != 0) return;

        boolean placeUpper = ((wallHash >>> 12) & 1) == 0;

        if (placeUpper) {
            for (int wy = endY; wy >= startY; wy--) {
                pos.set(wx, wy, wz);
                if (!chunk.getBlockState(pos).isAir()) continue;
                pos.set(wx, wy + 1, wz);
                BlockState above = chunk.getBlockState(pos);
                if (isCarveTarget(above)) {
                    pos.set(wx, wy, wz);
                    chunk.setBlockState(pos, STALACTITE, false);
                }
                break;
            }
        } else {
            for (int wy = startY; wy <= endY; wy++) {
                pos.set(wx, wy, wz);
                if (!chunk.getBlockState(pos).isAir()) continue;
                pos.set(wx, wy - 1, wz);
                BlockState below = chunk.getBlockState(pos);
                if (isCarveTarget(below)) {
                    pos.set(wx, wy, wz);
                    chunk.setBlockState(pos, STALACTITE, false);
                }
                break;
            }
        }
    }

    private static long mixSeed(long a, long b) {
        long h = a ^ (b * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }
}
