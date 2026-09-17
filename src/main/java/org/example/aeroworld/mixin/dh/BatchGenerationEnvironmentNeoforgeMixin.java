package org.example.aeroworld.mixin.dh;

import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Iterator;

/**
 * DH ставит MAX_WORLD_GEN_CHUNK_BORDER_NEEDED = 0, из-за чего
 * DhLitWorldGenRegion генерируется без запаса вокруг батча чанков
 * (refSize = widthInChunks - 1, без border). Многослойные структуры
 * (trial chambers и т.п.), чьи piece'ы лежат за пределами опорных
 * чанков, не попадают в регион генерации -> их лут-сундуки и спавнеры
 * не создаются на нижних слоях структуры.
 *
 * ВАЖНО: инжект делается не в статический инициализатор класса (там
 * гигантский код с ImmutableMap.Builder-цепочкой; Mixin при пересчёте
 * фреймов/LocalVariableTable для такого метода ломает байткод класса
 * -> java.lang.ClassFormatError: Illegal local variable table length
 * при загрузке класса, что и приводило к крашу). Вместо этого
 * подменяется само ЧТЕНИЕ поля (GETSTATIC) в generateEvent() —
 * маленьком обычном методе; статический инициализатор DH при этом
 * вообще не трогается.
 *
 * Таргет задан строкой (remap = false), а не .class-литералом, чтобы
 * не тянуть полный джар Distant Horizons в compile classpath — модуль
 * компилируется и без DH в classpath, применяется миксин только если
 * DH реально загружен (см. DhWorldGenBorderMixinPlugin).
 *
 * ВТОРАЯ ЧАСТЬ ФИКСА (после декомпиляции 3.2.0-b): одного увеличения
 * MAX_WORLD_GEN_CHUNK_BORDER_NEEDED было недостаточно и вызывало
 * NullPointerException в generateEvent() (BatchGenerationEnvironment_
 * neoforge.java:440, внутри лямбды fallbackChunkGetterFunc). Причина:
 * generateEvent() строит регион с geometry-радиусом
 * refSize = widthInChunks - 1 + borderSize * 2 (см. regionChunks /
 * DhLitWorldGenRegion_neoforge), но карту чанков chunkWrappersByDhPos,
 * из которой fallbackChunkGetterFunc берёт чанки, заполняют два
 * ChunkPosGenStream_neoforge.getIterator(...) вызова с extraRadius,
 * НЕ учитывающим borderSize (0 и 8 — фиксированные значения ванильного
 * кода DH). На периферии увеличенного региона (в кольце шириной
 * borderSize) чанков в карте нет -> chunkWrappersByDhPos.get(...)
 * возвращает null -> Objects.requireNonNull(...getChunk()) кидает NPE
 * в потоке DH-World Gen Thread. Поток эту ошибку просто логирует и
 * продолжает со следующим батчем (отсюда "generating chunks" быстро
 * тикает), но результат батча не сохраняется в LOD -> на клиенте
 * ничего не рендерится, хотя счётчик прогресса растёт.
 *
 * Фикс: оба вызова getIterator в generateEvent редиректятся так,
 * чтобы extraRadius был увеличен на тот же borderSize, каким
 * фактически построен regionChunks — тогда периферийные чанки
 * читаются/создаются наравне с чанками самого батча, и
 * fallbackChunkGetterFunc больше не встречает отсутствующих ключей.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment_neoforge", remap = false)
public abstract class BatchGenerationEnvironmentNeoforgeMixin
{
	/**
	 * Должно совпадать со значением, которое возвращает
	 * {@link #aeroworld$overrideWorldGenBorder()} ниже — оба редиректа
	 * обязаны использовать один и тот же border, иначе geometry региона
	 * (refSize) и заполнение карты чанков снова разойдутся.
	 */
	private static final int AEROWORLD_WORLD_GEN_BORDER = 4;

	@Redirect(
			method = "generateEvent",
			at = @At(
					value = "FIELD",
					target = "Lcom/seibel/distanthorizons/common/wrappers/worldGeneration/BatchGenerationEnvironment_neoforge;MAX_WORLD_GEN_CHUNK_BORDER_NEEDED:I",
					opcode = org.objectweb.asm.Opcodes.GETSTATIC
			),
			remap = false
	)
	private static int aeroworld$overrideWorldGenBorder()
	{
		// Минимально достаточное значение, чтобы piece'ы структур на
		// соседних чанках попадали в DhLitWorldGenRegion.
		return AEROWORLD_WORLD_GEN_BORDER;
	}

	/**
	 * Первый вызов getIterator в generateEvent (extraRadius = 0) —
	 * читает/создаёт чанки самого батча ("existingChunkPosIterator").
	 * Расширяем диапазон на border, чтобы периферийные позиции региона
	 * тоже попали в chunkWrappersByDhPos.
	 */
	@Redirect(
			method = "generateEvent",
			at = @At(
					value = "INVOKE",
					target = "Lcom/seibel/distanthorizons/common/wrappers/worldGeneration/ChunkPosGenStream_neoforge;getIterator(IIII)Ljava/util/Iterator;",
					ordinal = 0
			),
			remap = false
	)
	private static Iterator<ChunkPos> aeroworld$expandExistingChunkIteratorBorder(
			int genMinX, int genMinZ, int width, int extraRadius)
	{
		return com.seibel.distanthorizons.common.wrappers.worldGeneration.ChunkPosGenStream_neoforge
				.getIterator(genMinX, genMinZ, width, extraRadius + AEROWORLD_WORLD_GEN_BORDER);
	}

	/**
	 * Второй вызов getIterator в generateEvent (extraRadius = 8) —
	 * "пустые" чанки-заглушки ("emptyChunkPosIterator"). Тот же сдвиг
	 * по той же причине.
	 */
	@Redirect(
			method = "generateEvent",
			at = @At(
					value = "INVOKE",
					target = "Lcom/seibel/distanthorizons/common/wrappers/worldGeneration/ChunkPosGenStream_neoforge;getIterator(IIII)Ljava/util/Iterator;",
					ordinal = 1
			),
			remap = false
	)
	private static Iterator<ChunkPos> aeroworld$expandEmptyChunkIteratorBorder(
			int genMinX, int genMinZ, int width, int extraRadius)
	{
		return com.seibel.distanthorizons.common.wrappers.worldGeneration.ChunkPosGenStream_neoforge
				.getIterator(genMinX, genMinZ, width, extraRadius + AEROWORLD_WORLD_GEN_BORDER);
	}
}