package org.example.aeroworld.worldgen.biome;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import org.example.aeroworld.worldgen.noise.AeroNoise;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * AeroBiomeSource — независимый биомный источник для AeroWorld.
 *
 * FIX биомов: seed передаётся явно через withSeed() из AeroWorldChunkGenerator,
 * а не вытаскивается из Climate.Sampler (который всегда нули для кастомного генератора).
 *
 * FIX краша: CODEC использует xmap поверх MultiNoiseBiomeSource.CODEC — это сохраняет
 * совместимость с существующим JSON (поле "preset": "minecraft:overworld").
 * Seed не сериализуется в JSON; он передаётся программно через withSeed().
 */
public class AeroBiomeSource extends BiomeSource {

    // ── CODEC: оборачиваем MultiNoiseBiomeSource — совместимо с существующим JSON ──
    // JSON-формат: { "type": "aeroworld:aero_biome_source", "preset": "minecraft:overworld" }
    public static final MapCodec<AeroBiomeSource> CODEC =
            MultiNoiseBiomeSource.CODEC.xmap(
                    AeroBiomeSource::new,       // десериализация: MultiNoise → AeroBiomeSource
                    src -> src.delegate         // сериализация:  AeroBiomeSource → MultiNoise
            );

    // ── Масштаб шума ─────────────────────────────────────────────────────────
    // Уменьшен в ~3x по сравнению с исходным: биомные зоны ~80–120 блоков
    // (было ~250–300 блоков — крайние биомы почти не встречались вблизи спавна)
    private static final double TEMP_SCALE = 0.0025;
    private static final double HUM_SCALE  = 0.0030;

    // ── Таблица биомов [temp 0..4][hum 0..2] ────────────────────────────────
    private static final String[][] BIOME_TABLE = {
            { "frozen_peaks",    "snowy_plains",    "snowy_taiga"            }, // frozen
            { "windswept_hills", "taiga",           "old_growth_spruce_taiga"}, // cold
            { "plains",          "forest",          "swamp"                  }, // normal
            { "savanna",         "meadow",          "jungle"                 }, // warm
            { "desert",          "badlands",        "bamboo_jungle"          }, // hot
    };

    private static final String[][] RARE_TABLE = {
            { "ice_spikes",              "snowy_slopes",      "grove"               },
            { "windswept_gravelly_hills","windswept_forest",  "old_growth_pine_taiga"},
            { "sunflower_plains",        "birch_forest",      "dark_forest"         },
            { "savanna_plateau",         "cherry_grove",      "sparse_jungle"       },
            { "eroded_badlands",         "wooded_badlands",   "stony_peaks"         },
    };

    private static final double RARE_THRESHOLD = 0.65;

    /**
     * ПОЛНЫЙ список склонированных под aeroworld:* overworld-биомов (53 шт. —
     * все ванильные биомы, кроме незера/энда/void). Нужен для collectPossibleBiomes,
     * т.к. слои-острова (y > 12, через delegateWithSafety) могут вернуть любой
     * из них — не только те 30, что фигурируют в BIOME_TABLE/RARE_TABLE.
     */
    private static final String[] ALL_CLONED_BIOMES = {
            "badlands", "bamboo_jungle", "beach", "birch_forest", "cherry_grove",
            "cold_ocean", "dark_forest", "deep_cold_ocean", "deep_dark", "deep_frozen_ocean",
            "deep_lukewarm_ocean", "deep_ocean", "desert", "dripstone_caves", "eroded_badlands",
            "flower_forest", "forest", "frozen_ocean", "frozen_peaks", "frozen_river",
            "grove", "ice_spikes", "jagged_peaks", "jungle", "lukewarm_ocean",
            "lush_caves", "mangrove_swamp", "meadow", "mushroom_fields", "ocean",
            "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "plains", "river",
            "savanna", "savanna_plateau", "snowy_beach", "snowy_plains", "snowy_slopes",
            "snowy_taiga", "sparse_jungle", "stony_peaks", "stony_shore", "sunflower_plains",
            "swamp", "taiga", "warm_ocean", "windswept_forest", "windswept_gravelly_hills",
            "windswept_hills", "windswept_savanna", "wooded_badlands"
    };

    // ── Состояние ─────────────────────────────────────────────────────────────
    private final MultiNoiseBiomeSource delegate;
    private final long seed;
    // Ссылка на Layer1FlatGenerator — нужна ТОЛЬКО чтобы узнать, находится ли
    // точка внутри кольцевой горной долины (см. isInsideRingValley), и в этом
    // случае гарантированно поставить туда forest/cherry_grove вместо обычной
    // климатической таблицы. Может быть null до первого initializeWithSeed().
    private final org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1;

    // Шумовые генераторы — инициализируются один раз по seed
    private final AeroNoise tempNoise;
    private final AeroNoise humNoise;
    private final AeroNoise rareNoise;

    // ── Конструктор ───────────────────────────────────────────────────────────
    public AeroBiomeSource(MultiNoiseBiomeSource delegate, long seed) {
        this(delegate, seed, null);
    }

    public AeroBiomeSource(MultiNoiseBiomeSource delegate, long seed,
                           org.example.aeroworld.worldgen.layer.Layer1FlatGenerator layer1) {
        this.delegate  = delegate;
        this.seed      = seed;
        this.layer1    = layer1;
        this.tempNoise = new AeroNoise(seed ^ 0x9A4B1C2DL);
        this.humNoise  = new AeroNoise(seed ^ 0x3E7F8A5BL);
        this.rareNoise = new AeroNoise(seed ^ 0xC1D2E3F4L);
    }

    /** Удобный конструктор без seed (для обратной совместимости в ChunkGenerator до initSeed). */
    public AeroBiomeSource(MultiNoiseBiomeSource delegate) {
        this(delegate, 0xAE40F9C3L);
    }

    /**
     * Создаёт новый экземпляр с правильным seed.
     * Вызывается из AeroWorldChunkGenerator.initializeWithSeed().
     */
    public AeroBiomeSource withSeed(long newSeed) {
        if (newSeed == this.seed) return this;
        return new AeroBiomeSource(delegate, newSeed, this.layer1);
    }

    /**
     * Привязывает Layer1FlatGenerator этого мира — после этого кольцевые
     * горные долины гарантированно получают forest/cherry_grove.
     * Вызывается из AeroWorldChunkGenerator.initializeWithSeed() сразу после
     * создания layer1.
     */
    public AeroBiomeSource withRingChecker(org.example.aeroworld.worldgen.layer.Layer1FlatGenerator newLayer1) {
        if (newLayer1 == this.layer1) return this;
        return new AeroBiomeSource(delegate, this.seed, newLayer1);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() { return CODEC; }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        Stream<Holder<Biome>> aeroClones = java.util.Arrays.stream(ALL_CLONED_BIOMES)
                .map(name -> AeroBiomeRegistryCache.get(ResourceLocation.fromNamespaceAndPath("aeroworld", name)))
                .filter(Optional::isPresent)
                .map(Optional::get);

        // Ванильные minecraft:* биомы больше не нужны в качестве отдельной ветки:
        // delegateWithSafety теперь всегда подменяет их на клон aeroworld:*.
        // Оставляем как последний fallback на случай, если реестр ещё не
        // прогрелся (AeroBiomeRegistryCache пуст) в момент вызова.
        Stream<Holder<Biome>> vanillaFallback = delegate.possibleBiomes().stream()
                .filter(h -> h.unwrapKey()
                        .map(k -> !isExcluded(k.location()))
                        .orElse(true));

        return Stream.concat(aeroClones, vanillaFallback);
    }

    // ── Deep Dark (подземный биом, нужен для ancient_city) ───────────────────
    // y здесь — noise-координата в четвертях блока (y*4 ≈ блок). Ancient City
    // в ваниле всегда генерируется на Y=-51 (реже -64..-8), внутри deep_dark.
    // Раньше deep_dark был жёстко исключён (см. isExcluded) и НИКОГДА не
    // выбирался ни в одной точке мира — из-за этого ancient_city физически
    // не мог заспавниться (structure привязана к биому напрямую, не через
    // has_structure-тег). Теперь выделяем под deep_dark отдельный диапазон
    // глубин Layer 1 и редкий 2D-шум — независимо от температуры/влажности,
    // как и в ваниле (deep_dark не зависит от климата).
    private static final int DEEP_DARK_MAX_Y_BLOCK = -8;
    private static final int DEEP_DARK_MIN_Y_BLOCK = Layer1FlatGeneratorMinYHolder.MIN_Y;
    private static final double DEEP_DARK_NOISE_SCALE = 0.006;
    private static final double DEEP_DARK_THRESHOLD   = 0.30; // ~15% покрытия глубин

    private static final class Layer1FlatGeneratorMinYHolder {
        static final int MIN_Y = org.example.aeroworld.worldgen.layer.Layer1FlatGenerator.LAYER_MIN_Y;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        // Островные слои (Y > 12 noise units ≈ блок 50+) — делегируем ванили
        if (y > 12) {
            return delegateWithSafety(x, 20, z, sampler);
        }

        // Слой 1: 2D-шум по seed мира (больше не зависим от sampler)
        double wx = x * 4.0;
        double wz = z * 4.0;

        // ── Deep Dark: только на подземной глубине (Y от -64 до -8) ──────────
        int blockY = y * 4;
        if (blockY <= DEEP_DARK_MAX_Y_BLOCK && blockY >= DEEP_DARK_MIN_Y_BLOCK) {
            double dd = tempNoise.fbm2D(wx * DEEP_DARK_NOISE_SCALE, wz * DEEP_DARK_NOISE_SCALE, 3, 2.0, 0.5);
            if (dd > DEEP_DARK_THRESHOLD) {
                Optional<Holder<Biome>> deepDark =
                        findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", "deep_dark"));
                if (deepDark.isPresent()) return deepDark.get();
            }
        }

        // Считаем температуру заранее — нужна и для океана (вариант по
        // температуре: warm/lukewarm/ocean/cold/frozen), и для обычной
        // климатической таблицы ниже. Одно вычисление, не дублируем fbm2D.
        double temp = tempNoise.fbm2D(wx * TEMP_SCALE, wz * TEMP_SCALE, 4, 2.0, 0.5);

        // Кольцевые горные долины (см. Layer1FlatGenerator) гарантированно
        // получают forest/cherry_grove — независимо от того, что выпало бы
        // по обычной климатической таблице ниже.
        if (layer1 != null) {
            int bwx = (int) wx;
            int bwz = (int) wz;
            if (layer1.isInsideRingValley(bwx, bwz)) {
                String forced = layer1.ringValleyBiome(bwx, bwz);
                Optional<Holder<Biome>> forcedBiome =
                        findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", forced));
                if (forcedBiome.isPresent()) return forcedBiome.get();
            } else if (layer1.isBeachColumn(bwx, bwz)) {
                // Пляж переходит либо в обычный "beach", либо в "snowy_beach"
                // в холодных широтах — как и в ваниле (snowy_beach требует
                // холодного климата). Раньше snowy_beach был недостижим нигде.
                String beachName = (temp < -0.32) ? "snowy_beach" : "beach";
                Optional<Holder<Biome>> beach =
                        findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", beachName));
                if (beach.isPresent()) return beach.get();
            } else if (layer1.isOceanColumn(bwx, bwz)) {
                // ── Океан: раньше физически была вода, но биом всегда
                // резолвился в сухопутный (plains/forest/...) — из-за этого
                // ВСЕ ocean-биомы были недостижимы, и структуры, привязанные
                // к ним напрямую (ocean_monument, ocean_ruin_warm/cold,
                // shipwreck), не могли заспавниться. Теперь биом синхронизирован
                // с фактической водой из columnProfile через isOceanColumn/
                // oceanDepth01 (та же формула, читается, не дублирует рельеф).
                double depth01 = layer1.oceanDepth01(bwx, bwz);
                boolean deep = depth01 > 0.55; // ближе к OCEAN_DEEP_AT → deep_*
                String oceanName;
                if (temp < -0.32)      oceanName = deep ? "deep_frozen_ocean"   : "frozen_ocean";
                else if (temp < -0.096) oceanName = deep ? "deep_cold_ocean"    : "cold_ocean";
                else if (temp <  0.096) oceanName = deep ? "deep_ocean"         : "ocean";
                else if (temp <  0.32)  oceanName = deep ? "deep_lukewarm_ocean": "lukewarm_ocean";
                else                    oceanName = "warm_ocean"; // тёплый океан не бывает "deep_" в ваниле
                Optional<Holder<Biome>> ocean =
                        findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", oceanName));
                if (ocean.isPresent()) return ocean.get();
            } else if (layer1.isRiverColumn(bwx, bwz) || layer1.isLakeColumn(bwx, bwz)) {
                // Река ИЛИ озеро: у ванили нет отдельного биома "озеро" —
                // маленькие пруды в ваниле просто наследуют биом суши вокруг
                // (без seagrass). Но здесь озёра из columnProfile — крупные,
                // самостоятельные водоёмы (см. LAKE_THRESHOLD), а не мелкие
                // decoration-пруды, поэтому логичнее обращаться с ними как с
                // рекой: тот же river/frozen_river биом — у него уже есть
                // seagrass_river в фичах. Раньше isLakeColumn не существовал
                // вовсе, и озёра проваливались в обычную климатическую
                // таблицу (plains/meadow/forest/...) — без единой водной
                // фичи, отсюда голое дно под озёрами.
                String riverName = (temp < -0.32) ? "frozen_river" : "river";
                Optional<Holder<Biome>> river =
                        findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", riverName));
                if (river.isPresent()) return river.get();
            } else if (layer1.isStrandedShallowColumn(bwx, bwz)) {
                // "Случайная лужа" — landHeight провалился ниже WATER_LEVEL
                // сам по себе (шум continentalness/roughness), без участия
                // ocean/river/lake шумов (см. columnProfile — страховка от
                // сухой суши ниже уровня воды). columnProfile честно ставит
                // туда физическую воду, но раньше биом не совпадал вообще
                // никак — оставался сухопутным. Трактуем как мелкий ocean/
                // lukewarm_ocean по температуре, чтобы у воды тоже была хоть
                // какая-то подводная фича вместо голого дна.
                String shallowName = (temp < -0.096) ? "ocean" : "lukewarm_ocean";
                Optional<Holder<Biome>> shallow =
                        findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", shallowName));
                if (shallow.isPresent()) return shallow.get();
            }
        }

        double hum  = humNoise .fbm2D(wx * HUM_SCALE,  wz * HUM_SCALE,  4, 2.0, 0.5);
        double rare = rareNoise.fbm2D(wx * TEMP_SCALE * 1.5, wz * HUM_SCALE * 1.5, 2, 2.0, 0.5);

        // ── Квантильное распределение индексов ───────────────────────────────
        // Perlin FBM возвращает значения с нормальным (гауссовым) распределением,
        // std ≈ 0.38. Линейное t = (v+1)/2 даёт крайним индексам (desert/frozen)
        // лишь ~6% площади. Квантильный метод: делим нормальное распределение
        // на 5 равных квантилей → каждый биомный пояс ровно ~20% площади.
        //
        // Границы квантилей FBM(std=0.38): -0.32 / -0.096 / 0.096 / 0.32
        int ti = quantileIndex5(temp);
        int hi = quantileIndex3(hum);
        double r = (rare + 1.0) * 0.5;   // для редких достаточно линейного

        String biomeName = (r > RARE_THRESHOLD) ? RARE_TABLE[ti][hi] : BIOME_TABLE[ti][hi];

        // ВАЖНО: слой 1 (поверхность) теперь резолвится в наши клоны aeroworld:*,
        // а НЕ в ванильные minecraft:*. У клонов NeoForge biome modifier
        // (data/aeroworld/neoforge/biome_modifier/remove_ores.json) вырезал
        // все placed_feature руды из generation settings ещё на этапе загрузки
        // датапака — то есть руда физически не значится в списке фич биома
        // и просто не пытается разместиться, а не "спавнится и потом чистится".
        // Реальный minecraft:plains (используемый настоящим Overworld) при этом
        // не тронут — модификатор целится только в тег #aeroworld:aero_biomes.
        return findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", biomeName))
                .orElseGet(() -> findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", "plains"))
                        .orElseGet(() -> delegateWithSafety(x, 20, z, sampler)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Holder<Biome> delegateWithSafety(int x, int y, int z, Climate.Sampler sampler) {
        // Реальный ванильный делегат используется ТОЛЬКО как климатический
        // семплер — чтобы понять, какой биом "подошёл бы" по температуре/
        // влажности/континентальности в этой точке. Сам Holder<Biome>,
        // который он возвращает, указывает на настоящий minecraft:* биом
        // из общего реестра (со всей ванильной рудой) — использовать его
        // напрямую для генерации на островах нельзя, иначе получаем ровно
        // тот баг, что был найден (уголь на острове слоя 2).
        Holder<Biome> vanilla = delegate.getNoiseBiome(x, y, z, sampler);
        ResourceLocation vanillaId = vanilla.unwrapKey()
                .map(k -> k.location())
                .orElse(ResourceLocation.withDefaultNamespace("plains"));

        if (isExcluded(vanillaId)) {
            return findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", "plains")).orElse(vanilla);
        }

        // Подменяем на наш клон aeroworld:<то же имя> — он либо уже один из
        // 30 биомов основной/редкой таблицы, либо один из остальных 23
        // (океаны/пляжи/пещерные и т.д.), склонированных в рамках полного
        // набора overworld-биомов специально для этого случая — чтобы у
        // ЛЮБОГО биома, который может прийти с островов, была версия без руды.
        return findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", vanillaId.getPath()))
                .orElseGet(() -> findBiome(ResourceLocation.fromNamespaceAndPath("aeroworld", "plains"))
                        .orElse(vanilla));
    }

    private Optional<Holder<Biome>> findBiome(ResourceLocation id) {
        // Сначала — полный реестр биомов (единственное место, где видны
        // наши клоны aeroworld:*, т.к. их нет в possibleBiomes() пресета).
        Optional<Holder<Biome>> cached = AeroBiomeRegistryCache.get(id);
        if (cached.isPresent()) return cached;

        // Фолбэк — набор биомов ванильного multi-noise делегата
        // (нужен для minecraft:* биомов островных слоёв, y > 12).
        return delegate.possibleBiomes().stream()
                .filter(h -> h.unwrapKey().map(k -> k.location().equals(id)).orElse(false))
                .findFirst();
    }

    private static boolean isExcluded(ResourceLocation loc) {
        String p = loc.getPath();
        return p.contains("ocean") || p.equals("dripstone_caves")
                || p.equals("lush_caves") || p.equals("deep_dark");
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Делит нормально-распределённое значение FBM (диапазон -1..1, std≈0.38)
     * на 5 равновероятных квантилей → индекс 0..4, каждый ~20% площади.
     *
     * Границы квантилей: -0.32 / -0.096 / +0.096 / +0.32
     * (вычислены из ppf нормального распределения с std=0.38)
     */
    private static int quantileIndex5(double v) {
        if (v < -0.32)  return 0; // frozen  (~20%)
        if (v < -0.096) return 1; // cold    (~20%)
        if (v <  0.096) return 2; // normal  (~20%)
        if (v <  0.32)  return 3; // warm    (~20%)
        return 4;                  // hot     (~20%) ← desert, badlands
    }

    /**
     * Делит нормально-распределённое значение FBM на 3 равновероятных квантиля.
     * Границы: -0.096 / +0.096  (std=0.38, 33%/33%/33%)
     * Точнее: ppf(1/3) ≈ -0.153, ppf(2/3) ≈ +0.153
     */
    private static int quantileIndex3(double v) {
        if (v < -0.153) return 0;
        if (v <  0.153) return 1;
        return 2;
    }
}