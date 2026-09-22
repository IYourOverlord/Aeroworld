package org.example.aeroworld.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Конфигурация AeroWorld — файл {@code config/aeroworld-client.toml}.
 *
 * <h3>Как изменить настройки</h3>
 * Отредактируй файл {@code .minecraft/config/aeroworld-client.toml} в текстовом
 * редакторе. Изменения применяются автоматически без перезапуска игры
 * (NeoForge перечитывает конфиг при обнаружении изменений на диске).
 */
public class AeroWorldConfig {

    // ── Spec и экземпляр ──────────────────────────────────────────────────────

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public  static final ModConfigSpec         SPEC;

    public static final ModConfigSpec.BooleanValue DH_OVERRIDE_ENABLED;
    public static final ModConfigSpec.IntValue     DH_THROUGHPUT_LOG_INTERVAL_SEC;
    public static final ModConfigSpec.BooleanValue DH_ADJACENCY_CACHE_ENABLED;
    public static final ModConfigSpec.IntValue     DH_ADJACENCY_CACHE_SIZE;

    static {
        BUILDER.push("distant_horizons");
        DH_OVERRIDE_ENABLED = BUILDER
                .comment("Enable AeroWorld analytical IDhApiWorldGenerator override for Distant Horizons")
                .define("dhOverrideEnabled", true);
        DH_THROUGHPUT_LOG_INTERVAL_SEC = BUILDER
                .comment("Interval in seconds to log DH SeedGen throughput statistics")
                .defineInRange("throughputLogIntervalSec", 30, 5, 3600);
        DH_ADJACENCY_CACHE_ENABLED = BUILDER
                .comment("Enable LRU caching of decoded FullDataSourceV2 neighbor sections at chunk borders")
                .define("adjacencyCacheEnabled", true);
        DH_ADJACENCY_CACHE_SIZE = BUILDER
                .comment("Maximum number of decoded FullDataSourceV2 sections retained in the adjacency cache")
                .defineInRange("adjacencyCacheSize", 512, 16, 8192);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Регистрирует конфиг через ModContainer.
     * Вызывается из {@link org.example.aeroworld.AeroWorld} в конструкторе мода.
     * NeoForge 21.1.x: registerConfig перенесён с ModLoadingContext на ModContainer.
     */
    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, SPEC, "aeroworld-client.toml");
    }
}
