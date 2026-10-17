package org.example.aeroworld.worldgen.biome;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Слои «реального» рельефа, которые подмешиваются в полевой {@link AeroBiomeSource} поверх замороженных входов
 * ({@code AeroBiomeSource.FIELD_*}). Включаются {@code -Daeroworld.biomeOverlay=water,peaks,...}; по умолчанию {@code water,plateau,rivers}.
 * Чистый класс (без Minecraft), чтобы проверяться self-check'ом.
 */
public enum BiomeOverlay {
    /** континентальность и маска глубокого озера: океаны, пляжи, deep_lake */
    WATER,
    /** синоним {@code ashen_plateau} в {@code -Daeroworld.biomeLand}: один механизм (см. {@link #resolveWithLand}) */
    PLATEAU,
    /** эрозия (горные биомы) */
    PEAKS,
    /** сила хребта */
    RIDGES,
    /** биомы river / frozen_river в русле на суше (см. {@link #withRiver}); у настроенной копии для структур слой не действует */
    RIVERS;

    /** Входы {@code resolveLayer1Biome}, кроме температуры и влажности. */
    public record Inputs(double cont, double eros, double ridge, double plateau, boolean deepLake) {}

    /** Ленивый источник реальных входов рельефа: {@link #select} зовёт только то, что нужно включённым слоям. */
    public interface Real {
        double cont();
        double eros();
        double ridge();
        double plateau();
        boolean deepLake();
        /** Колонка в русле реки (только для слоя RIVERS). */
        boolean river();
    }

    /**
     * Значение свойства по умолчанию: вода, плато и реки. Без PLATEAU/RIVERS биомов ashen_plateau и river в поле нет, и
     * {@code /locate biome} их не находит, хотя рельеф плато и русел генерируется всегда. {@code none} (или пустое
     * значение) выключает всё; чтобы вернуть прежнее, {@code -Daeroworld.biomeOverlay=water}.
     */
    public static final String DEFAULT = "water,plateau,rivers";

    /** То же, что {@link #parse}, но отсутствующее свойство (null) означает {@link #DEFAULT}. */
    public static Set<BiomeOverlay> fromProperty(String value, Consumer<String> warn) {
        return parse(value == null ? DEFAULT : value, warn);
    }

    /**
     * Разбор значения свойства: через запятую, регистр и пробелы не важны, дубликаты схлопываются,
     * неизвестные значения игнорируются с сообщением в {@code warn}. null, пустая строка и {@code none} (в любом месте
     * списка, он гасит всё) дают пустой набор.
     */
    public static Set<BiomeOverlay> parse(String value, Consumer<String> warn) {
        EnumSet<BiomeOverlay> out = EnumSet.noneOf(BiomeOverlay.class);
        if (value != null) {
            for (String raw : value.split(",")) {
                String s = raw.trim();
                if (s.isEmpty()) continue;
                if (s.equalsIgnoreCase("none")) return Collections.unmodifiableSet(EnumSet.noneOf(BiomeOverlay.class));
                try {
                    out.add(valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    warn.accept("aeroworld.biomeOverlay: неизвестный слой '" + s + "', игнорирую");
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Какие реальные входы пропустить в формулу биома: для слоёв из набора берём {@code real}, остальное остаётся
     * замороженным {@code field}; нужные {@code real}-методы вызываются лениво (WATER не считает эрозию, хребет и плато).
     * {@code overlay == null} значит «всё реальное» (настроенная копия для структур).
     */
    public static Inputs select(Set<BiomeOverlay> overlay, Real real, Inputs field) {
        if (overlay == null) {
            return new Inputs(real.cont(), real.eros(), real.ridge(), real.plateau(), real.deepLake());
        }
        boolean water = overlay.contains(WATER);
        return new Inputs(
                water ? real.cont() : field.cont(),
                overlay.contains(PEAKS) ? real.eros() : field.eros(),
                overlay.contains(RIDGES) ? real.ridge() : field.ridge(),
                field.plateau(), // PLATEAU: синоним ashen_plateau в списке biomeLand, см. resolveWithLand
                water && real.deepLake());
    }

    /**
     * Биомы суши, которые можно включать по одному: {@code -Daeroworld.biomeLand=jagged_peaks,windswept_hills,...}
     * (по умолчанию пусто, карта прежняя). Что меняется на поверхности (проверено по classifyPath/surfaceBlocks):
     * <ul>
     * <li>{@code jagged_peaks}, {@code windswept_hills}, {@code alpine_meadow}: тип DEFAULT, снег с Y 60 как сейчас;
     *     вид почти прежний, меняются растительность, цвета и спавн;</li>
     * <li>{@code frozen_peaks}: COLD, снег только при surfaceY >= 60, ниже трава;</li>
     * <li>{@code stony_peaks}: STONY, верх камень, под ним полосы stonesky;</li>
     * <li>{@code karst_highlands}: KARST, верх кальцит; {@code volcanic_wastes}: VOLCANIC, базальт и блэкстоун;</li>
     * <li>{@code ashen_plateau}: блоки задаёт маска isPlateau, а не биом; меняются только имя биома и спавн.</li>
     * </ul>
     */
    public static final Set<String> LAND_BIOMES = Set.of("frozen_peaks", "jagged_peaks", "stony_peaks",
            "windswept_hills", "alpine_meadow", "karst_highlands", "volcanic_wastes", "ashen_plateau");

    /** Разбор {@code biomeLand}: через запятую, префикс {@code aeroworld:} и регистр не важны, неизвестное имя даёт предупреждение. */
    public static Set<String> parseLand(String value, Consumer<String> warn) {
        Set<String> out = new java.util.HashSet<>();
        if (value != null) {
            for (String raw : value.split(",")) {
                String n = raw.trim().toLowerCase(Locale.ROOT);
                if (n.startsWith("aeroworld:")) n = n.substring(10);
                if (n.isEmpty() || n.equals("none")) continue;
                if (LAND_BIOMES.contains(n)) out.add(n);
                else warn.accept("aeroworld.biomeLand: неизвестный биом '" + raw.trim() + "', игнорирую");
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Биом точки: {@code legacy} (входы по {@code overlay}, то есть карта как раньше) или {@code full} (все входы
     * реальные), если {@code full} входит в разрешённый список. {@code overlay == null} (настроенная копия) всегда
     * {@code full}. Слой PLATEAU равен {@code ashen_plateau} в списке. При пустом списке реальные входы не считаются
     * (ponytail: при непустом {@code full} считается на каждую кварт-колонку, потолок сглаживает кэш колонок источника).
     */
    public static String resolveWithLand(Set<BiomeOverlay> overlay, Set<String> land, Real real, Inputs field,
                                         java.util.function.Function<Inputs, String> resolve) {
        String legacy = resolve.apply(select(overlay, real, field));
        boolean plateau = overlay != null && overlay.contains(PLATEAU);
        if (overlay == null || (land.isEmpty() && !plateau)) return legacy;
        String full = resolve.apply(select(null, real, field));
        return land.contains(full) || (plateau && full.equals("ashen_plateau")) ? full : legacy;
    }

    /**
     * Слой RIVERS: на суше (cont >= 0), не в deep_lake, в русле реки биом становится river (frozen_river при
     * temp < -0.3). Выключен (overlay без RIVERS или null) возвращает биом как есть; дорогие проверки только когда слой включён.
     */
    public static String withRiver(Set<BiomeOverlay> overlay, Real real, String biome, double temp) {
        if (overlay == null || !overlay.contains(RIVERS) || biome.equals("deep_lake")
                || real.cont() < 0.0 || !real.river()) return biome;
        return temp < -0.3 ? "frozen_river" : "river";
    }

    /**
     * Сравнение биомов двух источников (поле и настроенная копия) для {@code /aeroworld biomeAt}. Тип: океан
     * (имя содержит "ocean"), deep_lake или суша. Разный тип даёт {@code MISMATCH} (структура проверится по одной
     * карте, а нарисуется на другой), одинаковый океан с разным именем даёт {@code variant differs} (теплее/холоднее,
     * сиды температуры разные), остальное пусто: варианты суши у источников расходятся штатно.
     */
    public static String compareSources(String field, String copy) {
        if (field.equals(copy)) return "";
        String kf = kind(field), kc = kind(copy);
        if (!kf.equals(kc)) return "MISMATCH";
        return kf.equals("ocean") ? "variant differs" : "";
    }

    private static String kind(String name) {
        return name.equals("deep_lake") ? "lake" : name.contains("ocean") ? "ocean" : "land";
    }
}
