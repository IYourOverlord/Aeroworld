package org.example.aeroworld.worldgen.dh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Снимок позиций игроков по измерениям для потоков DH world-gen (читать {@code ServerLevel.players()}
 * с чужого потока небезопасно). Обновляется раз в секунду на server thread.
 */
public final class AeroPlayerAnchors {

    private static final Map<String, long[]> SNAPSHOT = new ConcurrentHashMap<>();

    private AeroPlayerAnchors() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 0) return;
        var players = level.players();
        long[] packed = new long[players.size()];
        int n = 0;
        for (ServerPlayer p : players) {
            packed[n++] = ((long) p.getBlockX() << 32) | (p.getBlockZ() & 0xFFFFFFFFL);
        }
        SNAPSHOT.put(level.dimension().location().toString(), n == packed.length ? packed : java.util.Arrays.copyOf(packed, n));
    }

    /** Chebyshev-расстояние до ближайшего игрока в {@code dim} или -1, если игроков нет. */
    public static int nearestChebyshev(String dim, int blockX, int blockZ) {
        long[] players = SNAPSHOT.get(dim);
        if (players == null || players.length == 0) return -1;
        int best = Integer.MAX_VALUE;
        for (long p : players) {
            int px = (int) (p >> 32), pz = (int) p;
            best = Math.min(best, Math.max(Math.abs(px - blockX), Math.abs(pz - blockZ)));
        }
        return best;
    }

    /** Есть ли игрок в измерении {@code dim} на расстоянии (Chebyshev) не более {@code radiusBlocks} от точки. */
    public static boolean isWithin(String dim, int blockX, int blockZ, int radiusBlocks) {
        long[] players = SNAPSHOT.get(dim);
        if (players == null) return false;
        for (long p : players) {
            int px = (int) (p >> 32), pz = (int) p;
            if (Math.abs(px - blockX) <= radiusBlocks && Math.abs(pz - blockZ) <= radiusBlocks) return true;
        }
        return false;
    }
}