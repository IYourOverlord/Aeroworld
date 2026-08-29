package org.example.aeroworld.worldgen.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Пишет блоки напрямую в {@link LevelChunkSection}, минуя
 * {@link ChunkAccess#setBlockState} и его побочные эффекты (обновление
 * {@code Heightmap}, подсчёт блоков в секции).
 *
 * <h3>Зачем</h3>
 * {@code ProtoChunk.setBlockState} обновляет heightmap — это разделяемое
 * состояние, небезопасное для параллельной записи из нескольких потоков.
 * Однако во время {@code fillFromNoise} heightmap физически не нужен:
 * Minecraft пересчитывает heightmap позже при переходе {@code ProtoChunk}
 * → {@code LevelChunk} (через {@code ChunkStatus.FULL}).
 *
 * <h3>Гарантия безопасности</h3>
 * Каждая {@link LevelChunkSection} — независимый объект с собственным
 * {@link net.minecraft.world.level.chunk.PalettedContainer}. Если два
 * потока пишут в РАЗНЫЕ секции (непересекающиеся Y-диапазоны), гонки нет.
 * Это инвариант, который обеспечивается вызывающим кодом: Layer 2
 * (Y 300–500), Layer 3 (Y 1000–1100), Layer 4 (Y 1900–2031) никогда
 * не перекрываются.
 *
 * <h3>Ограничения</h3>
 * <ul>
 *   <li>НЕ обновляет heightmap — они будут пересчитаны ванильным движком.</li>
 *   <li>НЕ инкрементирует {@code nonEmptyBlockCount} в секции — для
 *       генерации это не критично (count используется для skip-рендеринга
 *       пустых секций; ванильный {@code fillFromNoise} пересчитывает
 *       counts при финализации чанка).</li>
 *   <li>Используйте ТОЛЬКО в {@code fillFromNoise}; для
 *       {@code applyBiomeDecoration} / {@code applyCarvers} используйте
 *       обычный {@code chunk.setBlockState}.</li>
 * </ul>
 */
public final class SectionDirectChunkWriter implements ChunkWriter {

    private final LevelChunkSection[] sections;
    private final int minSectionY; // e.g. -4 for minBuildHeight=-64

    /**
     * @param chunk чанк, в который будем писать
     */
    public SectionDirectChunkWriter(ChunkAccess chunk) {
        this.sections    = chunk.getSections();
        this.minSectionY = chunk.getMinSection();
    }

    /**
     * Записывает блок напрямую в секцию, минуя heightmap.
     *
     * @param wx    мировая X-координата блока
     * @param wy    мировая Y-координата блока
     * @param wz    мировая Z-координата блока
     * @param state состояние блока
     */
    @Override
    public void setBlockState(int wx, int wy, int wz, BlockState state) {
        int sectionIndex = (wy >> 4) - minSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return;

        LevelChunkSection section = sections[sectionIndex];
        section.setBlockState(
                wx & 15,
                wy & 15,
                wz & 15,
                state
        );
    }

    /**
     * Возвращает текущее состояние блока из секции (для проверок типа
     * {@code getBlockState(pos).isAir()} в мостах/деревьях).
     */
    @Override
    public BlockState getBlockState(int wx, int wy, int wz) {
        int sectionIndex = (wy >> 4) - minSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        LevelChunkSection section = sections[sectionIndex];
        return section.getBlockState(wx & 15, wy & 15, wz & 15);
    }
}
