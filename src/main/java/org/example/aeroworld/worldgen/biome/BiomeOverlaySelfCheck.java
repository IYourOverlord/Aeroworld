package org.example.aeroworld.worldgen.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Self-check для {@link BiomeOverlay} (Minecraft не нужен). Из корня репозитория:
 * {@code java -m jdk.compiler/com.sun.tools.javac.Main -d /tmp/bo src/main/java/org/example/aeroworld/worldgen/biome/BiomeOverlay.java
 * src/main/java/org/example/aeroworld/worldgen/biome/BiomeOverlaySelfCheck.java
 * && java -ea -cp /tmp/bo org.example.aeroworld.worldgen.biome.BiomeOverlaySelfCheck}
 */
public final class BiomeOverlaySelfCheck {

    public static void main(String[] args) {
        List<String> warns = new ArrayList<>();
        // парсер: пусто, null, регистр, пробелы, дубликаты, мусор
        assert BiomeOverlay.parse(null, warns::add).isEmpty() : "null";
        assert BiomeOverlay.parse("", warns::add).isEmpty() : "пустая строка";
        assert BiomeOverlay.parse(" , ,", warns::add).isEmpty() : "одни запятые";
        assert warns.isEmpty() : "пустые значения не должны предупреждать: " + warns;
        assert BiomeOverlay.parse("WaTeR, plateau ", warns::add).equals(Set.of(BiomeOverlay.WATER, BiomeOverlay.PLATEAU)) : "регистр/пробелы";
        assert BiomeOverlay.parse("water,WATER,water", warns::add).equals(Set.of(BiomeOverlay.WATER)) : "дубликаты";
        assert warns.isEmpty();
        assert BiomeOverlay.parse("water,bogus,rivers", warns::add).equals(Set.of(BiomeOverlay.WATER, BiomeOverlay.RIVERS)) : "мусор игнорируется";
        assert warns.size() == 1 && warns.get(0).contains("bogus") : "предупреждение о мусоре: " + warns;
        try {
            BiomeOverlay.parse("water", warns::add).add(BiomeOverlay.PEAKS);
            throw new AssertionError("набор должен быть неизменяемым");
        } catch (UnsupportedOperationException expected) { /* ok */ }

        // свойство: по умолчанию water, none и пустое значение выключают всё
        assert BiomeOverlay.fromProperty(null, warns::add).equals(Set.of(BiomeOverlay.WATER)) : "по умолчанию water";
        assert BiomeOverlay.fromProperty("none", warns::add).isEmpty() : "none";
        assert BiomeOverlay.fromProperty("NONE", warns::add).isEmpty() : "NONE";
        assert BiomeOverlay.fromProperty("water,none", warns::add).isEmpty() : "none гасит всё";
        assert BiomeOverlay.fromProperty("", warns::add).isEmpty() : "явно пустое значение";
        assert BiomeOverlay.fromProperty("plateau", warns::add).equals(Set.of(BiomeOverlay.PLATEAU)) : "явное значение вместо умолчания";
        assert warns.size() == 1 : "none/default не должны предупреждать: " + warns;

        // select: до/после на чистой функции входов
        BiomeOverlay.Inputs field = new BiomeOverlay.Inputs(0.2, 0.0, 0.0, 0.0, false);
        Random r = new Random(42);
        Set<BiomeOverlay> none = BiomeOverlay.parse("", warns::add);
        for (int i = 0; i < 200_000; i++) {
            BiomeOverlay.Inputs real = new BiomeOverlay.Inputs(r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1,
                    r.nextDouble(), r.nextDouble(), r.nextBoolean());
            Counting c = new Counting(real);
            assert BiomeOverlay.select(none, c, field).equals(field) : "пустой набор обязан давать замороженные входы";
            assert c.calls.isEmpty() : "пустой набор не должен считать рельеф: " + c.calls;
            assert BiomeOverlay.select(null, new Counting(real), field).equals(real) : "null = всё реальное (копия для структур)";
            c = new Counting(real);
            BiomeOverlay.Inputs w = BiomeOverlay.select(Set.of(BiomeOverlay.WATER), c, field);
            assert w.cont() == real.cont() && w.deepLake() == real.deepLake()
                    && w.eros() == 0.0 && w.ridge() == 0.0 && w.plateau() == 0.0 : "WATER: только континентальность и озеро";
            assert c.calls.equals(List.of("cont", "deepLake")) : "WATER не должен считать эрозию/хребет/плато: " + c.calls;
            BiomeOverlay.Inputs p = BiomeOverlay.select(Set.of(BiomeOverlay.PLATEAU, BiomeOverlay.RIVERS), new Counting(real), field);
            assert p.plateau() == real.plateau() && p.cont() == 0.2 && !p.deepLake() : "PLATEAU не трогает воду";
        }
        System.out.println("BiomeOverlaySelfCheck ok");
    }

    /** Real поверх готовых значений, запоминает, что у него спросили. */
    private static final class Counting implements BiomeOverlay.Real {
        final BiomeOverlay.Inputs v;
        final List<String> calls = new ArrayList<>();
        Counting(BiomeOverlay.Inputs v) { this.v = v; }
        public double cont() { calls.add("cont"); return v.cont(); }
        public double eros() { calls.add("eros"); return v.eros(); }
        public double ridge() { calls.add("ridge"); return v.ridge(); }
        public double plateau() { calls.add("plateau"); return v.plateau(); }
        public boolean deepLake() { calls.add("deepLake"); return v.deepLake(); }
    }
}
