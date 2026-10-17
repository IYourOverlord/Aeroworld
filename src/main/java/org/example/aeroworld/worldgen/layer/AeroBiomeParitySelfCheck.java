package org.example.aeroworld.worldgen.layer;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-check паритета «биом карты реальных чанков»: новые статические методы {@code AeroBiomeSource}
 * (temperature / humidity / resolveFieldBiome) против ЭТАЛОНА старых инлайн-формул. Minecraft не нужен:
 * проверка вырезает настоящие статические методы из исходника {@code AeroBiomeSource.java} (по скобкам),
 * компилирует вместе с реальным {@code AeroNoise.java} и вызывает по рефлексии.
 * Запуск из корня репозитория: {@code java -ea AeroBiomeParitySelfCheck.java} (нужен JDK, а не JRE).
 * ponytail: вырезание по скобкам ломается, если в телах методов появятся скобки в строках/комментариях;
 * апгрейд - вынести чистую логику в отдельный файл без зависимостей от MC.
 */
public final class AeroBiomeParitySelfCheck {

    private static final String ROOT = "src/main/java/org/example/aeroworld/worldgen/";

    public static void main(String[] args) throws Exception {
        String src = Files.readString(Path.of(ROOT + "biome/AeroBiomeSource.java"));
        StringBuilder gen = new StringBuilder("import org.example.aeroworld.worldgen.noise.AeroNoise;\nimport org.example.aeroworld.worldgen.layer.PlateauTerrace;\npublic final class Extracted {\n");
        for (String c : new String[]{"DEEP_LAKE", "TEMP_SALT", "HUMID_SALT", "DEFAULT_SEED", "FIELD_CONT"}) {
            Matcher m = Pattern.compile("public static final [^;]*\\b" + c + "\\b[^;]*;").matcher(src);
            assert m.find() : "нет константы " + c;
            gen.append(m.group()).append('\n');
        }
        for (String sig : new String[]{"static double temperature(", "static double humidity(", "static String resolveFieldBiome(",
                "static String resolveLayer1Biome(double cont, double eros, double ridge, double plateau, double temp,\n                                            double humid, boolean deepLake)",
                "static String resolveIslandBiome("}) {
            int a = src.indexOf(sig);
            assert a >= 0 : "нет метода " + sig;
            int open = src.indexOf('{', a), depth = 0, i = open;
            for (; i < src.length(); i++) {
                char ch = src.charAt(i);
                if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) break;
            }
            gen.append("public ").append(src, a, i + 1).append('\n');
        }
        gen.append("}\n");

        Path tmp = Files.createTempDirectory("aerobiome");
        Path genFile = tmp.resolve("Extracted.java");
        Files.writeString(genFile, gen);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", tmp.toString(),
                ROOT + "noise/AeroNoise.java", ROOT + "layer/PlateauTerrace.java", genFile.toString());
        if (rc != 0) throw new AssertionError("не скомпилировалось");
        try (URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL()})) {
            Class<?> ex = cl.loadClass("Extracted");
            Class<?> noiseCls = cl.loadClass("org.example.aeroworld.worldgen.noise.AeroNoise");
            long seed = (Long) ex.getField("DEFAULT_SEED").get(null);
            long tSalt = (Long) ex.getField("TEMP_SALT").get(null), hSalt = (Long) ex.getField("HUMID_SALT").get(null);
            Object tn = noiseCls.getConstructor(long.class).newInstance(seed ^ tSalt);
            Object hn = noiseCls.getConstructor(long.class).newInstance(seed ^ hSalt);
            var fbm = noiseCls.getMethod("fbm2D", double.class, double.class, int.class, double.class, double.class);
            var temperature = ex.getMethod("temperature", noiseCls, double.class, double.class);
            var humidity = ex.getMethod("humidity", noiseCls, double.class, double.class);
            var field = ex.getMethod("resolveFieldBiome", double.class, double.class);
            var layer1 = ex.getMethod("resolveLayer1Biome", double.class, double.class, double.class, double.class,
                    double.class, double.class, boolean.class);

            int n = 0, land = 0;
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (int x = -100_000; x <= 100_000; x += 256) {
                for (int z = -100_000; z <= 100_000; z += 256) {
                    double wx = x, wz = z;
                    // ЭТАЛОН: старые инлайн-формулы (getNoiseBiome / isJungle до рефакторинга)
                    double refT = (Double) fbm.invoke(tn, wx * 0.0008, wz * 0.0008, 3, 2.0, 0.5);
                    double refH = (Double) fbm.invoke(hn, wx * 0.0010, wz * 0.0010, 3, 2.0, 0.5);
                    String refName = (String) layer1.invoke(null, 0.2, 0.0, 0.0, 0.0, refT, refH, false);
                    double t = (Double) temperature.invoke(null, tn, wx, wz);
                    double h = (Double) humidity.invoke(null, hn, wx, wz);
                    assert Double.compare(refT, t) == 0 : "temp " + x + "," + z;
                    assert Double.compare(refH, h) == 0 : "humid " + x + "," + z;
                    String name = (String) field.invoke(null, t, h);
                    assert refName.equals(name) : "биом " + x + "," + z + ": " + refName + " != " + name;
                    names.add(name);
                    n++;
                    if (!name.contains("ocean") && !name.equals("deep_lake")) land++;
                }
            }
            assert n >= 100_000 : "мало точек: " + n;
            assert land == n : "в карте реальных чанков не должно быть океанов/deep_lake";
            // квантизация: (b >> 2) << 2 == quart * 4 (то, что считает getNoiseBiome), в т.ч. для отрицательных b
            for (int b = -5000; b <= 5000; b++) assert ((b >> 2) << 2) == (b >> 2) * 4 : "quant " + b;
            // ===== слой WATER: подстановка реальной cont (и deepLake) при замороженных eros/ridge/plateau =====
            // 1) синтетическая сетка cont -0.6..+0.9 x сетка (temp, humid) на чистой resolveLayer1Biome
            java.util.List<Double> conts = new java.util.ArrayList<>(java.util.List.of(-0.05, -0.20, 0.0, -0.0, 0.2));
            for (double c : new double[]{-0.05, -0.20, 0.0}) { conts.add(Math.nextUp(c)); conts.add(Math.nextDown(c)); }
            for (int i = 0; i <= 150; i++) conts.add(-0.6 + i * 0.01);
            long pairs = 0;
            for (int ti = 0; ti <= 100; ti++) {
                for (int hi = 0; hi <= 100; hi++) {
                    double t = -1 + ti * 0.02, h = -1 + hi * 0.02;
                    String before = (String) layer1.invoke(null, 0.2, 0.0, 0.0, 0.0, t, h, false);
                    for (double c : conts) {
                        String now = (String) layer1.invoke(null, c, 0.0, 0.0, 0.0, t, h, false);
                        pairs++;
                        if (c >= 0) assert now.equals(before) : "суша изменилась: cont=" + c + " t=" + t + " h=" + h + " " + before + " -> " + now;
                        else if (c < -0.05) assert now.equals(expectedOcean(c, t)) : "океан: cont=" + c + " t=" + t + " " + now;
                        else assert now.equals(t < -0.2 ? "snowy_beach" : "beach") : "берег: cont=" + c + " t=" + t + " " + now;
                        assert layer1.invoke(null, c, 0.0, 0.0, 0.0, t, h, true).equals("deep_lake") : "deepLake -> deep_lake";
                    }
                }
            }
            // 2) гистограмма по реальной формуле континентальности (вырезана из Layer1TerrainGenerator.java), сетка +-100000 шаг 256
            String l1 = Files.readString(Path.of(ROOT + "layer/Layer1TerrainGenerator.java"));
            Matcher sm = Pattern.compile("continentNoise\\s*=\\s*new AeroNoise\\(seed \\^ (0x[0-9A-Fa-f]+L)\\)").matcher(l1);
            Matcher fm = Pattern.compile("continentNoise\\.fbm2D\\(wx \\* ([0-9.]+), wz \\* ([0-9.]+), (\\d+), ([0-9.]+), ([0-9.]+)\\)").matcher(l1);
            if (!sm.find() || !fm.find()) throw new AssertionError("не нашёл формулу континентальности");
            long contSalt = Long.decode(sm.group(1).replaceAll("L$", ""));
            double cs1 = Double.parseDouble(fm.group(1)), cs2 = Double.parseDouble(fm.group(2)), ca = Double.parseDouble(fm.group(4)), cb = Double.parseDouble(fm.group(5));
            int coct = Integer.parseInt(fm.group(3));
            for (long worldSeed : new long[]{12345L, -1L, 987654321L, -5979674762087725943L}) {
                Object cn = noiseCls.getConstructor(long.class).newInstance(worldSeed ^ contSalt);
                java.util.Map<String, Integer> oldH = new java.util.TreeMap<>(), newH = new java.util.TreeMap<>();
                int total = 0, oceans = 0, beaches = 0, landSame = 0;
                for (int x = -100_000; x <= 100_000; x += 256) {
                    for (int z = -100_000; z <= 100_000; z += 256) {
                        double t = (Double) temperature.invoke(null, tn, (double) x, (double) z);
                        double h = (Double) humidity.invoke(null, hn, (double) x, (double) z);
                        double c = (Double) fbm.invoke(cn, x * cs1, z * cs2, coct, ca, cb);
                        String before = (String) field.invoke(null, t, h);
                        String now = (String) layer1.invoke(null, c, 0.0, 0.0, 0.0, t, h, false);
                        total++;
                        oldH.merge(before, 1, Integer::sum);
                        newH.merge(now, 1, Integer::sum);
                        if (c >= 0) { assert now.equals(before) : "суша изменилась в " + x + "," + z; landSame++; }
                        else if (c < -0.05) { assert now.contains("ocean") : "ожидался океан: " + now; oceans++; }
                        else { assert now.endsWith("beach") : "ожидался пляж: " + now; beaches++; }
                    }
                }
                for (var e : oldH.entrySet()) {
                    assert newH.getOrDefault(e.getKey(), 0) <= e.getValue() : "доля земляного биома выросла: " + e.getKey();
                }
                System.out.printf(java.util.Locale.ROOT, "WATER seed %d: точек %d, суша %.1f%% (биомы суши идентичны), пляж %.1f%%, океан %.1f%%%n",
                        worldSeed, total, 100.0 * landSame / total, 100.0 * beaches / total, 100.0 * oceans / total);
                if (worldSeed == 12345L) System.out.println("  биомы до: " + oldH + "\n  биомы после: " + newH);
            }
            System.out.println("WATER: " + pairs + " пар (cont, temp, humid) проверено");
            System.out.println("AeroBiomeParitySelfCheck ok: " + n + " точек, биомов " + names.size() + " " + names);
        }
    }

    /** Независимая запись океанской лестницы из задания: frozen/cold/ocean/lukewarm/warm, deep при cont < -0.20. */
    private static String expectedOcean(double cont, double temp) {
        boolean deep = cont < -0.20;
        if (temp < -0.3) return deep ? "deep_frozen_ocean" : "frozen_ocean";
        if (temp < 0.0) return deep ? "deep_cold_ocean" : "cold_ocean";
        if (temp < 0.35) return deep ? "deep_ocean" : "ocean";
        if (temp < 0.6) return deep ? "deep_lukewarm_ocean" : "lukewarm_ocean";
        return "warm_ocean";
    }
}
