package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.resources.ResourceLocation;

/**
 * Набор ссылок на ванильные loot table (datapack JSON) для Vault и Trial Spawner
 * одного "профиля" генерации (например, отдельного слоя острова).
 *
 * <p>Vault/Trial Spawner в ваниле хранят loot table как {@link ResourceLocation}
 * внутри своего NBT-конфига (тег {@code loot_table}, отдельно {@code ominous_config.loot_table}
 * или {@code normal_config}/{@code ominous_config} у Trial Spawner). Сама раздача
 * предметов (проценты, диапазоны количеств) описывается в JSON loot table, а не в Java —
 * так это делает ваниль, и это же даёт бесплатную интеграцию с системой "зловещих"
 * испытаний (Bad Omen / зловещая печать) без ручной проверки эффекта в коде генератора.</p>
 *
 * <p>Этот record нарочно не содержит никакой Layer2-специфичной логики — он лишь
 * связка из 4 id. Чтобы завести Vault/Trial Spawner на другом слое с другим дропом,
 * достаточно создать новые loot table JSON и новый {@code VaultTrialLootConfig},
 * не трогая {@link IslandVaultTrialGenerator}.</p>
 *
 * @param vaultLootNormal    loot table обычного Vault
 * @param vaultLootOminous   loot table зловещего Vault
 * @param trialLootNormal    loot table обычного Trial Spawner
 * @param trialLootOminous   loot table зловещего Trial Spawner
 */
public record VaultTrialLootConfig(
        ResourceLocation vaultLootNormal,
        ResourceLocation vaultLootOminous,
        ResourceLocation trialLootNormal,
        ResourceLocation trialLootOminous
) {

    /** Конфиг loot-таблиц Layer 2 (см. data/aeroworld/loot_table/gameplay/layer2/). */
    public static final VaultTrialLootConfig LAYER_2 = new VaultTrialLootConfig(
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/vault_normal"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/vault_ominous"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/trial_spawner_normal"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/trial_spawner_ominous")
    );
}
