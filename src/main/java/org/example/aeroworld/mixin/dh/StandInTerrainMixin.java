package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.example.aeroworld.worldgen.dh.AeroAdjacencyCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Заполнение "дыр" (StandInTerrain) в LOD данными родительских секций во время генерации.
 * При отсутствии детальных данных секции поднимается по иерархии родителей и растягивает
 * данные вниз через downsample, устраняя пустоты в 3D-рендере.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection", remap = false)
public class StandInTerrainMixin {

    @Shadow private IClientLevelWrapper clientLevelWrapper;
    @Shadow private FullDataSourceProviderV2 fullDataSourceProvider;

    @Inject(method = "getRenderSourceForPos(JLcom/seibel/distanthorizons/core/enums/EDhDirection;)"
            + "Lcom/seibel/distanthorizons/core/dataObjects/render/ColumnRenderSource;",
            at = @At("RETURN"), cancellable = true)
    private void aeroworld$standInTerrain(long pos, EDhDirection direction,
                                          CallbackInfoReturnable<ColumnRenderSource> cir) {
        ColumnRenderSource source = cir.getReturnValue();
        if (source != null && !source.isEmpty()) {
            return;
        }

        if (this.fullDataSourceProvider == null) {
            return;
        }

        long targetPos = (direction != null) ? DhSectionPos.getAdjacentPos(pos, direction) : pos;
        byte detailLevel = DhSectionPos.getDetailLevel(targetPos);

        // Не пытаемся искать родителей выше корня
        if (detailLevel >= FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL) {
            return;
        }

        long currentParentPos = targetPos;
        AeroAdjacencyCache.Entry validParentEntry = null;
        int steps = 0;

        while (DhSectionPos.getDetailLevel(currentParentPos) < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL) {
            currentParentPos = DhSectionPos.getParentPos(currentParentPos);
            steps++;

            final long parentPosToLoad = currentParentPos;
            AeroAdjacencyCache.Entry candidateEntry = AeroAdjacencyCache.get(
                    parentPosToLoad,
                    () -> this.fullDataSourceProvider.get(parentPosToLoad)
            );
            if (candidateEntry != null) {
                FullDataSourceV2 candidate = candidateEntry.get();
                if (candidate != null && !candidate.isEmpty) {
                    validParentEntry = candidateEntry;
                    break;
                } else {
                    candidateEntry.release();
                }
            }
        }

        if (validParentEntry == null) {
            return;
        }

        FullDataSourceV2 validParent = validParentEntry.get();
        try {
            // Собираем стек позиций от targetPos до currentParentPos
            long[] intermediatePositions = new long[steps];
            long walk = targetPos;
            for (int i = 0; i < steps; i++) {
                intermediatePositions[i] = walk;
                walk = DhSectionPos.getParentPos(walk);
            }

            // Каскадно даунсемплим от найденного родителя вниз до targetPos
            FullDataSourceV2 current = validParent;
            for (int i = steps - 1; i >= 0; i--) {
                FullDataSourceV2 child = FullDataSourceV2.createEmpty(intermediatePositions[i]);
                child.updateFromDataSource(current);
                if (current != validParent) {
                    current.close();
                }
                current = child;
            }

            ColumnRenderSource standIn = FullDataToRenderDataTransformer.transformFullDataToRenderSource(
                    current, this.clientLevelWrapper);

            if (current != validParent) {
                current.close();
            }

            if (standIn != null && !standIn.isEmpty()) {
                if (source != null) {
                    source.close();
                }
                cir.setReturnValue(standIn);
            } else if (standIn != null) {
                standIn.close();
            }
        } catch (Throwable ignored) {
            // В случае любой ошибки оставляем исходный результат
        } finally {
            validParentEntry.release();
        }
    }
}