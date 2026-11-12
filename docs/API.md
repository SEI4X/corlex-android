## API

```kotlin
Corlex.track("purchase", mapOf("plan" to "pro", "amount" to 12.5))
Corlex.identify("user_42", mapOf("plan" to "pro")) // связывает события устройства с пользователем
Corlex.setUserProperties(mapOf("locale" to "ru"))
Corlex.reset() // выход: забыть user_id, новый анонимный id

Corlex.flag("paywall", false)          // Boolean / String / Int / Long / Double
Corlex.config.get("trial_days", 7)     // то же через CorlexConfig
Corlex.experiment("paywall_test")      // ExperimentAssignment? (variant, value); чтение = экспозиция

Corlex.optIn(); Corlex.optOut(); Corlex.consent()
Corlex.screen("settings")              // экран вручную
```

Имя события: 1–128 символов `[a-z0-9_.:-]`, иначе оно отбрасывается (в `debug` выводится общий код `invalid_event_name` без значения, один раз на экземпляр). Свойства обрезаются по лимитам протокола (100 ключей, строки 1024 байта, вложенность 4). Событие больше 32 КиБ отбрасывается.

### Что есть в каждом событии

`context`: `platform: android`, `app_version` (`versionName`), `os: Android`, `os_version`, `device_model` (`Build.MODEL`), `locale`, `sdk`. Свойство `build` (`versionCode`, строкой) добавляется ко всем событиям. Пара `app_version` + `build` — ключ релиза, по которому сервер подбирает `mapping.txt`. ANDROID_ID и рекламный идентификатор не читаются; анонимный id — случайный UUID, который создаёт SDK.

### Что собирается само

- `app_open` при запуске (по первой Activity: запуск процесса из WorkManager без экрана событие не создаёт), `app_background` / `app_foreground` (поворот экрана не считается).
- `screen_view`: `screen`, `source` (`activity` | `compose`), `previous_screen`. Один и тот же экран подряд считается один раз.
- `corlex.crash` — см. ниже.

### Экраны в Compose

```kotlin
val navController = rememberNavController()
rememberCorlexScreens(navController) // одна строка
NavHost(navController, startDestination = "home") { … }
```

Экраном считается **шаблон маршрута** (`item/{id}`), а не то, что открыл человек (`item/8731`): значения аргументов не покидают устройство. В приложении с одной Activity запускайте SDK с `CorlexOptions(trackActivityScreens = false)`, иначе экран придёт и как `activity`, и как `compose`. Для экрана вне навигации (страница пейджера, вкладка): `CorlexScreen("pager/2")`. Имена Activity в сборке с R8 обфусцированы; задавайте имена экранов явно (`Corlex.screen`) или держите правила keep для своих Activity.


## Semantics

Event names use 1–128 characters from a-z, 0-9, underscore, dot, colon and hyphen. Do not build names/screens from user input. Properties are bounded/sanitized JSON; an individual event is limited to 32 KiB. identify links an internal application ID, not a hardware/advertising identifier. reset creates a fresh anonymous identity without relabeling queued history. Remote Config defaults are returned when a value is absent/inapplicable; listing config is not an experiment exposure.
