package org.example.aeroworld.worldgen.noise;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight OpenSimplex2-style noise used for terrain generation.
 * Adapted for use without external libraries.
 */
public class AeroNoise {

    private final long seed;
    private final int[] perm;

    // 16 градиентных векторов (12 стандартных + 4 повтора для степени двойки).
    // Позволяет заменить дорогой `% 12` (целочисленное деление) на
    // бесплатную битовую маску `& 15`. Статистически равнозначно стандартному
    // Perlin — именно так сделано во всех ванильных реализациях.
    private static final int[] GRAD3 = {
            1,1,0,  -1,1,0,  1,-1,0,  -1,-1,0,
            1,0,1,  -1,0,1,  1,0,-1,  -1,0,-1,
            0,1,1,  0,-1,1,  0,1,-1,  0,-1,-1,
            1,1,0,  -1,1,0,  0,-1,1,  0,-1,-1   // 4 повтора — закрывают до 16
    };
    // & 15 всегда даёт 0..15, никогда не отрицательное — ветка if убрана.
    private static final int GRAD_COUNT_MASK = 15;

    // Кэш нормировочных коэффициентов fbm: key = (octaves, persistence) → 1.0 / maxVal.
    // maxVal — сумма геометрической прогрессии, константа при фиксированных параметрах.
    // ConcurrentHashMap безопасен при параллельной генерации чанков (C2ME / vanilla).
    // На практике в проекте ~5 уникальных комбинаций, поэтому кэш крошечный.
    private static final ConcurrentHashMap<Long, Double> FBM_INV_MAX =
            new ConcurrentHashMap<>();

    /** Формирует ключ кэша из octaves и persistence без коллизий. */
    private static long fbmKey(int octaves, double persistence) {
        return ((long) octaves << 32) | (Double.doubleToRawLongBits(persistence) & 0xFFFFFFFFL);
    }

    /** Возвращает 1.0 / maxVal, вычисляя и кэшируя при первом обращении. */
    private static double invMaxVal(int octaves, double persistence) {
        return FBM_INV_MAX.computeIfAbsent(fbmKey(octaves, persistence), k -> {
            double max = 0, amp = 1.0;
            for (int i = 0; i < octaves; i++) { max += amp; amp *= persistence; }
            return 1.0 / max;
        });
    }

    public AeroNoise(long seed) {
        this.seed = seed;
        perm = new int[512];
        int[] source = new int[256];
        for (int i = 0; i < 256; i++) source[i] = i;

        long s = seed;
        for (int i = 255; i >= 0; i--) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int r = (int) ((s + 31) % (i + 1));
            if (r < 0) r += (i + 1);
            perm[i] = perm[i + 256] = source[r];
            source[r] = source[i];
        }
    }

    private double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private double grad(int hash, double x, double y, double z) {
        int idx = (hash & GRAD_COUNT_MASK) * 3; // битовая маска — без деления, без ветки
        return GRAD3[idx] * x + GRAD3[idx + 1] * y + GRAD3[idx + 2] * z;
    }

    /**
     * Classic Perlin noise in 2D (z=0 slice).
     */
    public double noise2D(double x, double y) {
        int X = (int) Math.floor(x); x -= X; X &= 255;
        int Y = (int) Math.floor(y); y -= Y; Y &= 255;
        double u = fade(x), v = fade(y);
        int A = perm[X] + Y, B = perm[X + 1] + Y;
        return lerp(
                lerp(grad(perm[A],     x,   y,   0), grad(perm[B],     x-1, y,   0), u),
                lerp(grad(perm[A + 1], x,   y-1, 0), grad(perm[B + 1], x-1, y-1, 0), u),
                v
        );
    }

    /**
     * Classic Perlin noise in 3D.
     */
    public double noise3D(double x, double y, double z) {
        int X = (int) Math.floor(x); x -= X; X &= 255;
        int Y = (int) Math.floor(y); y -= Y; Y &= 255;
        int Z = (int) Math.floor(z); z -= Z; Z &= 255;
        double u = fade(x), v = fade(y), w = fade(z);
        int A  = perm[X]     + Y, AA = perm[A]     + Z, AB = perm[A + 1] + Z;
        int B  = perm[X + 1] + Y, BA = perm[B]     + Z, BB = perm[B + 1] + Z;
        return lerp(
                lerp(lerp(grad(perm[AA],   x,   y,   z  ), grad(perm[BA],   x-1, y,   z  ), u),
                        lerp(grad(perm[AB],   x,   y-1, z  ), grad(perm[BB],   x-1, y-1, z  ), u), v),
                lerp(lerp(grad(perm[AA+1], x,   y,   z-1), grad(perm[BA+1], x-1, y,   z-1), u),
                        lerp(grad(perm[AB+1], x,   y-1, z-1), grad(perm[BB+1], x-1, y-1, z-1), u), v),
                w
        );
    }

    /**
     * Fractional Brownian Motion — layered octaves of noise.
     * @param octaves    number of noise layers
     * @param lacunarity frequency multiplier per octave
     * @param persistence amplitude multiplier per octave
     */
    public double fbm2D(double x, double y, int octaves, double lacunarity, double persistence) {
        double value = 0, amplitude = 1.0, frequency = 1.0;
        for (int i = 0; i < octaves; i++) {
            value += noise2D(x * frequency, y * frequency) * amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return value * invMaxVal(octaves, persistence);
    }

    public double fbm3D(double x, double y, double z, int octaves, double lacunarity, double persistence) {
        double value = 0, amplitude = 1.0, frequency = 1.0;
        for (int i = 0; i < octaves; i++) {
            value += noise3D(x * frequency, y * frequency, z * frequency) * amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return value * invMaxVal(octaves, persistence);
    }

    public long getSeed() { return seed; }
}