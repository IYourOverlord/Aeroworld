package org.example.aeroworld.worldgen.column;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Конвертер span'ов AeroColumnModel в структуры данных Distant Horizons API (DhApiTerrainDataPoint).
 * Кэширует обёртки BlockState и Biome в ConcurrentHashMap для максимального throughput.
 */
public class AeroColumnWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroColumnWriter.class);

    private final IDhApiLevelWrapper levelWrapper;
    private final IDhApiWrapperFactory wrapperFactory;

    private final ConcurrentHashMap<BlockState, IDhApiBlockStateWrapper> blockStateCache = new ConcurrentHashMap<>(16);
    private final ConcurrentHashMap<String, IDhApiBiomeWrapper> biomeCache = new ConcurrentHashMap<>(64);

    private volatile IDhApiBlockStateWrapper fallbackAirWrapper;
    private volatile IDhApiBiomeWrapper fallbackBiomeWrapper;

    public AeroColumnWriter(IDhApiLevelWrapper levelWrapper) {
        this.levelWrapper = levelWrapper;
        IDhApiWrapperFactory factory = null;
        try {
            factory = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
        } catch (Throwable t) {
            LOGGER.debug("Could not resolve IWrapperFactory from SingletonInjector: {}", t.getMessage());
        }
        if (factory == null) {
            try {
                factory = com.seibel.distanthorizons.common.wrappers.WrapperFactory_neoforge.INSTANCE;
            } catch (Throwable t) {
                LOGGER.warn("Could not resolve WrapperFactory_neoforge.INSTANCE: {}", t.getMessage());
            }
        }
        this.wrapperFactory = factory;
        initFallbacks();
    }

    private void initFallbacks() {
        if (wrapperFactory != null) {
            try {
                fallbackAirWrapper = wrapperFactory.getAirBlockStateWrapper();
            } catch (Throwable t) {
                LOGGER.debug("Failed to get air block wrapper: {}", t.getMessage());
            }
            try {
                fallbackBiomeWrapper = wrapperFactory.getBiomeWrapper("aeroworld:plains", levelWrapper);
            } catch (Throwable t) {
                LOGGER.debug("Failed to get fallback plains biome wrapper: {}", t.getMessage());
            }
        }
    }

    public IDhApiBlockStateWrapper getBlockStateWrapper(BlockState state) {
        if (state.isAir() && fallbackAirWrapper != null) {
            return fallbackAirWrapper;
        }
        return blockStateCache.computeIfAbsent(state, s -> {
            if (wrapperFactory != null) {
                try {
                    return wrapperFactory.getBlockStateWrapper(new Object[]{s}, levelWrapper);
                } catch (Throwable t) {
                    LOGGER.debug("Failed to wrap block state {}: {}", s, t.getMessage());
                }
            }
            return fallbackAirWrapper;
        });
    }

    public IDhApiBiomeWrapper getBiomeWrapper(AeroColumnModel.Span span) {
        String biomeName = span.biomeName();
        if (biomeName == null) {
            biomeName = "aeroworld:plains";
        }
        final String finalName = biomeName;
        return biomeCache.computeIfAbsent(finalName, name -> {
            if (wrapperFactory != null) {
                if (span.biomeHolder() != null) {
                    try {
                        return wrapperFactory.getBiomeWrapper(new Object[]{span.biomeHolder()}, levelWrapper);
                    } catch (Throwable ignored) {}
                }
                try {
                    return wrapperFactory.getBiomeWrapper(name, levelWrapper);
                } catch (Throwable t) {
                    LOGGER.debug("Failed to wrap biome {}: {}", name, t.getMessage());
                }
            }
            return fallbackBiomeWrapper;
        });
    }

    /**
     * Конвертирует список span'ов в список DhApiTerrainDataPoint для колонки.
     */
    public List<DhApiTerrainDataPoint> toDataPoints(List<AeroColumnModel.Span> spans, byte detailLevel) {
        if (spans.isEmpty()) {
            return List.of();
        }
        List<DhApiTerrainDataPoint> points = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            AeroColumnModel.Span span = spans.get(i);
            IDhApiBlockStateWrapper bsw = getBlockStateWrapper(span.state());
            IDhApiBiomeWrapper bw = getBiomeWrapper(span);
            points.add(DhApiTerrainDataPoint.create(detailLevel, 0, 15, span.bottomY(), span.topY(), bsw, bw));
        }
        return points;
    }
}
