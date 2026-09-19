package org.example.aeroworld.worldgen.dh;

import org.example.aeroworld.config.DhOverrideSettings;

/**
 * Профиль упрощения геометрии в зависимости от detailLevel Distant Horizons (п. 3.3 ТЗ).
 * Раздельные пороги для каждого слоя позволяют на дальних LOD пропускать дорогую геометрию
 * и оставлять только силуэты / bounding-box'ы.
 */
public class AeroFastDistantTerrain {

    private final int layer2Threshold;
    private final int layer3Threshold;
    private final int layer4Threshold;

    public AeroFastDistantTerrain(DhOverrideSettings settings) {
        this.layer2Threshold = settings != null ? settings.layer2DetailThreshold() : 4;
        this.layer3Threshold = settings != null ? settings.layer3DetailThreshold() : 5;
        this.layer4Threshold = settings != null ? settings.layer4DetailThreshold() : 6;
    }

    public boolean isLayer2Coarse(byte detailLevel) {
        return detailLevel >= layer2Threshold;
    }

    public boolean isLayer3Coarse(byte detailLevel) {
        return detailLevel >= layer3Threshold;
    }

    public boolean isLayer4Coarse(byte detailLevel) {
        return detailLevel >= layer4Threshold;
    }

    public int getLayer2Threshold() { return layer2Threshold; }
    public int getLayer3Threshold() { return layer3Threshold; }
    public int getLayer4Threshold() { return layer4Threshold; }
}
