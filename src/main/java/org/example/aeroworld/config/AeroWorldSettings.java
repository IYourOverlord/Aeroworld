package org.example.aeroworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Корневой объект настроек генератора AeroWorld.
 *
 * <p>Сериализуется как поле {@code "aero_settings"} внутри {@code aero_generator}
 * в JSON измерения. Это значит что настройки <b>сохраняются в мире</b> —
 * два мира с разными пресетами не конфликтуют, настройки не теряются при
 * перезагрузке сервера.
 *
 * <h3>Полный пример JSON в dimension/aeroworld.json</h3>
 * <pre>{@code
 * {
 *   "type": "aeroworld:aeroworld",
 *   "generator": {
 *     "type": "aeroworld:aero_generator",
 *     "biome_source": { "type": "aeroworld:aero_biome_source", "preset": "minecraft:overworld" },
 *     "vanilla_generator": { ... },
 *     "aero_settings": {
 *       "layer2": { "spawn_chance": 0.20, "grid_chunks": 20, ... },
 *       "layer3": { "spawn_chance": 0.10, ... },
 *       "layer4": { "tentacle_count": 10, ... }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h3>Минимальный JSON (всё по умолчанию)</h3>
 * <pre>{@code
 * "aero_settings": {}
 * }</pre>
 *
 * <p>Все поля опциональны — если поле отсутствует, используются значения
 * {@link Layer2Settings#DEFAULT} и т.д.
 */
public record AeroWorldSettings(
        Layer2Settings layer2,
        Layer3Settings layer3,
        Layer4Settings layer4
) {
    /** Полностью дефолтные настройки — используются при создании нового мира через world_preset. */
    public static final AeroWorldSettings DEFAULT = new AeroWorldSettings(
            Layer2Settings.DEFAULT,
            Layer3Settings.DEFAULT,
            Layer4Settings.DEFAULT
    );

    // ── Codec ─────────────────────────────────────────────────────────────────
    public static final Codec<AeroWorldSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Layer2Settings.CODEC.optionalFieldOf("layer2", Layer2Settings.DEFAULT)
                    .forGetter(AeroWorldSettings::layer2),
            Layer3Settings.CODEC.optionalFieldOf("layer3", Layer3Settings.DEFAULT)
                    .forGetter(AeroWorldSettings::layer3),
            Layer4Settings.CODEC.optionalFieldOf("layer4", Layer4Settings.DEFAULT)
                    .forGetter(AeroWorldSettings::layer4)
    ).apply(instance, AeroWorldSettings::new));
}
