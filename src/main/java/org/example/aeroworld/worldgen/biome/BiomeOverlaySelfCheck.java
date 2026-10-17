package org.example.aeroworld.worldgen.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Self-check для {@link BiomeOverlay} (Minecraft не нужен). Из корня репозитория:
 * {@code java -m jdk.compiler/com.sun.tools.javac.Main -d /tmp/bo src/main/java/org/example/aeroworld/worldgen/biome/BiomeOverlay.java
 * src/main/java/org/example/aeroworld/worldgen/biome/BiomeOverlaySelfCheck.java
 * src/main/java/org/example/aeroworld/worldgen/layer/PlateauTerrace.java src/main/java/org/example/aeroworld/worldgen/noise/AeroNoise.java
 * && java -ea -cp /tmp/bo org.example.aeroworld.worldgen.biome.BiomeOverlaySelfCheck}
 */
public final class BiomeOverlaySelfCheck {

    public static void main(String[] args) {
        List<String> warns = new ArrayList<>();
        // compareSources: тип различается -> MISMATCH, вариант океана -> мягкая пометка, суша-суша -> пусто
        assert BiomeOverlay.compareSources("ocean", "ocean").isEmpty() : "одинаковые";
        assert BiomeOverlay.compareSources("ocean", "warm_ocean").equals("variant differs") : "вариант океана";
        assert BiomeOverlay.compareSources("deep_ocean", "deep_lake").equals("MISMATCH") : "океан/озеро";
        assert BiomeOverlay.compareSources("taiga", "ocean").equals("MISMATCH") : "суша/океан";
        assert BiomeOverlay.compareSources("beach", "taiga").isEmpty() : "суша/суша не помечается";
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
        assert BiomeOverlay.fromProperty(null, warns::add)
                .equals(Set.of(BiomeOverlay.WATER, BiomeOverlay.PLATEAU, BiomeOverlay.RIVERS)) : "по умолчанию water,plateau,rivers";
        assert BiomeOverlay.fromProperty("water", warns::add).equals(Set.of(BiomeOverlay.WATER)) : "явный water отключает плато и реки";
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
            Counting pc = new Counting(real);
            assert BiomeOverlay.select(Set.of(BiomeOverlay.PLATEAU, BiomeOverlay.RIVERS), pc, field).equals(field)
                    && pc.calls.isEmpty() : "PLATEAU и RIVERS входы не трогают: PLATEAU это синоним ashen_plateau в biomeLand";
        }

        // biomeLand: парсер
        warns.clear();
        assert BiomeOverlay.parseLand(null, warns::add).isEmpty() && BiomeOverlay.parseLand("none", warns::add).isEmpty() : "пусто";
        assert BiomeOverlay.parseLand(" AeroWorld:Jagged_Peaks, stony_peaks,bogus", warns::add)
                .equals(Set.of("jagged_peaks", "stony_peaks")) : "префикс, регистр, мусор";
        assert warns.size() == 1 && warns.get(0).contains("bogus") : warns;

        // resolveWithLand на синтетической сетке реальных входов (резолвер повторяет ступени 1..4 resolveLayer1Biome)
        Set<BiomeOverlay> water = Set.of(BiomeOverlay.WATER);
        Set<BiomeOverlay> waterPlateau = Set.of(BiomeOverlay.WATER, BiomeOverlay.PLATEAU);
        java.util.Map<String, Integer> changed = new java.util.TreeMap<>();
        for (double cont : new double[]{-0.3, -0.1, 0.05, 0.3, 0.6})
        for (double eros : new double[]{-0.6, -0.1, 0.2})
        for (double ridge : new double[]{0, 0.5, 0.8})
        for (double plateau : new double[]{0, 1.0})
        for (double temp : new double[]{-0.5, -0.1, 0.2, 0.7})
        for (double humid : new double[]{-0.3, 0.0, 0.3})
        for (boolean lake : new boolean[]{false, true}) {
            BiomeOverlay.Inputs real = new BiomeOverlay.Inputs(cont, eros, ridge, plateau, lake);
            java.util.function.Function<BiomeOverlay.Inputs, String> res = in -> ladder(in, temp, humid);
            String legacy = res.apply(BiomeOverlay.select(water, new Counting(real), field));
            String full = res.apply(real);
            // пустой список: ровно прежний вывод (и реальные входы лишний раз не считаются)
            Counting cn = new Counting(real);
            assert BiomeOverlay.resolveWithLand(water, Set.of(), cn, field, res).equals(legacy) : "пустой список";
            assert cn.calls.equals(List.of("cont", "deepLake")) : "пустой список не считает эрозию/хребет/плато: " + cn.calls;
            // копия для структур: всегда full
            assert BiomeOverlay.resolveWithLand(null, Set.of("jagged_peaks"), new Counting(real), field, res).equals(full) : "копия = full";
            // каждый разрешённый биом: full, если full == он, иначе legacy
            for (String b : BiomeOverlay.LAND_BIOMES) {
                String got = BiomeOverlay.resolveWithLand(water, Set.of(b), new Counting(real), field, res);
                assert got.equals(full.equals(b) ? full : legacy) : b + " " + got + " full=" + full + " legacy=" + legacy;
                if (!legacy.equals(full) && full.equals(b)) changed.merge(b, 1, Integer::sum);
            }
            // PLATEAU = ashen_plateau в списке
            assert BiomeOverlay.resolveWithLand(waterPlateau, Set.of(), new Counting(real), field, res)
                    .equals(BiomeOverlay.resolveWithLand(water, Set.of("ashen_plateau"), new Counting(real), field, res)) : "синоним PLATEAU";
        }
        for (String b : BiomeOverlay.LAND_BIOMES) assert changed.containsKey(b) : "сетка не покрыла " + b;
        // withRiver: выключен = биом как есть и без вызовов; включён = river/frozen_river только на суше вне deep_lake
        Set<BiomeOverlay> rv = Set.of(BiomeOverlay.WATER, BiomeOverlay.RIVERS);
        for (double cont : new double[]{-0.3, 0.0, 0.4}) for (boolean riverCol : new boolean[]{false, true})
        for (String b : new String[]{"taiga", "deep_lake", "ocean"}) for (double temp : new double[]{-0.5, 0.2}) {
            Counting rc = new Counting(new BiomeOverlay.Inputs(cont, 0, 0, 0, false));
            rc.river = riverCol;
            assert BiomeOverlay.withRiver(water, rc, b, temp).equals(b) && rc.calls.isEmpty() : "слой выключен: ничего не меняется и не считается";
            assert BiomeOverlay.withRiver(null, rc, b, temp).equals(b) : "копия для структур без рек";
            String want = (cont >= 0 && riverCol && !b.equals("deep_lake")) ? (temp < -0.3 ? "frozen_river" : "river") : b;
            assert BiomeOverlay.withRiver(rv, rc, b, temp).equals(want) : b + " cont=" + cont + " river=" + riverCol + " temp=" + temp;
        }
        System.out.println("клеток сетки, где биом меняется: " + changed);
        System.out.println("BiomeOverlaySelfCheck ok");
    }

    /** Ступени 0..4 resolveLayer1Biome (AeroBiomeSource тянет Minecraft, поэтому повторено здесь); дальше "land". */
    private static String ladder(BiomeOverlay.Inputs in, double temp, double humid) {
        if (in.deepLake()) return "deep_lake";
        if (in.cont() < -0.05) return "ocean";
        if (in.cont() < 0.0) return "beach";
        if (org.example.aeroworld.worldgen.layer.PlateauTerrace.isPlateauBiome(in.plateau(), in.cont(), in.ridge(), temp)) return "ashen_plateau";
        if (in.ridge() >= 0.70) {
            if (temp < -0.10) return "frozen_peaks";
            if (temp >= 0.20 && humid <= -0.18) return "volcanic_wastes";
            return humid >= 0.05 ? "alpine_meadow" : "karst_highlands";
        }
        if (in.eros() < -0.35) {
            if (temp < -0.3) return "frozen_peaks";
            if (temp < 0.1) return "jagged_peaks";
            if (temp < 0.5) return "stony_peaks";
            return "windswept_hills";
        }
        return "land";
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
        boolean river;
        public boolean river() { calls.add("river"); return river; }
    }
}
