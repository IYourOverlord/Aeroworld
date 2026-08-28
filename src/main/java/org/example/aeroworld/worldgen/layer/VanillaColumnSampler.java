package org.example.aeroworld.worldgen.layer;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.lang.reflect.Field;

/**
 * Прямой запрос BlockState в произвольной колонке (wx, wz) через настоящий
 * ванильный {@link NoiseChunk}/density-function router — замена
 * {@code NoiseBasedChunkGenerator.getBaseColumn} для {@code columnProfile()}
 * в {@link Layer1FlatGenerator}.
 *
 * <p>{@code getBaseColumn}/{@code iterateNoiseColumn} у {@link
 * NoiseBasedChunkGenerator} строят ПОЛНЫЙ столбец блоков сверху донизу без
 * возможности раннего выхода, и сам метод {@code iterateNoiseColumn}
 * {@code protected} — недоступен извне пакета. Здесь мы напрямую
 * воспроизводим ЕГО ЖЕ логику (см. декомпилированный
 * {@code NoiseBasedChunkGenerator.iterateNoiseColumn}, 1.21.1) через
 * публичный конструктор {@link NoiseChunk} и top-down скан с ранним выходом
 * на первом твёрдом блоке — этого раньше не хватало для дешёвого запроса
 * "где дно" на каждую колонку {@code Layer1FlatGenerator.columnProfile}.
 *
 * <p>Собственный fluid picker ниже — копия {@code createFluidPicker}
 * (приватный метод {@code NoiseBasedChunkGenerator}, недоступен снаружи),
 * зависит только от публичных {@code NoiseGeneratorSettings.seaLevel()}/
 * {@code defaultFluid()}, поэтому безопасно воспроизводится один в один.
 */
final class VanillaColumnSampler {

    private VanillaColumnSampler() {}

    /**
     * {@code DensityFunctions.BeardifierMarker} — {@code protected} nested
     * класс (не путать с {@code BeardifierOrMarker}, который публичный) —
     * недоступен по имени снаружи пакета {@code net.minecraft.world.level.levelgen}.
     * Достаём его синглтон {@code INSTANCE} рефлексией один раз и приводим к
     * публичному интерфейсу {@code BeardifierOrMarker} — само имя protected-
     * класса в коде нигде не фигурирует, компилятору проверять нечего.
     */
    private static final DensityFunctions.BeardifierOrMarker NO_BEARDS = resolveBeardifierMarker();

    private static DensityFunctions.BeardifierOrMarker resolveBeardifierMarker() {
        try {
            Class<?> markerClass = Class.forName(
                    "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker");
            Field instanceField = markerClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            return (DensityFunctions.BeardifierOrMarker) instanceField.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Не удалось получить DensityFunctions.BeardifierMarker.INSTANCE рефлексией — "
                            + "возможно, имя/структура класса изменились в другой версии Minecraft.", e);
        }
    }

    /** Итог top-down скана одной колонки. */
    static final class Result {
        final int groundY;
        final int waterY;

        Result(int groundY, int waterY) {
            this.groundY = groundY;
            this.waterY  = waterY;
        }
    }

    /**
     * {@code NoiseChunk.getInterpolatedState()} объявлен как {@code
     * protected} — недоступен извне пакета {@code
     * net.minecraft.world.level.levelgen}. {@code NoiseChunk} — публичный
     * не-final класс, поэтому доступ открываем через подкласс-переопределение
     * (protected-члены доступны наследникам независимо от пакета).
     */
    private static final class ExposedNoiseChunk extends NoiseChunk {
        ExposedNoiseChunk(int cellCountXZ, RandomState random, int firstNoiseX, int firstNoiseZ,
                          NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
                          NoiseGeneratorSettings noiseGeneratorSettings, Aquifer.FluidPicker fluidPicker,
                          Blender blender) {
            super(cellCountXZ, random, firstNoiseX, firstNoiseZ, noiseSettings, beardifier,
                    noiseGeneratorSettings, fluidPicker, blender);
        }

        @Override
        public BlockState getInterpolatedState() {
            return super.getInterpolatedState();
        }
    }

    /**
     * Копия {@code NoiseBasedChunkGenerator.createFluidPicker} — приватный
     * метод, недоступен снаружи. Лава ниже -54, вода/иная жидкость слоя на
     * уровне {@code settings.seaLevel()} выше него, как в ваниле.
     *
     * <p>ВНИМАНИЕ: имя абстрактного метода {@code Aquifer.FluidPicker}
     * ({@code computeFluid(int x, int y, int z)}) взято по устойчивому
     * соглашению именования ванильного кода 1.19–1.21 (см. лямбду в
     * decompiled {@code createFluidPicker}: {@code (x, y, z) -> ...}) — если
     * в декомпилированном {@code Aquifer.java} у тебя в IntelliJ имя другое,
     * поменяй его только здесь.
     */
    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
    }

    /**
     * Top-down скан колонки (wx, wz) от верхней границы {@code
     * heightAccessor} до первого твёрдого (не воздух, не жидкость) блока —
     * с ранним выходом, без построения полного массива блоков колонки (в
     * отличие от getBaseColumn/iterateNoiseColumn). Логика интерполяции
     * NoiseChunk (cellWidth/cellHeight, updateForX/Y/Z, selectCellYZ)
     * воспроизводит {@code NoiseBasedChunkGenerator.iterateNoiseColumn} 1:1.
     *
     * @param groundFallback значение groundY, если колонка целиком воздух
     *                       (не должно происходить в реальном overworld-
     *                       рельефе, но как safety fallback).
     */
    static Result sample(NoiseBasedChunkGenerator generator, RandomState random,
                         LevelHeightAccessor heightAccessor, int wx, int wz, int groundFallback) {

        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        NoiseSettings noiseSettings = settings.noiseSettings().clampToHeightAccessor(heightAccessor);

        int cellWidth  = noiseSettings.getCellWidth();
        int cellHeight = noiseSettings.getCellHeight();
        int minY       = noiseSettings.minY();
        int cellCountY = Math.floorDiv(noiseSettings.height(), cellHeight);
        int minCellY   = Math.floorDiv(minY, cellHeight);

        int cellOriginX = Math.floorDiv(wx, cellWidth) * cellWidth;
        int cellOriginZ = Math.floorDiv(wz, cellWidth) * cellWidth;
        double dx = (double) Math.floorMod(wx, cellWidth) / (double) cellWidth;
        double dz = (double) Math.floorMod(wz, cellWidth) / (double) cellWidth;

        Aquifer.FluidPicker fluidPicker = createFluidPicker(settings);

        ExposedNoiseChunk noiseChunk = new ExposedNoiseChunk(
                1, random, cellOriginX, cellOriginZ, noiseSettings,
                NO_BEARDS, settings, fluidPicker, Blender.empty());

        noiseChunk.initializeForFirstCellX();
        noiseChunk.advanceCellX(0);

        int waterY  = -1;
        int groundY = groundFallback;

        cellLoop:
        for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
            noiseChunk.selectCellYZ(cellY, 0);
            for (int subY = cellHeight - 1; subY >= 0; subY--) {
                int y = (minCellY + cellY) * cellHeight + subY;
                double dy = (double) subY / (double) cellHeight;
                noiseChunk.updateForY(y, dy);
                noiseChunk.updateForX(wx, dx);
                noiseChunk.updateForZ(wz, dz);

                BlockState state = noiseChunk.getInterpolatedState();
                if (state == null) state = settings.defaultBlock();
                if (state.isAir()) continue;

                if (!state.getFluidState().isEmpty()) {
                    if (waterY == -1) waterY = y;
                    continue;
                }
                groundY = y;
                break cellLoop;
            }
        }

        noiseChunk.stopInterpolation();
        return new Result(groundY, waterY);
    }
}