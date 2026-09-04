# AeroWorld — Project Index

Мод для Minecraft 1.21.1 (NeoForge 21.1.228, mod_version 1.0.12) — кастомное измерение с многослойной генерацией мира: поверхность + три слоя парящих островов. Зависимости (compileOnly): `physical_structures` (структуры/сборка) и `DistantHorizonsApi`. Код поддержки `create_aeronautics_toolgun` и структуры `HAUL-01.excraft` полностью удалён.

Пакет: `org.example.aeroworld`. Корень исходников: `src/main/java/org/example/aeroworld/`.

---

## 1. Слои мира — быстрый обзор

| Слой | Y-диапазон | Форма | Генератор | Настройки |
|---|---|---|---|---|
| Layer 1 | -64 .. 300 | Полноценный ванильный рельеф Overworld (горы, 3D-пещеры, аквиферы, океаны) | `NoiseBasedChunkGenerator` (встроен в `AeroWorldChunkGenerator`) | `dimension/aeroworld.json`, `world_preset/aeroworld.json` (`settings: minecraft:overworld`) |
| Layer 2 | 400 .. 500 | Острова произвольной формы + сталактиты + мосты | `worldgen/layer/LowerIslandGenerator.java` | `config/Layer2Settings.java` |
| Layer 3 | 1000 .. 1100 | Шары и эллипсоиды | `worldgen/layer/HighIslandGenerator.java` | `config/Layer3Settings.java` |
| Layer 4 | 1900 .. 2031 | "Медузы" (купол + 10 щупалец) | `worldgen/layer/UpperIslandGenerator.java` | `config/Layer4Settings.java` |

Все четыре слоя координируются классом:
**`worldgen/AeroWorldChunkGenerator.java`** — точка входа в генерацию чанков (`fillFromNoise`, `applyCarvers`, `buildSurface`, `applyBiomeDecoration`, `createStructures`). Наследуется напрямую от **`NoiseBasedChunkGenerator`**, чтобы обеспечить корректную инициализацию `RandomState` (плотность шумов, параметры биомов) для нижнего ванильного слоя (Layer 1).

---

## 2. Карта пакетов

```
org.example.aeroworld
├── AeroWorld.java                 — главный класс мода (@Mod), регистрация шины событий, конфигов, структур tank21
├── client/
│   └── AeroWorldClientEvents.java — @EventBusSubscriber(CLIENT), заготовка под ScreenEvent.Init.Post
├── command/
│   └── AeroWorldCommands.java     — /aeroworld forcePlacePending, findIsland4, findIsland3, findIsland2 [type] [tier]
├── config/
│   ├── AeroWorldConfig.java       — ModConfigSpec (config/aeroworld-client.toml)
│   ├── AeroWorldSettings.java     — root-record {layer2, layer3, layer4}, сериализуется в dimension JSON
│   └── Layer2/3/4Settings.java    — параметры геометрии островов соответствующих слоёв
├── event/
│   ├── AeroStructureListener.java     — слушает PhysicalStructurePlacedEvent (physical_structures), звук размещения
│   ├── ProximityTriggerHandler.java   — спавн структур при приближении игрока (до 96 блоков XZ, экспоненциальный backoff, retry до 10 раз)
│   ├── ShorelineWaveHandler.java      — симуляция наката прибрежных волн на пляжах Layer 1
│   └── SpawnerProximityHandler.java   — поиск structure_spawner (physical_structures) рядом с игроком в Layer 2 (Y 250-450)
├── registry/
│   ├── AeroDimensions.java        — DeferredRegister CHUNK_GENERATOR: aero_generator (AeroWorldChunkGenerator.CODEC)
│   ├── AeroRegistries.java        — точка регистрации CHUNK_GENERATORS и BIOME_SOURCES (aero_biome_source)
│   ├── AeroResourceKeys.java      — ResourceKey для DimensionType, LevelStem и Level измерения aeroworld:aeroworld
│   └── AeroWorldPreset.java       — класс-документация: WorldPreset регистрируется через datapack JSON
├── spawning/
│   └── LayerSpawnRestriction.java — отменяет спавн мобов на Layer 4 (Y >= UpperIslandGenerator.LAYER_MIN_Y = 1900)
└── structure/
    ├── IslandStructureScheduler.java  — потокобезопасная очередь размещения структур (C2ME-безопасная дедупликация)
    ├── PendingStructureData.java      — SavedData: персистентная очередь структур с экспоненциальным backoff (record Entry)
    ├── StructurePlacementHelper.java  — чтение размера NBT, проверка статуса чанков FULL и свободного места
    └── StructureSizeCache.java        — кэш размеров NBT-структур со сбросом при reload датапаков
```

### worldgen/

```
worldgen/
├── AeroWorldChunkGenerator.java   — ★ главный генератор, расширяет NoiseBasedChunkGenerator, координирует все слои
├── biome/
│   ├── AeroBiomeRegistryCache.java — асинхронный кэш Registry<Biome> (CompletableFuture, заполняется в ServerAboutToStartEvent)
│   └── AeroBiomeSource.java       — кастомный BiomeSource: Layer 1 возвращает ванильные minecraft:* биомы (SurfaceRules) + deep_dark на глубине; острова (Y > 300) получают aeroworld:* клоны без океанов/пещер
├── cache/
│   ├── ChunkIslandCache.java   — общий кэш списков центров островов (layerId + chunkX/Z) для всех 3 слоёв
│   ├── ChunkKey.java           — упаковка пары (x, z) в long без аллокаций
│   ├── IslandCache.java        — потокобезопасный кэш геометрии островов (Y-bounds, radius, оси эллипсоида, щупальца)
│   └── IslandData.java         — иммутабельный value-object острова (bounds, radius, geometry)
├── carver/
│   └── SinkholeCarver.java     — карстовые воронки Layer 1 (шанс 1/12 на чанк, фильтр карстовых биомов без океанов/рек/пляжей, кэш высот поверхности, прямая запись в LevelChunkSection)
├── feature/
│   ├── Layer1OreFilter.java    — O(1) failsafe проверка палитры; шаг UNDERGROUND_ORES полностью отключён в applyBiomeDecoration
│   └── vault/                  — генерация Vault и Trial Spawner внутри тела островов (Layer 2/3/4)
│       ├── IslandVaultTrialCache.java     — потокобезопасный общий кэш прогресса размещения Vault/Trial по островам
│       ├── IslandVaultTrialGenerator.java — общая логика размещения Vault/Trial Spawner (NBT BlockEntity) внутри островам
│       ├── Layer2VaultTrialPlacer.java    — точка входа Vault/Trial для Layer 2 (POOR 50%, MEDIUM 35%, RICH 15%)
│       ├── Layer3VaultTrialPlacer.java    — точка входа Vault/Trial для эллипсоидов Layer 3
│       ├── Layer4VaultTrialPlacer.java    — точка входа Vault/Trial для куполов "медуз" Layer 4
│       ├── VaultTrialLootConfig.java      — конфигурация ссылок на loot tables и списков мобов для Trial Spawner
│       └── VaultTrialSpawnTier.java       — тиры богатства спавна (POOR / MEDIUM / RICH)
├── layer/
│   ├── Layer1FlatGenerator.java     — хелпер границ Layer 1 (-64..300) и делегат сэмплинга высот (surfaceHeight/topmostHeight); генерация блоков удалена
│   ├── LowerIslandGenerator.java    — Layer 2 (Y 400..500): острова + деревья по краям (0.6..1.0 радиуса) + сталактиты снизу + мосты (кэширование пар BridgePair на остров, AABB-фильтр чанка, fillBridges вынесен из цикла по островам; центральная зона и деревья с суженными циклами и ранним отсевом)
│   ├── HighIslandGenerator.java     — Layer 3 (Y 1000..1100): шары и эллипсоиды (аналитический расчет диапазона Y по формуле эллипсоида, без поблочного сканирования; суженные XZ-циклы)
│   ├── UpperIslandGenerator.java    — Layer 4 (Y 1900..2031): медузы (прямая растровая трассировка сплайнов щупалец в AABB чанка, суженный цикл купола без лишних шумов)
│   └── Layer2StructurePlacer.java   — постановка tank21 в очередь только на обычных островах с тиром RICH
├── noise/
│   ├── AeroNoise.java          — Perlin/Simplex шум и FBM без сторонних библиотек
│   ├── IslandPlacer.java       — детерминированная сетка размещения островов с пространственным AABB-отсевом (clamp ячеек сетки и центров/спутников по maxInfluence)
│   └── IslandShape.java        — SDF-профили островов (linear/convex/concave/stepped) и per-island edge noise
├── structure/                 — валидация структур под кастомный многослойный рельеф
│   ├── StructureCategory.java         — категории: SURFACE, ISLAND, UNDERGROUND, WATER, SKY_FLOATING, DENY
│   ├── StructureCategoryResolver.java — классификация структур по ID и фактическому слою (resolveForActualLayer)
│   ├── StructureSupportValidator.java — валидация структур; вызывается из createStructures и applyBiomeDecoration (failsafe для Distant Horizons); разрешает WATER на Layer 1 при наличии дна
│   ├── SupportSample.java             — record(x, z): точка сетки сэмплов с проваленной опорой
│   ├── TerrainColumnSampler.java      — сэмплирование опоры рельефа с глубиной сканирования 24 блока и определение фактического слоя
│   └── ValidationResult.java          — результат валидации размещения (accepted + диагностика)
└── util/
    ├── ChunkAccessWriter.java         — реализация ChunkWriter поверх ChunkAccess.setBlockState
    ├── ChunkWriter.java               — интерфейс записи/чтения блоков по мировым координатам
    └── SectionDirectChunkWriter.java  — прямая запись в LevelChunkSection в обход setBlockState и обновления heightmap (C2ME safe)
```

---

## 3. Жизненный цикл генерации одного чанка

Порядок вызовов в пайплайне NeoForge/Minecraft:

1. **`createBiomes`**
   - Вызывает `init(randomState)`.
   - Делегирует ванильному пайплайну через `super.createBiomes(...)`.

2. **`createStructures`**
   - Вызывает `super.createStructures(...)`.
   - Ленивая инициализация `initializeWithSeed(structureState.getLevelSeed())`.
   - Валидация структур через `StructureSupportValidator`: проверка `chunk.getAllStarts()`, затем `chunk.getAllReferences()`. Невалидные структуры заменяются на `StructureStart.INVALID_START`.

3. **`fillFromNoise`**
   - Layer 1 (при пересечении Y -64..300): делегируется `vanillaGenerator.fillFromNoise()` (полноценный ванильный рельеф Overworld).
   - Layer 2, 3, 4: прямое последовательное заполнение `fillChunk()` в рабочем потоке чанка через `SectionDirectChunkWriter` без оверхеда `CompletableFuture` и пула потоков.
   - На Layer 2: вызов `Layer2StructurePlacer.placeForChunk` — постановка `tank21` в очередь для островов с тиром RICH.

4. **`applyCarvers`**
   - Установка маски защиты островов (`CarvingMask.setAdditionalMask((cx, cy, cz) -> cy >= 320)`), предотвращающая карвинг ванильными пещерами и каньонами блоков выше Y=320.
   - `vanillaGenerator.applyCarvers()` — нарезка ванильных пещер и каньонов (строго ниже Y=320).
   - `SinkholeCarver.carveChunk()` — вырезание карстовых воронок в рельефе Layer 1 (Y < 300, шаг AIR).
   - `restoreIslandsInChunk` полностью удалён — двойная генерация блоков островов устранена.

5. **`buildSurface`**
   - Делегируется `vanillaGenerator.buildSurface()` — стандартные ванильные SurfaceRules (песок в пустынях, терракота в бэдлендсах, снег, гравий и т.д.).

6. **`applyBiomeDecoration`**
   - `super.applyBiomeDecoration()` — ванильная декорация биомов (дублирующая валидация структур удалена, структуры проверяются на этапе `createStructures`).
   - `lowerIslands.clearVanillaVegetationInCentralZone()` — очистка центральной зоны островов Layer 2 от ванильной растительности (с быстрым AABB-отсевом островов).
   - `lowerIslands.placeTreesInRegion()` — размещение листвы деревьев Layer 2 в регионе 3×3 чанка (с быстрым AABB-отсевом островов).
   - Размещение Vault / Trial Spawner через `layer2VaultTrialPlacer`, `layer3VaultTrialPlacer`, `layer4VaultTrialPlacer`.
   - `Layer1OreFilter.applyToChunk()` — удаление остаточных руд с прямой записью в палитру LevelChunkSection без накладных расходов ChunkAccess (-64..320).
   - Автоматическое LRU-управление кэшем центров островов (`ChunkIslandCache`, емкость 4096 слотов) без преждевременного ручного сброса, предотвращающее повторный расчет при последующих вызовах `getBaseHeight` / `getBaseColumn` / спавна мобов.

---

## 4. Размещение кастомных структур (tank21)

- **`tank21`** — NBT структура (Layer 2), регистрируется через API `physical_structures` с задержкой сборки Sable 20 тиков.
- Очередь спавна: C2ME WorldGen поток (`Layer2StructurePlacer`) -> `IslandStructureScheduler` -> `PendingStructureData` (SavedData) -> игрок приближается на расстояние <= 96 блоков XZ (`ProximityTriggerHandler`) -> `StructurePlacementHelper` -> `PhysicalStructures.spawnStructureResult`.
- Принудительный спавн для прегенерации (перед экспортом в Voxy / LOD): `/aeroworld forcePlacePending`.
- Интерактивный триггер: `SpawnerProximityHandler` раз в 20 тиков ищет блок `physical_structures:structure_spawner` в радиусе 10 блоков от игрока на высотах Y 250..450.

---

## 5. Особенности архитектуры и взаимодействия систем

1. **Генератор чанков:** Наследуется от `NoiseBasedChunkGenerator`, проксируя вызовы Layer 1 в инкапсулированный `vanillaGenerator`.
2. **C2ME и многопоточность:**
   - Поля генераторов слоёв и пласеров помечены `volatile`.
   - Генерация геометрии островов в `fillFromNoise` распараллелена через `SectionDirectChunkWriter`.
   - Общие кэши `ChunkIslandCache` и `IslandVaultTrialCache` пересоздаются при инициализации seed.
3. **Биомная система:**
   - В Layer 1 `AeroBiomeSource` возвращает оригинальные `minecraft:*` биомы, что обеспечивает работу ванильных SurfaceRules. Подземная зона Y ∈ [-64, -8] содержит участки `minecraft:deep_dark` для спавна Ancient City.
   - Для островных слоёв (Y > 300) биомы подменяются на `aeroworld:*` клоны, а океанические и пещерные биомы замещаются на `aeroworld:plains`.
   - Реестр биомов асинхронно кэшируется в `AeroBiomeRegistryCache`.
4. **Удаление руд (двухуровневое):**
   - Уровень датапака: `data/aeroworld/neoforge/biome_modifier/remove_ores.json` (`neoforge:remove_features`) вырезает руды из тега `#aeroworld:aero_biomes`.
   - Уровень генерации: `Layer1OreFilter` пост-фактум сканирует все секции чанка и заменяет любые блоки руды на камень/глубинный сланец.
5. **Валидация структур:**
   - Категории определяются через `StructureCategoryResolver.resolveForActualLayer` на основе данных `TerrainColumnSampler`, а не только по высоте Y.
   - Водные структуры (`WATER`) разрешены на Layer 1 при условии твёрдого основания на дне океана, но запрещены на парящих островах.
   - Двойная проверка: при создании структур (`createStructures`) и перед биомной декорацией (`applyBiomeDecoration`).
6. **Команды поиска островов:**
   - `/aeroworld findIsland2 [normal|archipelago_centre|satellite] [POOR|MEDIUM|RICH]` — поиск островов Layer 2 по сетке с фильтрацией по типу и тиру.
   - `/aeroworld findIsland3` — независимый поиск эллипсоидов Layer 3.
   - `/aeroworld findIsland4` — поиск медуз Layer 4.

---

## 6. Ресурсы датапака

- **`dimension/aeroworld.json`:** Определение измерения, генератор `aeroworld:aero_generator`, vanilla settings `minecraft:overworld`, настройки геометрии слоёв `aero_settings`.
- **`world_preset/aeroworld.json`:** Замена overworld на измерение `aeroworld:aeroworld` с `minecraft:overworld` настройками шума.
- **`dimension_type/aeroworld.json`:** `min_y: -64`, `height: 2096`, `logical_height: 2096`.
- **`neoforge/biome_modifier/remove_ores.json`:** Удаление ванильных рудных фичей из биомов `#aeroworld:aero_biomes`.
- **`loot_table/gameplay/layer{2,3,4}/`:** Лут-таблицы обычных и зловещих Vaults и Trial Spawners.
- **`presets/*.json`:** Пресеты конфигурации островов (`default`, `dense_archipelago`, `grand_isolation`, `skyblock_classic`, `vast_wilderness`).