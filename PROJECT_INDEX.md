# AeroWorld — Project Index

Мод для Minecraft 1.21.1 (NeoForge 21.1.227) — кастомное измерение с многослойной генерацией мира: поверхность + три слоя парящих островов. Зависит от мода `physical_structures` (структуры/сборки) и опционально от `create_aeronautics_toolgun` (формат `.excraft`).

Пакет: `org.example.aeroworld`. Корень исходников: `src/main/java/org/example/aeroworld/`.

---

## 1. Слои мира — быстрый обзор

| Слой | Y-диапазон | Форма | Генератор | Настройки |
|---|---|---|---|---|
| Layer 1 | -64 .. 300 | Полноценный ванильный рельеф (режим Amplified: горы, 3D-пещеры, аквиферы) | `NoiseBasedChunkGenerator` (встроен в `AeroWorldChunkGenerator`) | `world_preset/aeroworld.json` (vanilla settings = amplified) |
| Layer 2 | 400 .. 500 | острова произвольной формы + мосты | `worldgen/layer/LowerIslandGenerator.java` | `config/Layer2Settings.java` |
| Layer 3 | 1000 .. 1100 | шары/эллипсоиды | `worldgen/layer/HighIslandGenerator.java` | `config/Layer3Settings.java` |
| Layer 4 | 1900 .. 2031 | "медузы" (купол + щупальца) | `worldgen/layer/UpperIslandGenerator.java` | `config/Layer4Settings.java` |

Все четыре слоя координируются одним классом:
**`worldgen/AeroWorldChunkGenerator.java`** — точка входа в генерацию чанков (`fillFromNoise`, `applyCarvers`, `applyBiomeDecoration`, `createStructures`). Наследуется напрямую от **`NoiseBasedChunkGenerator`**, чтобы обеспечить корректную инициализацию `RandomState` (плотность шумов, параметры биомов) для нижнего ванильного слоя (Layer 1). Начинать чтение кода генерации стоит именно отсюда.

---

## 2. Карта пакетов

```
org.example.aeroworld
├── AeroWorld.java                 — главный класс мода (@Mod), регистрация всего
├── client/
│   └── AeroWorldClientEvents.java     — клиентский обработчик событий (заготовка под UI мирового пресета)
├── command/
│   └── AeroWorldCommands.java     — /aeroworld forcePlacePending, findIsland2/3/4
├── config/
│   ├── AeroWorldConfig.java       — ModConfigSpec (config/aeroworld-client.toml), регистрация в конструкторе мода
│   ├── AeroWorldSettings.java     — root-record {layer2, layer3, layer4}, сериализуется в dimension JSON
│   └── Layer2/3/4Settings.java    — параметры геометрии островов соответствующих слоёв
├── event/
│   ├── AeroStructureListener.java     — слушает PhysicalStructurePlacedEvent (мод physical_structures), звук/эффекты при появлении структуры
│   ├── ProximityTriggerHandler.java   — спавн структур когда игрок рядом (ключевой файл)
│   ├── ShorelineWaveHandler.java      — визуальная симуляция наката волн на пляже
│   └── SpawnerProximityHandler.java   — тиковый поиск блока structure_spawner (physical_structures) рядом с игроком в Layer 2 (Y 250-450)
├── registry/
│   ├── AeroDimensions.java        — регистрация ChunkGenerator codec
│   ├── AeroRegistries.java        — точка регистрации всех DeferredRegister'ов мода
│   ├── AeroResourceKeys.java      — ResourceKey констант для DimensionType/LevelStem/Level измерения aeroworld
│   └── AeroWorldPreset.java       — пустышка-документация: WorldPreset регистрируется через datapack JSON, не через Java
├── spawning/
│   └── LayerSpawnRestriction.java — блокирует спавн мобов на Layer 4 (Y >= 2000)
└── structure/
    ├── IslandStructureScheduler.java  — потокобезопасная очередь размещения структур
    ├── PendingStructureData.java      — SavedData: персистентная очередь структур с backoff/retry (record Entry)
    ├── StructurePlacementHelper.java  — проверка готовности чанков и свободного места
    └── StructureSizeCache.java        — кэш размеров NBT-структур
```

### worldgen/

```
worldgen/
├── AeroWorldChunkGenerator.java   — ★ главный генератор, расширяет NoiseBasedChunkGenerator, координирует все слои
├── biome/
│   ├── AeroBiomeRegistryCache.java — кэш полного динамического реестра биомов (включая клоны aeroworld:*), нужен т.к. AeroBiomeSource.delegate видит только ванильные биомы своего multi-noise пресета
│   └── AeroBiomeSource.java       — кастомный BiomeSource, подменяет биомы на aeroworld:* клоны
├── cache/
│   ├── ChunkIslandCache.java   — общий кэш списков центров островов (все 3 слоя)
│   ├── ChunkKey.java           — упаковка пары (x, z) в long без аллокаций (используется как ключ кэшей)
│   ├── IslandCache.java        — потокобезопасный кэш геометрии островов (Y-bounds, radius, оси эллипсоида) по ChunkKey
│   └── IslandData.java         — иммутабельный value-object острова (bounds, radius, geometry)
├── carver/
│   └── SinkholeCarver.java     — карстовые воронки под островами 2/3/4, работает напрямую с ChunkAccess после ванильного карвинга
├── feature/
│   ├── Layer1OreFilter.java    — активен: чистит ЛЮБУЮ руду по всей высоте чанка (ванильная генерация руды в датапаке не отключена, поэтому фильтр вырезает её пост-фактум)
│   ├── OreVeinHelper.java      — общая логика размещения сферических рудных жил (используется генераторами руды слоёв, если/когда они включены)
│   └── vault/                  — генерация Vault/Trial Spawner внутри тела острова (Layer 2/3/4)
│       ├── IslandVaultTrialCache.java     — per-layer кэш прогресса размещения Vault/Trial Spawner по острову (ConcurrentHashMap/AtomicInteger)
│       ├── IslandVaultTrialGenerator.java — общая логика размещения Vault/Trial Spawner (NBT BlockEntity) внутри острова
│       ├── Layer2VaultTrialPlacer.java    — точка входа генерации Vault/Trial для островов Layer 2
│       ├── Layer3VaultTrialPlacer.java    — точка входа генерации Vault/Trial для эллипсоидов Layer 3
│       ├── Layer4VaultTrialPlacer.java    — точка входа генерации Vault/Trial для "медуз" Layer 4
│       ├── VaultTrialLootConfig.java      — набор ссылок на loot table (datapack JSON) + список мобов Trial Spawner для одного профиля генерации
│       └── VaultTrialSpawnTier.java       — тир "богатства" спавна на острове (POOR/MEDIUM/RICH), количество Vault/Trial Spawner
├── layer/
│   ├── Layer1FlatGenerator.java     — [УСТАРЕЛО] Изначально кастомная генерация Layer 1, теперь код заполнения блоков не используется; делегируется ванильному генератору.
│   ├── LowerIslandGenerator.java    — Layer 2: острова + деревья + мосты
│   ├── HighIslandGenerator.java     — Layer 3: эллипсоиды
│   ├── UpperIslandGenerator.java    — Layer 4: медузы/щупальца
│   ├── Layer2StructurePlacer.java   — ставит tank21 в очередь
│   └── MountainForestScatter.java   — отдельный проход декорации: крупные разнообразные деревья по всему склону гор Layer 1 (не через ванильную декорацию биома)
├── noise/
│   ├── AeroNoise.java          — лёгкая OpenSimplex2-подобная реализация шума без внешних библиотек
│   ├── IslandPlacer.java       — детерминированная seed-based сетка размещения островов (грид по чанкам, минимальный интервал между островами)
│   └── IslandShape.java        — SDF-хелперы формы островов с профилями (linear/convex/concave/stepped) и per-island edge noise
├── structure/                 — валидация структур (деревни и т.п.) под кастомный рельеф
│   ├── StructureCategory.java         — категория структуры (SURFACE/ISLAND/UNDERGROUND/WATER/SKY_FLOATING), определяет тип валидации
│   ├── StructureCategoryResolver.java — определяет StructureCategory для конкретной ванильной структуры
│   ├── StructureSupportValidator.java — ★ главный класс валидации (вызывается из createStructures)
│   ├── SupportSample.java             — record(x, z): точка сетки, где проверка опоры провалилась (для диагностики)
│   ├── TerrainColumnSampler.java      — сэмплирует столбец рельефа по всем слоям (Layer1FlatGenerator/Lower/High/Upper) для валидации
│   └── ValidationResult.java          — результат валидации размещения (accepted + диагностика, список несовпадений)
└── util/
    ├── ChunkAccessWriter.java         — реализация ChunkWriter поверх ChunkAccess.setBlockState
    ├── ChunkWriter.java                — интерфейс записи/чтения блоков по мировым координатам
    └── SectionDirectChunkWriter.java  — пишет блоки напрямую в LevelChunkSection, минуя ChunkAccess.setBlockState и обновление heightmap
```

---

## 3. Жизненный цикл генерации одного чанка

Порядок вызовов в пайплайне NeoForge/Minecraft:

1. **`fillFromNoise`**
   - Layer 1: делегируется родительскому классу `super.fillFromNoise()` (ванильная amplified генерация 3D-рельефа).
   - Layer 2, 3, 4: `lowerIslands/highIslands/upperIslands.fillChunk()`.
   - Размещение структур в очередь (HAUL-01, tank21).

2. **`applyCarvers`**
   - Вызывает `super.applyCarvers()` (ванильные пещеры режут рельеф).
   - **Сразу после этого** вызывает `restoreIslandsInChunk()` — перегенерирует острова, чтобы ванильные пещеры не порвали тонкие мосты/щупальца.
   - Затем `SinkholeCarver.carveChunk()` — карстовые воронки под островами 2/3/4.

3. **`buildSurface`**
   - Layer 1: делегируется `super.buildSurface()` — корректные биомные правила поверхности (снег, песок, гравий).

4. **`applyBiomeDecoration`**
   - Ванильная декорация поверхности + деревья Layer 2.
   - `MountainForestScatter.scatterForChunk()` — дополнительный проход крупных деревьев по горам Layer 1.
   - Размещение Vault/Trial Spawner на островах слоев 2, 3 и 4.
   - **`Layer1OreFilter.applyToChunk()`** очищает чанк от любой сгенерированной руды (отключена во всем мире).

5. **`createStructures`**
   - Проверка легальности ванильных структур через `StructureSupportValidator`.

---

## 4. Размещение кастомных структур (tank21)

- **`tank21`** — NBT структура (Layer 2), регистрируется через `PhysicalStructures`.

Поток: C2ME WorldGen Поток -> `IslandStructureScheduler` -> `PendingStructureData` (SavedData) -> Игрок подходит близко (`ProximityTriggerHandler`) -> `StructurePlacementHelper` -> `spawnStructureResult`.

Дополнительно: `SpawnerProximityHandler` — тиковый поиск блока `physical_structures:structure_spawner` рядом с игроком в Layer 2 (Y 250-450, интервал 20 тиков, радиус поиска чанков 2). `AeroStructureListener` слушает `PhysicalStructurePlacedEvent` от `physical_structures` — звук/эффекты после того, как структура физически собрана (Sable).

Для прегенерации без игроков: `/aeroworld forcePlacePending`.

---

## 5. Известные особенности текущей архитектуры

1. **Наследование генератора:** Класс `AeroWorldChunkGenerator` наследуется от `NoiseBasedChunkGenerator`, чтобы движок Minecraft (через `RandomState.create()`) корректно считывал настройки шумов (например, профиль `minecraft:amplified`).
2. **Layer 1 полностью ванильный:** Старый самописный класс `Layer1FlatGenerator` больше не используется для заливки блоков и покраски поверхности (его методы `fillChunk` и `applyLayer1Surface` обойдены); используется только как источник данных для `TerrainColumnSampler`.
3. **Руда отключена постфактум:** Генератор руды слоёв (`OreVeinHelper`) сейчас не вызывается из пайплайна, а `Layer1OreFilter` активно вырезает всё, что могла добавить ванильная декорация — включая ваниль Layer 1.
4. **Vault / Trial Spawner:** Находятся внутри островов (Layer 2/3/4). Логика спавна использует ванильные лут-таблицы в ресурсах датапака, а размещение происходит напрямую в `applyBiomeDecoration` через ручную сборку NBT `BlockEntity` (`IslandVaultTrialGenerator` + per-layer `Layer{2,3,4}VaultTrialPlacer`, прогресс кэшируется в `IslandVaultTrialCache`).
5. **Прямая запись в чанк:** Для параллельной (C2ME) генерации без гонок по heightmap используется `SectionDirectChunkWriter`, обходящий побочные эффекты `ChunkAccess.setBlockState`.

---

## 6. Ресурсы датапака

- **`aeroworld.json` (world_preset):** Главный пресет мира. Внутри `vanilla_generator` установлен параметр `"settings": "minecraft:amplified"`.
- **Биомы:** `worldgen/biome/*.json` содержат клоны ванильных биомов без руды.
- **Лут таблицы:** `loot_table/gameplay/layerN/` содержит дроп для Vaults и Trial Spawners.

---

## 7. С чего начать при доработке

- **Настройка высоты / формы слоев:** `LayerNSettings.java` (параметры) + соответствующий `*IslandGenerator.java` (геометрия — `worldgen/noise/IslandShape.java`, `IslandPlacer.java`).
- **Генерация поверхности Layer 1:** Зависит теперь полностью от ванильного Amplified шума. Ищите изменения в датапаках плотности или биомах.
- **Дроп на островах:** Только через редактирование `.json` лут таблиц в `resources/data/aeroworld/loot_table/...`, либо через `VaultTrialLootConfig` для привязки таблиц к профилю слоя.
- **Проблемы с ванильными структурами:** `StructureSupportValidator.java` и `StructureCategoryResolver.java` (если подводная или надземная структура генерируется не там).
- **Конфиг клиента:** `AeroWorldConfig.java` — `config/aeroworld-client.toml`, применяется без перезапуска игры.
