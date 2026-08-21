package org.example.aeroworld.worldgen.feature.vault;

/**
 * Категория "богатства" спавна Vault/Trial Spawner на одном острове.
 *
 * <p>Не привязана к конкретному слою — количество структур на остров
 * одинаково для любого слоя, использующего {@link IslandVaultTrialGenerator}.
 * Различаться между слоями может только {@link VaultTrialLootConfig} (дроп)
 * и вероятности выбора тира (см. {@code tierChanceXxx} у конкретного Placer'а).</p>
 */
public enum VaultTrialSpawnTier {

    /** Один Vault, один Trial Spawner. */
    POOR(1, 1),

    /** Два Vault, три Trial Spawner. */
    MEDIUM(2, 3),

    /** Три Vault, пять Trial Spawner. */
    RICH(3, 5);

    private final int vaultCount;
    private final int trialSpawnerCount;

    VaultTrialSpawnTier(int vaultCount, int trialSpawnerCount) {
        this.vaultCount = vaultCount;
        this.trialSpawnerCount = trialSpawnerCount;
    }

    public int vaultCount() {
        return vaultCount;
    }

    public int trialSpawnerCount() {
        return trialSpawnerCount;
    }
}
