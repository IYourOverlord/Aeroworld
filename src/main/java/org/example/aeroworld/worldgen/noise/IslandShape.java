package org.example.aeroworld.worldgen.noise;

/**
 * IslandShape — SDF helpers for sky islands with varied profiles.
 *
 * FIX: Added per-island profile variation so islands look different from each other.
 * Each island gets a unique "profile curve" (linear / convex / concave / stepped)
 * determined by a hash of its center coordinates.
 * Edge noise is also scaled per-island for more variety.
 *
 * PERF: XZ-deformation (fbm2D × 2, bulge × 2, noiseIntensity) depends only on (wx, wz, cx, cz).
 * Use precomputeXZ() once per column, then call isSolid(distSq, ...) in the Y-loop.
 * Math.sqrt eliminated — comparisons use distSq vs coneRadius².
 */
public class IslandShape {

    private final AeroNoise edgeNoise;
    private final AeroNoise surfaceNoise;
    private final AeroNoise profileNoise;

    /** Precomputed result for one XZ-column + island center. */
    public static final class XZCache {
        public final double distSq;
        public final int    profile;
        public final double maxRadius; // stored for convenience

        XZCache(double distSq, int profile, double maxRadius) {
            this.distSq    = distSq;
            this.profile   = profile;
            this.maxRadius = maxRadius;
        }
    }

    public IslandShape(long seed) {
        this.edgeNoise    = new AeroNoise(seed ^ 0xABCDEF1234567890L);
        this.surfaceNoise = new AeroNoise(seed ^ 0x0987654321FEDCBAL);
        this.profileNoise = new AeroNoise(seed ^ 0xDEADBEEFCAFEBABEL);
    }

    /**
     * Fast path: precomputes the XZ deformation for one column using already-cached
     * {@code noiseIntensity} and {@code profile} from {@link org.example.aeroworld.worldgen.cache.IslandData}.
     *
     * <p>Eliminates one {@code profileNoise.noise2D} call (noiseIntensity) and one
     * hash computation (profile) that previously repeated for every XZ-column
     * (256×/chанк per island). Both values are constant for a given island center.
     *
     * @param noiseIntensity pre-computed via {@link #computeNoiseIntensity(int, int)},
     *                       stored in {@code IslandData.shapeNoiseIntensity}
     * @param profile        pre-computed via {@link #computeProfile(int, int)},
     *                       stored in {@code IslandData.shapeProfile}
     */
    public XZCache precomputeXZ(int wx, int wz, int cx, int cz,
                                 double maxRadius, double noiseScale,
                                 double noiseIntensity, int profile) {
        double effectiveNoise = noiseScale * noiseIntensity;

        double nx = edgeNoise.fbm2D(wx * 0.05,       wz * 0.05,       3, 2.0, 0.5) * effectiveNoise;
        double nz = edgeNoise.fbm2D(wx * 0.05 + 100, wz * 0.05 + 100, 3, 2.0, 0.5) * effectiveNoise;

        double bulgeX = profileNoise.noise2D(wx * 0.012 + cx * 0.003, wz * 0.012) * (effectiveNoise * 0.5);
        double bulgeZ = profileNoise.noise2D(wx * 0.012, wz * 0.012 + cz * 0.003) * (effectiveNoise * 0.5);

        double dx = (wx - cx) + nx + bulgeX;
        double dz = (wz - cz) + nz + bulgeZ;

        return new XZCache(dx * dx + dz * dz, profile, maxRadius);
    }

    /**
     * Convenience overload — computes noiseIntensity and profile on the fly.
     * Used by the slow-path methods ({@code isSurface}, {@code isSubsurface},
     * {@code getSurfaceY}) that are called outside hot loops and don't have
     * an {@code IslandData} at hand.
     */
    public XZCache precomputeXZ(int wx, int wz, int cx, int cz,
                                 double maxRadius, double noiseScale) {
        return precomputeXZ(wx, wz, cx, cz, maxRadius, noiseScale,
                computeNoiseIntensity(cx, cz), computeProfile(cx, cz));
    }

    /**
     * Fast per-Y test. Uses precomputed distSq; no noise calls, no sqrt.
     */
    public boolean isSolid(int wy, int bottomY, int topY, XZCache xz) {
        if (wy < bottomY || wy > topY) return false;

        double t = (double)(wy - bottomY) / (double)(topY - bottomY + 1);
        double coneRadius = xz.maxRadius * applyProfile(t, xz.profile);
        return xz.distSq <= coneRadius * coneRadius;
    }

    /**
     * Convenience overload — computes XZ on the fly.
     * Used by isSurface / isSubsurface / getSurfaceY (called outside hot loops).
     */
    public boolean isSolid(int wx, int wy, int wz,
                            int cx, int cz,
                            int bottomY, int topY,
                            double maxRadius, double noiseScale) {
        XZCache xz = precomputeXZ(wx, wz, cx, cz, maxRadius, noiseScale);
        return isSolid(wy, bottomY, topY, xz);
    }

    /** Maps normalised height t ∈ [0,1] to radius multiplier using the island's profile. */
    private double applyProfile(double t, int profile) {
        switch (profile) {
            case 0: return t;
            case 1: return Math.sin(t * Math.PI * 0.5) * 1.05;
            case 2: return t * t * t;
            case 3:
                if (t < 0.33) return t * 0.4;
                else if (t < 0.66) return 0.13 + (t - 0.33) * 0.7;
                else return 0.36 + (t - 0.66) * 1.9;
            default: return t;
        }
    }

    /**
     * Returns the per-island noise intensity multiplier.
     * Depends only on (cx, cz) — call once in computeIslandData, store in IslandData.
     */
    public double computeNoiseIntensity(int cx, int cz) {
        return 0.6 + ((profileNoise.noise2D(cx * 0.017, cz * 0.017) + 1.0) * 0.5) * 0.8;
    }

    /**
     * Returns 0–3 profile index for the island at (cx, cz).
     * Static — no instance state. Call once in computeIslandData, store in IslandData.
     */
    public static int computeProfile(int cx, int cz) {
        long h = (long) cx * 341873128712L ^ (long) cz * 132897987541L;
        h = h ^ (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        return (int) Math.abs(h % 4);
    }

    public boolean isSurface(int wx, int wy, int wz,
                              int cx, int cz,
                              int bottomY, int topY,
                              double maxRadius, double noiseScale) {
        if (!isSolid(wx, wy, wz, cx, cz, bottomY, topY, maxRadius, noiseScale)) return false;
        return !isSolid(wx, wy + 1, wz, cx, cz, bottomY, topY, maxRadius, noiseScale);
    }

    public boolean isSubsurface(int wx, int wy, int wz,
                                 int cx, int cz,
                                 int bottomY, int topY,
                                 double maxRadius, double noiseScale) {
        if (!isSolid(wx, wy, wz, cx, cz, bottomY, topY, maxRadius, noiseScale)) return false;
        for (int dy = 1; dy <= 3; dy++) {
            if (!isSolid(wx, wy + dy, wz, cx, cz, bottomY, topY, maxRadius, noiseScale)) return true;
        }
        return false;
    }

    public int getSurfaceY(int wx, int wz,
                           int cx, int cz,
                           int bottomY, int topY,
                           double maxRadius, double noiseScale) {
        for (int wy = topY; wy >= bottomY; wy--) {
            if (isSurface(wx, wy, wz, cx, cz, bottomY, topY, maxRadius, noiseScale)) return wy;
        }
        return -1;
    }
}
