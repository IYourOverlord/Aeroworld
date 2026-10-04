package org.example.aeroworld.worldgen.dh;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.column.AeroColumnModel;
import org.example.aeroworld.worldgen.column.SliceVote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Минималистичные маркеры структур на аналитическом LOD: на месте структуры стоит её миниатюра
 * ({@link AeroStructureIcon}: деревня — домик, монумент — башенка и т. д.), неизвестные структуры — цветной шпиль.
 *
 * <p>Позиции берутся пробной генерацией стартов: для каждого чанка-кандидата ({@link RandomSpreadStructurePlacement},
 * уже прошедшего {@code isStructureChunk}) вызывается наш {@code createStructures} на одноразовом {@link ProtoChunk}.
 * Это тот же код, что в реальном мире, включая {@code StructureSupportValidator}: отклонённые структуры маркера не
 * получают. Результат кэшируется по ячейкам {@link #CELL} x {@link #CELL} чанков до конца сессии. Подземные структуры
 * и End City не рисуются, см. {@link AeroStructureIcon#shownOnLod}.</p>
 *
 * <p>Миниатюра всегда стоит на земле: основание — верх самого высокого непустого спана колонки в «своём»
 * вертикальном поясе ({@link #bandBottom}/{@link #bandTop} по верху bounding box структуры), деревья на этом месте
 * срезаются. Если в поясе под центром структуры ничего нет (центр над пустотой), маркер не рисуется, а не висит.</p>
 *
 * <p>Выключается {@code -Daeroworld.dhStructures=false}. Любой сбой пробной генерации один раз пишется в лог
 * и отключает маркеры до перезапуска (LOD при этом остаётся рабочим).</p>
 *
 * <p>ponytail: маркеры только на detail &lt;= {@link #MAX_DETAIL} (колонка до 32 блоков): секция больше
 * считала бы тысячи кандидатов в потоке DH. На грубых колонках миниатюра масштабируется в {@code step/4} раз
 * и рисуется выборкой по центру колонки (силуэт приблизительный). Апгрейд: считать ячейки заранее фоновым
 * потоком вокруг игрока. Концентрические кольца (strongholds) не учитываются. Уже сгенерированные секции LOD
 * маркеров не получат — нужна перегенерация БД LOD.</p>
 */
public final class AeroStructureMarkers {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroStructureMarkers.class);

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("aeroworld.dhStructures"));
    /** Максимальный detail секции LOD, на которой рисуются маркеры. */
    public static final int MAX_DETAIL = 5;
    /** Сторона ячейки кэша в чанках. */
    private static final int CELL = 32;
    /** Границы вертикальных поясов: Layer 1 (до 350), Layer 2 (до 700), Layer 3 (до 1500), Layer 4. */
    private static final int[] BAND_TOPS = {350, 700, 1500, Integer.MAX_VALUE};
    /** Деревья выше земли срезаются под миниатюрой в пределах этой высоты. */
    private static final int TREE_CLEAR = 96;

    /** Точка структуры: центр (x, z), верх её bounding box и путь id (без namespace). */
    public record Marker(int x, int z, int topY, String path) {}

    /** Часть миниатюры, попавшая в одну колонку секции: отрезки по вертикали (в ячейках) и масштаб ячейки в блоках. */
    public record Placed(Marker marker, List<AeroStructureIcon.Seg> segs, int scale) {}

    private final AeroWorldChunkGenerator generator;
    private final ServerLevel level;
    private final Map<Long, List<Marker>> cells = new ConcurrentHashMap<>();
    private volatile boolean failed;

    public AeroStructureMarkers(AeroWorldChunkGenerator generator, ServerLevel level) {
        this.generator = generator;
        this.level = level;
    }

    /**
     * Миниатюры структур в секции LOD, ключ {@code relX * width + relZ}. Секция: {@code width} колонок
     * по {@code step} блоков от ({@code baseX}, {@code baseZ}). Ячейка миниатюры — {@code max(1, step/4)} блоков,
     * колонка берёт отрезки той ячейки, в которую попал её центр.
     */
    public Map<Integer, Placed> forSection(int baseX, int baseZ, int width, int step) {
        Map<Integer, Placed> out = new HashMap<>();
        if (failed) return out;
        int size = width * step;
        int cell = CELL << 4;
        int scale = Math.max(1, step / 4);
        for (int cx = Math.floorDiv(baseX, cell); cx <= Math.floorDiv(baseX + size - 1, cell); cx++) {
            for (int cz = Math.floorDiv(baseZ, cell); cz <= Math.floorDiv(baseZ + size - 1, cell); cz++) {
                for (Marker m : cells.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> computeCell((int) (k >> 32), (int) (long) k))) {
                    AeroStructureIcon icon = AeroStructureIcon.forPath(m.path());
                    int ox = m.x() - icon.w * scale / 2, oz = m.z() - icon.d * scale / 2;
                    int rx0 = Math.max(0, Math.floorDiv(ox - baseX, step)), rx1 = Math.min(width - 1, Math.floorDiv(ox + icon.w * scale - 1 - baseX, step));
                    int rz0 = Math.max(0, Math.floorDiv(oz - baseZ, step)), rz1 = Math.min(width - 1, Math.floorDiv(oz + icon.d * scale - 1 - baseZ, step));
                    for (int rx = rx0; rx <= rx1; rx++) {
                        for (int rz = rz0; rz <= rz1; rz++) {
                            var segs = icon.column(Math.floorDiv(baseX + rx * step + step / 2 - ox, scale),
                                    Math.floorDiv(baseZ + rz * step + step / 2 - oz, scale));
                            if (!segs.isEmpty()) out.putIfAbsent(rx * width + rz, new Placed(m, segs, scale));
                        }
                    }
                }
            }
        }
        return out;
    }

    private List<Marker> computeCell(int cellX, int cellZ) {
        if (failed) return List.of();
        try {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            RegistryAccess registries = level.registryAccess();
            Registry<Structure> structureRegistry = registries.registryOrThrow(Registries.STRUCTURE);
            Registry<net.minecraft.world.level.biome.Biome> biomes = registries.registryOrThrow(Registries.BIOME);

            int minCx = cellX * CELL, minCz = cellZ * CELL;
            Set<Long> candidates = new LinkedHashSet<>();
            for (Holder<StructureSet> set : state.possibleStructureSets()) {
                if (!(set.value().placement() instanceof RandomSpreadStructurePlacement spread)) continue;
                // Подземные структуры и End City не рисуем; набор целиком пропускаем, чтобы не тратить пробы
                // (шахты и End City имеют spacing=1, то есть 1024 пробы на ячейку)
                if (set.value().structures().stream().allMatch(e -> e.structure().unwrapKey()
                        .map(k -> !AeroStructureIcon.shownOnLod(k.location().getPath())).orElse(false))) continue;
                int sp = spread.spacing();
                for (int rx = Math.floorDiv(minCx, sp); rx <= Math.floorDiv(minCx + CELL - 1, sp); rx++) {
                    for (int rz = Math.floorDiv(minCz, sp); rz <= Math.floorDiv(minCz + CELL - 1, sp); rz++) {
                        ChunkPos p = spread.getPotentialStructureChunk(state.getLevelSeed(), rx * sp, rz * sp);
                        if (p.x >= minCx && p.x < minCx + CELL && p.z >= minCz && p.z < minCz + CELL
                                && spread.isStructureChunk(state, p.x, p.z)) candidates.add(p.toLong());
                    }
                }
            }

            List<Marker> out = new ArrayList<>();
            for (long packed : candidates) {
                ProtoChunk chunk = new ProtoChunk(new ChunkPos(packed), UpgradeData.EMPTY, level, biomes, null);
                generator.createStructures(registries, state, level.structureManager(), chunk,
                        level.getServer().getStructureManager());
                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    StructureStart start = e.getValue();
                    if (start == null || start == StructureStart.INVALID_START || !start.isValid()) continue;
                    ResourceLocation id = structureRegistry.getKey(e.getKey());
                    if (id == null || !AeroStructureIcon.shownOnLod(id.getPath())) continue;
                    BoundingBox b = start.getBoundingBox();
                    out.add(new Marker((b.minX() + b.maxX()) >> 1, (b.minZ() + b.maxZ()) >> 1, b.maxY(), id.getPath()));
                }
            }
            return out;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("[AeroWorld DH] Structure markers disabled until restart (trial structure generation failed):", t);
            return List.of();
        }
    }

    /** Нижняя (исключительная) граница пояса, в котором стоит структура с верхом {@code topY}. */
    static int bandBottom(int topY) {
        int prev = Integer.MIN_VALUE;
        for (int top : BAND_TOPS) {
            if (topY <= top) return prev;
            prev = top;
        }
        return prev;
    }

    /** Верхняя (включительная) граница пояса, в котором стоит структура с верхом {@code topY}. */
    static int bandTop(int topY) {
        for (int top : BAND_TOPS) if (topY <= top) return top;
        return Integer.MAX_VALUE;
    }

    private static boolean isTree(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
    }

    private static BlockState block(char c, String path) {
        return switch (c) {
            case 'p' -> Blocks.OAK_PLANKS.defaultBlockState();
            case 'd' -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
            case 'c' -> Blocks.COBBLESTONE.defaultBlockState();
            case 'b' -> Blocks.BRICKS.defaultBlockState();
            case 'l' -> Blocks.OAK_LOG.defaultBlockState();
            case 's' -> Blocks.SANDSTONE.defaultBlockState();
            case 'm' -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case 'w' -> Blocks.SNOW_BLOCK.defaultBlockState();
            case 'o' -> Blocks.OBSIDIAN.defaultBlockState();
            case 'P' -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
            case 'L' -> Blocks.SEA_LANTERN.defaultBlockState();
            case 'D' -> Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            case 'S' -> Blocks.SCULK.defaultBlockState();
            case 'n' -> Blocks.NETHER_BRICKS.defaultBlockState();
            case 'k' -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
            case 'g' -> Blocks.GOLD_BLOCK.defaultBlockState();
            default -> BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                    DyeColor.byId(Math.floorMod(path.hashCode(), 16)).getName() + "_concrete")).defaultBlockState();
        };
    }

    /**
     * Ставит часть миниатюры в колонку. Земля — верх самого высокого непустого не-древесного спана в поясе структуры
     * (поверх подземных структур и воды тоже поверхность, не bounding box); деревья над землёй срезаются.
     * Если земли в поясе нет, колонка не меняется. Биом миниатюры — биом спана-земли.
     */
    public static List<AeroColumnModel.Span> apply(List<AeroColumnModel.Span> spans, Placed placed, int maxY) {
        int lo = bandBottom(placed.marker().topY()), hi = bandTop(placed.marker().topY());
        AeroColumnModel.Span host = null;
        for (AeroColumnModel.Span sp : spans) {
            if (sp.bottomY() > lo && sp.bottomY() <= hi && !isTree(sp.state())
                    && (host == null || sp.topY() > host.topY())) host = sp;
        }
        if (host == null) return spans;

        int ground = host.topY();
        List<SliceVote.Seg<AeroColumnModel.Span>> segs = new ArrayList<>(spans.size() + placed.segs().size());
        for (AeroColumnModel.Span sp : spans) {
            boolean tree = isTree(sp.state()) && sp.bottomY() > ground && sp.bottomY() <= ground + TREE_CLEAR;
            if (!tree) segs.add(new SliceVote.Seg<>(sp.bottomY(), sp.topY() + 1, sp));
        }
        for (AeroStructureIcon.Seg g : placed.segs()) {
            int from = ground + 1 + g.y0() * placed.scale();
            int to = Math.min(ground + 1 + g.y1() * placed.scale(), maxY + 1);
            if (to <= from) continue;
            segs = SliceVote.carve(segs, from, to, new AeroColumnModel.Span(from, to - 1,
                    block(g.c(), placed.marker().path()), host.biomeName(), host.biomeHolder()));
        }
        List<AeroColumnModel.Span> out = new ArrayList<>(segs.size());
        for (SliceVote.Seg<AeroColumnModel.Span> seg : segs) {
            AeroColumnModel.Span sp = seg.value();
            out.add(new AeroColumnModel.Span(seg.bottom(), seg.top() - 1, sp.state(), sp.biomeName(), sp.biomeHolder()));
        }
        return out;
    }
}
