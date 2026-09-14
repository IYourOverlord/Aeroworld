package org.example.aeroworld.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment_neoforge", remap = false)
public abstract class BatchGenerationEnvironmentNeoforgeMixin
{
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
		return 4;
	}
}