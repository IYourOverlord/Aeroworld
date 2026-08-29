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
├── command/
│   └── AeroWorldCommands.java     — /aeroworld forcePlacePending, findIsland2/3/4 
├── config/
│   ├── AeroWorldSettings.java     — root-record {layer2, layer3, layer4}, сериализуется в dimension JSON
│   └── Layer2/3/4Settings.java    — параметры геометрии островов соответствующих слоёв
├── event/
│   ├── ProximityTriggerHandler.java   — спавн структур когда игрок рядом (ключевой файл)
│   └── ShorelineWaveHandler.java      — визуальная симуляция наката волн на пляже
├── registry/
│   ├── AeroDimensions.java        — регистрация ChunkGenerator codec
│   └── AeroRegistries.java        — точка регистрации всех DeferredRegister'ов мода
├── spawning/
│   └── LayerSpawnRestriction.java — блокирует спавн мобов на Layer 4 (Y >= 2000)
├── structure/
│   ├── IslandStructureScheduler.java  — потокобезопасная очередь размещения структур
│   ├── PendingStructureData.java      — SavedData: персистентная очередь структур с backoff/retry
│   ├── StructurePlacementHelper.java  — проверка готовности чанков и свободного места
│   └── StructureSizeCache.java        — кэш размеров NBT-структур
└── worldgen/
    ├── AeroWorldChunkGenerator.java   — ★ главный генератор, расширяет NoiseBasedChunkGenerator, координирует все слои
    ├── biome/
    │   └── AeroBiomeSource.java       — кастомный BiomeSource, подменяет биомы на aeroworld:* клоны
    ├── cache/
    │   ├── ChunkIslandCache.java   — общий кэш списков центров островов (все 3 слоя)
    │   └── IslandData.java         — иммутабельный value-object острова (bounds, radius, geometry)
    ├── feature/
    │   ├── Layer1OreFilter.java    — активен: чистит ЛЮБУЮ руду по всей высоте чанка
    │   └── vault/                  — генерация Vault/Trial Spawner внутри тела острова (Layer 2/3/4)
    ├── layer/
    │   ├── Layer1FlatGenerator.java     — [УСТАРЕЛО] Изначально кастомная генерация Layer 1, теперь код заполнения блоков не используется; делегируется ванильному генератору. 
    │   ├── Layer1SinkholeCarver.java    — карстовые воронки под островами 2/3/4
    │   ├── Layer1CoralScatter.java      — редкие кораллы на пляжах у кромки воды
    │   ├── LowerIslandGenerator.java    — Layer 2: острова + деревья + мосты
    │   ├── HighIslandGenerator.java     — Layer 3: эллипсоиды
    │   ├── UpperIslandGenerator.java    — Layer 4: медузы/щупальца
    │   ├── Layer2StructurePlacer.java   — ставит tank21 в очередь
    │   └── Layer3StructurePlacer.java   — ставит excraft:HAUL-01 в очередь
    ├── util/
    │   ├── ChunkWriter.java           — интерфейс записи блоков для абстрагирования от ChunkAccess
    │   ├── ChunkAccessWriter.java     — адаптер для ChunkAccess
    │   └── SectionDirectChunkWriter.java — ★ прямая запись в LevelChunkSection для потокобезопасной параллельной генерации слоёв
    └── structure/                 — валидация структур (деревни и т.п.) под кастомный рельеф
        └── StructureSupportValidator.java  — ★ главный класс валидации (вызывается из createStructures)
```

---

## 3. Жизненный цикл генерации одного чанка

Порядок вызовов в пайплайне NeoForge/Minecraft:

1. **`fillFromNoise`** 
   - Layer 1: делегируется родительскому классу `super.fillFromNoise()` (ванильная amplified генерация 3D-рельефа).
   - Layer 2, 3, 4: генерируются **асинхронно в параллельных потоках** (`CompletableFuture.runAsync`) с использованием `SectionDirectChunkWriter` (zero-allocation прямая запись в изолированные `LevelChunkSection`, обходящая разделяемый `Heightmap`).
   - Размещение структур в очередь (HAUL-01, tank21) также выполняется асинхронно в фоновых потоках вместе со слоями.

2. **`applyCarvers`**
   - Вызывает `super.applyCarvers()` (ванильные пещеры режут рельеф).
   - **Сразу после этого** вызывает `restoreIslandsInChunk()` — перегенерирует острова, чтобы ванильные пещеры не порвали тонкие мосты/щупальца.

3. **`buildSurface`**
   - Layer 1: делегируется `super.buildSurface()` — корректные биомные правила поверхности (снег, песок, гравий).

4. **`applyBiomeDecoration`**
   - Ванильная декорация поверхности + деревья Layer 2.
   - Размещение Vault/Trial Spawner на островах слоев 2, 3 и 4.
   - **`Layer1OreFilter.applyToChunk()`** очищает чанк от любой сгенерированной руды (отключена во всем мире).

5. **`createStructures`**
   - Проверка легальности ванильных структур через `StructureSupportValidator`.

---

## 4. Размещение кастомных структур (tank21 / HAUL-01)

- **`tank21`** — NBT структура (Layer 2), регистрируется через `PhysicalStructures`.
- **`HAUL-01`** — файл `.excraft` (Layer 3), размещается через `StructureSourceProviderRegistry`.

Поток: C2ME WorldGen Поток -> `IslandStructureScheduler` -> `PendingStructureData` (SavedData) -> Игрок подходит близко (`ProximityTriggerHandler`) -> `StructurePlacementHelper` -> `spawnStructureResult`.

Для прегенерации без игроков: `/aeroworld forcePlacePending`.

---

## 5. Известные особенности текущей архитектуры

1. **Наследование генератора:** Класс `AeroWorldChunkGenerator` наследуется от `NoiseBasedChunkGenerator`, чтобы движок Minecraft (через `RandomState.create()`) корректно считывал настройки шумов (например, профиль `minecraft:amplified`). 
2. **Layer 1 полностью ванильный:** Старый самописный класс `Layer1FlatGenerator` больше не используется для заливки блоков и покраски поверхности (его методы `fillChunk` и `applyLayer1Surface` обойдены). 
3. **Руда отключена:** Генераторы руды закомментированы, а `Layer1OreFilter` активно вырезает всё, что могла добавить ванильная декорация.
4. **Vault / Trial Spawner:** Находятся внутри островов (Layer 2/3/4). Логика спавна использует ванильные лут-таблицы в ресурсах датапака, а размещение происходит напрямую в `applyBiomeDecoration` через ручную сборку NBT `BlockEntity`.
5. **Zero-Allocation на горячих путях:** Используется библиотека `fastutil` (`LongArrayList`, упаковка XZ-координат через `ChunkKey.of(long)`) вместо `List<int[]>` в `IslandPlacer` и `ChunkIslandCache`. Это полностью предотвращает GC-паузы и аллокацию массивов `new int[]` при массовом поиске центров островов.

---

## 6. Ресурсы датапака

- **`aeroworld.json` (world_preset):** Главный пресет мира. Внутри `vanilla_generator` установлен параметр `"settings": "minecraft:amplified"`.
- **Биомы:** `worldgen/biome/*.json` содержат клоны ванильных биомов без руды.
- **Лут таблицы:** `loot_table/gameplay/layerN/` содержит дроп для Vaults и Trial Spawners.

---

## 7. С чего начать при доработке

- **Настройка высоты / формы слоев:** `LayerNSettings.java` (параметры) + соответствующий `*IslandGenerator.java`.
- **Генерация поверхности Layer 1:** Зависит теперь полностью от ванильного Amplified шума. Ищите изменения в датапаках плотности или биомах.
- **Дроп на островах:** Только через редактирование `.json` лут таблиц в `resources/data/aeroworld/loot_table/...`
- **Проблемы с ванильными структурами:** `StructureSupportValidator.java` и `StructureCategoryResolver.java` (если подводная или надземная структура генерируется не там).
