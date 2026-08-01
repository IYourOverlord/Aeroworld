package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.noise.AeroNoise;

import java.util.ArrayList;
import java.util.List;

/**
 * Карстовые воронки (sinkholes) под островами слоёв 2/3/4 — карвятся прямо
 * в террейне слоя 1 (Y -64..50), как будто остров когда-то "вырвался" из
 * земли и улетел вверх, оставив после себя провал, уходящий почти до
 * бедрока.
 *
 * <h3>Почему не переиспользован код SinkholeRestorer (github.com/Amrsatrio/SinkholeRestorer)</h3>
 * Тот мод — это Mixin, который восстанавливает старое (до-1.19.3) поведение
 * ванильного {@code Aquifer.NoiseBasedAquifer}: меняет порядок статического
 * массива {@code SURFACE_SAMPLING_OFFSETS_IN_CHUNKS} и отключает оптимизацию
 * {@code skipSamplingAboveY}. Из-за этого "провалы" в его случае возникают
 * СЛУЧАЙНО — только там, где по чистой случайности сошлись экстремальные
 * значения weirdness/erosion density-функций конкретного seed'а. Явно
 * указать координаты воронки там невозможно в принципе — это побочный
 * эффект ванильной генерации, а не управляемый инструмент.
 *
 * Нам же нужно ДЕТЕРМИНИРОВАННО разместить воронку именно под каждым
 * конкретным островом (координаты которого мы и так уже знаем из
 * {@link IslandData}), поэтому карвер написан с нуля как отдельный проход
 * по колонкам террейна слоя 1 — без каких-либо Mixin, просто обычный
 * блок-карвинг поверх уже готового рельефа, аналогично тому, как работают
 * {@code applyCarvers} у ванильных пещер.
 */
public class Layer1SinkholeCarver {

    // ── Форма воронки ────────────────────────────────────────────────────────
    // Радиус воронки = radius острова над ней × этот коэффициент.
    private static final double RADIUS_FACTOR  = 0.55;
    private static final double MIN_RADIUS     = 4.0;
    // Неровность краёв (0..1 от радиуса) — карстовый, а не идеально круглый вид.
    private static final double EDGE_NOISE_AMP = 0.35;

    private static final int SHAFT_TOP_Y    = Layer1FlatGenerator.LAYER_MAX_Y;      //  50
    private static final int SHAFT_BOTTOM_Y = Layer1FlatGenerator.LAYER_MIN_Y + 6;  // -58 (бедрок на -64 не трогаем)
    private static final int POOL_HEIGHT    = 3; // лужа лавы на самом дне воронки

    private static final BlockState BS_AIR  = Blocks.AIR.defaultBlockState();
    private static final BlockState BS_LAVA = Blocks.LAVA.defaultBlockState();

    // ── Источники островов (слои 2/3/4) ─────────────────────────────────────
    private final LowerIslandGenerator layer2;
    private final HighIslandGenerator  layer3;
    private final UpperIslandGenerator layer4;

    private final AeroNoise edgeNoise;

    public Layer1SinkholeCarver(long worldSeed,
                                 LowerIslandGenerator layer2,
                                 HighIslandGenerator  layer3,
                                 UpperIslandGenerator layer4) {
        this.layer2 = layer2;
        this.layer3 = layer3;
        this.layer4 = layer4;
        this.edgeNoise = new AeroNoise(worldSeed ^ 0x51CD40FEL);
    }

    /** Карвит воронки (если есть) для всех островов слоёв 2/3/4, чей центр рядом с этим чанком. */
    public void carveChunk(ChunkAccess chunk, int chunkX, int chunkZ) {
        List<IslandData> nearby = collectNearby(chunkX, chunkZ);
        if (nearby.isEmpty()) return;

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (IslandData d : nearby) {
            double baseR = Math.max(MIN_RADIUS, d.radius * RADIUS_FACTOR);
            double maxR  = baseR * (1.0 + EDGE_NOISE_AMP);

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = baseX + lx;
                    int wz = baseZ + lz;

                    double dx = wx - d.cx;
                    double dz = wz - d.cz;
                    double distSq = dx * dx + dz * dz;
                    if (distSq > maxR * maxR) continue; // быстрый отсев

                    double dist  = Math.sqrt(distSq);
                    double angle = Math.atan2(dz, dx);

                    // Неровный, "карстовый" край — не идеальная окружность.
                    double jitter = edgeNoise.noise2D(
                            d.cx * 0.01 + Math.cos(angle) * 3.0,
                            d.cz * 0.01 + Math.sin(angle) * 3.0) * baseR * EDGE_NOISE_AMP;
                    double edgeR = baseR + jitter;
                    if (dist > edgeR) continue;

                    carveColumn(chunk, pos, wx, wz, dist, edgeR);
                }
            }
        }
    }

    private void carveColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                              int wx, int wz, double dist, double edgeR) {
        for (int wy = SHAFT_BOTTOM_Y + POOL_HEIGHT; wy <= SHAFT_TOP_Y; wy++) {
            // Лёгкое "бульбовое" сужение к верху и низу шахты (карст, а не
            // идеальный цилиндр): в середине шире, у краёв уже.
            double t = (double) (wy - SHAFT_BOTTOM_Y) / (SHAFT_TOP_Y - SHAFT_BOTTOM_Y);
            double bulge = 0.75 + 0.25 * Math.sin(t * Math.PI); // 0.75 → 1.0 → 0.75
            if (dist > edgeR * bulge) continue;

            pos.set(wx, wy, wz);
            chunk.setBlockState(pos, BS_AIR, false);
        }
        // Лужа лавы на самом дне воронки — визуальный якорь, как на картинке-референсе.
        for (int wy = SHAFT_BOTTOM_Y; wy < SHAFT_BOTTOM_Y + POOL_HEIGHT; wy++) {
            pos.set(wx, wy, wz);
            chunk.setBlockState(pos, BS_LAVA, false);
        }
    }

    private List<IslandData> collectNearby(int chunkX, int chunkZ) {
        List<IslandData> out = new ArrayList<>();
        for (int[] c : layer2.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, layer2.getSearchRadius() + 1)) {
            out.add(layer2.getIslandData(c[0], c[1]));
        }
        for (int[] c : layer3.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, layer3.getSearchRadius() + 1)) {
            out.add(layer3.getIslandData(c[0], c[1]));
        }
        for (int[] c : layer4.getPlacer().getIslandCentresForChunk(chunkX, chunkZ, layer4.getSearchRadius() + 1)) {
            out.add(layer4.getIslandData(c[0], c[1]));
        }
        return out;
    }
}
