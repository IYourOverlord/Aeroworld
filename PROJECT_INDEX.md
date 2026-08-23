# AeroWorld — Project Index

Мод для Minecraft 1.21.1 (NeoForge 21.1.227) — кастомное измерение с
многослойной генерацией мира: поверхность + три слоя парящих островов.
Зависит от мода `physical_structures` (структуры/сборки) и опционально от
`create_aeronautics_toolgun` (формат `.excraft`) и `DistantHorizonsApi`
(есть в `libs/`, но в коде не используется — не найдено импортов/классов).

Пакет: `org.example.aeroworld`. Корень исходников: `src/main/java/org/example/aeroworld/`.

---

## 1. Слои мира — быстрый обзор

| Слой | Y-диапазон | Форма | Генератор | Настройки |
|---|---|---|---|---|
| Layer 1 | -64 .. 300 | ванилла-подобный рельеф (горы, реки, озёра, океаны, пещеры) | `worldgen/layer/Layer1FlatGenerator.java` | нет отдельного settings-класса (константы в коде) |
| Layer 2 | 400 .. 500 | острова произвольной формы + мосты | `worldgen/layer/LowerIslandGenerator.java` | `config/Layer2Settings.java` |
| Layer 3 | 1000 .. 1100 | шары/эллипсоиды | `worldgen/layer/HighIslandGenerator.java` | `config/Layer3Settings.java` |
| Layer 4 | 1900 .. 2031 | "медузы" (купол + щупальца) | `worldgen/layer/UpperIslandGenerator.java` | `config/Layer4Settings.java` |

Все четыре слоя координируются одним классом:
**`worldgen/AeroWorldChunkGenerator.java`** (666 строк) — точка входа
в генерацию чанков (`fillFromNoise`, `applyCarvers`, `applyBiomeDecoration`,
`getBaseHeight`, `getBaseColumn`, `createStructures`). Начинать чтение кода
генерации стоит именно отсюда.

Полный диапазон мира: Y -64..2099 (задан в `dimension_type/aeroworld.json`,
`height: 2096`, `min_y: -64`; в Java-коде это `FULL_HEIGHT`/`getGenDepth()=2164`,
т.к. используется `-64 + 2164 - 1 = 2099`, с небольшим запасом сверху для NoiseColumn).

---

## 2. Карта пакетов

```
org.example.aeroworld
├── AeroWorld.java                 — главный класс мода (@Mod), регистрация всего
├── client/
│   └── AeroWorldClientEvents.java — заглушка для клиентских UI-хуков (пока пусто)
├── command/
│   └── AeroWorldCommands.java     — /aeroworld forcePlacePending, /aeroworld findIsland4
├── config/
│   ├── AeroWorldConfig.java       — NeoForge ModConfigSpec (сейчас пустой, только регистрация)
│   ├── AeroWorldSettings.java     — root-record {layer2, layer3, layer4}, сериализуется в dimension JSON
│   ├── Layer2Settings.java        — параметры Layer 2 (radius, height, bridges...)
│   ├── Layer3Settings.java        — параметры Layer 3 (radius, height, noiseDeform)
│   └── Layer4Settings.java        — параметры Layer 4 (radius, height, tentacles)
├── event/
│   ├── AeroStructureListener.java     — звук/лог при сборке физической структуры
│   ├── ProximityTriggerHandler.java   — спавн структур когда игрок рядом (426 строк, ключевой файл)
│   ├── ShorelineWaveHandler.java      — визуальная симуляция наката волн на пляже
│   └── SpawnerProximityHandler.java   — триггер блок-спаунеров physical_structures (⚠ НЕ зарегистрирован в AeroWorld.java, см. раздел 6)
├── registry/
│   ├── AeroDimensions.java        — DeferredRegister для ChunkGenerator codec
│   ├── AeroRegistries.java        — точка регистрации всех DeferredRegister'ов мода
│   ├── AeroResourceKeys.java      — ResourceKey для DimensionType/LevelStem/Level
│   └── AeroWorldPreset.java       — пустышка-документация (world preset регистрируется через JSON, не Java)
├── spawning/
│   └── LayerSpawnRestriction.java — блокирует спавн мобов на Layer 4 (Y ≥ 2000)
├── structure/
│   ├── IslandStructureScheduler.java  — потокобезопасная очередь (worldgen-поток → SavedData)
│   ├── PendingStructureData.java      — SavedData: персистентная очередь структур с backoff/retry
│   ├── StructurePlacementHelper.java  — проверка готовности чанков и свободного места
│   └── StructureSizeCache.java        — кэш размеров NBT-структур (инвалидируется по /reload)
└── worldgen/
    ├── AeroWorldChunkGenerator.java   — ★ главный ChunkGenerator, координирует все слои
    ├── VanillaHeightChunkWrapper.java — ⚠ похоже, не используется (нет ссылок из остального кода)
    ├── biome/
    │   ├── AeroBiomeRegistryCache.java — кэш полного Registry<Biome> (нужен чтобы видеть клоны aeroworld:*)
    │   └── AeroBiomeSource.java        — кастомный BiomeSource, подменяет ванильные биомы на aeroworld:* клоны без руды
    ├── cache/
    │   ├── ChunkIslandCache.java   — общий кэш списков центров островов (все 3 слоя, один map)
    │   ├── ChunkKey.java           — упаковка (x,z) в long
    │   ├── IslandCache.java        — LRU-кэш вычисленных IslandData по острову
    │   └── IslandData.java         — иммутабельный value-object острова (bounds, radius, ellipsoidAxes/tentacleData)
    ├── feature/                    — генерация руды (⚠ вызовы закомментированы, см. раздел 6)
    │   ├── Layer1OreFilter.java    — активен: чистит ЛЮБУЮ руду по всей высоте чанка
    │   ├── Layer1OreGenerator.java — уголь/железо/медь/золото, Y -40..10
    │   ├── Layer2OreGenerator.java — +редстоун, Y 300..400, 2x vein size
    │   ├── Layer3OreGenerator.java — то же без алмаз/лазурит/изумруд, Y 1000..1100, x2 attempts
    │   ├── Layer4OreGenerator.java — ТОЛЬКО алмаз/лазурит/изумруд, Y 1900..2031
    │   ├── OreVeinHelper.java      — общая сферическая жила (используется Layer1-3, у Layer4 своя копия)
    │   └── vault/                  — генерация Vault/Trial Spawner внутри тела острова (Layer 2/3/4; см. раздел 4a)
    │       ├── IslandVaultTrialGenerator.java — ★ переиспользуемое ядро: постановка блока + NBT
    │       │   (`placeVault`/`placeTrialSpawner`/`buildSpawnerConfig`), сферическая расчистка воздуха
    │       │   вокруг (`clearAboveBlock`, радиус синхронизирован с NBT `spawn_range`). Три метода
    │       │   поиска точки под три разных геометрии слоёв: `findBuriedSpot` (Layer 2,
    │       │   `IslandShape.isSolid`/`precomputeXZ`), `findBuriedSpotEllipsoid` (Layer 3, эллипсоид через
    │       │   `HighIslandGenerator.getEllipsoidTopY`/`getEllipsoidBottomY`), `findBuriedSpotCap` (Layer 4,
    │       │   купол через `UpperIslandGenerator.getCapTopY`/`getCapBottomY` — щупальца исключены, слишком
    │       │   тонкие для структур). Три публичные точки входа — `placeForIsland`/`placeForEllipsoidIsland`/
    │       │   `placeForJellyfishIsland` — каждая вызывает свой метод поиска, но переиспользует общую
    │       │   постановку блока/NBT/расчистку.
    │       ├── Layer2VaultTrialPlacer.java / Layer3VaultTrialPlacer.java / Layer4VaultTrialPlacer.java —
    │       │   Layer-специфичные точки входа: выбор `VaultTrialSpawnTier` по детерминированному хэшу
    │       │   координат острова (соль RNG своя на каждый слой, чтобы не совпасть детерминированно),
    │       │   вероятности тиров (`POOR/MEDIUM/RICH_CHANCE`, у всех трёх слоёв сейчас 50/35/15), вызывают
    │       │   соответствующий `placeFor*` с `VaultTrialLootConfig.LAYER_2`/`LAYER_3`/`LAYER_4`.
    │       ├── VaultTrialSpawnTier.java — enum POOR(1 vault,1 trial) / MEDIUM(2,3) / RICH(3,5).
    │       └── VaultTrialLootConfig.java — record: ссылки на loot table JSON (vault/trial × normal/ominous)
    │           + список `spawnPotentials` (мобы Trial Spawner, одинаковый набор zombie/skeleton/spider/husk
    │           на всех трёх слоях). `LAYER_2` (медь/железо/уголь), `LAYER_3` (золото/редстоун/железо),
    │           `LAYER_4` (алмаз/изумруд/лазурит: блоки в Vault, сырые предметы в Trial Spawner).
    ├── layer/
    │   ├── Layer1FlatGenerator.java     — ★★ САМЫЙ БОЛЬШОЙ ФАЙЛ (1454 строки). Рельеф поверхности
    │   ├── Layer1SinkholeCarver.java    — карстовые воронки под островами 2/3/4, карвятся в Layer 1
    │   ├── Layer1CoralScatter.java      — редкие кораллы на пляжах у кромки воды
    │   ├── LowerIslandGenerator.java    — Layer 2: острова + деревья + мосты (LAYER_ID=0)
    │   ├── HighIslandGenerator.java     — Layer 3: эллипсоиды (LAYER_ID=1)
    │   ├── UpperIslandGenerator.java    — Layer 4: медузы/щупальца (LAYER_ID=2)
    │   ├── Layer2StructurePlacer.java   — ставит tank21 в очередь IslandStructureScheduler
    │   └── Layer3StructurePlacer.java   — ставит excraft:HAUL-01 в очередь
    ├── noise/
    │   ├── AeroNoise.java     — Perlin-подобный шум (2D/3D), fbm2D/fbm3D
    │   ├── IslandPlacer.java — детерминированная сетка размещения островов по seed
    │   └── IslandShape.java  — SDF-подобная форма острова, precomputeXZ/isSolid, per-island профиль
    └── structure/                 — валидация ванильных структур (деревни и т.п.) под кастомный рельеф
        ├── StructureCategory.java          — enum: SURFACE/ISLAND/UNDERGROUND/WATER/SKY_FLOATING/DENY
        ├── StructureCategoryResolver.java  — классификация id структуры → категория
        ├── StructureSupportValidator.java  — ★ главный класс валидации (вызывается из createStructures)
        ├── StructureCavityCarver.java      — вырезает воздушную полость под ванильными jigsaw-структурами
        │   (деревни, ancient_city и т.п.) в Layer 1 ДО того, как рельеф зальёт их объём сплошным камнем
        │   (`carveForChunk`, вызывается в `fillFromNoise` сразу после `layer1.fillChunk()`) — иначе
        │   пространство между jigsaw-пьесами остаётся закрашено камнем (см. javadoc класса)
        ├── TerrainColumnSampler.java       — детерминированные "будет ли здесь твёрдый блок" без чтения мира
        ├── SupportSample.java              — diag record (x,z)
        └── ValidationResult.java           — результат валидации + причина отказа
```

---

## 3. Жизненный цикл генерации одного чанка

Вызовы `AeroWorldChunkGenerator` в порядке, в котором их вызывает ванильный
пайплайн NeoForge/Minecraft:

1. **`fillFromNoise`** — основной проход:
   - Layer 1: `layer1.fillChunk()` → рельеф; сразу затем
     `StructureCavityCarver.carveForChunk()` (вырезает полость под ванильными
     jigsaw-структурами, пока рельеф не залил их объём камнем — см. javadoc
     класса), затем `sinkholeCarver.carveChunk()`
     (воронки) и `coralScatter.scatter()` (кораллы).
   - Layer 2: `lowerIslands.fillChunk()`, затем `structurePlacer.placeForChunk()`
     (ставит tank21 в очередь).
   - Layer 3: `highIslands.fillChunk()`, затем `layer3StructurePlacer.placeForChunk()`
     (ставит excraft:HAUL-01 в очередь).
   - Layer 4: `upperIslands.fillChunk()`.
   - **Важно**: структуры регистрируются здесь, а не в `applyBiomeDecoration`,
     потому что последний вызывается только для чанков рядом с игроком —
     см. комментарии в коде про C2ME/прегенерацию.

2. **`applyCarvers`** — вызывает `vanillaGenerator.applyCarvers()` (ванильные
   пещеры режут всё, включая острова), затем **безусловно**
   `restoreIslandsInChunk()` — перегенерирует острова поверх повреждений
   от carver'а (иначе тонкие мосты/щупальца ломаются carver'ом навсегда).

3. **`buildSurface`** → `applyLayer1Surface()` — красит верхний блок Layer 1
   (grass/sand/red_sand+terracotta) по биому, пропускает колонки с водой
   (те уже покрашены в `fillChunk`).

4. **`applyBiomeDecoration`** — вызывает ванильную декорацию (деревья, трава
   и т.д. по biome features), затем `lowerIslands.placeTreesInRegion()`
   (листья деревьев Layer 2, т.к. они выходят за границы чанка), затем
   по очереди `layer2VaultTrialPlacer.placeForChunk()`,
   `layer3VaultTrialPlacer.placeForChunk()`, `layer4VaultTrialPlacer.placeForChunk()`
   (Vault/Trial Spawner на островах всех трёх слоёв, замурованы/встроены
   в тело/купол острова — требует `WorldGenLevel`, а не `ChunkAccess`, ради
   `registryAccess()` при инициализации NBT blockEntity, поэтому не может
   вызываться из `fillFromNoise`; см. раздел 4a), затем
   **`Layer1OreFilter.applyToChunk()`** — чистит любую руду по всей высоте
   чанка (единственный активный шаг работы с рудой, см. раздел 6).

5. **`createStructures`** — после ванильной генерации структур прогоняет
   каждую через `StructureSupportValidator.validate()`; непрошедшие
   помечаются `StructureStart.INVALID_START`.

6. **`getBaseHeight` / `getBaseColumn`** — используются другими системами
   (heightmap, mob spawn, structure placement) для получения высоты без
   реальной генерации чанка; проверяют острова Layer 4→3→2, затем Layer 1.

---

## 4. Жизненный цикл размещения структур (tank21 / HAUL-01)

Две разные структуры, два разных механизма доставки, общая очередь:

- **`tank21`** — обычная ванильная NBT-структура (`physical_structures:tank21`),
  ставится на Layer 2. Регистрируется в `AeroWorld.registerAeroStructures()`
  через `PhysicalStructures.registerStructure()`.
- **`HAUL-01`** — файл `.excraft` (снимок Sable sub-level'а формата Toolgun,
  НЕ ванильный NBT, хотя технически тоже gzip-NBT). Ставится на Layer 3.
  Требует мод `create_aeronautics_toolgun` и физический файл
  `<gamedir>/blueprints/HAUL-01.excraft` — см. `blueprints_reference/README.md`.
  Размещается через `excraft:` namespace и `StructureSourceProviderRegistry`,
  **не** через `PhysicalStructures`.

Поток данных:
```
worldgen-поток (C2ME, может быть параллельным)
  → Layer2StructurePlacer / Layer3StructurePlacer
  → IslandStructureScheduler.enqueue(x, z, id)     [in-memory очередь, дедупликация по чанку]
       ↓ каждый тик
     IslandStructureScheduler.flushToPersistence()
  → PendingStructureData (SavedData, персистентно на диск)
       ↓ каждые 20 тиков (ProximityTriggerHandler.onLevelTick)
     если игрок в радиусе 96 блоков (XZ) и подходящего Y-диапазона:
       → findRealSurface() — ищет реальный Y поверхности острова
       → StructurePlacementHelper.areChunksReady() / isSpaceClear()
       → PhysicalStructures.spawnStructureResult()  ИЛИ  StructureSourceProviderRegistry.place() (excraft)
       → при неудаче: экспоненциальный backoff, до 10 попыток (PendingStructureData.Entry)
```

Для прегенерации (Chunky/C2ME) без живых игроков рядом с каждым островом:
команда **`/aeroworld forcePlacePending`** → `ProximityTriggerHandler.forcePlaceAll()`
обходит всю очередь без проверки дистанции/backoff. Нужно выполнять **до**
любого внешнего импорта чанков (например, Voxy) — иначе LOD-рендерер увидит
острова без структур.

---

## 4a. Vault / Trial Spawner (Layer 2 / Layer 3 / Layer 4)

Отдельная от tank21/HAUL-01/excraft подсистема — не структура в понимании
`PhysicalStructures`/`.excraft`, а обычные ванильные блоки `minecraft:vault`
и `minecraft:trial_spawner`, вставляемые с ручным NBT прямо во время
decoration. Пакет: `worldgen/feature/vault/`. Реализовано на всех трёх
слоях островов (Layer 2, 3, 4); Layer 1 (поверхность) их не имеет.

```
Layer{2,3,4}VaultTrialPlacer.placeForChunk() [вызывается из applyBiomeDecoration]
  → pickTier()  — детерминированный хэш координат острова (+ соль слоя) → POOR/MEDIUM/RICH
  → IslandVaultTrialGenerator.placeFor{Island,EllipsoidIsland,JellyfishIsland}(..., tier, LootConfig, rng, chunkX, chunkZ)
       для каждого vault/trial (tier.vaultCount()/trialSpawnerCount()):
       → findBuriedSpot{,Ellipsoid,Cap}() — геометрия-специфичный поиск точки, но общая логика:
                               семплирует точку СТРОГО внутри chunkX/chunkZ (WorldGenLevel.setBlock
                               тихо отбрасывает запись за пределами чанка-инициатора decoration),
                               MIN_SPACING от уже поставленных структур на этом острове
                               • Layer 2 (findBuriedSpot): IslandShape.isSolid/precomputeXZ,
                                 island.radius*0.7, EFFECTIVE_EXCLUSION_RADIUS вокруг tank21
                               • Layer 3 (findBuriedSpotEllipsoid): HighIslandGenerator.getEllipsoidTopY/
                                 getEllipsoidBottomY, ELLIPSOID_INNER_XZ_SQ, EFFECTIVE_EXCLUSION_RADIUS
                                 вокруг HAUL-01
                               • Layer 4 (findBuriedSpotCap): UpperIslandGenerator.getCapTopY/
                                 getCapBottomY — точки ищутся ТОЛЬКО на куполе медузы, не в щупальцах
                                 (слишком тонкие для CLEAR_RADIUS=4); нет своего блюпринта-структуры,
                                 поэтому эксклюзии вокруг центра острова нет
       → placeVault() / placeTrialSpawner() → placeBlockWithEntity() — ставит блок, вручную строит
                               BlockEntity + loadWithComponents(NBT), проверяет возврат setBlock
       → clearAboveBlock() — расчищает СФЕРУ воздуха радиусом CLEAR_RADIUS (синхронизирован с NBT
                               spawn_range=4) вокруг блока; ниже уровня блока снос грунта ограничен
                               CLEAR_BELOW_RADIUS=1 (не всей сферой) — иначе кольцевая прорезь в полу
                               острова у границы spawn_range
```

Дроп настраивается ЧЕРЕЗ ВАНИЛЬНЫЕ loot table JSON, а не в Java (проценты/
диапазоны количеств — в `resources/data/aeroworld/loot_table/gameplay/layerN/`,
см. раздел 7); Java-код (`VaultTrialLootConfig`) хранит только
`ResourceLocation`-ссылки на них + список мобов Trial Spawner
(`spawnPotentials`, вес выбора). Чтобы завести новый профиль дропа —
не трогая `IslandVaultTrialGenerator`, достаточно новых JSON + нового
`VaultTrialLootConfig`.

Текущий дроп по слоям:
- **Layer 2** — медь/железо/уголь.
- **Layer 3** — золото/редстоун/железо.
- **Layer 4** — Vault: алмазный/изумрудный/лазуритовый блок (1–2 / 3–5 / 5–8,
  шансы 50% / 25% / 25%). Trial Spawner: сырые алмаз/изумруд/лазурит
  (5–12 / 20–32 / 20–40, шансы 33% / 33% / 34%), не считая отдельно
  идущего `trial_key`.

Вероятности тиров (POOR/MEDIUM/RICH = 50%/35%/15%) и список мобов
spawn_potentials (zombie/skeleton/spider/husk) одинаковы на всех трёх слоях —
меняется только дроп (`VaultTrialLootConfig`) и геометрия поиска точки.

---

## 5. Кэши — зачем каждый нужен

| Класс | Что кэширует | Почему |
|---|---|---|
| `ChunkIslandCache` | список центров островов на (layerId, chunkX, chunkZ) | без него — 289 hash-вычислений на каждый вызов `getIslandCentresForChunk` (searchRadius=8), причём вызывается дважды за чанк (fillFromNoise + applyCarvers) |
| `IslandCache` (per-generator, LRU 512) | `IslandData` (bounds, radius, ellipsoid/tentacle) на конкретный остров | иначе пересчитывается 256 раз (на каждый блок XZ чанка) |
| `StructureSizeCache` | размер NBT-структуры (Vec3i) | избегает повторного IO/парсинга NBT на каждый retry-тик; инвалидируется на `/reload` |
| `TerrainColumnSampler` (per-validate-call, не static) | "твёрдый ли блок здесь" / topY острова | заменяет O(height × islands) скан на O(islands) через `ChunkIslandCache`+`IslandCache` |
| `AeroNoise.FBM_INV_MAX` (static) | нормировочный коэффициент fbm по (octaves, persistence) | маленький, общий для всех экземпляров |

Общий `ChunkIslandCache` создаётся один раз в `AeroWorldChunkGenerator`
(`sharedChunkIslandCache`) и передаётся во все генераторы слоёв,
`Layer2/3StructurePlacer` и `StructureSupportValidator` — так избегается
дублирование ключей и рассинхронизация при повторных вызовах `fillChunk`.

---

## 6. Известные особенности / потенциальные ловушки

Замечено при чтении кода — стоит перепроверить при внесении изменений:

1. **Генерация руды отключена во всех слоях.** `Layer1-4OreGenerator` полностью
   реализованы, но вызовы закомментированы в
   `AeroWorldChunkGenerator.applyBiomeDecoration()` (строки ~570-573).
   Единственное активное действие с рудой — `Layer1OreFilter.applyToChunk()`,
   который зачищает **любую** руду (включая ванильную, оставшуюся от
   `super.applyBiomeDecoration()`) по всей высоте чанка. То есть сейчас
   в мире руды в принципе нет нигде. Если требуется вернуть руду —
   раскомментировать вызовы и решить, нужен ли по-прежнему полный `Layer1OreFilter`
   (иначе он тут же зачистит только что сгенерированную руду).

2. **`SpawnerProximityHandler` не зарегистрирован.** Класс существует и
   выглядит завершённым (тикающий обработчик, ищет `structure_spawner`
   блок в радиусе 10 блоков на Layer 2), но в `AeroWorld.java` нет
   `NeoForge.EVENT_BUS.register(new SpawnerProximityHandler())` — сравните
   с `ProximityTriggerHandler` и `ShorelineWaveHandler`, которые
   зарегистрированы. Либо мёртвый код, либо забытая регистрация.

3. **`VanillaHeightChunkWrapper` не используется.** Нет ссылок на этот класс
   нигде в остальном коде (`grep` не находит других упоминаний). Возможно,
   заготовка для будущей интеграции с ванильным `NoiseBasedChunkGenerator`
   в реальных Y-границах (-64..384) вместо кастомных.

4. **`presets/*.json` (dense_archipelago, grand_isolation, skyblock_classic,
   vast_wilderness) не подключены к коду.** Java-код (`AeroWorldSettings`)
   читает `aero_settings` только из `data/aeroworld/dimension/aeroworld.json`.
   Файлы в `presets/` не реферируются никаким JSON или Java-кодом — похоже
   на референсные/будущие пресеты, не активную функциональность. Если нужно
   их подключить — потребуется код выбора пресета в UI создания мира или
   отдельный datapack-механизм.

5. **`AeroWorldConfig` (NeoForge ModConfigSpec) фактически пуст** — `SPEC`
   собирается без единого поля. Настройки слоёв идут не через игровой
   конфиг, а через JSON измерения (`aero_settings`), которое хранится
   в самом мире (см. `AeroWorldSettings`-javadoc).

6. **`DistantHorizonsApi-*.jar`** объявлен как `compileOnly` зависимость
   в `build.gradle`, но в исходниках нет ни одного класса/импорта, который
   бы его использовал. Упоминание Distant Horizons встречается только
   в комментариях (например, в `LowerIslandGenerator.getDeformedTopY`),
   как задел на будущее.

7. **Разные форматы структур на одном слое рядом.** `tank21` (Layer 2) —
   обычный NBT/`PhysicalStructures`. `HAUL-01` (Layer 3) — `.excraft`/Toolgun.
   При добавлении новой структуры важно не перепутать пайплайн: для
   `.excraft`-файлов регистрация через `PhysicalStructures.registerStructure()`
   молча "соберёт" почти пустую структуру (см. развёрнутый комментарий в
   `AeroWorld.registerAeroStructures()`).

8. **Y-константы разбросаны, но задокументированы в нескольких местах** —
   `LAYER_MIN_Y`/`LAYER_MAX_Y` определены в каждом `*IslandGenerator`,
   и продублированы (с запасом `LAYER_MARGIN=20`) в
   `StructureCategoryResolver.isIslandLayerY/isVoidGapY`. При изменении
   границ слоя нужно поменять константу в генераторе — резолвер сам
   подтянет новое значение (использует статические поля напрямую, не
   захардкожен).

9. **Vault/Trial Spawner реализованы на Layer 2, 3 и 4.** См. раздел 4a.
   Каждый слой использует свой метод поиска точки в `IslandVaultTrialGenerator`
   (`findBuriedSpot`/`findBuriedSpotEllipsoid`/`findBuriedSpotCap`), т.к. их
   геометрия принципиально разная — `IslandShape.isSolid()` (Layer 2, сплошная
   форма с профилем), сплошной эллипсоид через `IslandData.ellipsoidAxes`
   (Layer 3), купол медузы без сплошного тела ниже него (Layer 4,
   `IslandData.tentacleData` описывает только тонкие щупальца, непригодные
   для структур — см. javadoc `IslandVaultTrialGenerator.placeForJellyfishIsland`).
   Постановка блока/NBT/расчистка воздуха (`placeVault`/`placeTrialSpawner`/
   `clearAboveBlock`) остаётся общей для всех слоёв и geometry-независимой.

---

## 7. Ресурсы (data/assets)

```
resources/
├── assets/aeroworld/lang/{en_us,ru_ru}.json      — локализация (preset name, custom death message)
└── data/
    ├── aeroworld/
    │   ├── dimension/aeroworld.json               — ★ единственный активный источник aero_settings
    │   ├── dimension_type/aeroworld.json           — Y-границы мира (-64..2031, height=2096)
    │   ├── loot_table/gameplay/                     — дроп Vault/Trial Spawner по слоям (см. раздел 4a)
    │   │   ├── layer2/
    │   │   │   ├── vault_normal.json / vault_ominous.json         — медь/железо/уголь (равные веса) + ominous_bottle
    │   │   │   └── trial_spawner_normal.json / trial_spawner_ominous.json — trial_key + медь/железо/уголь (raw + block)
    │   │   ├── layer3/
    │   │   │   ├── vault_normal.json / vault_ominous.json         — золото/железо/редстоун (равные веса) + ominous_bottle
    │   │   │   └── trial_spawner_normal.json / trial_spawner_ominous.json — trial_key + золото/железо/редстоун (raw + block)
    │   │   └── layer4/
    │   │       ├── vault_normal.json / vault_ominous.json         — алмазный/изумрудный/лазуритовый блок
    │   │       │   (50%/25%/25%) + ominous_bottle
    │   │       └── trial_spawner_normal.json / trial_spawner_ominous.json — trial_key + сырые алмаз/изумруд/лазурит
    │   │           (33%/33%/34%)
    │   ├── neoforge/biome_modifier/remove_ores.json — вырезает все руд-фичи из #aeroworld:aero_biomes
    │   ├── presets/*.json                          — ⚠ см. пункт 4 раздела 6, не подключены к коду
    │   ├── tags/worldgen/biome/aero_biomes.json     — список всех 53 клонированных биомов
    │   └── worldgen/
    │       ├── biome/*.json (53 файла)              — клоны ванильных биомов aeroworld:* (без руды)
    │       └── world_preset/aeroworld.json          — сам world preset (overworld=aeroworld generator, nether/end — ванильные)
    ├── minecraft/tags/worldgen/world_preset/normal.json — добавляет aeroworld в список пресетов создания мира
    └── physical_structures/
        ├── physical_structures/tank21.json          — регистрация tank21 как JSON (дублирует Java-регистрацию, см. safeRegister)
        └── structures/tank21.nbt                    — сама NBT-структура танка
```

`blueprints/HAUL-01.excraft` и `blueprints_reference/` (копия +
README с объяснением) лежат в корне репозитория, не в `resources/` —
это ожидаемо, т.к. `.excraft` должен физически лежать в
`<gamedir>/blueprints/`, а не быть частью датапака/jar.

---

## 8. С чего начать при доработке

- **Меняете форму/размер островов слоя N** → `config/LayerNSettings.java`
  (диапазоны значений) + `worldgen/layer/*IslandGenerator.java`
  (`computeIslandData`, форма в `fillChunk`).
- **Меняете рельеф поверхности** (горы, реки, пещеры, пляжи) →
  `worldgen/layer/Layer1FlatGenerator.java`. Единственный источник истины
  для высоты поверхности — `columnProfile()`/`surfaceHeight()`; не дублируйте
  логику высоты в другом месте (см. javadoc метода `surfaceHeight`).
- **Меняете биомы** → `worldgen/biome/AeroBiomeSource.java` (таблицы
  `BIOME_TABLE`/`RARE_TABLE`) + соответствующие JSON в
  `resources/data/aeroworld/worldgen/biome/`.
- **Добавляете новую структуру** → сначала решите: ванильный NBT
  (`PhysicalStructures`, как tank21) или `.excraft` (Toolgun, как HAUL-01).
  Затем: `AeroWorld.registerAeroStructures()` (если NBT) +
  новый/существующий `LayerNStructurePlacer` + очередь через
  `IslandStructureScheduler`.
- **Структуры (деревни и т.п.) неправильно/не размещаются** →
  `worldgen/structure/StructureSupportValidator.java` и
  `StructureCategoryResolver.java` — логика принятия/отказа, пороги
  поддержки, категории.
- **Меняете дроп Vault/Trial Spawner на существующем слое (2/3/4)** →
  правьте только JSON в `resources/data/aeroworld/loot_table/gameplay/layerN/`
  (проценты — через `weight`, диапазоны количеств — через `set_count`);
  Java (`VaultTrialLootConfig`) трогать не нужно, если состав предметов не
  меняется, только веса/диапазоны. См. раздел 4a за текущими значениями по
  слоям.
- **Добавляете Vault/Trial Spawner на Layer 1** (сейчас есть на 2/3/4,
  реализовано по образцу друг друга) → см. раздел 4a и пункт 9 раздела 6.
  Нужен отдельный метод поиска точки в `IslandVaultTrialGenerator` под
  геометрию поверхности Layer 1 — постановка блока/NBT/расчистка уже
  geometry-независимы. Плюс новый `Layer1VaultTrialPlacer` (по образцу
  `Layer4VaultTrialPlacer` — ближайший пример со своей геометрией без
  привязанного блюпринта-структуры) и вызов из `applyBiomeDecoration`. Дроп —
  новый `VaultTrialLootConfig` + loot table JSON в
  `resources/data/aeroworld/loot_table/gameplay/layer1/`.
- **Проблемы с производительностью генерации** → сначала проверьте кэши
  (раздел 5); большинство «дорогих» путей уже кэшированы, но новый код,
  добавленный в горячие циклы (`fillChunk` любого слоя), должен следовать
  тому же паттерну: precompute вне цикла по Y, использовать существующий
  `ChunkIslandCache`/`IslandCache`.
