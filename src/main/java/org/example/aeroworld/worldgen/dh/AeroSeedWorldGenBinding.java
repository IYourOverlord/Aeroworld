package org.example.aeroworld.worldgen.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.example.aeroworld.config.AeroWorldConfig;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Регистрация аналитического генератора AeroWorld в Distant Horizons.
 * Слушает событие {@link DhApiLevelLoadEvent} и регистрирует {@link AeroSeedWorldGenerator}.
 * При несовместимости или отключении настройки выполняет stand-down и отдаёт управление
 * штатному батчевому генератору DH.
 */
public class AeroSeedWorldGenBinding extends DhApiLevelLoadEvent {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroSeedWorldGenBinding.class);

    /**
     * Безопасная точка входа для регистрации слушателя DH.
     * Проверяет наличие классов DH API на classpath; если DH не установлен — тихо пропускает.
     */
    public static void registerIfDhPresent() {
        try {
            Class.forName("com.seibel.distanthorizons.api.DhApi");
            bindDhEvents();
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.info("[AeroWorld] Distant Horizons API not detected on classpath, SeedGen override disabled.");
        } catch (Throwable t) {
            LOGGER.warn("[AeroWorld] Unexpected error during DH API detection:", t);
        }
    }

    private static void bindDhEvents() {
        try {
            DhApi.events.bind(DhApiLevelLoadEvent.class, new AeroSeedWorldGenBinding());
            LOGGER.info("[AeroWorld] Registered AeroSeedWorldGenBinding in Distant Horizons events.");
        } catch (Throwable t) {
            LOGGER.error("[AeroWorld] Failed to bind DhApiLevelLoadEvent listener:", t);
        }
    }

    /**
     * Программно выставляет DH chunk render distance сверх лимита UI-ползунка
     * через {@code DhApi.Delayed.configs.graphics().chunkRenderDistance()}.
     * Client-only настройка: на dedicated-сервере DH не хранит graphics-конфиг,
     * поэтому вызов оборачивается в try/catch и молча пропускается (3.9).
     */
    private static void applyExtendedRenderDistance(IDhApiLevelWrapper levelWrapper) {
        if (!AeroWorldConfig.DH_EXTENDED_RENDER_DISTANCE_ENABLED.get()) {
            return;
        }
        try {
            int targetChunks = AeroWorldConfig.DH_EXTENDED_RENDER_DISTANCE_CHUNKS.get();
            DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(targetChunks);
            LOGGER.info("[AeroWorld] Extended DH render distance to {} chunks for {}.",
                    targetChunks, levelWrapper.getDimensionName());
        } catch (Throwable t) {
            LOGGER.warn("[AeroWorld] Failed to apply extended DH render distance (client-only config, likely dedicated server):", t);
        }
    }

    /**
     * Выставляет DH horizontalQuality (LOWEST/MEDIUM/HIGH/EXTREME) — квадратичную базу дистанции
     * LOD-уровней, включая ближайший (самый детализированный). Выше пресет — дальше от игрока
     * проходит граница ближнего уровня детализации. Client-only, как и render distance (3.9).
     */
    private static void applyHorizontalQuality(IDhApiLevelWrapper levelWrapper) {
        String raw = AeroWorldConfig.DH_HORIZONTAL_QUALITY.get();
        EDhApiHorizontalQuality quality;
        try {
            quality = EDhApiHorizontalQuality.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[AeroWorld] Invalid horizontalQuality '{}' in config, expected one of LOWEST/MEDIUM/HIGH/EXTREME, skipping.", raw);
            return;
        }
        try {
            DhApi.Delayed.configs.graphics().horizontalQuality().setValue(quality);
            LOGGER.info("[AeroWorld] Set DH horizontalQuality to {} for {}.", quality, levelWrapper.getDimensionName());
        } catch (Throwable t) {
            LOGGER.warn("[AeroWorld] Failed to apply DH horizontalQuality (client-only config, likely dedicated server):", t);
        }
    }

    @Override
    public void onLevelLoad(DhApiEventParam<EventParam> eventParam) {
        long startTime = System.currentTimeMillis();
        try {
            if (eventParam == null || eventParam.value == null || eventParam.value.levelWrapper == null) {
                return;
            }

            IDhApiLevelWrapper levelWrapper = eventParam.value.levelWrapper;
            applyExtendedRenderDistance(levelWrapper);
            applyHorizontalQuality(levelWrapper);

            Object mcObj = levelWrapper.getWrappedMcObject();
            if (!(mcObj instanceof ServerLevel serverLevel)) {
                LOGGER.debug("[AeroWorld] Level {} wrapped object is not ServerLevel (was {}), standing down.",
                        levelWrapper.getDimensionName(), mcObj != null ? mcObj.getClass().getName() : "null");
                return;
            }

            ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
            if (!(generator instanceof AeroWorldChunkGenerator aeroGen)) {
                LOGGER.info("[AeroWorld] Level {} chunk generator is not AeroWorldChunkGenerator (was {}), standing down.",
                        levelWrapper.getDimensionName(), generator.getClass().getName());
                return;
            }

            if (AeroWorldConfig.DH_OVERRIDE_ENABLED != null && !AeroWorldConfig.DH_OVERRIDE_ENABLED.get()) {
                LOGGER.info("[AeroWorld] DH SeedGen override is disabled in aeroworld-client.toml, standing down for {}.",
                        levelWrapper.getDimensionName());
                return;
            }

            if (aeroGen.getSettings() != null && aeroGen.getSettings().dhOverride() != null && !aeroGen.getSettings().dhOverride().enabled()) {
                LOGGER.info("[AeroWorld] DH SeedGen override is disabled in world settings, standing down for {}.",
                        levelWrapper.getDimensionName());
                return;
            }

            aeroGen.initializeWithSeed(serverLevel.getSeed());

            AeroSeedWorldGenerator seedGen = new AeroSeedWorldGenerator(aeroGen, levelWrapper);
            DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, seedGen);

            long boundMs = System.currentTimeMillis() - startTime;
            LOGGER.info("AeroWorld SeedGen is now generating LODs for {} (bound in {} ms)",
                    levelWrapper.getDimensionName(), boundMs);

        } catch (Throwable t) {
            LOGGER.error("[AeroWorld] Failed to handle DH level load event:", t);
        }
    }
}