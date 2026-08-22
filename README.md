# Vault/Trial Spawner для Layer 3 — патч

Архив содержит только новые и изменённые файлы, пути соответствуют структуре
репозитория Aeroworld — распаковывайте прямо в корень репозитория (файлы лягут
в нужные директории, ничего лишнего не перезапишут).

## Новые файлы
- `src/main/java/org/example/aeroworld/worldgen/feature/vault/Layer3VaultTrialPlacer.java`
- `src/main/resources/data/aeroworld/loot_table/gameplay/layer3/vault_normal.json`
- `src/main/resources/data/aeroworld/loot_table/gameplay/layer3/vault_ominous.json`
- `src/main/resources/data/aeroworld/loot_table/gameplay/layer3/trial_spawner_normal.json`
- `src/main/resources/data/aeroworld/loot_table/gameplay/layer3/trial_spawner_ominous.json`

## Изменённые файлы
- `src/main/java/org/example/aeroworld/worldgen/layer/HighIslandGenerator.java`
  — добавлен `computeXZSq(wx, wz, IslandData)`.
- `src/main/java/org/example/aeroworld/worldgen/feature/vault/IslandVaultTrialGenerator.java`
  — добавлены `placeForEllipsoidIsland(...)` и `findBuriedSpotEllipsoid(...)`
  (постановка блока/NBT/расчистка переиспользуются без изменений).
- `src/main/java/org/example/aeroworld/worldgen/feature/vault/VaultTrialLootConfig.java`
  — добавлен `LAYER_3` (золото/редстоун/железо).
- `src/main/java/org/example/aeroworld/worldgen/AeroWorldChunkGenerator.java`
  — подключён вызов `layer3VaultTrialPlacer.placeForChunk(...)` в `applyBiomeDecoration`.

`CHANGES.diff` в этом архиве — unified diff по изменённым (не новым) Java-файлам,
относительно исходного состояния репозитория, для быстрого ревью без построчного
сравнения файлов вручную.

⚠️ Компиляция gradle-проектом в текущей среде не проверялась (нет доступа к
Maven-репозиториям NeoForge/Minecraft из песочницы) — проверена только
синтаксическая корректность (баланс скобок, сигнатуры, импорты). Перед мержем
рекомендуется прогнать `./gradlew build` локально.
