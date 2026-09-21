package org.example.aeroworld.worldgen.dh;

import org.example.aeroworld.config.DhOverrideSettings;

/**
 * Профиль упрощения геометрии в зависимости от detailLevel Distant Horizons (п. 3.3 ТЗ).
 * Раздельные пороги для каждого слоя позволяют на дальних LOD пропускать дорогую геометрию
 * и оставлять только силуэты / bounding-box'ы.
 */
public class AeroFastDistantTerrain {

    /**
     * Глобальный runtime-переключатель упрощения геометрии для DH SeedGen,
     * управляемый командой {@code /aeroworld DHopt enable|disable}.
     * <p>
     * {@code volatile}, а не final/per-instance: {@link org.example.aeroworld.worldgen.dh.AeroSeedWorldGenerator}
     * создаётся один раз при регистрации оверрайда ({@code DhApi.worldGenOverrides.registerWorldGeneratorOverride})
     * и живёт до перезапуска сервера — DH API не даёт способа пересоздать или отменить регистрацию оверрайда
     * (см. {@code IDhApiWorldGeneratorOverrideRegister}, там нет {@code unregister}). Поэтому переключение
     * должно происходить внутри уже существующего объекта, без его пересоздания — статический флаг,
     * читаемый на каждый вызов {@code isLayerNCoarse}, это обеспечивает без блокировок и без держания
     * ссылки на активный {@code AeroSeedWorldGenerator} в команде.
     */
    private static volatile boolean ENABLED = true;

    public static void setEnabled(boolean enabled) { ENABLED = enabled; }
    public static boolean isEnabled() { return ENABLED; }

    private final int layer2Threshold;
    private final int layer3Threshold;
    private final int layer4Threshold;

    public AeroFastDistantTerrain(DhOverrideSettings settings) {
        this.layer2Threshold = settings != null ? settings.layer2DetailThreshold() : 4;
        this.layer3Threshold = settings != null ? settings.layer3DetailThreshold() : 5;
        this.layer4Threshold = settings != null ? settings.layer4DetailThreshold() : 6;
    }

    public boolean isLayer2Coarse(byte detailLevel) {
        return ENABLED && detailLevel >= layer2Threshold;
    }

    public boolean isLayer3Coarse(byte detailLevel) {
        return ENABLED && detailLevel >= layer3Threshold;
    }

    public boolean isLayer4Coarse(byte detailLevel) {
        return ENABLED && detailLevel >= layer4Threshold;
    }

    public int getLayer2Threshold() { return layer2Threshold; }
    public int getLayer3Threshold() { return layer3Threshold; }
    public int getLayer4Threshold() { return layer4Threshold; }
}