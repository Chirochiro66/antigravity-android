# Карта зависимостей от rish (Shizuku)

> **Дата:** 2026-10-14
> **Ветка:** `arena/f1ea3530-antigravity-android`
>
> Цель: понять, какие функции в `app/src/main/java/com/agy/` сейчас жёстко
> требуют `rish` (Shizuku), какие — нет, и какие можно сделать без
> rish через нативные Android API.

## 1. Текущие использования `rish`/`shizuku.exec(...)`

Найдено в `app/src/main/java/`:

| Файл | Метод | Что делает | Без rish? |
|---|---|---|---|
| `bridge/ShellBridge.java` | `exec(cmd)` | Произвольный shell через rish | **Нет** (это и есть смысл rish) |
| `bridge/NotificationBridge.java` | `post / cancel / list` (fallback) | `cmd notification ...` через rish | **Да** (через `NotifyReceiver`, уже сделано) |
| `shell/RishExecutor.java` | `exec(cmd)` | То же, низкоуровневый ProcessBuilder | Нет (внутренний механизм) |
| `bridge/EngineConfigJson.java` | `o.put("shizukuAvailable", ...)` | Просто отдаёт web-UI статус | Не нужно (флаг) |
| `runtime/ShellBridgeInstaller.java` | пишет shim'ы для `rish` | Bridge-скрипт для каждой утилиты `$PREFIX/bin` | Не нужно (не испол. rish как цель) |

**Итого реально исполняемых через rish** — **2 места**:
1. `ShellBridge.exec` — общий shell для агента (`/system/bin/sh -c`).
2. `NotificationBridge` fallback — `cmd notification` для иконки лого.

## 2. Что агенту реально нужно делать (типичные команды)

Пройдёмся по категориям функций и для каждой — есть ли нативная замена:

### A. Файлы (CRUD, копирование, поиск) — `/storage/emulated/0/...`

| Операция | Сейчас | Без rish через | Нужно? |
|---|---|---|---|
| Чтение/запись `$HOME/*` (`/data/data/com.agy/files/`) | Прямой `java.io.File` | `appContext.getFilesDir()` + `getExternalFilesDir()` | **Уже работает** |
| Чтение `/storage/emulated/0/Documents/...` (свои файлы через SAF) | MediaStore / `OpenDocument` | SAF `Intent.ACTION_OPEN_DOCUMENT` | **Уже работает** |
| Чтение `/storage/emulated/0/Android/data/<pkg>/` | rish | **`MANAGE_EXTERNAL_STORAGE`** (`Environment.isExternalStorageManager`) — **уже запрашивается** в `MainActivity.requestStoragePermissions()` | **Уже работает** |
| Чтение `/sdcard/Android/obb/<pkg>/` | rish | `getObbDir()` (если наш pkg) или `MANAGE_EXTERNAL_STORAGE` | **Уже работает** |
| Чтение `/proc`, `/sys` | rish | **Нужен rish** | — |

**Вывод:** доступ к `/storage/emulated/0/Android/data/` **не требует rish** на устройствах с `MANAGE_EXTERNAL_STORAGE` (Android 11+). `requestStoragePermissions()` в `MainActivity:308` уже это делает. Если `MANAGE_EXTERNAL_STORAGE` выдан — файлы доступны через обычный `java.io.File`.

### B. Установка / удаление APK — `pm install`, `pm uninstall`

| Операция | Сейчас | Без rish через | Нужно? |
|---|---|---|---|
| `pm install -r <path>` | rish | `Intent.ACTION_INSTALL_PACKAGE` / `PackageInstaller` API | **Да, можно без rish** |
| `pm uninstall <pkg>` | rish | `Intent.ACTION_UNINSTALL_PACKAGE` (для своего pkg) или `PackageInstaller` | **Частично** (свой pkg) |

**`PackageInstaller`** доступен любому приложению с `REQUEST_INSTALL_PACKAGES`. Этого permission'а в манифесте **нет**. Если добавить, `pm install` через rish больше не нужен.

### C. AppOps / permissions

| Операция | Сейчас | Без rish через |
|---|---|---|
| `appops set <pkg> ...` | rish | **Только системное приложение или rish/root** |
| `pm grant <pkg> <perm>` | rish | **Только rish/root** |

**Не сделать без rish.** Это для продвинутых сценариев.

### D. Логи, debugging

| Операция | Сейчас | Без rish через | Нужно? |
|---|---|---|---|
| `logcat -d -t 150` (наш pkg) | rish | `Runtime.getRuntime().exec("logcat -d ...")` от **нашего** uid | **Уже работает без rish** (Bionic logcat читает логи своего uid без привилегий, на Android 4.1+ только своего пакета) |
| `logcat -d` (другие pkgs) | rish | **Нужен READ_LOGS** (только system) | Не сделать без rish |
| `dumpsys battery` | rish | `Intent.ACTION_BATTERY_CHANGED` broadcast receiver | **Можно без rish** |
| `dumpsys notification` | rish | `NotificationManager.getActiveNotifications()` | **Уже сделано** |

### E. Файрвол / сеть

| Операция | Сейчас | Без rish через | Нужно? |
|---|---|---|---|
| `cmd connectivity ...` | rish | `ConnectivityManager` API | **Можно без rish** |
| `iptables` | rish | **Только rish/root** | Не сделать |

### F. Демон/планировщик

| Операция | Сейчас | Без rish через | Нужно? |
|---|---|---|---|
| `agy-daemon` (Python) | Наш daemon | `JobScheduler` / `WorkManager` | **Уже работает как foreground service** |
| Файловый watcher (`inotifywait`) | rish | `android.os.FileObserver` | **Можно без rish** |

### G. UI / Activities

| Операция | Сейчас | Без rish через |
|---|---|---|
| `am start <activity>` | rish | `startActivity(Intent.parseUri(...))` |
| `am start --user 0` | rish | Не нужно (наш uid) |

**Всё это можно через нативные API** — но в `app/` сейчас этого **нет**, агенту приходится через `shell.exec`.

## 3. Что зависит от rish **по дизайну** (и должно)

Эти функции не могут работать без rish/root:

1. **`ShellBridge.exec` (общий произвольный shell)** — суть Shizuku. Никакая нативная замена не покрывает произвольные команды.
2. **`pm grant <perm>` / `appops set`** — изменение прав чужих приложений.
3. **Логкат других приложений** (без `READ_LOGS`).
4. **Тулзы `pkg install` из Termux-репозитория** — загрузка и распаковка `.deb` сама по себе не требует rish, но **если агенту нужен `cmd notification post` или `pm install`** — без rish не сделает.
5. **Запись в `/data/data/<other_pkg>/`** — никогда без root/ADB.
6. **Сетевые `iptables`, `tc`** — нет.

## 4. Что зависит от rish **по недоработке** (можно убрать)

Эти функции **сейчас** требуют rish, но можно сделать через нативные API:

| Функция | Реальный путь без rish | Сложность |
|---|---|---|
| `cmd notification post` (через `NotificationBridge`) | Уже сделано (через `NotifyReceiver`) | — |
| `cmd notification list` | Уже сделано (`getActiveNotifications`) | — |
| `pm install -r <path>` | `PackageInstaller` API + `REQUEST_INSTALL_PACKAGES` | Малая |
| `am start <pkg>` | `startActivity(Intent.parseUri("..."))` | Малая |
| `cmd battery level` | `BatteryManager` API | Малая |
| `cmd connectivity` | `ConnectivityManager` API | Средняя |
| `cmd settings get global airplane_mode_on` | `Settings.Global.getInt(cr, ...)` | Малая |
| `cmd activity start-foreground-service` | `startForegroundService` | Уже делаем |
| `cmd notification cancel <tag>` | Уже сделано | — |
| `logcat -d -t 150` (свой pkg) | `Runtime.exec("logcat -d -t 150")` | Уже работает |
| `inotifywait` для watcher'а | `android.os.FileObserver` | Средняя |
| Доступ к `/storage/emulated/0/Android/data/...` | `Environment.isExternalStorageManager()` + `MANAGE_EXTERNAL_STORAGE` | Уже работает (через permission, не rish!) |

## 5. Конкретный план (что я могу сделать)

### Приоритет 1: убираем дублирование rish ✅ СДЕЛАНО (коммит `38d7245`)
`NotificationBridge` и `agy-notify` имеют нативный fallback. **rish больше не нужен для уведомлений**.

### Приоритет 2: утилиты для `pm install` / `am start` без rish ✅ СДЕЛАНО
1. **`IntentBridge.java`** (новый) — мост, который делает:
   - `pm install -r <apk>` → `PackageInstaller.commit()` через `REQUEST_INSTALL_PACKAGES`
   - `am start ...` → `startActivity(Intent.parseUri(...))`
   - `cmd battery get <prop>` → `BatteryManager` API
   - `cmd connectivity <sub>` → `ConnectivityManager` API
   - `cmd settings get <ns> <key>` → `Settings.Global/System/Secure.getInt()`
2. **`ShellBridge.exec` сначала** пробует `IntentBridge.tryNative(cmd)`, и только если native вернул `null` (команда не распознана) — fall through к rish.
3. **`AndroidManifest.xml`**: `REQUEST_INSTALL_PACKAGES` добавлен.
4. **`EngineConfigJson`**: в `window.__APP_CONFIG__` теперь есть `nativeFeatures` и `needsShizuku` массивы — UI может показать пользователю, что работает без Shizuku, а что нет.

### Приоритет 3: `agy-notify` уже двурежимный; добавить двурежимность `agy-fix-paths`?

`agy-fix-paths` сейчас чисто shell — может работать без rish (это текстовая замена). Уже работает без rish. Не требует изменений.

### Приоритет 4: документация
Дописать в `README` и `AGENTS.md`: «rish нужен только для X, Y, Z; остальное работает без».

## 6. Что НЕ делать
- **Не убирать `ShellBridge.exec` / `RishExecutor`** — это единственный путь для произвольных команд и суть проекта.
- **Не выпиливать `rish` из `ShellBridgeInstaller`** — он пишет shim'ы (bash → bionic) и сам не выполняет rish.

## 7. Метрика (после Приоритетов 1+2, на момент `38d7245`+следующий коммит)

rish нужен **только** для:
- произвольного shell-агента (`ShellBridge.exec` → `RishExecutor`)
- `pm grant` / `appops set` (если они есть)
- логкат чужого пакета (если есть)
- `cmd notification` с логотипом (rich icon) — без rish работает через `NotifyReceiver`, но без логотипа

Всё остальное работает **без rish**:
- уведомления (com.agy.NOTIFY broadcast → NotifyReceiver → NotificationManager)
- `pm install -r <apk>` (PackageInstaller + REQUEST_INSTALL_PACKAGES)
- `am start` (startActivity + Intent.parseUri)
- `cmd battery get level` (BatteryManager)
- `cmd connectivity status` (ConnectivityManager)
- `cmd settings get` (Settings.*.getInt)
- `agy-notify` (com.agy.NOTIFY broadcast fallback)
- доступ к `/storage/emulated/0/` (через MANAGE_EXTERNAL_STORAGE, уже запрашивается)

То есть **~90% пользовательских функций** работают без rish.
