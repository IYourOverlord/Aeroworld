# AeroWorld - Project Index

Мод для Minecraft 1.21.1 (NeoForge 21.1.228, Java 21, ModDevGradle 2.0.141, `mod_version` 1.0.12). Кастомное измерение с многослойной генерацией: поверхность + три слоя парящих островов, разделённых большими пустыми зазорами (зазоры являются частью дизайна: до островов должно быть трудно добраться).

Зависимости: `compileOnly` jar из `libs/` (`physical_structures-*.jar`, `DistantHorizons-*.jar`). В `neoforge.mods.toml` обязательна только `physical_structures [1,)`. DH является опциональной мягкой зависимостью (soft dependency). Регистрация `HAUL-01.excraft` удалена; в `ProximityTriggerHandler` осталась ветка для id с namespace `excraft`, которая в текущей конфигурации не срабатывает (в очередь ставится только `physical_structures:tank21`).

Пакет: `org.example.aeroworld`. Корень исходников: `src/main/java/org/example/aeroworld/`.

Индекс сверен с кодовой базой 2026-10-11.

---

## 1. Слои мира

| Слой | Y-диапазон | Что генерируется | Генератор | Настройки |
|---|---|---|---|---|
| Layer 1 | -64 .. 300 (`Layer1TerrainGenerator.MIN_Y/MAX_Y`) | Кастомный рельеф на собственном шуме (континентальность, эрозия, хребты, речные долины). `SEA_LEVEL = 0`. Высота грунта клампится в -58 .. 220: глубокий океан около -47, шельф около -8, пляж около 2, суша от 3, горы и хребты до 220. Редкие плато `ashen_plateau` (огромные, километры: кайма из плит +30 с крутым краем и террасами шагом 6 на разных уровнях, внутри узкие извилистые каньоны по нулевому контуру отдельного шума (дно ~60 блоков, стены ~30 блоков с карнизами, `PlateauTerrace.core`), мелкие овраги (`gully`, до 35% глубины), останцы-башни до +32 с карнизами (`pillar`) и каменные мосты поперёк каньона (`bridge`: плита 4 блока на уровне каймы, пустота под ней вырезается в `buildSurface` через `plateauBridgeVoid`; LOD видит перемычку сплошной), дно не ниже Y 8; поверхность STRATA выбирается по маске рельефа, а не по биому чанка, маска `getPlateauMask`, математика в `PlateauTerrace`; биом и высота берут одну маску). Гигантская пещера Y -50 .. -25 под всей сушей (не под океаном) с мхом, светящимся лишайником и пещерными лианами. 5 слоёв бедрока. Крепость Нижнего мира фиксируется в Y -55..-50 (`NetherFortressStructureMixin`). Глубокие озёра: 10% сток-озёр (`AeroRiverNetwork.DEEP_LAKE_SHARE`) копаются плавной чашей `deep^1.5` до плоского дна Y -50 (`DEEP_LAKE_FLOOR_Y`), биом `deep_lake` (маска `Layer1TerrainGenerator.isDeepLake`), по одному океаническому монументу на озеро (раздел 5, п. 9). | `worldgen/layer/Layer1TerrainGenerator.java` | Жёстко в коде. `settings: minecraft:overworld` нужен только базовому `NoiseBasedChunkGenerator` (структуры), рельеф из него не берётся |
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
│   ├── dh/                            - миксины оптимизации throughput, исправления багов и диагностики Distant Horizons
│   │   ├── BatchGenerationEnvironmentNeoforgeMixin.java - фикс бага DH: бордер региона и структуры на стыке батча (таргет DH 3.3.x: DhChunkGenerator_neoforge.generateChunks)
│   │   ├── CompleteSectionCacheMixin.java             - закорачивает getPositionsToRetrieve для подтверждённо полных секций (HEAD/RETURN-инъекции в кэш AeroCompleteSectionCache, аналог CompleteSectionCache из DH SeedGen)
│   │   ├── DhWorldGenBorderMixinPlugin.java             - IMixinConfigPlugin: soft dependency проверка наличия DH
│   │   ├── GeneratorBusyMixin.java                      - масштабирование порога занятости генератора (IN_FLIGHT_SCALE)
│   │   ├── LodQuadTreeRealRadiusMixin.java              - держит detail 0 (лист) до реального радиуса R*16 блоков вокруг игрока (штатная формула calcDetailLevelFromDistance даёт лишь 24-44 чанка)
│   │   ├── LodRenderSectionAdjDiagMixin.java            - диагностика швов на границах секций LOD (getRenderSourceForPos), счётчик пустых соседей
│   │   ├── CanQueueStatsMixin.java                      - этап 4, только измерение: причины отказа canQueueRetrievalNow и несохранённые секции (AeroHybridStats), применяется только при `-Daeroworld.dhStatsMixins=true`
│   │   ├── QueueOrderStatsMixin.java                    - этап 0, только измерение: при старте листовой задачи считает ждущие грубые задачи и сколько из них накрывают лист (AeroHybridStats), удаляется вместе с ней
│   │   ├── RegenGateStatsMixin.java                     - этап 0, только измерение: попытки перезапроса листьев и условие-стопер queueRegeneration (AeroHybridStats.RegenGate), require = 0
│   │   ├── RenderLoaderPriorityMixin.java               - этап 4в, эксперимент: подмена приоритета пула Render Loader (константа 6 в ThreadPoolUtil.setupThreadPools) флагом `-Daeroworld.dhRenderLoaderPriority`, по умолчанию без изменений, require = 0
│   │   ├── RetrievalQueueLimitMixin.java                - масштабирование очереди выборки LOD (QUEUE_SCALE), цель GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount()I
│   │   ├── SaveDelayMixin.java                          - задержка сброса LOD на диск (SAVE_DELAY_MS = 10000)
│   │   ├── SqliteTuningMixin.java                       - тюнинг PRAGMA SQLite (cache_size, mmap_size, synchronous)
│   │   ├── StandInTerrainMixin.java                     - устранение дыр рендера LOD каскадным downsample родительских секций
│   │   ├── TranslucentAdjWallMixin.java                 - скрытие паразитных вертикальных граней воды на стыках с неготовыми соседними секциями
│   │   ├── WorldGenSpeedGateMixin.java                  - устранение замедления генерации при очереди рендера
│   │   └── WorldGenUtilRegenRadiusMixin.java            - лимит штатной регенерации листьев 2*R*16+128 вместо surfaceRegenMaxDistancePercent от дальности LOD (R=0 -> 0: второй проход выключен)
│   └── structure/                     - инжекции в ванильные структуры для привязки к слоям AeroWorld
│       ├── EndCityStructureAccessor.java     - аксессор ванильного generatePieces в EndCityStructure
│       ├── EndCityStructureMixin.java        - принудительный спавн End City на планетах Layer 3, вырезка корабля
│       ├── OceanMonumentBuildingMixin.java   - низ монумента Y 39 -> MONUMENT_MIN_Y (дно озера), заливка водой до SEA_LEVEL вместо Y 64 (только здания ниже нуля)
│       ├── OceanMonumentStructureMixin.java  - старт монумента только в чанке центра глубокого озера, считает MONUMENT_MIN_Y
│       └── NetherFortressStructureMixin.java - сдвиг Nether Fortress в пещеру Layer 1 (Y -55..-50)
├── registry/
│   ├── AeroDimensions.java            - CHUNK_GENERATOR aeroworld:aero_generator
│   ├── AeroRegistries.java            - регистрация генератора, aeroworld:aero_biome_source и aeroworld:deep_lake_monument (тип размещения структур)
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
│   ├── AeroColumnWriter.java          - конвертер спанов AeroColumnModel в DhApiTerrainDataPoint (detailLevel=0, эксклюзивный topY, воздух в пустотах, порядок сверху вниз) с кэшированием обёрток
│   ├── AeroStructureCover.java        - аналитическая модель покрытия структурами для LOD: детерминированный предикат End City на планете Layer 3 + вертикальный шаблон блоков поверх спанов AeroColumnModel (без RNG и StructureManager)
│   ├── AeroTreeCover.java             - аналитическая модель деревьев для LOD: детерминированные спаны стволов и крон по климатическим группам биомов без запуска ванильного Feature-пайплайна
│   └── SliceVote.java                 - слияние колонок по Y-срезам по схеме DH (твёрдое побеждает пустоту, среди твёрдых побеждает самое частое), carve() для вставки шаблонов; проверка: SliceVoteSelfCheck
├── dh/
│   ├── AeroAdjacencyCache.java        - LRU-кэш декодированных FullDataSourceV2 соседних секций (StandInTerrain)
│   ├── AeroCompleteSectionCache.java  - LRU-кэш подтверждённо полных секций, закорачивает getPositionsToRetrieve
│   ├── AeroFastDistantTerrain.java    - пороги detailLevel для раздельного упрощения геометрии слоёв
│   ├── AeroHybridStats.java           - этап 0 гибридной прогрузки: только счётчики и лог 30 с (повторные запросы секций, порядок очереди, цена реальных чанков); отключается `-Daeroworld.dhstats=false`, удаляется после этапа 0; проверка: AeroHybridStatsSelfCheck
│   ├── AeroLeafLedger.java            - журнал листовых секций detail 0, которым аналитика уже отдала картинку (шаг SURFACE): append-only файл на диске уровня, фаза 2 (реальный chunk-gen) переживает перезапуск; проверка: AeroLeafLedgerSelfCheck
│   ├── AeroLeafSlots.java             - чистый (без MC) счётчик одновременных реальных листьев с атомарным резервированием под лимит `-Daeroworld.dhRealLeafCap` (этап 1б), проверка: AeroLeafSlotsSelfCheck
│   ├── AeroPlayerAnchors.java         - снимок XZ-позиций игроков по измерениям (обновляется раз в 20 тиков на server thread) для потоков DH world-gen: чтение ServerLevel.players() с чужого потока небезопасно
│   ├── AeroPoolStats.java             - чистая (без MC) диагностика этапа 4в: ожидание в очереди пула, тело задачи и время после тела (цепочка колбэков DH) для аналитических задач; включается `-Daeroworld.dhPoolStats=true`, проверка: AeroPoolStatsSelfCheck
│   ├── AeroSeedGenValidation.java     - dev-валидатор: поблочное сравнение AeroColumnModel и getBaseColumn на N точках
│   ├── AeroSeedWorldGenBinding.java   - слушатель DhApiLevelLoadEvent, регистрация оверрайда и stand-down логика
│   ├── AeroSeedWorldGenerator.java    - реализация IDhApiWorldGenerator (EDhApiWorldGeneratorReturnType.API_CHUNKS)
│   ├── AeroStructureMarkers.java      - миниатюры структур на LOD (detail <= 5), стоят на земле своего слоя; позиции пробным createStructures на одноразовом ProtoChunk (кэш по ячейкам 32x32 чанка, подземные структуры и End City пропущены, см. AeroStructureIcon.shownOnLod); -Daeroworld.dhStructures=false отключает
│   ├── AeroStructureIcon.java         - чистые (без MC) воксельные шаблоны миниатюр по id структуры (послойные ASCII-схемы), проверка: AeroStructureIconSelfCheck
│   └── AeroThroughputLimits.java      - счётчик чанков и секций, периодический отчёт производительности (chunks/s, sections/s, declined листьев); `startRealLeaf` запускает реальный лист под лимитом `REAL_LEAF_CAP`
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
│   ├── AeroRiverNetwork.java          - чистая (без MC) сеть рек и озёр: узлы сетки 640 блоков стекают к соседу с меньшей континентальностью, русла расширяются к устью, замкнутые впадины и часть узлов дают озёра; Layer1TerrainGenerator.getHeight вырезает русла/чаши ниже SEA_LEVEL, в горах (height > 20..50) реки гасятся; `Sample.deep` и `deepLakeCentre(chunkX, chunkZ)` для глубоких озёр (центр в мире решается обратным преобразованием искажения); проверка: AeroRiverNetworkSelfCheck
│   ├── Layer1FlatGenerator.java        - тонкая обёртка (surfaceHeight/topmostHeight) для валидатора и биомов
│   ├── PlateauTerrace.java            - чистая (без MC) математика плато `ashen_plateau`: террасы, каньоны, овраги, останцы, мосты с пустотой под декой, биом-маска; проверка: PlateauTerraceSelfCheck
│   ├── LowerIslandGenerator.java       - Layer 2: fillChunk, placeTreesInRegion, clearVanillaVegetationInCentralZone
│   ├── LowerIslandGeneratorAccess.java - узкий интерфейс доступа к world seed нижних островов для кода, которому нужен только сид, без зависимости от полного генератора (реализует LowerIslandGenerator)
│   ├── HighIslandGenerator.java        - Layer 3: полые метеориты с кратерами и планеты с кольцами астероидов
│   ├── UpperIslandGenerator.java       - Layer 4: купол + трассировка щупалец в AABB чанка
│   └── Layer2StructurePlacer.java      - в fillFromNoise ставит tank21 в очередь для обычных RICH-островов
├── noise/
│   ├── AeroNoise.java                 - Perlin 2D/3D + fbm
│   ├── IslandPlacer.java              - сетка ячеек, anti-overlap, архипелаги (25% ячеек Layer 2), AABB-фильтр
│   └── IslandShape.java               - профили конуса, precomputeXZ, isSolid
├── structure/
│   ├── DeepLakeMonumentPlacement.java      - StructurePlacement «один старт на глубокое озеро» (чанк центра озера), срабатывает только при IS_GENERATING; ThreadLocal MONUMENT_MIN_Y для миксинов монумента
│   ├── AncientCityIslandSupportPlacer.java - ступенчатая deepslate-платформа под ancient_city до пола пещеры
│   ├── StructureCategory.java         - SURFACE, ISLAND, UNDERGROUND, WATER, SKY_FLOATING, DENY
│   ├── StructureCategoryResolver.java - deny-список, токены путей, resolveForActualLayer
│   ├── StructureSupportValidator.java - вызывается ТОЛЬКО из createStructures; проверка медианы подошвы (medianOf, глубокие выбросы не смещают): StructureSoleYSelfCheck
│   ├── SupportSample.java             - record сэмпла неудачной опоры структуры (x, z)
│   ├── ValidationResult.java          - результат валидации структуры (accepted, rejectionReason, supportRatio)
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
3. **Биомы.** `AeroBiomeSource` с `ThreadLocal<BiomeColumnCache>` (64 слота direct-mapped) — все 17 октав шума Layer 1 и `delegate.getNoiseBiome` на quart y=20 сэмплируются ровно 1 раз на XZ-колонку (16 раз на чанк вместо 8384). Мемоизация `vanilla -> aero` через `ConcurrentHashMap<Holder<Biome>, Holder<Biome>>`. quart y > 75: клон `aeroworld:<path>`; океаны, `dripstone_caves`, `lush_caves`, `deep_dark` -> `aeroworld:plains`. quart y <= 75: `aeroworld:<name>` через `AeroBiomeRegistryCache`. Y -64..-8: пятна `aeroworld:deep_dark` для Ancient City. `possibleBiomes()` = 61 клон + биомы ванильного пресета. `deep_lake` (клон `deep_ocean`) ставится маской `Layer1TerrainGenerator.isDeepLake` (параметр `deepLake` в `resolveLayer1Biome`), пятно `deep_dark` на дне озера его не перекрывает.
   Для аналитического пути добавлены прямые методы `getLayer1BiomeName(x, z)`, `isDeepDark(x, z)`, `getIslandBiomeName(x, z)`, `findAeroBiome(name)`.
4. **Интеграция с Distant Horizons (SeedGen Override).**
    - Реализована мягкая зависимость: при отсутствии DH на classpath (`ClassNotFoundException`) оверрайд тихо отключается без падения игры (`AeroSeedWorldGenBinding.registerIfDhPresent()`).
    - Плагин миксинов `DhWorldGenBorderMixinPlugin` загружает миксины пакета `org.example.aeroworld.mixin.dh` только если класс `com.seibel.distanthorizons.core.Initializer` присутствует.
    - Оверрайд регистрируется в `DhApiLevelLoadEvent` только для генераторов `instanceof AeroWorldChunkGenerator` и при включённом тумблере в конфигурации (`dhOverrideEnabled`). При несовместимости выполняется stand-down, и DH возвращается к штатному батчингу.
    - Throughput-миксины оптимизируют очереди, приоритизацию рендера над генерацией и транзакции SQLite базы LOD.
5. **Руды.** `remove_ores.json` убирает 19 ванильных `minecraft:ore_*` (шаг `underground_ores`) из всех 61 биома `#aeroworld:aero_biomes`; `Layer1OreFilter` дочищает блоки ванильных руд по списку (чужие руды не трогает). Руды других модов (Create: цинк и т.д.) добавляют `neoforge:add_features`, привязанные к `#minecraft:is_overworld`; клоны `aeroworld:*` входят в этот тег через `data/minecraft/tags/worldgen/biome/is_overworld.json`, иначе чужие руды, спавны и фичи в измерение не попадают. Действует только на новые чанки.
6. **Структуры.** Валидация только в `createStructures`. `ancient_city` принимается всегда и получает платформу. WATER допускаются на Layer 1 без проверки опоры, на островах отклоняются. `end_city` принудительно центрируется на планетах Layer 3, `fortress` смещается в главную пещеру Layer 1.
7. **Команды.** `findIsland2/3/4`: спиральный обход ячеек `IslandPlacer` нужного слоя, телепорт на `topY + 5`. `validateSeedGen [count]`: сверка вывода столбцовой модели с `getBaseColumn`.
8. **Два способа попасть в измерение.** World preset заменяет overworld и не содержит `aero_settings` (используются Java-DEFAULT: Layer 2 grid 25, радиус 25..110; Layer 4 spawn 0.05, grid 30, радиус 25..35). Отдельное измерение `aeroworld:aeroworld` из `dimension/aeroworld.json` использует явные `aero_settings` (grid 20, радиус 50..110 и т.д.). Команды и тик-обработчики проверяют namespace `dimension_type` и работают в обоих случаях; `LayerSpawnRestriction` сравнивает ключ уровня с `aeroworld:aeroworld` и в мире через preset не срабатывает.
9. **Глубокие озёра и монумент.** Сток-озёра (радиус 130..270) с шансом 10% глубокие: `Sample.deep` (сила только глубоких озёр, `<= lake`) добавляет к чаше `(SEA_LEVEL - DEEP_LAKE_FLOOR_Y - 12) * deep^1.5`, дно плоское на Y -50 внутри 0.55 радиуса. Биом и высота читают одну маску (`isDeepLake`: `deep > 0.5` и дно не выше Y -15, чтобы горы, гасящие чашу, не давали `deep_lake` без воды). Монумент: `DeepLakeMonumentPlacement` даёт старт в чанке центра озера (только если там дно на Y -50), `OceanMonumentStructureMixin` отклоняет все прочие старты монумента в AeroWorld и считает `MONUMENT_MIN_Y = дно - 4`, `OceanMonumentBuildingMixin` подставляет его вместо ванильного Y 39 и режет заливку водой до `SEA_LEVEL`. Валидатор пропускает WATER на Layer 1 без проверки опоры.

---

## 6. Ресурсы датапака и конфигурации

- `aeroworld.mixins.json`: конфигурация миксинов DH с плагином `DhWorldGenBorderMixinPlugin` (мягкая зависимость).
- `aeroworld_structures.mixins.json`: конфигурация миксинов структур (`EndCityStructureMixin`, `NetherFortressStructureMixin`, `OceanMonumentStructureMixin`, `OceanMonumentBuildingMixin`).
- `data/aeroworld/dimension/aeroworld.json`: генератор `aeroworld:aero_generator`, `settings: minecraft:overworld`, `aero_settings`.
- `data/aeroworld/dimension_type/aeroworld.json`: `min_y -64`, `height 2096`.
- `data/aeroworld/worldgen/world_preset/aeroworld.json` + `data/minecraft/tags/worldgen/world_preset/normal.json`: пресет, overworld заменён без `aero_settings`.
- `data/aeroworld/worldgen/biome/*.json`: 61 клон `aeroworld:*` (включая `deep_lake`).
- `data/aeroworld/tags/worldgen/biome/aero_biomes.json`: все 61 клон, область `remove_ores.json`.
- `data/minecraft/tags/worldgen/biome/is_overworld.json`: `#aeroworld:aero_biomes`, чтобы биом-модификаторы других модов (руды Create и др.) применялись к клонам; `c:is_overworld` включает этот тег.
- `data/aeroworld/neoforge/biome_modifier/remove_ores.json`: `neoforge:remove_features`, шаг `underground_ores`.
- `data/minecraft/tags/worldgen/biome/has_structure/*.json`: биомы ванильных структур переопределены на `aeroworld:*`.
- `data/minecraft/worldgen/structure_set/*.json`: переопределённые spacing/separation (включая `end_cities.json` с `spacing=1`).
- `data/aeroworld/worldgen/structure_set/deep_lake_monuments.json`: `minecraft:monument` с размещением `aeroworld:deep_lake_monument` (по одному на глубокое озеро). `data/minecraft/tags/worldgen/biome/required_ocean_monument_surrounding.json` добавляет `aeroworld:deep_lake` (ванильная проверка биомов вокруг монумента), `has_structure/ocean_monument.json` тоже содержит `deep_lake`. `/locate` для этого размещения не работает.
- `data/minecraft/worldgen/structure/end_city.json`, `bastion_remnant.json`: кастомные высоты и настройки структур.
- `data/aeroworld/loot_table/gameplay/layer{2,3,4}/`: vault и trial_spawner, normal и ominous.
- `data/aeroworld/presets/*.json`: справочные примеры `aero_settings`. Загрузчика в коде нет, игрой не читаются.
- `data/physical_structures/...`: `tank21.json`, `tank21.nbt`.
- `assets/aeroworld/lang/en_us.json`, `ru_ru.json`; `src/main/templates/META-INF/neoforge.mods.toml`.

---

## 7. Прочие файлы

- `PROGRESS.md`: последовательный трекер задач по воспроизведению функционала SeedGen (деревья, поверхности, миксины DH).
- `AGENTS.md`: системные правила для разработки и рефакторинга в репозитории.
- `README.md`: историческое описание патча Vault/Trial для Layer 3.
- `build.gradle`: `options.encoding = 'UTF-8'` обязателен из-за кириллицы в исходниках. Зависимости включают `compileOnly` библиотеки DH и Physical Structures.


