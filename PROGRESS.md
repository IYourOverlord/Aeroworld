# Трекер воспроизведения функционала SeedGen в AeroWorld (PROGRESS.md)

Полный последовательный чеклист и техническое руководство по воспроизведению, адаптации и закрытию всех 11 функциональных блоков SeedGen в моде AeroWorld.

---

### [x] 1. Быстрый heightfield-семплер вместо полного ChunkGenerator
* **Аналог в SeedGen**: `SeedHeightSampler.create/of`, `SeedColumnSampler`, `LodNoiseContext`, `binarySearchSurface`/`solvedSurface`.
* **Что делает SeedGen**:
  - Оборачивает `NoiseRouter` из `RandomState` через `LodRouterWrapper.apply` (кэши `LodCaches.CellInterpolated`, `Column2D`, `LastValue`).
  - Вместо поблочного вертикального сканирования density-функции на каждый Y использует бинарный поиск по двум точкам (solid/air) с линейной интерполяцией точки пересечения. Сложность $O(\log \text{height})$ на столбец.
* **Статус в AeroWorld**: **ЗАКРЫТО (Решено эффективнее)**.
* **Детали реализации**:
  - [x] Рельеф Layer 1 в `Layer1TerrainGenerator.getHeight(wx, wz)` вычисляется по прямой аналитической формуле (fbm2D/ridged noise).
  - [x] Сложность вычисления в AeroWorld — $O(1)$ без обращения к NoiseRouter и без бинарного поиска. Перенос кода SeedGen не требуется.

---

### [x] 2. Интерполяция density-функции по ячейкам
* **Аналог в SeedGen**: `LodCaches.CellInterpolated`, `LodRouterWrapper`.
* **Что делает SeedGen**: Кэширует density-функцию в узлах грубой сетки (`cellWidth`/`cellHeight`) и интерполирует промежуточные значения (`corner`, `lerp`).
* **Статус в AeroWorld**: **ЗАКРЫТО (Неприменимо)**.
* **Детали реализации**:
  - [x] Специфично исключительно для генераторов на функциях плотности (ванильный `NoiseBasedChunkGenerator`). В кодовой базе AeroWorld рельеф полностью формульный.

---

### [x] 3. Аналитическая генерация поверхностных блоков по правилам биома
* **Аналог в SeedGen**: `BiomeSurfaces`, `BiomeSurfaces.read`, `Compiler.rule/test/block`, `Rule.blockAt`, `SurfaceBlocks.Surface`.
* **Что делает SeedGen**: Парсит JSON `SurfaceRules` биомов через собственный легковесный интерпретатор (дерево `Node/Test`), заменяя тяжелый `SurfaceSystem.buildSurface()`.
* **Статус в AeroWorld**: **ЗАКРЫТО (Выполнено)**.
* **Текущее состояние в коде**:
  - [x] Аналитический маппинг поверхности по биомам и типам рельефа уже работает в `Layer1TerrainGenerator.surfaceBlocks`, `classifyPath`, `SurfaceType` со сложностью $O(1)$.
* **Задачи к выполнению**:
  - [x] **3.1. Высотные пояса (`Elevation Bands` / `Surface.withElevationBands`)**:
    - Реализация вариаций блоков по высоте (например, чередующиеся полосы цветной терракоты в биомах бэдлендов, скальные прожилки в горах) напрямую в `Layer1TerrainGenerator.surfaceBlocks`.
    - **Закрыто 2026-09-22**: Добавлен `BADLANDS_BANDS[32]` (6 цветов терракоты), `STONY_BANDS[16]` (stone/calcite/tuff). `buildSurface` применяет per-Y бэнды на глубину 15 блоков для бэдлендов, 8 для stony/karst. `surfaceBlocks` возвращает band-блок по `surfaceY-1`. LOD (`AeroColumnModel.addSurfaceColumn`) использует увеличенную глубину подповерхностного слоя.
  - [x] **3.2. Снежные шапки и обледенение (`Snow Caps` / `Frozen Surface` / `withSnowCaps` / `withFrozenSurface`)**:
    - Динамическое наложение снежного покрова и льда на воду/поверхность в зависимости от высоты Y и температуры биома при построении LOD-столбца.
    - **Закрыто 2026-09-22**: Снежные шапки (Y >= 60, COLD/DEFAULT) уже работали через `surfaceBlocks`. Добавлено: ICE на поверхности воды (SEA_LEVEL) для frozen-биомов — и в `buildSurface` (реальная генерация), и в `AeroColumnModel.buildSpans` (LOD-столбец, split water/ice span).
  - [x] **3.3. Подводная растительность (`Kelp` / `Seagrass` / `withKelp`)**:
    - Генерация спанов ламинарии и морской травы в океанических биомах для устранения "голого" дна на дальних LOD.
    - **Закрыто 2026-09-22**: Реализован детерминированный семплер `Layer1TerrainGenerator.sampleKelpHeight` и наложение спанов `BS_KELP_PLANT` и `BS_SEAGRASS` в `AeroColumnModel.buildSpans`.

---

### [x] 4. Деревья на LOD через захват ванильных фич
* **Аналог в SeedGen**: `TreeCover`, `TreeGrower`, `BiomeTrees`, `ParsedTree`, `TreeShape`, `TreeTemplate`.
* **Что делает SeedGen**:
  - `BiomeTrees.Parser` читает `ConfiguredFeature<TreeConfiguration>` биома.
  - Прогоняет через виртуальный мир-перехватчик `TreeGrower` с перехватом через `FoliagePlacer.FoliageSetter`.
  - Фиксирует геометрию как `ParsedTree` (`TreeShape`: `log`, `leaves`, `trunkHeightFor(variation)`, `canopyTopAt(radius)`, `canopyBottomAt(radius)`).
  - `TreeCover.Rule` задает параметры: `treesPerChunk`, взвешенный набор видов (`cumulative`), высотные границы (`lowest`/`highest`).
  - `TreeCover.sample(seed, x, z, surfaceTop, rule, ground, canopy)` детерминированно решает, растет ли дерево, и выбирает вид/высоту (`pick`, `pickIndex`, `grows`).
  - Встраивает спаны ствола и кроны поверх поверхности.
* **Статус в AeroWorld**: **ЗАКРЫТО (Выполнено)**.
* **Задачи к выполнению**:
  - [x] **4.1. Архитектура модели деревьев (`AeroTreeShape` / `AeroParsedTree`)**:
    - Созданы структуры `AeroTreeCover.AeroTreeShape` (блок ствола, листва, диапазон высот, радиус, высота кроны, профиль `SPHERE`/`CONE`/`FLAT`) с пресетами для 8 климатических групп: Oak, Birch, Spruce, Dark Oak, Jungle, Acacia, Cherry, Mangrove.
  - [x] **4.2. Правила покрытия биомов (`AeroTreeCover.Rule`)**:
    - Реализован маппинг `AeroTreeCover.getRuleForBiome` с плотностью `chancePerCell`, допустимым высотным диапазоном и привязкой к биомам AeroWorld.
  - [x] **4.3. Детерминированный семплер (`AeroTreeCover.sample`)**:
    - Реализован быстрый клеточный семплер `AeroTreeCover.sampleLayer1` (сетка 4×4, 64-битный SplitMix хеш, отсечение крон по профилям) и `LowerIslandGenerator.sampleTreeColumn` (воспроизведение шума и кольцевой зоны деревьев Layer 2).
  - [x] **4.4. Встраивание в столбцовую модель (`AeroColumnModel.buildSpans`)**:
    - В `AeroColumnModel.buildSpans` для Layer 1 и Layer 2 при `sampleBiomes == true` добавляются спаны стволов и листвы в общий упорядоченный список спанов LOD.

---

### [x] 5. Заполнение "дыр" в LOD данными родителя во время генерации (StandInTerrain)
* **Аналог в SeedGen**: `StandInTerrain`, `StandInTerrainMixin`.
* **Что делает SeedGen**:
  - Механизм мгновенной видимости рельефа (эффект "17500 чанков/сек"): при запросе детального сектора, если реальные данные еще в генерации (`EMPTY` / `DOWN_SAMPLED` gap), берет данные ближайшего готового родительского сектора quadtree (`copiedDown` / `fillGaps`) и растягивает их вниз через `updateFromDataSource`.
  - При готовности детальной генерации фоновые данные бесшовно перезаписывают заглушку.
* **Статус в AeroWorld**: **ЗАКРЫТО (Выполнено)**.
* **Задачи к выполнению**:
  - [x] **5.1. Инжекция в рендер-секцию DH**:
    - Создан `StandInTerrainMixin` на `com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection.getRenderSourceForPos` (`@Inject(..., at = @At("RETURN"), cancellable = true)`).
  - [x] **5.2. Реализация апсемплинга заглушек (`withGapsFilled`)**:
    - При пустом или отсутствующем `ColumnRenderSource` выполняется обход вверх по дереву `DhSectionPos.getParentPos` (до 3 уровней), каскадный downsample через `FullDataSourceV2.updateFromDataSource` и трансформация в `ColumnRenderSource`. Пустые "дыры" рендера мгновенно замещаются геометрией родителя.

---

### [ ] 6. Структуры на дальнем LOD
* **Аналог в SeedGen**: `StructureCover`, `Structures`, `TreeTemplate.Column`.
* **Что делает SeedGen**: Детерминированно по site-хешу (`solve`) проверяет наличие структуры в регионе, тестирует уклон рельефа (`isFlatEnough`) и накладывает предзаписанный вертикальный шаблон блоков структуры поверх рельефа.
* **Статус в AeroWorld**: **НЕ НАЧАТО (Низкий приоритет, опционально)**.
* **Задачи к выполнению**:
  - [ ] **6.1.** Оценить необходимость отображения крупных структур (древние города, платформы, крепости) на сверхдальних LOD.
  - [ ] **6.2.** Реализация легковесного детерминированного чекера `AeroStructureCover.solve(chunkX, chunkZ)`.
  - [ ] **6.3.** Наложение колоночных шаблонов структуры поверх спанов `AeroColumnModel`.

---

### [ ] 7. Кэш соседних секций для границ чанков (AdjacencyCache)
* **Аналог в SeedGen**: `AdjacencyCache`, `FullDataSourceV2RepoMixin`, `FetchSplitProbeMixin`, `AdjacencyDecodeProbeMixin`.
* **Что делает SeedGen**: Потокобезопасный LRU-кэш (`hits/misses/evictions`, `synchronized(ROWS)`) для исключения повторного чтения и декодирования соседних `FullDataSourceV2` из SQLite при построении граней геометрии на стыках секций.
* **Статус в AeroWorld**: **НЕ НАЧАТО (Оптимизация ядра DH)**.
* **Задачи к выполнению**:
  - [ ] **7.1.** Провести профилирование частоты промахов и нагрузки на чтение SQLite при сшивке секций в AeroWorld.
  - [ ] **7.2.** При выявлении просадок перенести миксины кэширования смежных секций в `org.example.aeroworld.mixin.dh`.

---

### [ ] 8. Кэш полностью сгенерированных секций (CompleteSectionCache)
* **Аналог в SeedGen**: `CompleteSectionCache`.
* **Что делает SeedGen**: LRU-кэш битовых масок секций, подтверждающий полную готовность всех дочерних уровней секции и исключающий повторные обходы дерева квадрантов.
* **Статус в AeroWorld**: **НЕ НАЧАТО (Оптимизация ядра DH)**.
* **Задачи к выполнению**:
  - [ ] **8.1.** Анализ необходимости миксина в пайплайне проверок статуса секций DH.

---

### [x] 9. Троттлинг мировой генерации vs рендер-загрузки
* **Аналог в SeedGen**: `ThroughputLimits`, `GeneratorPlan`.
* **Что делает SeedGen**: Динамический балансировщик очередей и лимитов между пулом потоков worldgen и потоком загрузки рендера.
* **Статус в AeroWorld**: **ЗАКРЫТО (Выполнено)**.
* **Детали реализации**:
  - [x] Внедрен класс `AeroThroughputLimits` (`SECTIONS_PER_CHUNK = 131`, адаптивный `distantHorizonsThreadCount()`, замер throughput в секциях/сек и чанках/сек).
  - [x] Миксины `GeneratorBusyMixin`, `RetrievalQueueLimitMixin`, `WorldGenSpeedGateMixin` уже устраняют блокировки генерации со стороны рендера.

---

### [ ] 10. Программное расширение дистанции прогрузки (ExtendedRenderDistance)
* **Аналог в SeedGen**: `ExtendedRenderDistance`.
* **Что делает SeedGen**: Слушает событие загрузки уровня/изменение конфига и программно выставляет `renderDistance` сверх официальных лимитов UI ползунка через API DH (`follow`, `apply(chunks)`).
* **Статус в AeroWorld**: **НЕ НАЧАТО (Тривиальная интеграция)**.
* **Задачи к выполнению**:
  - [ ] **10.1.** В `AeroSeedWorldGenBinding` добавить хук чтения целевой дистанции из `aeroworld-client.toml` или серверных настроек.
  - [ ] **10.2.** Программная установка значения через `DhApi.Delayed.configs` в соответствующий `IDhApiConfigValue<Integer>`.

---

### [ ] 11. Защита сохранений SQLite и коалесценция запросов (DurableSaveGuard)
* **Аналог в SeedGen**: `DurableSaveGuard`, `ReloadCoalescer`.
* **Что делает SeedGen**: Контролирует SQLite-чекпоинты (WAL-checkpointing) для предотвращения повреждения БД при падениях и схлопывает частые каскадные reload-запросы одной секции.
* **Статус в AeroWorld**: **Частично закрыто через миксины тюнинга**.
* **Текущее состояние в коде**:
  - [x] `SqliteTuningMixin` настраивает PRAGMA `cache_size`, `mmap_size`, `synchronous = NORMAL`.
  - [x] `SaveDelayMixin` предотвращает чрезмерно частый сброс на диск (`SAVE_DELAY_MS = 10000`).
* **Задачи к выполнению**:
  - [ ] **11.1.** Проверить устойчивость файла `.sqlite` при резком завершении процесса и при необходимости внедрить хук штатного закрытия/чекпоинта SQLite перед остановкой сервера/клиента.
