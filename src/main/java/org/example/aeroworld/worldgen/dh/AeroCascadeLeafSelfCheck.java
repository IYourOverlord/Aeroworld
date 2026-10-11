package org.example.aeroworld.worldgen.dh;

/**
 * Self-check для {@link AeroCascadeLeaf}: выключено при радиусе 0, только родители детализации 7, расстояние от края секции.
 * Запуск: {@code javac -d /tmp/cl AeroCascadeLeaf.java AeroCascadeLeafSelfCheck.java && java -ea -cp /tmp/cl org.example.aeroworld.worldgen.dh.AeroCascadeLeafSelfCheck}
 */
public final class AeroCascadeLeafSelfCheck {

    public static void main(String[] args) {
        // секция детализации 7: ширина 128, угол (0,0); игрок в (0,0)
        assert !AeroCascadeLeaf.shouldSkip(0, 7, 0, 0, 128, 0, 0) : "радиус 0 = выключено";
        assert !AeroCascadeLeaf.shouldSkip(1024, 7, 0, 0, 128, 0, 0) : "под игроком не пропускать";
        // край секции ровно на 1024 от игрока: не пропускаем; на 1025: пропускаем
        assert !AeroCascadeLeaf.shouldSkip(1024, 7, 1024, 0, 128, 0, 0) : "край на радиусе";
        assert AeroCascadeLeaf.shouldSkip(1024, 7, 1025, 0, 128, 0, 0) : "край за радиусом";
        // по оси Z и слева от игрока
        assert AeroCascadeLeaf.shouldSkip(1024, 7, 0, 1025, 128, 0, 0);
        assert AeroCascadeLeaf.shouldSkip(1024, 7, -1025 - 128, 0, 128, 0, 0);
        assert !AeroCascadeLeaf.shouldSkip(1024, 7, -1024 - 128, 0, 128, 0, 0);
        // другие уровни не трогаем
        assert !AeroCascadeLeaf.shouldSkip(1024, 8, 100_000, 0, 256, 0, 0);
        assert !AeroCascadeLeaf.shouldSkip(1024, 6, 100_000, 0, 64, 0, 0);
        // игрок не в начале координат
        assert !AeroCascadeLeaf.shouldSkip(1024, 7, 5000, 5000, 128, 5000, 5000);
        assert AeroCascadeLeaf.shouldSkip(1024, 7, 5000 + 1025, 5000, 128, 5000, 5000);
        System.out.println("AeroCascadeLeafSelfCheck OK");
    }
}
