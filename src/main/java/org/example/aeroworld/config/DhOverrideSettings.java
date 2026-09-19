package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Настройки интеграции с Distant Horizons SeedGen override.
 */
public record DhOverrideSettings(
        boolean enabled,
        int layer2DetailThreshold,
        int layer3DetailThreshold,
        int layer4DetailThreshold,
        int throughputLogIntervalSec
) {
    public static final DhOverrideSettings DEFAULT = new DhOverrideSettings(
            true,
            4,
            5,
            6,
            30
    );

    public static final Codec<DhOverrideSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("enabled", DEFAULT.enabled).forGetter(DhOverrideSettings::enabled),
            Codec.INT.optionalFieldOf("layer2_detail_threshold", DEFAULT.layer2DetailThreshold).forGetter(DhOverrideSettings::layer2DetailThreshold),
            Codec.INT.optionalFieldOf("layer3_detail_threshold", DEFAULT.layer3DetailThreshold).forGetter(DhOverrideSettings::layer3DetailThreshold),
            Codec.INT.optionalFieldOf("layer4_detail_threshold", DEFAULT.layer4DetailThreshold).forGetter(DhOverrideSettings::layer4DetailThreshold),
            Codec.INT.optionalFieldOf("throughput_log_interval_sec", DEFAULT.throughputLogIntervalSec).forGetter(DhOverrideSettings::throughputLogIntervalSec)
    ).apply(instance, DhOverrideSettings::new));
}
