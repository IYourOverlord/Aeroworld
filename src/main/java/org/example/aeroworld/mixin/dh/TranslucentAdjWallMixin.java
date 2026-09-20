package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Убирает "стенки" воды на границах секций LOD.
 * <p>
 * Если данные соседней секции ещё пусты (ColumnRenderView.size == 0), DH считает соседа пустотой
 * и рисует полноразмерную боковую грань столбца от поверхности до дна. Для непрозрачной геометрии
 * это скрытая или малозаметная грань, а для полупрозрачной воды она просвечивает как тёмная
 * полоса. Здесь такие грани полупрозрачных боксов отбрасываются; когда соседняя секция
 * догружается, DH сам перестраивает буфер.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnBox", remap = false)
public class TranslucentAdjWallMixin {

    @Inject(method = "makeAdjVerticalQuad(Lcom/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder;"
            + "Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;"
            + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;"
            + "Lcom/seibel/distanthorizons/core/dataObjects/render/columnViews/ColumnRenderView;"
            + "[SZILcom/seibel/distanthorizons/core/enums/EDhDirection;SSSSSIBB)V",
            at = @At("HEAD"), cancellable = true)
    private static void aeroworld$skipTranslucentWallToEmptyNeighbour(
            LodQuadBuilder builder, PhantomArrayListCheckout checkout, IClientLevelWrapper levelWrapper,
            ColumnRenderView adjView, short[] textureIds, boolean flag, int index, EDhDirection direction,
            short x, short y, short z, short width, short height, int color, byte skyLight, byte blockLight,
            CallbackInfo ci) {
        if (adjView != null && adjView.size == 0 && ColorUtil.getAlpha(color) < 255) {
            ci.cancel();
        }
    }
}
