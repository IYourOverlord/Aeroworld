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

        IDhApiBiomeWrapper cached = biomeCache.get(finalName);
        if (cached != null) {
            return cached;
        }

        IDhApiBiomeWrapper resolved = null;
        if (wrapperFactory != null) {
            if (span.biomeHolder() != null) {
                try {
                    resolved = wrapperFactory.getBiomeWrapper(new Object[]{span.biomeHolder()}, levelWrapper);
                } catch (Throwable ignored) {}
            }
            if (resolved == null) {
                try {
                    resolved = wrapperFactory.getBiomeWrapper(finalName, levelWrapper);
                } catch (Throwable t) {
                    LOGGER.debug("Failed to wrap biome {}: {}", finalName, t.getMessage());
                }
            }
        }

        if (resolved != null) {
            // Cache only successful resolutions. A miss here usually means
            // AeroBiomeSource.findAeroBiome hadn't resolved a Holder yet and
            // the DH wrapper factory also couldn't resolve the name (registry
            // not warmed up / race with world init on the first SeedGen
            // worker call). Caching the fallback would permanently poison
            // this biome name for the rest of the session, painting every
            // future column with this biome using the wrong (fallback) tint.
            biomeCache.put(finalName, resolved);
            return resolved;
        }
        return fallbackBiomeWrapper;
    }

    /**
     * Конвертирует список span'ов в список DhApiTerrainDataPoint для колонки.
     * <p>
     * Формат DH (см. LodDataBuilder.validateOrThrowApiDataColumn): все точки блочного размера
     * (detailLevel = 0), верхняя граница эксклюзивная, точки идут сверху вниз без пропусков
     * и пересечений, пустоты заполнены воздухом. Span'ы AeroColumnModel имеют инклюзивный topY,
     * поэтому здесь они переводятся в полуоткрытые интервалы [bottomY, topY + 1) и дополняются
     * воздухом на всём диапазоне [minY, maxY].
     */
    public List<DhApiTerrainDataPoint> toDataPoints(List<AeroColumnModel.Span> spans, int minY, int maxY) {
        int top = maxY + 1;
        if (top <= minY) {
            return List.of();
        }
        IDhApiBlockStateWrapper air = getBlockStateWrapper(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        if (air == null) {
            return List.of();
        }
        if (fallbackBiomeWrapper == null) {
            initFallbacks();
        }

        List<DhApiTerrainDataPoint> points = new ArrayList<>(spans.size() * 2 + 1);

        if (spans.isEmpty()) {
            points.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, minY, top, air, getFallbackBiome()));
            return points;
        }

        // Спаны отсортированы по возрастанию bottomY и не пересекаются (mergeSpans).
        // Строим снизу вверх, затем разворачиваем в порядок сверху вниз.
        int cursor = minY;
        IDhApiBiomeWrapper lastBiome = getBiomeWrapper(spans.get(0));
        for (int i = 0; i < spans.size(); i++) {
            AeroColumnModel.Span span = spans.get(i);
            int bottom = Math.max(span.bottomY(), cursor);
            int spanTop = Math.min(span.topY() + 1, top);
            if (spanTop <= bottom) {
                continue;
            }
            IDhApiBiomeWrapper biome = getBiomeWrapper(span);
            if (bottom > cursor) {
                points.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, cursor, bottom, air, lastBiome));
            }
            points.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, bottom, spanTop, getBlockStateWrapper(span.state()), biome));
            cursor = spanTop;
            lastBiome = biome;
        }
        if (cursor < top) {
            points.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, cursor, top, air, lastBiome));
        }

        java.util.Collections.reverse(points);
        return points;
    }

    private IDhApiBiomeWrapper getFallbackBiome() {
        return fallbackBiomeWrapper;
    }
}