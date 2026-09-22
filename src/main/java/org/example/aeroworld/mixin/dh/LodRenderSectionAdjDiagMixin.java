package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Диагностика швов на границах секций LOD: DH рисует полноразмерные боковые грани (в т.ч. воды),
 * если данные соседней секции пусты (ColumnBox.makeAdjVerticalQuad, size == 0). Здесь считается,
 * сколько раз при сборке буфера соседняя секция оказалась отсутствующей или пустой, и логируются
 * первые случаи с позицией и направлением.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection", remap = false)
public class LodRenderSectionAdjDiagMixin {

    private static final Logger AERO_LOGGER = LoggerFactory.getLogger("AeroWorld/AdjDiag");
    private static final AtomicLong AERO_TOTAL = new AtomicLong();
    private static final AtomicLong AERO_NULL = new AtomicLong();
    private static final AtomicLong AERO_EMPTY = new AtomicLong();
    private static final AtomicLongArray AERO_TOTAL_BY_LEVEL = new AtomicLongArray(40);
    private static final AtomicLongArray AERO_EMPTY_BY_LEVEL = new AtomicLongArray(40);
    private static final AtomicLongArray AERO_LOGGED_BY_LEVEL = new AtomicLongArray(40);
    private static final int AERO_MAX_LOGS_PER_LEVEL = 6;
    private static final long AERO_SUMMARY_EVERY = 10000;

    @Inject(method = "getRenderSourceForPos(JLcom/seibel/distanthorizons/core/enums/EDhDirection;)"
            + "Lcom/seibel/distanthorizons/core/dataObjects/render/ColumnRenderSource;",
            at = @At("RETURN"))
    private void aeroworld$diagAdjacent(long pos, EDhDirection direction,
                                        CallbackInfoReturnable<ColumnRenderSource> cir) {
        if (direction == null) {
            return;
        }
        long total = AERO_TOTAL.incrementAndGet();
        int level = Math.min(39, Math.max(0, DhSectionPos.getDetailLevel(pos)));
        AERO_TOTAL_BY_LEVEL.incrementAndGet(level);
        ColumnRenderSource source = cir.getReturnValue();
        boolean isNull = source == null;
        boolean isEmpty = !isNull && source.isEmpty();
        if (isNull) {
            AERO_NULL.incrementAndGet();
        } else if (isEmpty) {
            AERO_EMPTY.incrementAndGet();
        }
        if (isNull || isEmpty) {
            AERO_EMPTY_BY_LEVEL.incrementAndGet(level);
            if (AERO_LOGGED_BY_LEVEL.incrementAndGet(level) <= AERO_MAX_LOGS_PER_LEVEL) {
                AERO_LOGGER.info("[AeroWorld] Adjacent section {} for {} dir={} neighbour={}",
                        isNull ? "MISSING" : "EMPTY", DhSectionPos.toString(pos), direction,
                        DhSectionPos.toString(DhSectionPos.getAdjacentPos(pos, direction)));
            }
        }
        if (total % AERO_SUMMARY_EVERY == 0) {
            StringBuilder perLevel = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                long t = AERO_TOTAL_BY_LEVEL.get(i);
                if (t > 0) {
                    perLevel.append(" L").append(i).append('=').append(AERO_EMPTY_BY_LEVEL.get(i)).append('/').append(t);
                }
            }
            AERO_LOGGER.info("[AeroWorld] Adjacent lookups: {} total, {} null, {} empty | empty/total per detail level:{}",
                    total, AERO_NULL.get(), AERO_EMPTY.get(), perLevel);
            org.example.aeroworld.worldgen.dh.AeroAdjacencyCache.logSummary();
        }
    }
}