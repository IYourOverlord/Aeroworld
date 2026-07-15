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

    static {
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
