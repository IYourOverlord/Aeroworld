package org.example.aeroworld.worldgen.structure;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Результат валидации размещения структуры.
 *
 * <p>Содержит не только итоговое решение ({@link #accepted}), но и
 * диагностические данные для логирования и возможного смещения структуры.
 */
public final class ValidationResult {

    // ── Итог ─────────────────────────────────────────────────────────────────
    public final boolean accepted;

    // ── Причина отклонения (только при accepted=false) ────────────────────────
    public final RejectionReason rejectionReason;

    // ── Контекст ──────────────────────────────────────────────────────────────
    public final ResourceLocation structureId;
    public final StructureCategory category;
    public final BoundingBox bounds;

    // ── Статистика сэмплирования ──────────────────────────────────────────────
    public final int supportedSamples;
    public final int totalSamples;
    public final double supportRatio;
    public final double requiredRatio;

    // ── Диагностика: несупортированные точки (до 8 штук) ─────────────────────
    public final List<SupportSample> failingSamples;

    // ── Специфика для SKY_FLOATING ────────────────────────────────────────────
    /** Количество колонн с островами в радиусе 96 блоков */
    public final int nearbyIslandColumns;
    /** Количество точек с коллизией с рельефом */
    public final int collisionSamples;

    private ValidationResult(boolean accepted, RejectionReason reason,
                              ResourceLocation structureId, StructureCategory category,
                              BoundingBox bounds,
                              int supportedSamples, int totalSamples,
                              double supportRatio, double requiredRatio,
                              List<SupportSample> failingSamples,
                              int nearbyIslandColumns, int collisionSamples) {
        this.accepted          = accepted;
        this.rejectionReason   = reason;
        this.structureId       = structureId;
        this.category          = category;
        this.bounds            = bounds;
        this.supportedSamples  = supportedSamples;
        this.totalSamples      = totalSamples;
        this.supportRatio      = supportRatio;
        this.requiredRatio     = requiredRatio;
        this.failingSamples    = failingSamples;
        this.nearbyIslandColumns = nearbyIslandColumns;
        this.collisionSamples  = collisionSamples;
    }

    // ── Фабрики ───────────────────────────────────────────────────────────────

    public static ValidationResult accepted(ResourceLocation id, StructureCategory category,
                                             BoundingBox bounds,
                                             int supported, int total, double ratio,
                                             double required) {
        return new ValidationResult(true, RejectionReason.NONE,
                id, category, bounds, supported, total, ratio, required,
                List.of(), 0, 0);
    }

    public static ValidationResult denied(ResourceLocation id, StructureCategory category,
                                           BoundingBox bounds) {
        return new ValidationResult(false, RejectionReason.DENIED_STRUCTURE,
                id, category, bounds, 0, 0, 0, 0, List.of(), 0, 0);
    }

    public static ValidationResult voidGap(ResourceLocation id, StructureCategory category,
                                            BoundingBox bounds) {
        return new ValidationResult(false, RejectionReason.VOID_GAP,
                id, category, bounds, 0, 0, 0, 0, List.of(), 0, 0);
    }

    public static ValidationResult insufficientSupport(ResourceLocation id, StructureCategory category,
                                                         BoundingBox bounds,
                                                         int supported, int total,
                                                         double ratio, double required,
                                                         List<SupportSample> failing) {
        return new ValidationResult(false, RejectionReason.INSUFFICIENT_SUPPORT,
                id, category, bounds, supported, total, ratio, required, failing, 0, 0);
    }

    public static ValidationResult skyFloating(ResourceLocation id, BoundingBox bounds,
                                                boolean accepted,
                                                int nearbyColumns, int collisions,
                                                int supported, int total, double ratio) {
        RejectionReason reason = accepted ? RejectionReason.NONE
                : (nearbyColumns == 0 ? RejectionReason.NO_NEARBY_ISLAND
                                       : RejectionReason.SKY_COLLISION);
        return new ValidationResult(accepted, reason,
                id, StructureCategory.SKY_FLOATING, bounds,
                supported, total, ratio, 0, List.of(), nearbyColumns, collisions);
    }

    public static ValidationResult waterStructure(ResourceLocation id, BoundingBox bounds) {
        return new ValidationResult(false, RejectionReason.WATER_STRUCTURE_NOT_SUPPORTED,
                id, StructureCategory.WATER, bounds, 0, 0, 0, 0, List.of(), 0, 0);
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    public boolean isAccepted() { return accepted; }

    @Override
    public String toString() {
        if (accepted) {
            return String.format("[OK] %s (%s) ratio=%.2f/%d/%d",
                    structureId, category, supportRatio, supportedSamples, totalSamples);
        }
        return String.format("[REJECT:%s] %s (%s) ratio=%.2f/%d/%d",
                rejectionReason, structureId, category, supportRatio, supportedSamples, totalSamples);
    }

    // ── Причины отклонения ────────────────────────────────────────────────────

    public enum RejectionReason {
        NONE,
        DENIED_STRUCTURE,
        VOID_GAP,
        INSUFFICIENT_SUPPORT,
        NO_NEARBY_ISLAND,
        SKY_COLLISION,
        WATER_STRUCTURE_NOT_SUPPORTED
    }
}
