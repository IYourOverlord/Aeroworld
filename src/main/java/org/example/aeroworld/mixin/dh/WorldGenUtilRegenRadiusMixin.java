package org.example.aeroworld.mixin.dh;

import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ограничивает штатную регенерацию листьев (вместо surfaceRegenMaxDistancePercent от дальности LOD).
 * <p>
 * Зачем: когда DH применяет данные грубой секции к детям, он помечает каждый дочерний лист {@code regenerateLeaf = true}
 * (FullDataUpdatePropagatorV2), и регенерация запрашивает эти листья по одному в радиусе в тысячи блоков.
 * Вне R такой лист нашему генератору нужен только для аналитики FEATURES, а их сотни тысяч.
 * <p>
 * Метрика DH: {@code abs(minCornerX - playerX) + abs(minCornerZ - playerZ)} (FullDataSourceV2Repo.getRegenPositionsToUpdateSql),
 * то есть манхэттенская, а R у нас квадрат (Chebyshev). Чтобы углы квадрата не выпали, лимит равен
 * {@code 2 * R * 16 + 128} (по 64 блока запаса на смещение угла секции в каждой оси). За пределами квадрата остаётся
 * ограниченный хвост листьев вдоль осей, это на порядки меньше прежнего потока.
 * generationMaxChunkRadius не трогаем: он режет и грубую генерацию.
 * На dedicated-сервере без игрока DH сам подставляет -1 (без ограничения).
 */
@Mixin(targets = "com.seibel.distanthorizons.core.util.WorldGenUtil", remap = false)
public class WorldGenUtilRegenRadiusMixin {

    @Inject(method = "getMaxRegenDistanceInBlocks()I", at = @At("HEAD"), cancellable = true)
    private static void aeroworld$regenInsideRealRadius(CallbackInfoReturnable<Integer> info) {
        int radius = AeroThroughputLimits.realRadiusBlocks();
        // R = 0 (только аналитика): 0 = второй проход регенерации выключен (SQL "Distance <= 0" ничего не выбирает).
        // Раньше при R = 0 действовал дефолт DH (-1 = бесконечность) и листья перегенерировались по всей дальности LOD.
        info.setReturnValue(radius > 0 ? 2 * radius + 128 : 0);
    }
}