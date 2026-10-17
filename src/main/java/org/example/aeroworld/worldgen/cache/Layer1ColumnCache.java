package org.example.aeroworld.worldgen.cache;

import org.example.aeroworld.worldgen.layer.Layer1TerrainGenerator;

/**
 * Потокобезопасный (ThreadLocal) кэш колонок высот и пещер Layer 1 для одного чанка.
 * Устраняет многократный пересчёт 27 октав шума на колонку при последовательных
 * вызовах fillTerrain -> decorateCaveCeiling -> decorateCaveFloor -> buildSurface.
 */
public final class Layer1ColumnCache {

    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;

    public final short[] surfaceY = new short[256];
    public final short[] caveTop = new short[256];
    public final short[] caveBottom = new short[256];
    /** Верхний воздушный блок lush-полости горы или {@link Layer1TerrainGenerator#LUSH_NONE}. */
    public final short[] lushAirTop = new short[256];
    /** Верхний блок пола lush-полости (имеет смысл только при lushAirTop != LUSH_NONE). */
    public final short[] lushFloor = new short[256];

    public boolean isForChunk(int cx, int cz) {
        return this.chunkX == cx && this.chunkZ == cz;
    }

    public void initForChunk(int cx, int cz, Layer1TerrainGenerator generator) {
        if (isForChunk(cx, cz)) return;

        this.chunkX = cx;
        this.chunkZ = cz;

        int startX = cx << 4;
        int startZ = cz << 4;

        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                int sY = generator.getHeight(wx, wz);
                this.surfaceY[idx] = (short) sY;

                if (sY >= Layer1TerrainGenerator.SEA_LEVEL) {
                    this.caveTop[idx] = (short) generator.computeCaveTop(wx, wz);
                    this.caveBottom[idx] = (short) generator.computeCaveBottom(wx, wz);
                } else {
                    this.caveTop[idx] = Short.MIN_VALUE;
                    this.caveBottom[idx] = Short.MAX_VALUE;
                }
            }
        }

        // Второй проход (нужны высоты всего чанка): полости гор. Минимум по колонке и её четырём соседям; соседи за
        // границей чанка считаются прямо, но только для колонок, прошедших порог горы, и до первого соседа ниже порога.
        final int min = Layer1TerrainGenerator.LUSH_MIN_SURFACE;
        for (int lx = 0; lx < 16; lx++) {
            int wx = startX + lx;
            int xOffset = lx << 4;
            for (int lz = 0; lz < 16; lz++) {
                int wz = startZ + lz;
                int idx = xOffset | lz;

                this.lushAirTop[idx] = (short) Layer1TerrainGenerator.LUSH_NONE;
                this.lushFloor[idx] = (short) Layer1TerrainGenerator.LUSH_NONE;

                int m = this.surfaceY[idx];
                if (m < min) continue;
                m = Math.min(m, lx > 0  ? this.surfaceY[idx - 16] : generator.getHeight(wx - 1, wz));
                if (m < min) continue;
                m = Math.min(m, lx < 15 ? this.surfaceY[idx + 16] : generator.getHeight(wx + 1, wz));
                if (m < min) continue;
                m = Math.min(m, lz > 0  ? this.surfaceY[idx - 1]  : generator.getHeight(wx, wz - 1));
                if (m < min) continue;
                m = Math.min(m, lz < 15 ? this.surfaceY[idx + 1]  : generator.getHeight(wx, wz + 1));

                int airTop = Layer1TerrainGenerator.lushAirTopFromMin(m);
                if (airTop == Layer1TerrainGenerator.LUSH_NONE) continue;
                this.lushAirTop[idx] = (short) airTop;
                this.lushFloor[idx] = (short) generator.lushFloorY(wx, wz);
            }
        }
    }
}
