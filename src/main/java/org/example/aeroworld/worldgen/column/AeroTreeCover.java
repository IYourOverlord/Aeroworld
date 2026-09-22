package org.example.aeroworld.worldgen.column;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;

import javax.annotation.Nullable;

/**
 * Аналитическая модель покрытия деревьями для LOD (Distant Horizons).
 * Генерирует детерминированные спаны стволов и крон без запуска ванильного Feature-пайплайна.
 */
public final class AeroTreeCover {

    public enum CanopyProfile {
        SPHERE,
        CONE,
        FLAT
    }

    public record AeroTreeShape(
            BlockState log,
            BlockState leaves,
            int minTrunkHeight,
            int maxTrunkHeight,
            int canopyRadius,
            int canopyHeight,
            CanopyProfile profile
    ) {}

    public record Rule(
            AeroTreeShape shape,
            double chancePerCell,
            int minSurfaceY,
            int maxSurfaceY
    ) {}

    public record TreeSpans(
            int trunkBottom,
            int trunkTop,
            BlockState log,
            int canopyBottom,
            int canopyTop,
            BlockState leaves
    ) {
        public boolean hasTrunk() { return log != null && trunkTop >= trunkBottom; }
        public boolean hasCanopy() { return leaves != null && canopyTop >= canopyBottom; }
    }

    // ── Пресеты климатических групп деревьев ──────────────────────────────────
    public static final AeroTreeShape SHAPE_OAK = new AeroTreeShape(
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            4, 6, 2, 4, CanopyProfile.SPHERE
    );

    public static final AeroTreeShape SHAPE_BIRCH = new AeroTreeShape(
            Blocks.BIRCH_LOG.defaultBlockState(),
            Blocks.BIRCH_LEAVES.defaultBlockState(),
            5, 7, 2, 4, CanopyProfile.SPHERE
    );

    public static final AeroTreeShape SHAPE_SPRUCE = new AeroTreeShape(
            Blocks.SPRUCE_LOG.defaultBlockState(),
            Blocks.SPRUCE_LEAVES.defaultBlockState(),
            6, 9, 2, 6, CanopyProfile.CONE
    );

    public static final AeroTreeShape SHAPE_DARK_OAK = new AeroTreeShape(
            Blocks.DARK_OAK_LOG.defaultBlockState(),
            Blocks.DARK_OAK_LEAVES.defaultBlockState(),
            5, 7, 3, 4, CanopyProfile.SPHERE
    );

    public static final AeroTreeShape SHAPE_JUNGLE = new AeroTreeShape(
            Blocks.JUNGLE_LOG.defaultBlockState(),
            Blocks.JUNGLE_LEAVES.defaultBlockState(),
            7, 12, 2, 4, CanopyProfile.SPHERE
    );

    public static final AeroTreeShape SHAPE_ACACIA = new AeroTreeShape(
            Blocks.ACACIA_LOG.defaultBlockState(),
            Blocks.ACACIA_LEAVES.defaultBlockState(),
            4, 6, 3, 2, CanopyProfile.FLAT
    );

    public static final AeroTreeShape SHAPE_CHERRY = new AeroTreeShape(
            Blocks.CHERRY_LOG.defaultBlockState(),
            Blocks.CHERRY_LEAVES.defaultBlockState(),
            4, 6, 3, 4, CanopyProfile.SPHERE
    );

    public static final AeroTreeShape SHAPE_MANGROVE = new AeroTreeShape(
            Blocks.MANGROVE_LOG.defaultBlockState(),
            Blocks.MANGROVE_LEAVES.defaultBlockState(),
            5, 8, 2, 4, CanopyProfile.SPHERE
    );

    private AeroTreeCover() {}

    /**
     * Возвращает правило генерации деревьев для указанного имени биома.
     */
    @Nullable
    public static Rule getRuleForBiome(@Nullable String biomeName) {
        if (biomeName == null) return null;
        int colon = biomeName.indexOf(':');
        String path = colon >= 0 ? biomeName.substring(colon + 1) : biomeName;

        if (path.contains("dark_forest")) {
            return new Rule(SHAPE_DARK_OAK, 0.80, 1, 120);
        }
        if (path.contains("birch")) {
            return new Rule(SHAPE_BIRCH, 0.60, 1, 120);
        }
        if (path.contains("taiga") || path.contains("grove")) {
            return new Rule(SHAPE_SPRUCE, 0.55, 1, 130);
        }
        if (path.contains("jungle")) {
            double chance = path.contains("sparse") ? 0.30 : 0.75;
            return new Rule(SHAPE_JUNGLE, chance, 1, 120);
        }
        if (path.contains("savanna")) {
            return new Rule(SHAPE_ACACIA, 0.20, 1, 120);
        }
        if (path.contains("cherry")) {
            return new Rule(SHAPE_CHERRY, 0.55, 1, 130);
        }
        if (path.contains("mangrove")) {
            return new Rule(SHAPE_MANGROVE, 0.50, 1, 60);
        }
        if (path.contains("swamp")) {
            return new Rule(SHAPE_OAK, 0.40, 1, 60);
        }
        if (path.contains("forest")) {
            return new Rule(SHAPE_OAK, 0.60, 1, 120);
        }
        if (path.contains("meadow")) {
            return new Rule(SHAPE_OAK, 0.08, 1, 120);
        }
        if (path.contains("plains") || path.contains("heather")) {
            return new Rule(SHAPE_OAK, 0.05, 1, 120);
        }
        return null;
    }

    /**
     * Быстрый 64-битный детерминированный хеш для ячейки (cx, cz).
     */
    public static long hash(long seed, int cx, int cz) {
        long h = seed ^ (cx * 0x4f9939f508L) ^ (cz * 0x1ef1565bd5L);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }

    /**
     * Семплирует наличие дерева в точке (x, z) для Layer 1.
     * Возвращает TreeSpans со спанами ствола и листвы или null если дерева здесь нет.
     */
    @Nullable
    public static TreeSpans sampleLayer1(
            long seed, int x, int z, int surfaceY,
            @Nullable String biomeName,
            @Nullable Layer1TerrainGenerator terrainGen
    ) {
        Rule rule = getRuleForBiome(biomeName);
        if (rule == null) return null;

        int cellX = x >> 2;
        int cellZ = z >> 2;
        int r = rule.shape().canopyRadius();

        // Проверяем 9 соседних ячеек 4×4
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                int cx = cellX + dcx;
                int cz = cellZ + dcz;
                long h = hash(seed, cx, cz);
                double roll = (h & 0xFFFF) / 65536.0;
                if (roll >= rule.chancePerCell()) continue;

                int treeX = (cx << 2) + (int) ((h >> 16) & 3);
                int treeZ = (cz << 2) + (int) ((h >> 18) & 3);
                int dx = x - treeX;
                int dz = z - treeZ;
                if (Math.abs(dx) > r || Math.abs(dz) > r) continue;

                int treeSurfaceY = (dx == 0 && dz == 0) ? surfaceY
                        : (terrainGen != null ? terrainGen.getHeight(treeX, treeZ) : surfaceY);
                if (treeSurfaceY < rule.minSurfaceY() || treeSurfaceY > rule.maxSurfaceY()) continue;

                int minH = rule.shape().minTrunkHeight();
                int maxH = rule.shape().maxTrunkHeight();
                int trunkHeight = minH + (int) (((h >> 20) & 0x7FFF) % (maxH - minH + 1));

                int cBottom = -1, cTop = -1;
                CanopyProfile profile = rule.shape().profile();

                if (profile == CanopyProfile.CONE) {
                    int distMax = Math.max(Math.abs(dx), Math.abs(dz));
                    if (distMax == 2) {
                        cBottom = treeSurfaceY + trunkHeight - 3;
                        cTop = treeSurfaceY + trunkHeight - 2;
                    } else if (distMax == 1) {
                        cBottom = treeSurfaceY + trunkHeight - 1;
                        cTop = treeSurfaceY + trunkHeight;
                    } else { // distMax == 0
                        cBottom = treeSurfaceY + trunkHeight - 3;
                        cTop = treeSurfaceY + trunkHeight + 1;
                    }
                } else if (profile == CanopyProfile.FLAT) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r) continue;
                    cBottom = treeSurfaceY + trunkHeight;
                    cTop = treeSurfaceY + trunkHeight + 1;
                } else { // SPHERE
                    int distSq = dx * dx + dz * dz;
                    if (distSq > r * r || (Math.abs(dx) == r && Math.abs(dz) == r)) continue;
                    cBottom = treeSurfaceY + trunkHeight - 1;
                    cTop = treeSurfaceY + trunkHeight + 2;
                    if (Math.abs(dx) == r || Math.abs(dz) == r) {
                        cTop = cBottom + 1;
                    }
                }

                int trunkBottom = -1, trunkTop = -1;
                if (dx == 0 && dz == 0) {
                    trunkBottom = treeSurfaceY + 1;
                    trunkTop = cBottom - 1;
                }

                return new TreeSpans(
                        trunkBottom, trunkTop, rule.shape().log(),
                        cBottom, cTop, rule.shape().leaves()
                );
            }
        }
        return null;
    }
}
