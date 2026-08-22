# Фикс: IslandVaultTrialGenerator — спавнеры испытаний не спавнят существ

## Диагноз

`IslandVaultTrialGenerator.findBuriedSpot` искал точку размещения Vault/Trial
Spawner в радиусе `island.radius * 0.7` от центра острова. Так как острова
Layer 2 обычно крупнее одного чанка (16×16), кандидатная точка нередко
оказывалась в чанке, соседнем с тем, для которого `Layer2VaultTrialPlacer`
вызывал `applyBiomeDecoration`.

`placeBlockWithEntity` записывает `blockEntity` через
`region.getChunk(pos).setBlockEntity(be)`, используя фактические координаты
точки, а не переданный в decoration чанк. Если эта точка попадала в соседний,
ещё не декорированный на тот момент чанк — NBT-конфиг Trial Spawner (в
частности `spawn_potentials`) не переживал последующую генерацию того чанка.
В итоге блок Trial Spawner физически стоял в мире, но без списка мобов —
он не мог никого заспавнить и, соответственно, никогда не переходил в фазу
выдачи наград.

## Исправление

1. **`IslandVaultTrialGenerator.java`**
   - Добавлена константа `CHUNK_SAFETY_MARGIN`.
   - `placeForIsland(...)` и `findBuriedSpot(...)` теперь принимают
     `chunkX`/`chunkZ` — координаты чанка, вызвавшего decoration.
   - `findBuriedSpot` жёстко ограничивает кандидатов границами этого чанка
     (с отступом `CHUNK_SAFETY_MARGIN`, учитывающим `CLEAR_RADIUS` расчистки
     площадки вокруг блока).
   - `innerRadius` дополнительно подрезается расстоянием от центра острова
     до ближайшей безопасной границы чанка — чтобы не тратить впустую
     `MAX_PLACEMENT_ATTEMPTS` попыток на заведомо отбракованные точки.
   - Добавлена вторичная защита: `region.hasChunk(...)` перед возвратом
     кандидата.

2. **`Layer2VaultTrialPlacer.java`**
   - Пробрасывает уже имеющиеся `chunkX`/`chunkZ` в обновлённый
     `placeForIsland(...)`.

## Как накатить

Замените файлы по тем же путям в репозитории:

```
src/main/java/org/example/aeroworld/worldgen/feature/vault/IslandVaultTrialGenerator.java
src/main/java/org/example/aeroworld/worldgen/feature/vault/Layer2VaultTrialPlacer.java
```

либо примените `changes.diff` через `git apply changes.diff` из корня репозитория.

## Проверка после накатки

- Сгенерировать новый мир (или новую область), долететь до нескольких Layer 2
  островов, посмотреть debug-лог: `IslandVaultTrialGenerator: island (...) tier=...
  placed N structure(s) in chunk (...)`.
- Убедиться, что у поставленных Trial Spawner при разрушении/через
  `/data get block <pos>` присутствует непустой `spawn_potentials` в
  `normal_config`/`ominous_config`.
- Проверить, что мобы реально спавнятся при подходе игрока и что после их
  уничтожения спавнер переходит в фазу наград (эжектит loot table).
