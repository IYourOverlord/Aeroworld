# AeroWorld - Project Index

Мод для Minecraft 1.21.1 (NeoForge 21.1.228, Java 21, ModDevGradle 2.0.141, `mod_version` 1.0.12). Кастомное измерение с многослойной генерацией: поверхность + три слоя парящих островов, разделённых большими пустыми зазорами (зазоры являются частью дизайна: до островов должно быть трудно добраться).

Зависимости: `compileOnly` jar из `libs/` (`physical_structures-*.jar`, `DistantHorizonsApi-*.jar`, `DistantHorizons-3.2.0-b-1.21.1-fabric-neoforge.jar`). В `neoforge.mods.toml` обязательна только `physical_structures [1,)`. DH является опциональной мягкой зависимостью (soft dependency). Регистрация `HAUL-01.excraft` и класс `Layer3StructurePlacer` удалены; в `ProximityTriggerHandler` осталась ветка для id с namespace `excraft`, которая в текущей конфигурации не срабатывает (в очередь ставится только `physical_structures:tank21`).

Пакет: `org.example.aeroworld`. Корень исходников: `src/main/java/org/example/aeroworld/`.

Индекс сверен с кодовой базой 2026-09-19. Список расхождений и актуальный статус см. раздел 8.

---

## 1. Слои мира

| Слой | Y-диапазон | Что генерируется | Генератор | Настройки |
|---|---|---|---|---|
| Layer 1 | -64 .. 300 (`Layer1TerrainGenerator.MIN_Y/MAX_Y`) | Кастомный рельеф на собственном шуме (континентальность, эрозия, хребты, речные долины). `SEA_LEVEL = 0`. Высота грунта клампится в -58 .. 220: глубокий океан около -47, шельф около -8, пляж около 2, суша от 3, горы и хребты до 220. Гигантская пещера Y -50 .. -25 под всей сушей (не под океаном) с мхом, светящимся лишайником и пещерными лианами. 5 слоёв бедрока. Крепость Нижнего мира фиксируется в Y -55..-50 (`NetherFortressStructureMixin`). | `worldgen/layer/Layer1TerrainGenerator.java` | Жёстко в коде. `settings: minecraft:overworld` нужен только базовому `NoiseBasedChunkGenerator` (структуры), рельеф из него не берётся |
| Layer 2 | 400 .. 500 | Острова-конусы с 4 профилями и шумовой деформацией края, архипелаги (центр + 5-6 спутников), деревья по кольцу края, сталактиты снизу, мосты между соседями | `worldgen/layer/LowerIslandGenerator.java` | `config/Layer2Settings.java` (`aero_settings.layer2`) |
| Layer 3 | 1000 .. 1100 | Небесные тела двух типов (`BodyType`): 50% полые метеориты с кратерами, 50% сплошные планеты с 3 кольцами астероидов. На каждой планете строго по центру спавнится End City без корабля (`EndCityStructureMixin`). | `worldgen/layer/HighIslandGenerator.java` | `config/Layer3Settings.java`, `config/Layer3BodySettings.java` (`aero_settings.layer3`) |
| Layer 4 | 1900 .. 2031 | "Медузы": купол + 10 щупалец длиной 90-120, щупальца свисают ниже `LAYER_MIN_Y` (до ~1780) | `worldgen/layer/UpperIslandGenerator.java` | `config/Layer4Settings.java` |

Измерение: `min_y = -64`, `height = 2096` (131 секция на чанк). `getGenDepth()` возвращает 2164, `getSeaLevel()` возвращает 0.

Все слои координирует **`worldgen/AeroWorldChunkGenerator.java`**. Он наследуется от `NoiseBasedChunkGenerator`, но `super` вызывается только в `createBiomes`, `createStructures` и `applyBiomeDecoration`. Рельеф, карвинг и поверхность полностью свои (раздел 3).

Для Distant Horizons реализован аналитический оверрайд **`worldgen/dh/AeroSeedWorldGenerator.java`** (`IDhApiWorldGenerator`), генерирующий LOD-данные напрямую по seed через единое аналитическое ядро **`worldgen/column/AeroColumnModel.java`**, минуя батчевый chunk-gen.

---

## 2. Карта пакетов

```
org.example.aeroworld
├── AeroWorld.java                     - @Mod: конфиг, реестры, слушатели, регистрация tank21 в PhysicalStructures
│                                        (assembleDelayTicks = 20), сброс StructureSizeCache на /reload,
│                                        регистрация AeroSeedWorldGenBinding.registerIfDhPresent()
├── client/AeroWorldClientEvents.java  - пустая заготовка под ScreenEvent.Init.Post
├── command/AeroWorldCommands.java     - /aeroworld forcePlacePending | findIsland4 | findIsland3 |
│                                        findIsland2 [normal|archipelago_centre|satellite] [POOR|MEDIUM|RICH] |
│                                        validateSeedGen [count]
├── config/
│   ├── AeroWorldConfig.java           - ModConfigSpec (aeroworld-client.toml): DH_OVERRIDE_ENABLED,
│   │                                    DH_THROUGHPUT_LOG_INTERVAL_SEC
│   ├── AeroWorldSettings.java         - record {layer2, layer3, layer4, dhOverride}, codec поля "aero_settings"
│   ├── DhOverrideSettings.java        - record {enabled, layer2DetailThreshold, layer3DetailThreshold,
│   │                                    layer4DetailThreshold, throughputLogIntervalSec}, codec для DH-оверрайда
│   ├── Layer2Settings.java            - параметры Layer 2 с валидацией и DEFAULT
│   ├── Layer3BodySettings.java        - параметры полых метеоритов, кратеров и колец планет Layer 3 (codec "body")
│   ├── Layer3Settings.java            - параметры Layer 3 с валидацией и DEFAULT
│   └── Layer4Settings.java            - параметры Layer 4 с валидацией и DEFAULT
├── event/
│   ├── AeroStructureListener.java     - звук ANVIL_LAND на PhysicalStructurePlacedEvent
│   ├── ProximityTriggerHandler.java   - раз в 20 тиков: flush очереди в SavedData, спавн tank21 при игроке
│   │                                    в 96 блоках XZ (Y-окно Layer 2 и 3 +-128), backoff 20..200 тиков, до 10 попыток
│   ├── ShorelineWaveHandler.java      - волны на берегах Layer 1 (BFS-полукольца воды LEVEL 0..7, окно Y -10..90)
│   └── SpawnerProximityHandler.java   - раз в 20 тиков ищет physical_structures:structure_spawner в радиусе 10
│                                        блоков (чанки +-2) на Y 250..450 и вызывает trigger() рефлексией
├── mixin/
│   ├── dh/                            - миксины оптимизации throughput и исправления багов Distant Horizons
│   │   ├── BatchGenerationEnvironmentNeoforgeMixin.java - фикс бага DH: бордер региона и структуры на стыке батча
│   │   ├── DhWorldGenBorderMixinPlugin.java             - IMixinConfigPlugin: soft dependency проверка наличия DH
│   │   ├── ExecutorNameAccessor.java                    - аксессор имени потока ThreadPoolExecutor
│   │   ├── GeneratorBusyMixin.java                      - масштабирование порога занятости генератора (IN_FLIGHT_SCALE)
│   │   ├── LodQuadTreeAccessor.java                     - аксессор методов LodQuadTree
│   │   ├── ReloadCoalesceMixin.java                     - дебаунс и дедлайн каскадных перезагрузок деревьев LOD
│   │   ├── RetrievalQueueLimitMixin.java                - масштабирование очереди выборки LOD (QUEUE_SCALE), цель canQueueRetrievalNow(Z)Z
│   │   ├── SaveDelayMixin.java                          - задержка сброса LOD на диск (SAVE_DELAY_MS = 10000)
│   │   ├── SchedulerPriorityMixin.java                  - приоритет рендера над генерацией в PriorityTaskPicker
│   │   ├── SqliteTuningMixin.java                       - тюнинг PRAGMA SQLite (cache_size, mmap_size, synchronous)
│   │   └── WorldGenSpeedGateMixin.java                  - устранение замедления генерации при очереди рендера
│   └── structure/                     - инжекции в ванильные структуры для привязки к слоям AeroWorld
│       ├── EndCityStructureAccessor.java     - аксессор ванильного generatePieces в EndCityStructure
│       ├── EndCityStructureMixin.java        - принудительный спавн End City на планетах Layer 3, вырезка корабля
│       └── NetherFortressStructureMixin.java - сдвиг Nether Fortress в пещеру Layer 1 (Y -55..-50)
├── registry/
│   ├── AeroDimensions.java            - CHUNK_GENERATOR aeroworld:aero_generator
│   ├── AeroRegistries.java            - регистрация генератора и aeroworld:aero_biome_source
│   ├── AeroResourceKeys.java          - ResourceKey DimensionType / LevelStem / Level aeroworld:aeroworld
│   └── AeroWorldPreset.java           - документация: WorldPreset только через datapack
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
├── AeroWorldChunkGenerator.java       - главный генератор (раздел 3), делегирует getBaseColumn/getBaseHeight в AeroColumnModel
├── biome/
│   ├── AeroBiomeRegistryCache.java    - CompletableFuture<Registry<Biome>>, заполняется в ServerAboutToStartEvent
│   └── AeroBiomeSource.java           - ThreadLocal 2D quart-кэш колонок; аналитическое разрешение биомов Layer 1 и островов
├── cache/
│   ├── BodyType.java                  - enum {METEORITE, PLANET} для Layer 3
│   ├── ChunkIslandCache.java          - общий кэш центров островов (layerId, chunkX, chunkZ), 4096, LRU + StampedLock
│   ├── ChunkKey.java                  - упаковка (x, z) в long
│   ├── IslandCache.java               - кэш IslandData по центру острова, 512 на слой, LRU + StampedLock
│   ├── IslandData.java                - bounds, radius, bodyType, кольца астероидов, эллипсоиды, щупальца
│   └── Layer1ColumnCache.java         - ThreadLocal кэш колонок Layer 1 (surfaceY, caveTop, caveBottom)
├── carver/SinkholeCarver.java         - карстовые воронки (НЕ ВЫЗЫВАЕТСЯ: applyCarvers пустой)
├── column/
│   ├── AeroColumnModel.java           - единое ядро столбцовой генерации: аналитический расчёт спанов [bottomY..topY, state, biome]
│   └── AeroColumnWriter.java          - конвертер спанов AeroColumnModel в DhApiTerrainDataPoint (detailLevel=0, эксклюзивный topY, воздух в пустотах, порядок сверху вниз) с кэшированием обёрток
├── dh/
│   ├── AeroFastDistantTerrain.java    - пороги detailLevel для раздельного упрощения геометрии слоёв
│   ├── AeroSeedGenValidation.java     - dev-валидатор: поблочное сравнение AeroColumnModel и getBaseColumn на N точках
│   ├── AeroSeedWorldGenBinding.java   - слушатель DhApiLevelLoadEvent, регистрация оверрайда и stand-down логика
│   ├── AeroSeedWorldGenerator.java    - реализация IDhApiWorldGenerator (EDhApiWorldGeneratorReturnType.API_CHUNKS)
│   ├── AeroThroughputLimits.java      - счётчик чанков и секций, периодический отчёт производительности (chunks/s, sections/s)
│   └── ReloadCoalescer.java           - дебаунс (50ms) и максимальный дедлайн (200ms) для объединения запросов reload
├── feature/
│   ├── Layer1OreFilter.java           - в applyBiomeDecoration заменяет руду на камень/сланец в секциях Y <= 320
│   └── vault/                         - Vault и Trial Spawner на островах всех трёх слоёв
│       ├── IslandVaultTrialCache.java     - прогресс по острову, ключ (layerId, cx, cz)
│       ├── IslandVaultTrialGenerator.java - поиск точки внутри чанка-инициатора, блок + NBT, расчистка сферы r=4
│       ├── Layer2VaultTrialPlacer.java    - POOR 50% / MEDIUM 35% / RICH 15%; центр архипелага MEDIUM, спутник POOR 25%
│       ├── Layer3VaultTrialPlacer.java    - те же тиры, размещение внутри полого метеорита или на планете
│       ├── Layer4VaultTrialPlacer.java    - те же тиры, только купол
│       ├── VaultTrialLootConfig.java      - лут по слоям; мобы zombie, skeleton, spider, husk
│       └── VaultTrialSpawnTier.java       - POOR 1+1, MEDIUM 2+3, RICH 3+5
├── layer/
│   ├── Layer1TerrainGenerator.java    - весь Layer 1: высоты, пещера, заливка, декор пещеры, buildSurface
│   ├── Layer1FlatGenerator.java        - тонкая обёртка (surfaceHeight/topmostHeight) для валидатора и биомов
│   ├── LowerIslandGenerator.java       - Layer 2: fillChunk, placeTreesInRegion, clearVanillaVegetationInCentralZone
│   ├── HighIslandGenerator.java        - Layer 3: полые метеориты с кратерами и планеты с кольцами астероидов
│   ├── UpperIslandGenerator.java       - Layer 4: купол + трассировка щупалец в AABB чанка
│   └── Layer2StructurePlacer.java      - в fillFromNoise ставит tank21 в очередь для обычных RICH-островов
├── noise/
│   ├── AeroNoise.java                 - Perlin 2D/3D + fbm
│   ├── IslandPlacer.java              - сетка ячеек, anti-overlap, архипелаги (25% ячеек Layer 2), AABB-фильтр
│   └── IslandShape.java               - профили конуса, precomputeXZ, isSolid
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

Инициализация: единый источник истины — сид мира. `initializeWithSeed(seed)` (`synchronized`) вызывается из `createState` (переопределён), `createStructures`, `applyCarvers` и DH-биндинга и помечает сид как мировой (`seedFromWorld`). `init(randomState)` использует `aeroworld:seed_probe` только как fallback, пока мировой сид не получен, и никогда не перезаписывает мировой сид.

1. **`createBiomes`**: `init`, затем `super.createBiomes` (ванильный `fillBiomesFromNoise` по всем 131 секциям с `AeroBiomeSource`; ваниль создаёт `NoiseChunk`). `AeroBiomeSource` сэмплирует шум и delegate ровно 1 раз на XZ-колонку (16 раз на чанк), кэшируя результат в ThreadLocal-таблице.
2. **`createStructures`**: `super.createStructures`, затем для каждого `StructureStart` из `getAllStarts()` и `getAllReferences()` вызывается `StructureSupportValidator.validate`. Категория определяется по фактическому слою в центре bounding box. Отклонённые старты заменяются на `INVALID_START`.
    - `NetherFortressStructureMixin`: в AeroWorld смещает собранные части крепости вертикально в диапазон Y -55..-50 пещеры Layer 1.
    - `EndCityStructureMixin`: принудительно центрирует End City на вершине сферы планеты Layer 3 (`BodyType.PLANET`) и удаляет пивсы корабля.
3. **`fillFromNoise`**: запись через `SectionDirectChunkWriter` (`LevelChunkSection.setBlockState(..., false)` без мониторов, с корректным обновлением `nonEmptyBlockCount`), последовательно в потоке чанка.
    - Layer 1: `fillTerrain` через `Layer1ColumnCache` (бедрок, deepslate ниже -8, переход в -8..0, stone выше, пропуск полостей пещеры без повторных вызовов шума по Y, вода до `SEA_LEVEL = 0`), затем `decorateCaveCeiling`, `decorateCaveFloor`.
    - Layer 2: `lowerIslands.fillChunk` (grass/dirt/stone, стволы, сталактиты, мосты), затем `Layer2StructurePlacer.placeForChunk`.
    - Layer 3: `highIslands.fillChunk` (полые метеориты с кратерами из basalt/smooth_basalt/blackstone и планеты из end_stone с кольцами астероидов).
    - Layer 4: `upperIslands.fillChunk` (купол и щупальца медуз).
    - Финализация: пакетный прайминг `Heightmap.primeHeightmaps(chunk, Set.of(OCEAN_FLOOR_WG, WORLD_SURFACE_WG))`.
4. **`applyCarvers`**: пустой, быстрая проверка сида (`if (!seedInitialized || worldSeed != seed) initializeWithSeed(seed)`). Ванильных пещер/каньонов нет, `SinkholeCarver` не вызывается.
5. **`buildSurface`**: `layer1Terrain.buildSurface`, ванильные SurfaceRules не применяются. Использует `Layer1ColumnCache` и кэш `surfaceInfoCache` (`SurfaceType`, `BiomeSurfaceInfo` — O(1) без парсинга строк). Под водой гравий (глубина >= 8) или песок/песчаник; кораллы в биомах с "warm", ламинария, морская трава.
6. **`applyBiomeDecoration`** по порядку: `super` (ванильные фичи) -> `Layer1OreFilter` -> `clearVanillaVegetationInCentralZone` (внутренние 60% радиуса островов Layer 2, скан topY..topY+16) -> `placeTreesInRegion` (листва) -> Vault/Trial для Layer 2, 3, 4 -> `AncientCityIslandSupportPlacer`.

### Аналитический путь (Distant Horizons & getBaseColumn)
`getBaseHeight` и `getBaseColumn` в `AeroWorldChunkGenerator` не дублируют логику, а делегируют вызовы в **`AeroColumnModel`**:
- `AeroColumnModel.getBaseHeight()`: сканирует сверху вниз Layer 4 -> 3 -> 2 по эффективному радиусу островов, затем Layer 1 через `getHeight` / `getTopmostHeight`.
- `AeroColumnModel.buildSpans()`: возвращает непересекающийся упорядоченный список вертикальных спанов `[bottomY, topY, BlockState, biomeName, biomeHolder]` по всем 4 слоям.
- При активном оверрайде Distant Horizons DH обращается к `AeroSeedWorldGenerator.generateApiChunks()`, который для каждого блока чанка строит спаны через `AeroColumnModel` и конвертирует их в структуры DH через `AeroColumnWriter`, минуя вызовы `fillFromNoise` и декорирования.

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
   Для аналитического пути добавлены прямые методы `getLayer1BiomeName(x, z)`, `isDeepDark(x, z)`, `getIslandBiomeName(x, z)`, `findAeroBiome(name)`.
4. **Интеграция с Distant Horizons (SeedGen Override).**
    - Реализована мягкая зависимость: при отсутствии DH на classpath (`ClassNotFoundException`) оверрайд тихо отключается без падения игры (`AeroSeedWorldGenBinding.registerIfDhPresent()`).
    - Плагин миксинов `DhWorldGenBorderMixinPlugin` загружает миксины пакета `org.example.aeroworld.mixin.dh` только если класс `com.seibel.distanthorizons.core.Initializer` присутствует.
    - Оверрайд регистрируется в `DhApiLevelLoadEvent` только для генераторов `instanceof AeroWorldChunkGenerator` и при включённом тумблере в конфигурации (`dhOverrideEnabled`). При несовместимости выполняется stand-down, и DH возвращается к штатному батчингу.
    - Throughput-миксины оптимизируют очереди, приоритизацию рендера над генерацией и транзакции SQLite базы LOD.
5. **Руды.** `remove_ores.json` действует только на 6 биомов из `#aeroworld:aero_biomes` (meadow, plains, sunflower_plains, alpine_meadow, autumn_forest, heather_moor). Остальные ~52 биома чистит `Layer1OreFilter`.
6. **Структуры.** Валидация только в `createStructures`. `ancient_city` принимается всегда и получает платформу. WATER допускаются на Layer 1 без проверки опоры, на островах отклоняются. `end_city` принудительно центрируется на планетах Layer 3, `fortress` смещается в главную пещеру Layer 1.
7. **Команды.** `findIsland2/3/4`: спиральный обход ячеек `IslandPlacer` нужного слоя, телепорт на `topY + 5`. `validateSeedGen [count]`: сверка вывода столбцовой модели с `getBaseColumn`.
8. **Два способа попасть в измерение.** World preset заменяет overworld и не содержит `aero_settings` (используются Java-DEFAULT: Layer 2 grid 25, радиус 25..110; Layer 4 spawn 0.05, grid 30, радиус 25..35). Отдельное измерение `aeroworld:aeroworld` из `dimension/aeroworld.json` использует явные `aero_settings` (grid 20, радиус 50..110 и т.д.). Команды и тик-обработчики проверяют namespace `dimension_type` и работают в обоих случаях; `LayerSpawnRestriction` сравнивает ключ уровня с `aeroworld:aeroworld` и в мире через preset не срабатывает.

---

## 6. Ресурсы датапака и конфигурации

- `aeroworld.mixins.json`: конфигурация миксинов DH с плагином `DhWorldGenBorderMixinPlugin` (мягкая зависимость).
- `aeroworld_structures.mixins.json`: конфигурация миксинов структур (`EndCityStructureMixin`, `NetherFortressStructureMixin`).
- `data/aeroworld/dimension/aeroworld.json`: генератор `aeroworld:aero_generator`, `settings: minecraft:overworld`, `aero_settings`.
- `data/aeroworld/dimension_type/aeroworld.json`: `min_y -64`, `height 2096`.
- `data/aeroworld/worldgen/world_preset/aeroworld.json` + `data/minecraft/tags/worldgen/world_preset/normal.json`: пресет, overworld заменён без `aero_settings`.
- `data/aeroworld/worldgen/biome/*.json`: 58 клонов `aeroworld:*`.
- `data/aeroworld/tags/worldgen/biome/aero_biomes.json`: 6 биомов, область `remove_ores.json`.
- `data/aeroworld/neoforge/biome_modifier/remove_ores.json`: `neoforge:remove_features`, шаг `underground_ores`.
- `data/minecraft/tags/worldgen/biome/has_structure/*.json`: биомы ванильных структур переопределены на `aeroworld:*`.
- `data/minecraft/worldgen/structure_set/*.json`: переопределённые spacing/separation (включая `end_cities.json` с `spacing=1`).
- `data/minecraft/worldgen/structure/end_city.json`, `bastion_remnant.json`: кастомные высоты и настройки структур.
- `data/aeroworld/loot_table/gameplay/layer{2,3,4}/`: vault и trial_spawner, normal и ominous.
- `data/aeroworld/presets/*.json`: справочные примеры `aero_settings`. Загрузчика в коде нет, игрой не читаются.
- `data/physical_structures/...`: `tank21.json`, `tank21.nbt`.
- `assets/aeroworld/lang/en_us.json`, `ru_ru.json`; `src/main/templates/META-INF/neoforge.mods.toml`.

---

## 7. Прочие файлы

- `AeroWorld_DH_SeedGen_TZ.md`: детальное техническое задание на реализацию аналитического DH SeedGen оверрайда и throughput-миксинов.
- `README.md`: историческое описание патча Vault/Trial для Layer 3.
- `build.gradle`: `options.encoding = 'UTF-8'` обязателен из-за кириллицы в исходниках. Зависимости включают `compileOnly` библиотеки DH и Physical Structures.

---

## 8. Расхождения и статус кодовой базы (2026-09-19)

### Добавленные компоненты, отсутствовавшие в старом индексе:
1. Пакет `org.example.aeroworld.mixin.dh`: 11 файлов интеграции, аксессоров и тюнинга Distant Horizons.
2. Пакет `org.example.aeroworld.mixin.structure`: миксины End City и Nether Fortress для корректного позиционирования в слоях AeroWorld.
3. Пакет `org.example.aeroworld.worldgen.column`: `AeroColumnModel` (единое аналитическое ядро) и `AeroColumnWriter` (конвертер span -> DH).
4. Пакет `org.example.aeroworld.worldgen.dh`: `AeroSeedWorldGenerator`, `AeroSeedWorldGenBinding`, `AeroThroughputLimits`, `AeroFastDistantTerrain`, `ReloadCoalescer`, `AeroSeedGenValidation`.
5. Конфигурации: `DhOverrideSettings.java`, `Layer3BodySettings.java`, обновлённый `AeroWorldConfig.java` с клиентскими настройками DH-оверрайда.
6. Модели и энумы: `BodyType.java` (планеты и метеориты Layer 3).
7. Рефакторинг: `getBaseColumn` и `getBaseHeight` в `AeroWorldChunkGenerator` переведены на делегирование в `AeroColumnModel`.

### Исправлено: миксины DH не применялись ("loaded too early"):
- `DhWorldGenBorderMixinPlugin` в `onLoad()` по строковому литералу грузил таргет-класс
  `BatchGenerationEnvironment_neoforge` через `Class.forName(...)`. Это полностью загружало
  (линковало) класс, который является таргетом `BatchGenerationEnvironmentNeoforgeMixin`,
  ДО того как Mixin успевал применить трансформацию -> в логах
  "Critical problem: ... loaded too early", инжекты не срабатывали, и батчевая генерация
  DH не сохраняла результат в LOD (на клиенте ничего не рендерилось, хотя счётчик прогресса рос).
- Фикс: детектор наличия DH заменён на не-таргетный класс `com.seibel.distanthorizons.core.Initializer`,
  который не является таргетом ни одного миксина. Мягкая зависимость сохранена, а
  `BatchGenerationEnvironmentNeoforgeMixin` теперь корректно применяется.

### Исправлено: дальние LOD не отображаются (applyToParent у API-пути SeedGen):
- Симптом (подтверждён логами и БД): DH-оверлей показывает огромную сгенерированную территорию
  (в `FullData` overworld-БД лежат ~82k источников, ~318 МБ), но в 3D-рендере виден только небольшой
  участок вокруг спавна; все миксины применяются, ошибок нет.
- Причина: vanilla-путь DH (`LodDataBuilder.createFromChunk`) выставляет на создаваемом
  `FullDataSourceV2` флаг `applyToParent = TRUE`, из-за чего DH даунсемплит точные LOD-данные в
  грубые родительские уровни, которые рендер использует для дальних блоков. API-путь SeedGen
  (`LodDataBuilder.createFromApiChunkData`) этот флаг НЕ ставит -> иерархия грубых LOD не строится:
  в БД у всех источников `ApplyToParent=0`, грубая пирамида обрывается на detail 8, выше ничего нет.
- Фикс: новый миксин `LodDataBuilderApplyToParentMixin` в `createFromApiChunkData` выставляет
  `applyToParent = TRUE` на возвращаемый источник (зеркально vanilla `createFromChunk`).
  `FullDataSourceV2.updateFromDataSource()` прокидывает флаг в агрегированный источник
  (guard detail < 15), и грубые LOD-уровни строятся.

### Мёртвый код и известные ограничения:
- `SinkholeCarver.java` не вызывается (`applyCarvers` пустой).
- `Layer1FlatGenerator.setVanillaSource`: no-op метод.
- `AeroColumnModel` (LOD-модель): слой 1 получает поверхность по биому/высоте через `Layer1TerrainGenerator.surfaceBlocks` (трава/песок/снег/подзол/гравий + 3 подповерхностных блока, единая логика с `buildSurface`); острова слоя 2 — перевёрнутый конус (`getDeformedBottomY`) с травой/землёй сверху, слоя 3 — эллипсоид (`getEllipsoidTop/BottomY`), слоя 4 — купол (`getCapTop/BottomY`). Не отражены в LOD: щупальца слоя 4, кольца планет, полость метеорита, мосты, деревья, растительность. `getBaseHeight` для островов всё ещё использует цилиндр (расхождение с `getBaseColumn`).
- Валидация `/aeroworld validateSeedGen 10000` и замеры производительности throughput (секции/сек) подготовлены в коде, но требуют запуска и фиксации результатов в работающей тестовой среде с DH.