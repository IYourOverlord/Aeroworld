package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import org.example.aeroworld.worldgen.dh.AeroAdjReadStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Этап 5, только измерение: цена чтений соседних секций {@code getAdjForDirection} (поток Render Loader).
 * {@code @Redirect} на {@code repo.getAdjByPosAndDirection} меряет сам запрос и «строки нет» (null) и запоминает начало
 * в потоке; {@code @Inject RETURN} берёт общее время метода (запрос + закрытие DTO + декодирование) и пишет в
 * {@link AeroAdjReadStats} (строка {@code AdjRead:} в логе). Поведение DH не меняется. Включается плагином только при
 * {@code -Daeroworld.dhPoolStats=true}. {@code require = 0}: каждая инъекция при первом срабатывании пишет свою строку
 * {@code AdjRead hook ... active}; нет строки значит имена методов не совпали (DH 3.3.3 против 3.3.2).
 */
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2", remap = false)
public class AdjReadStatsMixin {

    private static final Logger AERO_LOGGER = LoggerFactory.getLogger("AeroWorld/AdjRead");
    /** Состояние чтения текущего потока: {valid, startNs, repoNs, noRow}. Рекурсии getAdjForDirection в самом себе нет. */
    private static final ThreadLocal<long[]> AERO_STATE = ThreadLocal.withInitial(() -> new long[4]);

    @Redirect(method = "getAdjForDirection", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/sql/repo/FullDataSourceV2Repo;getAdjByPosAndDirection(JLcom/seibel/distanthorizons/core/enums/EDhDirection;)"
                    + "Lcom/seibel/distanthorizons/core/sql/dto/FullDataSourceV2DTO;"), require = 0)
    private FullDataSourceV2DTO aeroworld$adjRepo(FullDataSourceV2Repo repo, long pos, EDhDirection direction) {
        if (AeroAdjReadStats.firstHit(0)) AERO_LOGGER.info("[AeroWorld DH SeedGen] AdjRead hook repo-redirect active");
        long[] st = AERO_STATE.get();
        long t0 = System.nanoTime();
        FullDataSourceV2DTO dto = repo.getAdjByPosAndDirection(pos, direction);
        long t1 = System.nanoTime();
        st[0] = 1; st[1] = t0; st[2] = t1 - t0; st[3] = dto == null ? 1 : 0;
        return dto;
    }

    @Inject(method = "getAdjForDirection", at = @At("RETURN"), require = 0)
    private void aeroworld$adjReturn(long pos, EDhDirection direction, CallbackInfoReturnable<FullDataSourceV2> cir) {
        if (AeroAdjReadStats.firstHit(1)) AERO_LOGGER.info("[AeroWorld DH SeedGen] AdjRead hook return active");
        long[] st = AERO_STATE.get();
        if (st[0] == 0) return; // метод вышел до запроса (shutdown)
        st[0] = 0;
        AeroAdjReadStats.record(DhSectionPos.getDetailLevel(pos), direction.ordinal() - 2, pos,
                System.nanoTime() - st[1], st[2], st[3] != 0);
    }
}
