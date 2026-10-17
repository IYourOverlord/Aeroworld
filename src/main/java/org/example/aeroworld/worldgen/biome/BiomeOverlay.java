package org.example.aeroworld.worldgen.biome;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Слои «реального» рельефа, которые подмешиваются в полевой {@link AeroBiomeSource} поверх замороженных входов
 * ({@code AeroBiomeSource.FIELD_*}). Включаются {@code -Daeroworld.biomeOverlay=water,plateau,...}; по умолчанию пусто.
 * Чистый класс (без Minecraft), чтобы проверяться self-check'ом.
 */
public enum BiomeOverlay {
    /** континентальность и маска глубокого озера: океаны, пляжи, deep_lake */
    WATER,
    /** маска плато */
    PLATEAU,
    /** эрозия (горные биомы) */
    PEAKS,
    /** сила хребта */
    RIDGES,
    /** реки (входа в resolveLayer1Biome нет; слой дописывается заданием про реки) */
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
    }

    /** Значение свойства по умолчанию: слой воды включён. {@code none} (или пустое значение) выключает всё. */
    public static final String DEFAULT = "water";

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
                overlay.contains(PLATEAU) ? real.plateau() : field.plateau(),
                water && real.deepLake());
    }
}
