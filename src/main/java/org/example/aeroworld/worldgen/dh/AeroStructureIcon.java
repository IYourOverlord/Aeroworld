package org.example.aeroworld.worldgen.dh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Миниатюра структуры для LOD: крошечная воксельная модель из послойных ASCII-схем. Не зависит от Minecraft
 * ({@link AeroStructureIconSelfCheck}); символ -> блок сопоставляет {@link AeroStructureMarkers}.
 *
 * <p>Схема слоя — строки через {@code '/'} (число строк = глубина по Z, длина строки = ширина по X), {@code '.'} — пусто.
 * Суффикс {@code ";n"} повторяет слой n раз. Слои идут снизу вверх от земли.</p>
 */
public final class AeroStructureIcon {

    /** Сплошной отрезок символа по вертикали, в ячейках: [y0, y1) над землёй. */
    public record Seg(int y0, int y1, char c) {}

    public final int w, d;
    private final List<Seg>[][] cols;

    @SuppressWarnings("unchecked")
    public static AeroStructureIcon of(String... layers) {
        List<String[]> flat = new ArrayList<>();
        for (String layer : layers) {
            int cut = layer.lastIndexOf(';');
            int n = cut < 0 ? 1 : Integer.parseInt(layer.substring(cut + 1));
            String[] rows = (cut < 0 ? layer : layer.substring(0, cut)).split("/");
            for (int i = 0; i < n; i++) flat.add(rows);
        }
        return new AeroStructureIcon(flat);
    }

    @SuppressWarnings("unchecked")
    private AeroStructureIcon(List<String[]> layers) {
        this.d = layers.get(0).length;
        this.w = layers.get(0)[0].length();
        this.cols = new List[w][d];
        for (int u = 0; u < w; u++) {
            for (int v = 0; v < d; v++) {
                List<Seg> col = new ArrayList<>();
                for (int y = 0; y < layers.size(); y++) {
                    char c = layers.get(y)[v].charAt(u);
                    if (c == '.') continue;
                    int last = col.size() - 1;
                    if (last >= 0 && col.get(last).y1() == y && col.get(last).c() == c) {
                        col.set(last, new Seg(col.get(last).y0(), y + 1, c));
                    } else {
                        col.add(new Seg(y, y + 1, c));
                    }
                }
                cols[u][v] = col;
            }
        }
    }

    /** Отрезки колонки (u, v) снизу вверх; вне габаритов — пусто. */
    public List<Seg> column(int u, int v) {
        return u < 0 || v < 0 || u >= w || v >= d ? List.of() : cols[u][v];
    }

    // ── Каталог ─────────────────────────────────────────────────────────────

    /** Квадрат n×n из c (слой). */
    private static String sq(int n, char c) { return rect(n, n, c); }

    private static String rect(int w, int d, char c) {
        return String.join("/", Collections.nCopies(d, String.valueOf(c).repeat(w)));
    }

    /** Сетка n×n, в центре квадрат m×m из c. */
    private static String cen(int n, int m, char c) {
        String empty = ".".repeat(n);
        String mid = ".".repeat((n - m) / 2) + String.valueOf(c).repeat(m) + ".".repeat((n - m) / 2);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(i >= (n - m) / 2 && i < (n + m) / 2 ? mid : empty);
        return String.join("/", rows);
    }

    private static final AeroStructureIcon VILLAGE = of(sq(5, 'p') + ";8", sq(5, 'c'), cen(5, 3, 'c'));
    private static final AeroStructureIcon MONUMENT = of(cen(3, 3, 'P') + ";6", cen(3, 1, 'L'));
    private static final AeroStructureIcon OUTPOST = of(cen(5, 3, 'd') + ";13", sq(5, 'd'), cen(5, 3, 'd'));
    private static final AeroStructureIcon MANSION = of(sq(7, 'd') + ";4", sq(7, 'c'), cen(7, 5, 'c'), cen(7, 3, 'c'));
    private static final AeroStructureIcon IGLOO = of(sq(5, 'w'), cen(5, 3, 'w'), cen(5, 1, 'w'));
    private static final AeroStructureIcon DESERT_PYRAMID = of(sq(7, 's'), cen(7, 5, 's'), cen(7, 3, 's'), cen(7, 1, 's'));
    private static final AeroStructureIcon JUNGLE_TEMPLE = of(sq(7, 'm'), cen(7, 5, 'm'), cen(7, 3, 'm'), cen(7, 1, 'm'));
    private static final AeroStructureIcon SWAMP_HUT = of("l.l/.../l.l;2", sq(3, 'p') + ";2", sq(3, 'c'), cen(3, 1, 'c'));
    private static final AeroStructureIcon SHIPWRECK = of(rect(3, 5, 'p') + ";2", ".../.../.l./.../...;4");
    private static final AeroStructureIcon BASTION = of(sq(5, 'k') + ";4", cen(5, 3, 'k'), cen(5, 1, 'g'));
    private static final AeroStructureIcon PORTAL = of("oooo", "o..o;3", "oooo");
    private static final AeroStructureIcon RUIN = of(sq(3, 'b'), cen(3, 1, 'b'));
    private static final AeroStructureIcon TRAIL_RUINS = of(sq(5, 'b'), cen(5, 3, 'b'), cen(5, 1, 'b'));
    /** Неизвестная (модовая) структура: тонкий шпиль цвета, зависящего от id ({@code '*'}). */
    private static final AeroStructureIcon GENERIC = of("*;8");

    private static final Map<String, AeroStructureIcon> BY_PATH = new HashMap<>();
    static {
        BY_PATH.put("ocean_monument", MONUMENT);
        BY_PATH.put("pillager_outpost", OUTPOST);
        BY_PATH.put("woodland_mansion", MANSION);
        BY_PATH.put("igloo", IGLOO);
        BY_PATH.put("desert_pyramid", DESERT_PYRAMID);
        BY_PATH.put("jungle_pyramid", JUNGLE_TEMPLE);
        BY_PATH.put("swamp_hut", SWAMP_HUT);
        BY_PATH.put("shipwreck", SHIPWRECK);
        BY_PATH.put("shipwreck_beached", SHIPWRECK);
        BY_PATH.put("bastion_remnant", BASTION);
        BY_PATH.put("trail_ruins", TRAIL_RUINS);
    }

    /**
     * Рисуется ли структура на LOD. Подземные (шахты, Ancient City, trial chambers, клад, крепость Нижнего
     * мира, stronghold) сверху не видны, а End City уже рисует {@code AeroStructureCover}.
     */
    public static boolean shownOnLod(String path) {
        return !(path.startsWith("mineshaft") || path.equals("ancient_city") || path.equals("trial_chambers")
                || path.equals("buried_treasure") || path.equals("fortress")
                || path.equals("stronghold") || path.equals("end_city"));
    }

    /** Миниатюра по пути id структуры (без namespace); неизвестные — {@link #GENERIC}. */
    public static AeroStructureIcon forPath(String path) {
        AeroStructureIcon icon = BY_PATH.get(path);
        if (icon != null) return icon;
        if (path.startsWith("village")) return VILLAGE;
        if (path.startsWith("ruined_portal")) return PORTAL;
        if (path.startsWith("ocean_ruin")) return RUIN;
        return GENERIC;
    }
}
