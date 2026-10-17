package org.example.aeroworld.worldgen.layer;

/**
 * Self-check для {@link AeroCenote}: {@code javac -d /tmp/cn AeroCenote.java AeroCenoteSelfCheck.java
 * && java -ea -cp /tmp/cn org.example.aeroworld.worldgen.layer.AeroCenoteSelfCheck} (MC не нужен).
 */
public final class AeroCenoteSelfCheck {

    public static void main(String[] args) {
        AeroCenote c = new AeroCenote(100, -40, 8, 10);
        int centre = c.floorY(100, -40, 0.0);
        int edge = c.floorY(107, -40, 0.0);
        assert centre == 10 - 2 - AeroCenote.BOWL : "центр: " + centre;
        assert edge > centre && edge < 10 - 1 : "край мельче центра, но под водой: " + edge;
        assert c.floorY(108, -40, 0.0) == AeroCenote.NONE : "вне радиуса";
        assert c.floorY(108, -40, 1.5) != AeroCenote.NONE : "волна расширяет шахту";
        assert AeroCenote.ABSENT.floorY(0, 0, 5.0) == AeroCenote.NONE : "пустая ячейка";
        int inside = 0;
        for (int x = 90; x <= 110; x++) for (int z = -50; z <= -30; z++) {
            int f = c.floorY(x, z, 0.0);
            if (f == AeroCenote.NONE) continue;
            inside++;
            assert f < c.waterY() : "дно ниже воды " + x + "," + z;
        }
        assert inside > 150 && inside < 230 : "площадь круга r=8 ~201: " + inside;
        System.out.println("AeroCenoteSelfCheck ok");
    }
}
