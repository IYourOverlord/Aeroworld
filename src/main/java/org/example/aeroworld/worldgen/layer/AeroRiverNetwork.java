package org.example.aeroworld.worldgen.layer;

import org.example.aeroworld.worldgen.noise.AeroNoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleBinaryOperator;

/**
 * Речная сеть, впадающая в океан и озёра. Чистая функция от seed и координат (без шва между чанками и без
 * зависимости от порядка генерации), реки на уровне моря: сеть только вырезает русла и чаши озёр, воду
 * до {@code SEA_LEVEL} доливает обычная заливка Layer 1.
 *
 * <p>Устройство. Мир поделён на ячейки {@link #CELL} блоков, в каждой один узел со случайным сдвигом.
 * Узел стекает в соседа (из 8) с наименьшей континентальностью: она строго убывает, поэтому циклов нет и
 * каждый путь заканчивается либо в открытой воде (узел с {@code cont < }{@link #WET}), либо в замкнутой впадине,
 * где возникает озеро. Рёбра узел→сосед и есть реки. Показывается ребро, у начала которого есть притоки
 * (или часть «листьев» для густоты); цель показанного ребра сама принимает приток, значит тоже показана и
 * её путь не обрывается на суше. Ширина растёт от истока к устью: по числу притоков и по близости к берегу
 * (эстуарий). Часть узлов с рекой получает проточное озеро. Координаты точки предварительно искажаются шумом,
 * чтобы русла извивались; узлы и озёра живут в том же искажённом пространстве, поэтому реки с ними не расходятся.</p>
 *
 * <p>ponytail: кэш узлов и ячеек сбрасывается целиком при переполнении ({@link #CACHE_LIMIT}); апгрейд — LRU.
 * Соседа выбирает только континентальность, без учёта рельефа гор: реки в горах гасит вызывающий код.</p>
 */
public final class AeroRiverNetwork {

    static final int CELL = 640;
    /** Узел с континентальностью ниже — открытая вода: река здесь кончается. */
    static final double WET = -0.12;
    private static final int CACHE_LIMIT = 16384;
    private static final int[][] NEIGHBOURS = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};

    /** Узел сетки: положение, континентальность и хэш ячейки. */
    record Node(double x, double z, double cont, long h) {}

    /** Данные ячейки: исток-ребро к (ti, tj), число притоков, радиус озера (0 = нет) и показано ли ребро. */
    record Cell(Node node, boolean hasTarget, int ti, int tj, int imp, double lakeR, boolean shown) {}

    /** Вырезание: сила реки и озера, 0..1. */
    public record Sample(double river, double lake) {}

    private final long seed;
    private final DoubleBinaryOperator continentality;
    private final AeroNoise noise;
    /** Масштаб искажения координат (в тестах 0 — прямые рёбра). */
    private final double warp;
    private final ConcurrentHashMap<Long, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Cell> cells = new ConcurrentHashMap<>();

    public AeroRiverNetwork(long seed, DoubleBinaryOperator continentality) {
        this(seed, continentality, 1.0);
    }

    AeroRiverNetwork(long seed, DoubleBinaryOperator continentality, double warp) {
        this.seed = seed;
        this.continentality = continentality;
        this.noise = new AeroNoise(seed ^ 0xC3815F29L);
        this.warp = warp;
    }

    // ── Структура сети ──────────────────────────────────────────────────────

    private static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xFFFFFFFFL);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 20-битная доля [0, 1) из хэша со сдвигом. */
    private static double unit(long h, int shift) {
        return ((h >>> shift) & 0xFFFFF) / 1048576.0;
    }

    Node node(int i, int j) {
        long k = key(i, j);
        Node n = nodes.get(k);
        if (n == null) {
            if (nodes.size() > CACHE_LIMIT) nodes.clear();
            long h = mix(seed ^ mix(k));
            double x = (i + 0.2 + 0.6 * unit(h, 0)) * CELL, z = (j + 0.2 + 0.6 * unit(h, 20)) * CELL;
            n = new Node(x, z, continentality.applyAsDouble(x, z), h);
            nodes.put(k, n);
        }
        return n;
    }

    /** Ячейка, в которую стекает (i, j): сосед с наименьшей континентальностью ниже своей; {@code null} — сток или вода. */
    private int[] target(int i, int j) {
        Node n = node(i, j);
        if (n.cont() < WET) return null;
        int[] best = null;
        double bestCont = n.cont();
        for (int[] d : NEIGHBOURS) {
            double c = node(i + d[0], j + d[1]).cont();
            if (c < bestCont) {
                bestCont = c;
                best = new int[]{i + d[0], j + d[1]};
            }
        }
        return best;
    }

    Cell cell(int i, int j) {
        long k = key(i, j);
        Cell c = cells.get(k);
        if (c == null) {
            if (cells.size() > CACHE_LIMIT) cells.clear();
            Node n = node(i, j);
            int[] t = target(i, j);
            int imp = 1;
            for (int[] d : NEIGHBOURS) {
                int[] tt = target(i + d[0], j + d[1]);
                if (tt != null && tt[0] == i && tt[1] == j) imp++;
            }
            long h2 = mix(n.h() ^ 0x5DEECE66DL);
            boolean sink = t == null && n.cont() >= WET;
            double lakeR = sink ? 130 + 140 * unit(h2, 20) : t != null && unit(h2, 0) < 0.10 ? 100 + 90 * unit(h2, 20) : 0;
            boolean shown = t != null && (imp >= 2 || unit(h2, 40) < 0.35);
            c = new Cell(n, t != null, t == null ? 0 : t[0], t == null ? 0 : t[1], imp, lakeR, shown);
            cells.put(k, c);
        }
        return c;
    }

    // ── Выборка ─────────────────────────────────────────────────────────────

    private static double smoothstep(double e0, double e1, double v) {
        double t = Math.max(0.0, Math.min(1.0, (v - e0) / (e1 - e0)));
        return t * t * (3.0 - 2.0 * t);
    }

    /** Полуширина русла у истока: растёт с числом притоков. */
    private static double baseHalfWidth(int imp) {
        return 3.0 + 1.6 * Math.min(imp - 1, 4);
    }

    /**
     * Сила вырезания реки и озера в колонке.
     * @param cont континентальность колонки: чем ближе к берегу, тем шире русло (эстуарий)
     */
    public Sample sample(int wx, int wz, double cont) {
        double px = wx + warp * (noise.fbm2D(wx * 0.0018 + 91.0, wz * 0.0018 - 37.0, 3, 2.0, 0.5) * 110.0
                + noise.noise2D(wx * 0.006 + 13.0, wz * 0.006 + 7.0) * 24.0);
        double pz = wz + warp * (noise.fbm2D(wx * 0.0018 - 53.0, wz * 0.0018 + 71.0, 3, 2.0, 0.5) * 110.0
                + noise.noise2D(wx * 0.006 - 29.0, wz * 0.006 + 41.0) * 24.0);
        int ci = (int) Math.floor(px / CELL), cj = (int) Math.floor(pz / CELL);
        double estuary = 1.0 + 2.2 * smoothstep(0.30, -0.02, cont);
        double shore = 1.0 + 0.18 * noise.noise2D(wx * 0.01 + 101.0, wz * 0.01 - 77.0);

        double river = 0, lake = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                Cell a = cell(ci + di, cj + dj);
                if (a.lakeR() > 0) {
                    double r = a.lakeR() * shore;
                    double d = Math.hypot(px - a.node().x(), pz - a.node().z());
                    lake = Math.max(lake, 1.0 - smoothstep(0.55 * r, r, d));
                }
                if (a.shown()) {
                    Cell b = cell(a.ti(), a.tj());
                    double abx = b.node().x() - a.node().x(), abz = b.node().z() - a.node().z();
                    double s = Math.max(0.0, Math.min(1.0,
                            ((px - a.node().x()) * abx + (pz - a.node().z()) * abz) / (abx * abx + abz * abz)));
                    double d = Math.hypot(px - (a.node().x() + s * abx), pz - (a.node().z() + s * abz));
                    double hw = (baseHalfWidth(a.imp()) * (1 - s) + baseHalfWidth(b.imp()) * s) * estuary;
                    river = Math.max(river, 1.0 - smoothstep(0.45 * hw, hw, d));
                }
            }
        }
        return new Sample(river, lake);
    }
}
