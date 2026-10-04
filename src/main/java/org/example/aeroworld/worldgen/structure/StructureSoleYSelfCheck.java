package org.example.aeroworld.worldgen.structure;

/**
 * Self-check для {@link StructureSupportValidator#medianOf}: глубокие выбросы (колодец у деревни) не смещают подошву.
 * Запуск: main в IDE с {@code -ea} (класс валидатора тянет MC, отдельным javac его не собрать). Логика медианы проверена
 * отдельно копией на чистом JDK.
 */
public final class StructureSoleYSelfCheck {

    public static void main(String[] args) {
        // деревня: земля около 0, колодец и две глубокие части на -13..-9
        assert StructureSupportValidator.medianOf(new int[] {0, 1, 0, -13, 1, 0, -9, 1, 0}) == 0;
        // порядок входа не важен, вход не меняется
        int[] in = {5, 1, 3};
        assert StructureSupportValidator.medianOf(in) == 3 && in[0] == 5;
        // одна часть
        assert StructureSupportValidator.medianOf(new int[] {7}) == 7;
        System.out.println("StructureSoleYSelfCheck: all checks passed");
    }
}
