package org.example.aeroworld.worldgen.layer;

/**
 * Self-check для {@link AeroCenote}: {@code javac -d /tmp/cn AeroCenote.java AeroCenoteSelfCheck.java
 * && java -ea -cp /tmp/cn org.example.aeroworld.worldgen.layer.AeroCenoteSelfCheck} (MC не нужен).
 * Сверяет профиль колонки с прямой проверкой каждого блока и проверяет, что глина герметична.
 */
public final class AeroCenoteSelfCheck {

    /** 0 - камень, 1 - пустота, 2 - глина: блок считается напрямую по формуле шара. */
    private static int kind(AeroCenote c, int x, int y, int z) {
        int dx = x - c.cx(), dy = y - c.waterY(), dz = z - c.cz();
        int d2 = dx * dx + dz * dz, h = c.cutHeight(), r = c.radius();
        boolean air = (dy <= 0 && d2 + dy * dy < r * r) || (dy > 0 && dy <= h && d2 < r * r) || (dy > h && d2 < c.openingRadius2());
        if (air) return 1;
        return (dy <= 0 ? d2 + dy * dy < r * r + 3 * r + 2 : dy <= h && d2 < r * r + 3 * r + 2) ? 2 : 0;
    }

    private static int kindFromColumn(AeroCenote.Column k, int y) {
        if (k == null) return 0;
        if (y >= k.airLo() && y <= k.airHi()) return 1;
        return (y >= k.clayLo() && y < k.airLo()) || (y > k.airHi() && y <= k.clayHi()) ? 2 : 0;
    }

    public static void main(String[] args) {
        for (int r : new int[]{9, 11, 13}) {
            AeroCenote c = new AeroCenote(100, -40, r, -8);
            int lo = c.waterY() - r - 3, hi = c.waterY() + r + 3;
            for (int x = 100 - r - 3; x <= 100 + r + 3; x++) for (int z = -40 - r - 3; z <= -40 + r + 3; z++) {
                AeroCenote.Column k = c.column(x, z);
                for (int y = lo; y <= hi; y++) {
                    assert kindFromColumn(k, y) == kind(c, x, y, z) : "r=" + r + " " + x + "," + y + "," + z;
                }
            }
            AeroCenote.Column mid = c.column(100, -40);
            assert mid.airHi() == AeroCenote.OPEN : "над центром отверстие";
            assert mid.airLo() == c.waterY() - r + 1 || mid.airLo() == c.waterY() - r : "дно шара: " + mid.airLo();
            assert mid.clayLo() < mid.airLo() : "под дном глина";
            AeroCenote.Column side = c.column(100 + r - 1, -40);
            assert side.airHi() == c.waterY() + c.cutHeight() : "потолок на срезе, выше только поверхность";
            assert c.column(100 + r + 3, -40) == null : "вне оболочки";
            // герметичность: у каждого блока пустоты на уровне воды и ниже все 6 соседей - пустота или глина
            for (int x = 100 - r; x <= 100 + r; x++) for (int z = -40 - r; z <= -40 + r; z++) for (int y = lo; y <= c.waterY(); y++) {
                if (kind(c, x, y, z) != 1) continue;
                int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                for (int[] d : n) assert kind(c, x + d[0], y + d[1], z + d[2]) != 0 : "вода граничит с камнем";
            }
        }
        assert AeroCenote.ABSENT.column(0, 0) == null : "пустая ячейка";
        System.out.println("AeroCenoteSelfCheck ok");
    }
}
