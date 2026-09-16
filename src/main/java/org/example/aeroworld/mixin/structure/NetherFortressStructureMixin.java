package org.example.aeroworld.mixin.structure;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code minecraft:bastion_remnant} — обычный {@code minecraft:jigsaw} с полем
 * {@code start_height}, поэтому его высота переносится в главную пещеру Layer 1
 * чисто датапаком (см. {@code data/minecraft/worldgen/structure/bastion_remnant.json}).
 *
 * {@code minecraft:fortress} — нет: {@link NetherFortressStructure} — legacy
 * захардкоженная структура без height-конфига. {@code findGenerationPoint}
 * фиксирует стартовую точку на Y=64, а {@code generatePieces} в конце ВСЕГДА
 * пересчитывает финальную высоту через
 * {@code StructurePiecesBuilder.moveInsideHeights(random, 48, 70)}
 * — то есть даже если бы стартовый Y=64 удалось поменять датапаком (нельзя),
 * это всё равно перезаписывается этим вызовом. В AeroWorld крепость нужно
 * зафиксировать строго в диапазоне Y -55..-50. Единственная точка, где
 * высоту можно перехватить — сам этот вызов.
 *
 * <p>Затрагивает ТОЛЬКО измерение AeroWorld: guard по
 * {@code instanceof AeroWorldChunkGenerator}. Ванильный Нижний мир и любые
 * другие измерения используют {@code NoiseBasedChunkGenerator} напрямую —
 * под условие не попадают, вызывают оригинальный
 * {@code moveInsideHeights} без изменений.
 */
@Mixin(value = NetherFortressStructure.class, remap = false)
public abstract class NetherFortressStructureMixin {

    @Redirect(
            method = "generatePieces",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePiecesBuilder;moveInsideHeights(Lnet/minecraft/util/RandomSource;II)V",
                    remap = false
            ),
            remap = false
    )
    private static void aeroworld$placeFortressInMainCave(StructurePiecesBuilder collector,
                                                          RandomSource random, int minY, int maxY) {
        // context недоступен как captured-параметр в этой точке инжекции
        // (генератор чанков определяется через сам конструируемый мир, а не
        // через явный параметр метода-владельца) — гейт по измерению делаем
        // через ThreadLocal-флаг, который выставляет AeroWorldChunkGenerator
        // перед вызовом findGenerationPoint/generatePieces для этой структуры.
        if (!AeroWorldChunkGenerator.IS_GENERATING.get()) {
            collector.moveInsideHeights(random, minY, maxY);
            return;
        }

        // Смещаем уже собранные piece'ы так, чтобы структура целиком попала
        // в фиксированный диапазон высот Y -55..-50 внутри AeroWorld.
        int caveBottom = -55;
        int caveTop    = -50;

        BoundingBox current = collector.getBoundingBox();
        int span = current.getYSpan();
        int range = Math.max(1, (caveTop - caveBottom + 1) - span);
        int targetMinY = caveBottom + (range > 1 ? random.nextInt(range) : 0);
        int offset = targetMinY - current.minY();

        collector.offsetPiecesVertically(offset);
    }
}