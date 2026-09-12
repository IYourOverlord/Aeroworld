# AeroWorld - Project Index

Мод для Minecraft 1.21.1 (NeoForge 21.1.228, Java 21, ModDevGradle 2.0.141, `mod_version` 1.0.12). Кастомное измерение с многослойной генерацией: поверхность + три слоя парящих островов, разделённых большими пустыми зазорами (зазоры являются частью дизайна: до островов должно быть трудно добраться).

Зависимости: `compileOnly` jar из `libs/` (`physical_structures-*.jar`, `DistantHorizonsApi-*.jar`). В `neoforge.mods.toml` обязательна только `physical_structures [1,)`. Регистрация `HAUL-01.excraft` и класс `Layer3StructurePlacer` удалены; в `ProximityTriggerHandler` осталась ветка для id с namespace `excraft`, которая в текущей конфигурации не срабатывает (в очередь ставится только `physical_structures:tank21`).

Пакет: `org.example.aeroworld`. Корень исходников: `src/main/java/org/example/aeroworld/`.

Индекс сверен с кодом 2026-09-12. Список расхождений см. раздел 8.

---

## 1. Слои мира

| Слой | Y-диапазон | Что генерируется | Генератор | Настройки |
|---|---|---|---|---|
| Layer 1 | -64 .. 300 (`Layer1TerrainGenerator.MIN_Y/MAX_Y`) | Кастомный рельеф на собственном шуме (континентальность, эрозия, хребты, речные долины). `SEA_LEVEL = 0`. Высота грунта клампится в -58 .. 220: глубокий океан около -47, шельф около -8, пляж около 2, суша от 3, горы и хребты до 220. Гигантская пещера Y -50 .. -25 под всей сушей (не под океаном) с мхом, светящимся лишайником и пещерными лианами. 5 слоёв бедрока. | `worldgen/layer/Layer1TerrainGenerator.java` | Жёстко в коде. `settings: minecraft:overworld` нужен только базовому `NoiseBasedChunkGenerator` (структуры, `getBaseColumn`), рельеф из него не берётся |
| Layer 2 | 400 .. 500 | Острова-конусы с 4 профилями и шумовой деформацией края, архипелаги (центр + 5-6 спутников), деревья по кольцу края, сталактиты снизу, мосты между соседями | `worldgen/layer/LowerIslandGenerator.java` | `config/Layer2Settings.java` (`aero_settings.layer2`) |
| Layer 3 | 1000 .. 1100 | Шары и эллипсоиды (5 вариантов осей) | `worldgen/layer/HighIslandGenerator.java` | `config/Layer3Settings.java` |
| Layer 4 | 1900 .. 2031 | "Медузы": купол + 10 щупалец длиной 90-120, щупальца свисают ниже `LAYER_MIN_Y` (до ~1780) | `worldgen/layer/UpperIslandGenerator.java` | `config/Layer4Settings.java` |

Измерение: `min_y = -64`, `height = 2096` (131 секция на чанк). `getGenDepth()` возвращает 2164, `getSeaLevel()` возвращает 0.

Все слои координирует **`worldgen/AeroWorldChunkGenerator.java`**. Он наследуется от `NoiseBasedChunkGenerator`, но `super` вызывается только в `createBiomes`, `createStructures` и `applyBiomeDecoration`. Рельеф, карвинг и поверхность полностью свои (раздел 3).

---

## 2. Карта пакетов

```
org.example.aeroworld
├── AeroWorld.java                 - @Mod: конфиг, реестры, слушатели, регистрация tank21 в PhysicalStructures
│                                    (assembleDelayTicks = 20), сброс StructureSizeCache на /reload
├── client/AeroWorldClientEvents.java - пустая заготовка под ScreenEvent.Init.Post
├── command/AeroWorldCommands.java    - /aeroworld forcePlacePending | findIsland4 | findIsland3 |
│                                       findIsland2 [normal|archipelago_centre|satellite] [POOR|MEDIUM|RICH]
├── config/
│   ├── AeroWorldConfig.java       - ModConfigSpec (aeroworld-client.toml), spec пустой
│   ├── AeroWorldSettings.java     - record {layer2, layer3, layer4}, codec поля "aero_settings"
│   └── Layer2/3/4Settings.java    - параметры слоёв с валидацией и DEFAULT
├── event/
│   ├── AeroStructureListener.java     - звук ANVIL_LAND на PhysicalStructurePlacedEvent
│   ├── ProximityTriggerHandler.java   - раз в 20 тиков: flush очереди в SavedData, спавн tank21 при игроке
│   │                                    в 96 блоках XZ (Y-окно Layer 2 и 3 +-128), backoff 20..200 тиков, до 10 попыток
│   ├── ShorelineWaveHandler.java      - волны на берегах Layer 1 (BFS-полукольца воды LEVEL 0..7, окно Y -10..90)
│   └── SpawnerProximityHandler.java   - раз в 20 тиков ищет physical_structures:structure_spawner в радиусе 10
│                                        блоков (чанки +-2) на Y 250..450 и вызывает trigger() рефлексией
├── registry/
│   ├── AeroDimensions.java        - CHUNK_GENERATOR aeroworld:aero_generator
│   ├── AeroRegistries.java        - регистрация генератора и aeroworld:aero_biome_source
│   ├── AeroResourceKeys.java      - ResourceKey DimensionType / LevelStem / Level aeroworld:aeroworld
│   └── AeroWorldPreset.java       - документация: WorldPreset только через datapack
├── spawning/LayerSpawnRestriction.java - отмена спавна при Y >= 1900, только если level.dimension() == aeroworld:aeroworld (5.7)
└── structure/
    ├── IslandStructureScheduler.java  - потокобезопасная очередь из worldgen-потоков, дедупликация по чанку
    ├── PendingStructureData.java      - SavedData: Entry(pos, id, attempts, nextRetryTick)
    ├── StructurePlacementHelper.java  - размер NBT, проверка FULL, сэмплинг свободного места
    └── StructureSizeCache.java        - кэш размеров NBT
```

### worldgen/

```
worldgen/
├── AeroWorldChunkGenerator.java   - главный генератор (раздел 3)
├── biome/
│   ├── AeroBiomeRegistryCache.java - CompletableFuture<Registry<Biome>>, заполняется в ServerAboutToStartEvent
│   └── AeroBiomeSource.java       - ThreadLocal 2D quart-кэш колонок (16 вызовов на чанк);
│                                    Layer 1: свой шум -> aeroworld:* клоны + deep_dark;
│                                    острова: ванильный MultiNoise на quart y=20 -> мемоизированный aeroworld:* клон
├── cache/
│   ├── ChunkIslandCache.java   - общий кэш центров островов (layerId, chunkX, chunkZ), 4096, fastutil LRU + StampedLock
│   ├── ChunkKey.java           - упаковка (x, z) в long
│   ├── IslandCache.java        - кэш IslandData по центру острова, 512 на слой, fastutil LRU + StampedLock
│   ├── IslandData.java         - bounds, radius, профиль/шум (L2), оси (L3), щупальца (L4)
│   └── Layer1ColumnCache.java  - ThreadLocal кэш колонок Layer 1 (surfaceY, caveTop, caveBottom на 256 блоков чанка)
├── carver/SinkholeCarver.java  - карстовые воронки. НЕ ВЫЗЫВАЕТСЯ: applyCarvers пустой
├── feature/
│   ├── Layer1OreFilter.java    - в applyBiomeDecoration заменяет руду на камень/сланец в секциях Y <= 320
│   └── vault/                  - Vault и Trial Spawner на островах всех трёх слоёв
│       ├── IslandVaultTrialCache.java     - прогресс по острову, ключ (layerId, cx, cz)
│       ├── IslandVaultTrialGenerator.java - поиск точки внутри чанка-инициатора, блок + NBT, расчистка сферы r=4
│       ├── Layer2VaultTrialPlacer.java    - POOR 50% / MEDIUM 35% / RICH 15%; центр архипелага MEDIUM,
│       │                                    спутник POOR с шансом 25%
│       ├── Layer3VaultTrialPlacer.java    - те же тиры, эллипсоид
│       ├── Layer4VaultTrialPlacer.java    - те же тиры, только купол
│       ├── VaultTrialLootConfig.java      - LAYER_2 медь/железо/уголь, LAYER_3 золото/редстоун/железо,
│       │                                    LAYER_4 алмаз/изумруд/лазурит; мобы zombie, skeleton, spider, husk
│       └── VaultTrialSpawnTier.java       - POOR 1+1, MEDIUM 2+3, RICH 3+5
├── layer/
│   ├── Layer1TerrainGenerator.java  - весь Layer 1: высоты, пещера, заливка, декор пещеры, buildSurface (Layer1ColumnCache, surfaceInfoCache)
│   ├── Layer1FlatGenerator.java     - тонкая обёртка (surfaceHeight/topmostHeight) для валидатора и биомов
│   ├── LowerIslandGenerator.java    - Layer 2: fillChunk, placeTreesInRegion, clearVanillaVegetationInCentralZone, LRU islandBridgeCache
│   ├── HighIslandGenerator.java     - Layer 3: аналитический диапазон Y эллипсоида на колонку
│   ├── UpperIslandGenerator.java    - Layer 4: купол + трассировка щупалец в AABB чанка
│   └── Layer2StructurePlacer.java   - в fillFromNoise ставит tank21 в очередь для обычных RICH-островов (searchRadius генератора)
├── noise/
│   ├── AeroNoise.java          - Perlin 2D/3D + fbm
│   ├── IslandPlacer.java       - сетка ячеек, anti-overlap, архипелаги (25% ячеек Layer 2), AABB-фильтр
│   └── IslandShape.java        - профили конуса, precomputeXZ, isSolid
├── structure/
│   ├── AncientCityIslandSupportPlacer.java - ступенчатая deepslate-платформа под ancient_city до пола пещеры
│   ├── StructureCategory.java         - SURFACE, ISLAND, UNDERGROUND, WATER, SKY_FLOATING, DENY
│   ├── StructureCategoryResolver.java - deny-список, токены путей, resolveForActualLayer
│   ├── StructureSupportValidator.java - вызывается ТОЛЬКО из createStructures
│   ├── SupportSample.java, ValidationResult.java
│   └── TerrainColumnSampler.java      - опора под структурой, глубина скана 24
└── util/
    ├── ChunkAccessWriter.java         - через ChunkAccess.setBlockState (утилитарный)
    ├── ChunkWriter.java               - интерфейс
    └── SectionDirectChunkWriter.java  - прямая запись в LevelChunkSection (useLocking=false, ИСПОЛЬЗУЕТСЯ в fillFromNoise)
```

---

## 3. Жизненный цикл генерации чанка

Инициализация ленивая: `init(randomState)` / `initializeWithSeed(seed)` (`synchronized`; seed из `RandomState` через `aeroworld:seed_probe` либо из `ChunkGeneratorStructureState.getLevelSeed()`).

1. **`createBiomes`**: `init`, затем `super.createBiomes` (ванильный `fillBiomesFromNoise` по всем 131 секциям с `AeroBiomeSource`; ваниль создаёт `NoiseChunk`). `AeroBiomeSource` сэмплирует шум и delegate ровно 1 раз на XZ-колонку (16 раз на чанк), кэшируя результат в ThreadLocal-таблице.
2. **`createStructures`**: `super.createStructures`, затем для каждого `StructureStart` из `getAllStarts()` и `getAllReferences()` вызывается `StructureSupportValidator.validate`. Категория определяется по фактическому слою в центре bounding box. Отклонённые старты заменяются на `INVALID_START`. Это единственное место валидации.
3. **`fillFromNoise`**: запись через `SectionDirectChunkWriter` (`LevelChunkSection.setBlockState(..., false)` без мониторов, с корректным обновлением `nonEmptyBlockCount`), последовательно в потоке чанка.
   - Layer 1: `fillTerrain` через `Layer1ColumnCache` (бедрок, deepslate ниже -8, переход в -8..0, stone выше, пропуск полостей пещеры без повторных вызовов шума по Y, вода до `SEA_LEVEL = 0`), затем `decorateCaveCeiling`, `decorateCaveFloor` (тот же кэш).
   - Layer 2: `lowerIslands.fillChunk` (grass/dirt/stone, стволы, сталактиты, мосты), затем `Layer2StructurePlacer.placeForChunk`.
   - Layer 3, 4: сплошной камень.
   - Финализация: пакетный прайминг `Heightmap.primeHeightmaps(chunk, Set.of(OCEAN_FLOOR_WG, WORLD_SURFACE_WG))`.
4. **`applyCarvers`**: пустой, быстрая проверка сида (`if (!seedInitialized || worldSeed != seed) initializeWithSeed(seed)`). Ванильных пещер/каньонов нет, `SinkholeCarver` не вызывается.
5. **`buildSurface`**: `layer1Terrain.buildSurface`, ванильные SurfaceRules не применяются. Использует `Layer1ColumnCache` и кэш `surfaceInfoCache` (`SurfaceType`, `BiomeSurfaceInfo` — O(1) без парсинга строк). Под водой гравий (глубина >= 8) или песок/песчаник; кораллы в биомах с "warm", ламинария, морская трава.
6. **`applyBiomeDecoration`** по порядку: `super` (ванильные фичи) -> `Layer1OreFilter` -> `clearVanillaVegetationInCentralZone` (внутренние 60% радиуса островов Layer 2, скан topY..topY+16) -> `placeTreesInRegion` (листва) -> Vault/Trial для Layer 2, 3, 4 -> `AncientCityIslandSupportPlacer`.

`getBaseHeight` / `getBaseColumn`: сначала острова Layer 4 -> 3 -> 2 по XZ-радиусу, затем Layer 1 через `getHeight` / `getTopmostHeight`; `getBaseColumn` воспроизводит пещеру через вычисленные `computeCaveBottom/computeCaveTop`.

---

## 4. Размещение tank21

- Регистрируется в `AeroWorld.registerAeroStructures()` с `assembleDelayTicks = 20`, если не задан датапаком (`data/physical_structures/.../tank21.json` + `tank21.nbt`).
- Очередь: `Layer2StructurePlacer` (worldgen-поток, только RICH, не архипелаги) -> `IslandStructureScheduler.enqueue` -> `flushToPersistence` каждый тик -> `PendingStructureData` -> `ProximityTriggerHandler.tryPlace` при игроке в 96 блоках XZ: поиск поверхности (heightmap, затем скан Y 1150..990 и 510..390), размер NBT, `areChunksReady`, `isSpaceClear` (с 3-й попытки), `PhysicalStructures.spawnStructureResult`.
- Backoff `20 << attempts`, потолок 200 тиков, максимум 10 попыток.
- Прегенерация: `/aeroworld forcePlacePending` (подгружает чанки 5x5 вокруг каждой записи).

---

## 5. Особенности архитектуры

1. **Генератор.** Ванильный пайплайн рельефа не используется ни для одного слоя; наследование от `NoiseBasedChunkGenerator` нужно для codec `settings`, структур и Distant Horizons. Поля `vanillaGenerator` нет.
2. **Многопоточность.** Поля генераторов `volatile`; `initializeWithSeed` `synchronized`, `applyCarvers` проверяет сид перед входом в монитор. Кэши `ChunkIslandCache`, `IslandCache` и `islandBridgeCache` на `Long2ObjectLinkedOpenHashMap` + `StampedLock` с истинным O(1) LRU-вытеснением. Запись блоков в `fillFromNoise` через `SectionDirectChunkWriter` (`useLocking=false`), heightmap праймятся пакетом один раз на чанк.
3. **Биомы.** `AeroBiomeSource` с `ThreadLocal<BiomeColumnCache>` (64 слота direct-mapped) — все 17 октав шума Layer 1 и `delegate.getNoiseBiome` на quart y=20 сэмплируются ровно 1 раз на XZ-колонку (16 раз на чанк вместо 8384). Мемоизация `vanilla -> aero` через `ConcurrentHashMap<Holder<Biome>, Holder<Biome>>`. quart y > 75: клон `aeroworld:<path>`; океаны, `dripstone_caves`, `lush_caves`, `deep_dark` -> `aeroworld:plains`. quart y <= 75: `aeroworld:<name>` через `AeroBiomeRegistryCache`. Y -64..-8: пятна `aeroworld:deep_dark` для Ancient City. `possibleBiomes()` = 58 клонов + биомы ванильного пресета.
4. **Руды.** `remove_ores.json` действует только на 6 биомов из `#aeroworld:aero_biomes` (meadow, plains, sunflower_plains, alpine_meadow, autumn_forest, heather_moor). Остальные ~52 биома чистит `Layer1OreFilter`.
5. **Структуры.** Валидация только в `createStructures`. `ancient_city` принимается всегда и получает платформу. WATER допускаются на Layer 1 без проверки опоры, на островах отклоняются.
6. **Команды.** `findIsland2/3/4`: спиральный обход ячеек `IslandPlacer` нужного слоя, телепорт на `topY + 5`.
7. **Два способа попасть в измерение.** World preset заменяет overworld и не содержит `aero_settings` (используются Java-DEFAULT: Layer 2 grid 25, радиус 25..110; Layer 4 spawn 0.05, grid 30, радиус 25..35). Отдельное измерение `aeroworld:aeroworld` из `dimension/aeroworld.json` использует явные `aero_settings` (grid 20, радиус 50..110 и т.д.). Команды и тик-обработчики проверяют namespace `dimension_type` и работают в обоих случаях; `LayerSpawnRestriction` сравнивает ключ уровня с `aeroworld:aeroworld` и в мире через preset не срабатывает.

---

## 6. Ресурсы датапака

- `data/aeroworld/dimension/aeroworld.json`: генератор `aeroworld:aero_generator`, `settings: minecraft:overworld`, `aero_settings`.
- `data/aeroworld/dimension_type/aeroworld.json`: `min_y -64`, `height 2096`.
- `data/aeroworld/worldgen/world_preset/aeroworld.json` + `data/minecraft/tags/worldgen/world_preset/normal.json`: пресет, overworld заменён без `aero_settings`.
- `data/aeroworld/worldgen/biome/*.json`: 58 клонов `aeroworld:*`.
- `data/aeroworld/tags/worldgen/biome/aero_biomes.json`: 6 биомов, область `remove_ores.json`.
- `data/aeroworld/neoforge/biome_modifier/remove_ores.json`: `neoforge:remove_features`, шаг `underground_ores`.
- `data/minecraft/tags/worldgen/biome/has_structure/*.json`: биомы ванильных структур переопределены на `aeroworld:*`.
- `data/minecraft/worldgen/structure_set/*.json`: переопределённые spacing/separation.
- `data/aeroworld/loot_table/gameplay/layer{2,3,4}/`: vault и trial_spawner, normal и ominous.
- `data/aeroworld/presets/*.json`: справочные примеры `aero_settings`. Загрузчика в коде нет, игрой не читаются; часть полей (`structure_support`, `y_variance`/`bridge_chance` у layer3/4) не соответствует codec'ам.
- `data/physical_structures/...`: `tank21.json`, `tank21.nbt`.
- `assets/aeroworld/lang/en_us.json`, `ru_ru.json`; `src/main/templates/META-INF/neoforge.mods.toml`.

---

## 7. Прочие файлы

- `README.md`: описание патча Vault/Trial для Layer 3, а не проекта.
- `build.gradle`: `options.encoding = 'UTF-8'` обязателен из-за кириллицы в исходниках.
- Артефакты в корне, не относящиеся к сборке: `NoiseBasedChunkGenerator.class`, `WorldCarver.class`, `old_layer1.java`, `git_log.txt`.

---

## 8. Расхождения, найденные при сверке (2026-09-12)

Что описывал старый индекс и чего в коде нет:

- Делегирование Layer 1 в `vanillaGenerator.fillFromNoise/buildSurface/applyCarvers`: такого поля нет, весь Layer 1 делает `Layer1TerrainGenerator`.
- `SinkholeCarver.carveChunk` и ванильные карверы с `CarvingMask`: `applyCarvers` пустой.
- Ванильные SurfaceRules и `minecraft:*` биомы в Layer 1: поверхность своя, биомы это клоны `aeroworld:*`.
- Двойная валидация структур: только `createStructures`.
- "Океаны до Y=63": `SEA_LEVEL = 0`.
- "Шаг UNDERGROUND_ORES отключён": шаг выполняется, руды вырезаются biome modifier (6 биомов) и `Layer1OreFilter`.
- `AncientCityIslandSupportPlacer` отсутствовал в карте пакетов.

Мёртвый код и устаревшие комментарии:

- `SinkholeCarver.java` не вызывается (внутри `WATER_LEVEL = 62` от старого уровня моря).
- `Layer1FlatGenerator.setVanillaSource`: no-op.
- Заголовок `AeroWorld.java` описывает старые диапазоны (Layer 1 до 50, Layer 2 300..400, Layer 4 2000..2100).
- Javadoc `PendingStructureData`: backoff 60..1200, код использует 20..200.
- Javadoc `AeroWorldSettings` упоминает поле `vanilla_generator`, codec его не содержит.

Потенциальные проблемы (не исправлены в этом коммите):

- `LayerSpawnRestriction` не работает в мире, созданном через world preset (5.7).
- `IslandVaultTrialGenerator.findBuriedSpot*` ставит структуры только в чанке-инициаторе, результат для острова зависит от порядка генерации чанков.
