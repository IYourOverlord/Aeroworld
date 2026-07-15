# HAUL-01.excraft — куда класть файл

Этот файл (копия `HAUL-01.excraft`, которую вы прислали) — НЕ ресурс датапака
и не должен лежать внутри jar/датапака мода. Это снимок Sable sub-level'а
(формат `create_aeronautics_toolgun`), и его читает только сам Toolgun.

## Куда положить на сервере/клиенте

```
<gamedir>/blueprints/HAUL-01.excraft
```

Пример для инстанса из логов:
```
C:\Users\Admin\AppData\Roaming\PrismLauncher\instances\1.21.1\minecraft\blueprints\HAUL-01.excraft
```

`ExcraftStructureHandler` (physical_structures) ищет файл именно там, беря
имя из id `excraft:HAUL-01` → `blueprints/HAUL-01.excraft`. Регистр имени
файла должен совпадать точно.

## Почему не как раньше

Раньше AeroWorld пытался прочитать этот файл как обычную vanilla NBT-
структуру (`data/aeroworld/structures/haul_01.nbt`, `nbt_location` в JSON).
Технически файл — валидный gzip-NBT, поэтому загрузка не падала с ошибкой,
но `StructureTemplate`-парсер вычитывал из него не те теги (внутри —
`toolgun_constraints`, `root_sublevel`, `sublevels`, `plot`, `chunks`, а не
`size`/`palette`/`blocks`) и в итоге "собирал" почти пустую структуру —
отсюда `Assembled 1 block(s)` в логе.

Теперь AeroWorld ставит в очередь id `excraft:HAUL-01` и делегирует
размещение `StructureSourceProviderRegistry` (physical_structures) →
Toolgun, который понимает формат нативно.

## Требование

Для этого обязательно должен быть установлен `create_aeronautics_toolgun` —
без него провайдер `excraft:` не регистрируется, и HAUL-01 не сможет
разместиться (об этом будет явная запись в логе AeroWorld при старте).
