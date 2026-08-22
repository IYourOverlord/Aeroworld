package org.example.aeroworld.worldgen.feature.vault;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Набор ссылок на ванильные loot table (datapack JSON) для Vault и Trial Spawner
 * одного "профиля" генерации (например, отдельного слоя острова), плюс список
 * мобов, которых должен спавнить Trial Spawner.
 *
 * <p>Vault/Trial Spawner в ваниле хранят loot table как {@link ResourceLocation}
 * внутри своего NBT-конфига (тег {@code loot_table}, отдельно {@code ominous_config.loot_table}
 * или {@code normal_config}/{@code ominous_config} у Trial Spawner). Сама раздача
 * предметов (проценты, диапазоны количеств) описывается в JSON loot table, а не в Java —
 * так это делает ваниль, и это же даёт бесплатную интеграцию с системой "зловещих"
 * испытаний (Bad Omen / зловещая печать) без ручной проверки эффекта в коде генератора.</p>
 *
 * <p>Этот record нарочно не содержит никакой Layer2-специфичной логики — он лишь
 * связка id + список мобов. Чтобы завести Vault/Trial Spawner на другом слое
 * с другим дропом и другими мобами, достаточно создать новые loot table JSON
 * и новый {@code VaultTrialLootConfig}, не трогая {@link IslandVaultTrialGenerator}.</p>
 *
 * @param vaultLootNormal    loot table обычного Vault
 * @param vaultLootOminous   loot table зловещего Vault
 * @param trialLootNormal    loot table обычного Trial Spawner
 * @param trialLootOminous   loot table зловещего Trial Spawner
 * @param spawnPotentials    мобы, которых спавнит Trial Spawner (id + вес выбора)
 */
public record VaultTrialLootConfig(
        ResourceLocation vaultLootNormal,
        ResourceLocation vaultLootOminous,
        ResourceLocation trialLootNormal,
        ResourceLocation trialLootOminous,
        List<SpawnPotential> spawnPotentials
) {

    /** Один потенциальный моб для spawn_potentials Trial Spawner. */
    public record SpawnPotential(ResourceLocation entityId, int weight) {
    }

    /** Конфиг loot-таблиц Layer 2 (см. data/aeroworld/loot_table/gameplay/layer2/). */
    public static final VaultTrialLootConfig LAYER_2 = new VaultTrialLootConfig(
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/vault_normal"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/vault_ominous"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/trial_spawner_normal"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer2/trial_spawner_ominous"),
            List.of(
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("zombie"), 2),
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("skeleton"), 2),
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("spider"), 1),
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("husk"), 1)
            )
    );

    /**
     * Конфиг loot-таблиц Layer 3 (см. data/aeroworld/loot_table/gameplay/layer3/).
     *
     * <p>Дроп — золото, редстоун, железо (без угля и меди), в тех же
     * пропорциях/весах, что и {@link #LAYER_2}: {@code copper → gold},
     * {@code coal → redstone}, {@code iron} без изменений. Список мобов
     * spawn_potentials оставлен таким же, как у Layer 2.</p>
     */
    public static final VaultTrialLootConfig LAYER_3 = new VaultTrialLootConfig(
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer3/vault_normal"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer3/vault_ominous"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer3/trial_spawner_normal"),
            ResourceLocation.fromNamespaceAndPath("aeroworld", "gameplay/layer3/trial_spawner_ominous"),
            List.of(
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("zombie"), 2),
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("skeleton"), 2),
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("spider"), 1),
                    new SpawnPotential(ResourceLocation.withDefaultNamespace("husk"), 1)
            )
    );
}
