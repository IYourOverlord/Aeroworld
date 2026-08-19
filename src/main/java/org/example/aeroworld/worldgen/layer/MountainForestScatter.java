package org.example.aeroworld.worldgen.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Крупные, кучные, разнообразные деревья, покрывающие горы Layer 1
 * ЦЕЛИКОМ — от подножия склона до самой вершины, а не только верхушку.
 *
 * <h3>Почему это отдельный проход, а не ванильная декорация биома</h3>
 * Ванильные горные биомы этого мода (windswept_hills/gravelly_hills/forest,
 * frozen_peaks, jagged_peaks, stony_peaks) по дизайну почти безлесные —
 * их {@code features} либо не содержат деревьев вообще, либо дают лишь
 * единичные редкие экземпляры. Запрошено намеренно кастомное поведение:
 * "покрой их деревьями полностью со всех сторон" — гора должна выглядеть
 * заросшей целиком, а не с "лысыми" склонами и лесом только на макушке.
 *
 * <h3>Как это работает</h3>
 * В отличие от первой версии (единый порог "гора/не гора", из-за которого
 * деревья появлялись только у самого пика), здесь ЛЮБАЯ точка выше
 * {@link #SLOPE_START_HEIGHT_ABOVE_BASE} (заметно ниже вершины — начало
 * склона, а не сам пик) уже получает право на деревья. Верхней границы
 * по высоте НЕТ — деревья ставятся вплоть до самой вершины горы.
 * Внутри этого диапазона плотность деревьев нарастает с высотой
 * (у подножия склона реже — плавный переход к обычной равнине, ближе
 * к вершине гуще), но нижняя граница уже достаточно густая, чтобы гора
 * выглядела заросшей по всей высоте, а не только сверху.
 *
 * <p>Каждое дерево ставится через штатный ванильный
 * {@link PlacedFeature#place}, поэтому форма кроны/ствола остаётся
 * полностью ванильной — меняется только ГДЕ и КАК ЧАСТО они появляются.
 *
 * <p>Вызывать из {@code applyBiomeDecoration}, после
 * {@code super.applyBiomeDecoration(...)} — то есть когда рельеф и
 * стандартная растительность уже на месте, лес горы лишь дополняет
 * картину сверху.
 */
public final class MountainForestScatter {

    // Высота (блоков) над базовым уровнем равнины, начиная с которой
    // колонка УЖЕ получает право на деревья — это НАЧАЛО склона, а не
    // вершина. BASE_SURFACE_Y = 48 → склон "включается" примерно с Y=68,
    // то есть уже заметно выше равнины, но далеко не только у пика.
    private static final int SLOPE_START_HEIGHT_ABOVE_BASE = 20;

    // Высота, на которой плотность деревьев выходит на максимум (дальше
    // не растёт, просто остаётся "очень крупной кучностью" до самой
    // вершины). Между SLOPE_START и этим значением плотность нарастает
    // линейно — подножие склона гуще обычного леса, но не так плотно,
    // как настоящая вершина.
    private static final int SLOPE_FULL_DENSITY_HEIGHT_ABOVE_BASE = 70;

    // Плотность деревьев на чанк 16×16 у САМОГО НАЧАЛА склона (ещё не
    // полная густота) и на пике/выше SLOPE_FULL_DENSITY (максимум).
    // Обе границы уже плотнее обычного ванильного леса (там 0-2 на чанк) —
    // "очень крупная кучность" запрошена явно, поэтому минимум тоже высокий.
    private static final int TREES_PER_CHUNK_AT_SLOPE_START = 6;
    private static final int TREES_PER_CHUNK_AT_FULL_DENSITY = 20;
    // Разброс (+/-) поверх интерполированного значения, чтобы соседние
    // чанки не были заметно "по линейке" одинаковыми.
    private static final int TREES_PER_CHUNK_JITTER = 4;

    // Множитель попыток размещения относительно "видимой" плотности выше.
    // На крутых участках склона многие PlacedFeature.place() вызовы
    // проваливаются (нет ровной опоры под деревом) — без запаса попыток
    // итоговая плотность на крутых стенах горы выходила заметно ниже
    // расчётной. Пробуем в 2 раза больше точек, чем хотим видеть деревьев.
    private static final int PLACEMENT_ATTEMPTS_FACTOR = 2;

    // Разные виды деревьев из разных ванильных биомов — намеренно смешиваем,
    // чтобы на одной горе могли соседствовать дуб, ель, берёза, тёмный
    // дуб, тропический куст и т.п. (запрошено явно: "самые разные деревья
    // из разных биомов").
    private static final ResourceLocation[] TREE_FEATURE_IDS = {
            ResourceLocation.withDefaultNamespace("oak_checked"),
            ResourceLocation.withDefaultNamespace("fancy_oak_checked"),
            ResourceLocation.withDefaultNamespace("birch_checked"),
            ResourceLocation.withDefaultNamespace("birch_tall"),
            ResourceLocation.withDefaultNamespace("spruce_checked"),
            ResourceLocation.withDefaultNamespace("pine_checked"),
            ResourceLocation.withDefaultNamespace("mega_spruce_checked"),
            ResourceLocation.withDefaultNamespace("mega_pine_checked"),
            ResourceLocation.withDefaultNamespace("dark_oak_checked"),
            ResourceLocation.withDefaultNamespace("jungle_bush"),
            ResourceLocation.withDefaultNamespace("jungle_tree"),
            ResourceLocation.withDefaultNamespace("acacia_checked"),
            ResourceLocation.withDefaultNamespace("cherry_checked"),
    };

    private MountainForestScatter() {}

    /**
     * Расставляет деревья по склону горы в этом чанке (если чанк вообще
     * задевает склон). Безопасно вызывать для каждого чанка — быстро
     * отсеивает "равнинные" колонки по высоте рельефа до какой-либо
     * генерации деревьев.
     */
    public static void scatterForChunk(WorldGenRegion region, ChunkGenerator chunkGenerator,
                                        ChunkAccess chunk, Layer1FlatGenerator layer1, long worldSeed) {
        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();

        int slopeStartY = Layer1FlatGenerator.PUBLIC_BASE_SURFACE_Y + SLOPE_START_HEIGHT_ABOVE_BASE;
        int fullDensityY = Layer1FlatGenerator.PUBLIC_BASE_SURFACE_Y + SLOPE_FULL_DENSITY_HEIGHT_ABOVE_BASE;

        // Быстрый отсев: проверяем высоту в центре и 4 углах чанка. Если
        // ни одна точка не дотягивает до начала склона — выходим сразу,
        // не трогая PlacedFeature/holder lookups (дорогие операции).
        int[][] probe = {
                {baseX + 8, baseZ + 8},
                {baseX,     baseZ},
                {baseX + 15, baseZ},
                {baseX,     baseZ + 15},
                {baseX + 15, baseZ + 15},
        };
        int maxProbeHeight = Integer.MIN_VALUE;
        for (int[] p : probe) {
            maxProbeHeight = Math.max(maxProbeHeight, layer1.surfaceHeight(p[0], p[1]));
        }
        if (maxProbeHeight < slopeStartY) return;

        RegistryAccess registryAccess = region.registryAccess();
        var placedFeatureRegistry = registryAccess.registryOrThrow(Registries.PLACED_FEATURE);

        List<Holder<PlacedFeature>> available = new ArrayList<>();
        for (ResourceLocation id : TREE_FEATURE_IDS) {
            ResourceKey<PlacedFeature> key = ResourceKey.create(Registries.PLACED_FEATURE, id);
            placedFeatureRegistry.getHolder(key).ifPresent(available::add);
        }
        if (available.isEmpty()) return; // на случай отсутствия реестра (тестовые/кастомные datapacks)

        long chunkSeed = worldSeed
                ^ ((long) cp.x * 341873128712L + (long) cp.z * 132897987541L)
                ^ 0x7EE5CA7EL; // "TREESCATTER" salt
        RandomSource random = RandomSource.create(chunkSeed);

        // Плотность чанка интерполируется по САМОЙ ВЫСОКОЙ пробной точке —
        // чанк у вершины получает максимальную густоту, чанк у самого
        // начала склона — минимальную (но всё ещё заметно гуще равнины).
        double t = (double) (maxProbeHeight - slopeStartY) / (fullDensityY - slopeStartY);
        t = Math.max(0.0, Math.min(1.0, t));
        int baseCount = TREES_PER_CHUNK_AT_SLOPE_START
                + (int) Math.round(t * (TREES_PER_CHUNK_AT_FULL_DENSITY - TREES_PER_CHUNK_AT_SLOPE_START));
        int count = Math.max(1, baseCount
                + random.nextInt(TREES_PER_CHUNK_JITTER * 2 + 1) - TREES_PER_CHUNK_JITTER);
        int attempts = count * PLACEMENT_ATTEMPTS_FACTOR;

        for (int i = 0; i < attempts; i++) {
            int lx = random.nextInt(16);
            int lz = random.nextInt(16);
            int wx = baseX + lx;
            int wz = baseZ + lz;

            int surfaceY = layer1.surfaceHeight(wx, wz);
            // Нет верхней границы — дерево ставится на любой высоте склона,
            // вплоть до самой вершины. Нижняя граница та же, что и для
            // отсева чанка: конкретная точка внутри чанка могла оказаться
            // ниже склона, даже если чанк в целом его задевает.
            if (surfaceY < slopeStartY) continue;

            Holder<PlacedFeature> chosen = available.get(random.nextInt(available.size()));
            BlockPos pos = new BlockPos(wx, surfaceY + 1, wz);
            chosen.value().place(region, chunkGenerator, random, pos);
            // NB: PlacedFeature API — метод называется place() в 1.21.x
            // маппингах (Mojang), возвращает boolean (успех размещения),
            // здесь результат не важен — при неудаче (например, слишком
            // крутой склон без опоры) просто ничего не ставится, а не
            // прерывает остальной цикл.
        }
    }
}
