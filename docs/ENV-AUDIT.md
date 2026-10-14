# Аудит `env/` и план упрощения

> Найдено при разборе: `env/`, `env/pkg/`, `env/overlay/`, `env/tests/`, `env/bootstrap/`,
> `env/scripts/`, `env/liblse_compat/`, а также смежных `bus/`, `build/phases/05b-env.sh`.
>
> Цель аудита — **сохранить поведение**, но убрать копипаст, мёртвый код, лишние
> форки, гонки и небезопасные предположения. Никакой публичный API не ломаем:
> формат `Packages.tsv`, файлы `*.meta`/`*.files`, `index_lookup`/`index_provider`,
> `cmd_install/cmd_remove/...` остаются совместимы. Тесты `selftest`/`invariant`/`e3`
> зелёные до и после.

## 0. TL;DR — топ косяков по убыванию критичности

| # | Где | Что | Риск | Действие |
|---|---|---|---|---|
| 1 | `env/overlay/bin/agy-fix-paths` vs `env/pkg/lib/util.sh:rewrite_prefix` | Два независимых path-rewrite'а, расходящаяся семантика: `agy-fix-paths` идёт по файлам через `grep -rIlZ`, `rewrite_prefix` (через `pkg install`) идёт по каталогам, но `sed -i` без `cmp`. `agy-fix-paths` **создаёт** `tmp` рядом с исходником (атомарно через `mv`), а `rewrite_prefix` правит in-place. | Дрейф поведения, лишние 200+ строк кода, шанс пропустить edge-case в одном из путей. | Один модуль `lib/rewrite.sh`, оба враппера — тонкие. |
| 2 | `env/pkg/lib/deb.sh:deb_field` | Использует `ar_member \| deb_decode \| tar -x` в `mktemp -d` **только** чтобы достать одну строку `control`. Это 3 процесса (ar → decode → tar) + создание временного каталога на каждое поле. | Производительность и лаг в холодном старте. | Парсить control вручную через `awk` после `ar_member \| deb_decode` (одна пайплайнизация). |
| 3 | `env/pkg/lib/util.sh:rewrite_prefix` | Передаёт `--` в `grep -rIlZ` после `${targets[@]}` — если целью передан файл, `grep -r` всё равно рекурсирует, что для больших префиксов создаёт лишние stat'ы. И ещё: идёт по `PREFIX` рекурсивно **без `--include`, но без `--exclude`** — после каждого `pkg install` обходит весь `$PREFIX` заново. | Время `pkg install` линейно зависит от размера префикса. | `--include` фильтр по расширениям и узким каталогам. |
| 4 | `env/overlay/bin/agy-daemon` (Python) | В shebang захардкожен `/data/user/0/com.agy/files/usr/bin/python3` — но в самом начале README проекта явно сказано, что префикс другой (`/data/data/com.agy/files/usr`). | Скрипт не запустится на устройстве, потому что `$PREFIX/bin/python3` — это `com.agy`, а не `com.agy`. | Shebang должен быть `#!/usr/bin/env python3` либо использовать `$PREFIX/bin/env python3`. |
| 5 | `env/overlay/bin/agy-daemon` | `def log()` пишет stdout **и** `LOG_FILE` через `open(..., "a")` без блокировки и без `flush()` файла. Если демон упал — лог может потерять хвост. | Молчаливая потеря логов. | Использовать `logging.handlers.WatchedFileHandler` или `print(..., file=f, flush=True)`. |
| 6 | `env/overlay/bin/agy-daemon` | `webhook` стартует на каждый цикл daemon_loop (10 c) — `if port not in webhook_threads` спасает от двойного bind, но при падении HTTP-сервера (`OSError: [Errno 98]`) демон **молча зависнет**: `serve_forever` блокирует, и нет ветки восстановления. | Демон ломается навсегда при коллизии порта. | try/except вокруг `HTTPServer`, перезапуск в следующем цикле. |
| 7 | `env/pkg/pkg:cmd_install` | `index_lookup` отдаёт **первое** имя, удовлетворяющее провайдеру, но `index_provider` делает `return 0` на первом же совпадении по Provides. Из-за этого, если в `repo.1` и `repo.2` есть `Provides: foo` для разных пакетов, всегда выигрывает repo.1, даже если repo.2 имеет более новую версию провайдера. | Резолвер может выбрать устаревший пакет. | Использовать `index_lookup`-подобную логику (best version) внутри `index_provider`. |
| 8 | `env/pkg/lib/resolver.sh:r_visit` | `mkdir -p` создаёт `$work/done` и `$work/doing` **внутри** рекурсии, не один раз. На графе с 1000 пакетов — 2000 лишних `mkdir`/stat. | Мелочь, но скользкая дорожка. | Один `mkdir` в `resolve`, проверка циклов — без `doing`. |
| 9 | `env/pkg/pkg:cmd_install` | Использует heredoc без кавычек: `while ... done <<EOF`. Внутри `index_lookup` есть `\t` — он пройдёт нормально, но если когда-нибудь `name` содержит `$(...)`, будет инъекция. Сейчас `index_lookup` фильтрует по `$1`, но лучше явная защита. | RCE-капля: при злом `.Packages.gz`. | Передавать имя через env var, не через stdin heredoc. |
| 10 | `env/overlay/bin/agy-notify` | Поведение `-m <msg>` и bare-аргумента **различается** без явного контракта: при наличии `-t` без `-m` bare-arg становится сообщением, иначе bare-arg = title, а message = title. Логика запутана. | Неочевидное API, баги в скриптах-обёртках. | Чёткий контракт: только флаги, никакой магии. |
| 11 | `env/overlay/bin/agy-fix-paths` | Хранит `tmp="$f.agyfix.$$"`. Если два процесса стартуют одновременно, у них разный `$$, всё ок. Но если процесс упадёт между `sed` и `mv`, останется мусор. Не критично, но мусор. | Мусор в `$PREFIX`. | `trap 'rm -f "$tmp"' EXIT`. |
| 12 | `env/pkg/lib/deb.sh:ar_members` | `size="$(printf '%s' "$hdr" \| cut -c49-58 \| sed 's/ //g')"` — `cut` берёт 10 символов и `sed` убирает пробелы. Но в ar формат `size` — **десятичная** ASCII-цифра, не hex. Если архив создавался на машине с локалью, отличной от C, в этих 10 байтах могут быть многобайтные UTF-8 цифры (арабские, деванагари) — и парсинг сломается. | Теоретический, но на андроиде локаль может быть что угодно. | `LC_ALL=C dd ...` перед парсингом. |
| 13 | `env/pkg/pkg:cmd_upgrade` | Для каждого пакета вызывает `db_remove` (теряет список файлов) и сразу `install_one`. Между этими шагами пакет **отсутствует** — если установка упадёт, пакет будет потерян. | Данные теряются на partial-failure. | Сначала скачать все `.deb`, потом `db_remove` + `deb_extract_data` атомарно, в конце `db_put`. |
| 14 | `env/pkg/lib/util.sh:rewrite_prefix` | `sed` без `LC_ALL=C` — если в файле есть UTF-8 путь, который выглядит как `/data/data/com.termux`, мы его не тронем. Но и не сломаем. | Минимальный. | `LC_ALL=C` для grep+sed. |
| 15 | `env/overlay/bin/agy-daemon` | `os.path.expanduser("~/.gemini/...")` — но `HOME` не выставлен в среде Android-приложения по умолчанию. Упадёт на относительном пути. | На устройстве: каждый запуск пишет лог в `~`, который раскрывается в `$HOME`/root, что может быть недоступно. | `CONFIG_DIR = os.environ.get("AGY_CONFIG_DIR", "/data/data/com.agy/files/.gemini/antigravity-app/daemon")`. |
| 16 | `env/overlay/bin/agy-daemon` | `--interval` для `delay`/`watch_file` — не используется, но парсится. Для `delay` оно просто игнорируется. Мусорный API. | Путаница. | Разделить: `--interval` только для periodic-триггеров. |
| 17 | `env/pkg/pkg:cmd_install` | При `install` с уже установленным пакетом пишет `log "already installed: $n"`, но **продолжает** устанавливать зависимости, если те не установлены — и в случае, когда сам пакет уже есть, его зависимости могут быть пропущены. Точнее: цикл идёт по `$order`, и для already-installed делает `continue`. Зависимости, которые уже установлены, в `$order` тоже есть, и для них тоже `continue` — ОК. Но если `resolve` для одного из уже установленных недопустим, мы не упадём (warn молча). | Тихий пропуск. | Хотя бы exit 2 при unresolved + сообщение. |
| 18 | `env/pkg/lib/db.sh:db_owner` | Использует `grep -rlF -- "$1" "$PKG_DB"/*.files`. На больших `$PKG_DB` — линейный скан всех `.files` на каждый `pkg why`. | Производительность. | Простой инвертированный индекс `path -> pkg` лениво, или хотя бы `grep -F -l` вместо `grep -rlF` (одна и та же скорость, но `grep -lF` без рекурсии и быстрее). |
| 19 | `env/pkg/lib/resolver.sh:choose_dep` | Парсит `(<op> <ver>)` через два `awk '{print $1}'` + `'{print $2}'` — это 2 форка на каждый alt. Для пакета с 20 зависимостями, каждая из которых 5 альтов = 200 форков. | Производительность. | Один `awk` + `set -f` для шелла. |
| 20 | `env/pkg/lib/index.sh:index_lookup` | Для каждого `tsv` запускает `awk -F'\t' -v n="$name" '$1 == n'`, и потом **внутренний** цикл `while read`. То есть: `awk` фильтрует строки, шелл их перебирает. | Лишний слой. | Можно сразу печатать из awk. Но это уже микро. |
| 21 | `env/pkg/pkg:install_one` | `ver="$(printf '%s' "$line" \| cut -f2)"` потом `arch="$(printf '%s' "$line" \| cut -f3)"` ... 4 раза `printf|cut` для 4 полей. | 4 форка на пакет. | `read -r ver arch fn sha <<<"${line#*\t}"` через `IFS=\t`. |
| 22 | `env/scripts/pack_bootstrap.py:pack_bootstrap` | `info.external_attr` и `info.date_time` берутся **как есть**, а zip-источник может быть сжат по-разному. `compresslevel=9` даёт ~5% лучший коэффициент, но жрёт RAM на устройствах с 2 ГБ. | Память. | `compresslevel=6` по умолчанию. |
| 23 | `env/liblse_compat/liblse_compat.c` | Хранится в git **скомпилированный** `.so` (`env/liblse_compat/liblse_compat.so`). Этот файл — артефакт сборки, и его `git diff` бессмыслен. | Мусор в репе. | `*.so` в `.gitignore` (или отдельный артефакт в `dist/`). |
| 24 | `env/pkg/pkg:cmd_remove` | Удаляет файлы, потом пытается удалить пустые родительские директории. Но если в `$PREFIX/foo/bar/baz` лежал `bar` (директория) с нашим файлом, а у другого пакета был `bar/keep` — мы удалим `baz`, потом `bar`, и сломаем чужой пакет. | Ломает чужие пакеты. | Не удалять директории, в которых есть **любой** файл, не наш. |
| 25 | `env/pkg/lib/util.sh` | `log`/`warn`/`die` пишут в stderr, но `cmd_install` в `--quiet`-режиме (которого нет) шумел бы так же. Сейчас нет флага, но если кто-то добавит — будет сложно. | Будущий баг. | Префикс `[pkg]` есть, но `--quiet`/`-q` нет. Добавить заранее. |
| 26 | `env/tests/e3` | `curl -sI --max-time 25 https://packages.termux.dev/...` — e3 требует **сеть**. Это сломает оффлайн-CI и любого разработчика без доступа. | CI ломается. | `e3` стоит разделить: `e3-offline` (сборка prefix'а) и `e3-online` (сетевые проверки). |
| 27 | `env/tests/invariant` | `find "$ENV_DIR/$src" -type f -print0` сравнивает с `$DIST/env/$src/...` побайтово. ОК. Но **symlink'и** не сравниваются — а `overlay/bin/rish` это симлинк на скрипт; если в `$DIST` окажется **копия**, тест не заметит. | Скрытый дрейф. | Расширить invariant: `find -type l` + сравнение target. |
| 28 | `env/pkg/pkg:install_one` | На каждом `pkg install` пересобирает `index_rebuild_all` — даже если индексы свежие. | Мелочь. | Кэш mtime. |
| 29 | `env/overlay/etc/profile` | `PREFIX="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"` — если `BASH_SOURCE[0]` содержит путь с пробелами, `dirname` ломается (без кавычек). | На устройстве путь может содержать пробелы. | `"$(cd "$(dirname "$_tlx_self")/.." && pwd)"`. |
| 30 | `env/overlay/etc/profile.d/00-env.sh` | Экспортирует `CFLAGS="-march=armv8-a -moutline-atomics ${CFLAGS:-}"` — но **всегда** в `-march=armv8-a`, даже если устройство ARMv8.1+. Это регрессия по сравнению с README, где говорится про «native LSE». | Код на устройстве работает медленнее, чем мог бы. | `${CFLAGS:-}` уже включает `-march=...` если `pkg install` правильно. Сейчас просто перебиваем. Лучше `CFLAGS=${CFLAGS:-}` без принудительного `-march`. |
| 31 | `env/overlay/bin/agy-daemon` | `parse_duration` поддерживает `s/m/h` — но не `d` (days). В README примере `4h` есть, `30m` есть, а `1d` нет. | Неполный API. | Добавить `d` (86400 сек). |
| 32 | `env/pkg/lib/version.sh:deb_vercmp` | Каждый вызов — это `awk -f vercmp.awk`. На `resolve` с 100 пакетами и 5 альтов каждый — 500 форков awk. | Производительность. | Можно закешировать в индексе `vercmp.awk` как mtime-проверку. Мелочь. |
| 33 | `env/overlay/bin/agy-daemon` | Импортирует `argparse`, но не использует его для `--battery-below` и т.д. — для каждого триггера `if/elif/elif` руками. | Копипаст. | Реально можно сделать чище через subparsers. |
| 34 | `env/overlay/bin/agy-daemon` | `db_sync_dpkg` пишет каждый раз при старте демона — но **на каждый вызов** она перечитывает `$PREFIX/var/lib/dpkg/status`. У Termux `status` может быть 50 МБ. | Перформанс на старте. | Один раз при старте, инвалидация по mtime. |
| 35 | `env/pkg/pkg:cmd_install` | Использует `index_rebuild_all` даже если `repo.*.tsv` уже свежий. | См. п.28. | -- |
| 36 | `env/pkg/pkg:cmd_install` | При `db_exists "$n"` — `log "already installed: $n"`, но **не запускает** `install_one`. То есть, если пакет был частично установлен (бывает после crash), мы **молча** пропускаем и идём дальше. | Тихая частичная установка. | Хотя бы `warn`. |
| 37 | `env/overlay/bin/agy-daemon` | `os.makedirs(CONFIG_DIR, exist_ok=True)` срабатывает только при `start` или `add`. А если первая команда — `list` при пустой `CONFIG_DIR`? `load_tasks` вернёт `[]`. ОК. | -- | OK, не баг. |
| 38 | `env/overlay/bin/agy-notify` | При `cancel` тег **обязателен** позиционно: `agy-notify cancel mytag`, но `--tag` тоже принимает. Двойной API. | Путаница. | Только `--tag`. |
| 39 | `env/pkg/pkg:cmd_install` | `fetch` падает через `die`, но `db_put` не вызывается (правильно). Но **файлы уже извлечены** в `$PREFIX`! | Полу-установка. | Транзакция: извлечь в `tmp/`, потом `mv` в `$PREFIX`. |
| 40 | `env/overlay/etc/antigravity/AGENTS.md` | Утверждает `LD_LIBRARY_PATH="/data/user/0/com.agy/files/usr/lib"` и `PATH="/data/user/0/com.agy/files/usr/bin"`, но фактический `$PREFIX` (см. `env/pkg/lib/util.sh`) — `/data/data/com.agy/files/usr`. | Документация лжёт. | Синхронизировать AGENTS.md и util.sh на один путь. |
| 41 | `env/overlay/bin/agy-notify` | Логика `LARGE_ICON_OPT` — `test -f $LOGO_CACHE` без кавычек, через `rish -c`. Если в пути есть пробелы (Documents/antigravity v2/), `$LOGO_CACHE` склеится в 2 токена. | Документация говорит «path with spaces». | `printf %q` для каждого аргумента или просто `--arg-file`. |
| 42 | `env/overlay/bin/agy-fix-paths` | Использует `grep -rIlZ -e "$PATTERN" -- "${targets[@]}"`. `grep -r` **рекурсивно** обходит каталоги. Если `$PREFIX` — это симлинк, рекурсия идёт по нему. Это обычно ОК, но если `$PREFIX` — симлинк на **самого себя** через `..` (бывает), `grep` зациклится. | Редко, но возможно. | `find -L` с защитой от циклов или `readlink -f` сначала. |
| 43 | `env/pkg/pkg:cmd_install` | `install_one` принимает имя пакета из stdin heredoc'а. Если в индексе пакет с переводом строки в имени — сегфолт не случится, но молча установится не тот пакет. | Edge case для кривых зеркал. | `awk` сам печатает имя, без участия шелла. |
| 44 | `env/pkg/lib/deb.sh:ar_member` | Цикл while без выхода, если файл не ar. Может крутиться до EOF (8 байт). | -- | Защита от не-ar. |
| 45 | `env/overlay/etc/profile.d/00-env.sh` | `mkdir -p "$TMPDIR" "$HOME" 2>/dev/null \|\| true` — даже если `$TMPDIR` = `/tmp`, мы создадим `/tmp` как `$HOME`? Нет, `$HOME` и `$TMPDIR` независимы. ОК. | -- | OK. |
| 46 | `env/pkg/lib/deb.sh:deb_files` | `ar_member` возвращает байты, дальше `tar -t`. Если `ar_member` не нашёл — deb_files выводит пустой `tar -t`, awk получает 0 строк, печатает ничего. ОК, но `tar` ругается в stderr — тесты могут ловить этот шум. | Шум. | `2>/dev/null` для `tar -t` тоже (уже есть). |
| 47 | `env/overlay/bin/agy-notify` | `RISH_BIN="/data/user/0/com.agy/files/usr/bin/rish"` — но `bus/cmd/env.sh` для профиля `default` ставит `PATH=$PREFIX/bin:...`. То есть `rish` доступен через `$PATH`. Жёстко зашитый абсолютный путь. | Дублирование. | `command -v rish`. |
| 48 | `env/pkg/pkg:cmd_info` | `awk -F'\t'` с длинным `printf` в одну строку. ОК. | Стиль. | Можно разбить. |
| 49 | `env/overlay/bin/agy-daemon` | `wake_antigravity_app` и `execute_action` и `check_*` — все на уровне модуля, не обёрнуты в `class`. | Стиль. | Оставить. |
| 50 | `env/tests/e3` | В конце делает `rm -rf "$(dirname "$PREFIX")"`. Если PREFIX = `/tmp/X/usr`, удалит `/tmp/X` — ок. Но если PREFIX = `/data/data/com.agy/files/usr` (кто-то задал переменную), удалит `com.agy/files` — **катастрофа**. | Катастрофа. | Защита: PREFIX должен начинаться с `/tmp/` или `$BUS_TMP`. |
| 51 | `env/pkg/lib/index.sh:index_get` | Это «E1 compat» — оставлено для совместимости, но не используется. Мёртвый код. | Мусор. | Удалить. |
| 52 | `env/pkg/lib/index.sh:index_best` | То же — мёртвый код, нигде не вызывается. | Мусор. | Удалить. |
| 53 | `env/pkg/lib/index.sh:index_files` | Используется `pkg list` и `pkg search`. Но `ls` без `-1` печатает в строку, а потом `for f in $(index_files)` разделит по пробелам. Если в имени файла есть пробел — сломается. | Edge case. | `find ... -print0`. |
| 54 | `env/overlay/bin/agy-notify` | `RISH_BIN` ищется в 3 местах по приоритету: `/data/user/0/com.agy/files/usr/bin/rish` → `cmd notification`. Третий фоллбек — голый `cmd notification`, без `rish`. Но `cmd notification` требует shell (`uid=2000`), а наше приложение работает под `uid` процесса приложения (не shell). То есть третий путь **не работает** на устройстве. | Молча не работает. | `die` если `rish` нет. |
| 55 | `env/pkg/lib/util.sh:rewrite_prefix` | `sed -i` без указания backup-расширения — в GNU sed это ОК, в BusyBox (Android shell) — тоже ОК. Но: `sed` **портит inode**, что ломает running процесс, который этот файл исполняет. `agy-fix-paths` это явно учитывает (rename atomic), `rewrite_prefix` — нет. | Гонка. | Использовать `tmp + mv` (как agy-fix-paths). |
| 56 | `env/pkg/pkg:cmd_install` | Принимает `install_one` через `while read -r n`. `n` может содержать пробелы (если в индексе такое имя, что невалидно, но всё же). heredoc без кавычек не интерпретирует, но `read -r` всё равно споткнётся о пробел. | Мусор. | `read -r n; [ -n "$n" ] || continue`. |
| 57 | `env/scripts/pack_bootstrap.py:pack_bootstrap` | `with open(target_file, "wb") as dst` без `os.chmod(target_file, mode)` если `mode == 0`. Сейчас `if mode:` — ОК. | -- | OK. |
| 58 | `env/overlay/bin/agy-notify` | `RISH_BIN` определена 3 раза в трёх ветках (`if/elif/else`). Дублирование. | -- | Один раз. |
| 59 | `env/tests/invariant` | `find "$DIST/env/pkg" "$DIST/env/overlay" -type f` ищет `*.tmp|*.part` — но `*.part` и `*.tmp` могут быть легитимными именами (если кто-то добавит файл с таким именем). | Ложное срабатывание. | Узкий матч или `find -name '*.part' -newer ...`. |
| 60 | `env/pkg/lib/db.sh:db_get` | `sed -n "s/^$2=//p"` — `$2` это **имя поля**, например `version`. Если оно содержит `=`, сломается. Но `version` не содержит. | -- | OK. |

## 1. Крупные блоки — что переписать

### 1.1 Path rewrite (объединить 2 → 1)

Сейчас две реализации:

* `env/overlay/bin/agy-fix-paths` (50 строк, **атомарно** через `mv`, корректно обрабатывает режимы)
* `env/pkg/lib/util.sh:rewrite_prefix` (10 строк, **неатомарно** через `sed -i`, не сохраняет режим)

План:
1. Создать `env/pkg/lib/rewrite.sh` — единственная реализация, **атомарная** как agy-fix-paths.
2. `agy-fix-paths` становится враппером: `exec pkg-fix-paths "$@"` или одна функция, если на устройстве только bash.
3. `pkg install` зовёт `rewrite_prefix` — теперь через общую библиотеку.

### 1.2 `deb_field` (убрать tar)

Сейчас:
```bash
ar_member "$f" "$m" | deb_decode "$m" | tar -x -C "$tmp"
awk -v k="$field" '...control'
```

Будет:
```bash
ar_member "$f" "$m" | deb_decode "$m" | awk -v k="$field" '
    BEGIN{IGNORECASE=1}
    /^[A-Za-z][A-Za-z-]*: /{
        key = substr($0, 1, index($0, ":") - 1)
        if (key == k) { sub(/^[^:]+: /, ""); print; exit }
    }'
```

Это **в 3 раза** меньше форков, и не нужно создавать временный каталог.

### 1.3 `cmd_install` (транзакционность)

Сейчас: extract → db_put. Если упадёт между — мусор в `$PREFIX` и нет записи в БД.

План:
1. Скачать все `.deb` (текущее поведение — fetch с cache, ОК).
2. Извлечь каждый во временный `$PKG_ROOT/staging/<name>`.
3. После успешной распаковки всех — `cp -al` (или `mv`) в `$PREFIX`.
4. Только теперь `db_put`.

Используем атомарность через staging + rename.

### 1.4 `agy-daemon` (Python: shebang, recovery, config)

* shebang → `#!/data/data/com.agy/files/usr/bin/python3` (уже), но добавить fallback на `python3` через `/usr/bin/env`.
* CONFIG_DIR через `AGY_CONFIG_DIR` env var с дефолтом.
* webhook try/except + restart.
* `logging.handlers.WatchedFileHandler` вместо ручного `open("a")`.
* Удалить мёртвый `--interval` для delay.

### 1.5 `agy-notify` (убрать дублирование RISH_BIN)

* Один `RISH_BIN` через `command -v` или `${PREFIX}/bin/rish` (через `$PREFIX`).
* Чёткий контракт: только флаги.
* `die` если rish нет и нужно постить.

## 2. Косметика и стиль

* `e3` разделить на `e3-offline` и `e3-online`.
* `invariant` расширить проверкой симлинков.
* `index_get`/`index_best` — удалить (мёртвый код).
* `index_files` — `find -print0`.
* `00-env.sh` — убрать принудительный `-march=armv8-a`.

## 3. Безопасность

* `cmd_install` — heredoc → `IFS= read -r n` через `printf '%s\n' "$order" | while read`.
* `cmd_remove` — не удалять родительские каталоги, если в них есть файлы, не принадлежащие удаляемому пакету.
* `e3` — защита: `[[ "$PREFIX" == /tmp/* ]]` или явный `BUS_TMP`.

## 4. Что НЕ трогаем (по SPEC.md и осторожности)

* Формат `Packages.tsv` — обратно совместим.
* Формат `*.meta`/`*.files` — обратно совместим.
* `cmd_*` имена и сигнатуры — обратно совместимы.
* `env/tests/selftest` — все проверки должны проходить до и после.
* `env/tests/invariant` — все проверки должны проходить до и после.
* Файлы `agy-daemon`, `agy-fix-paths`, `agy-notify`, `rish` остаются в `env/overlay/bin/`.
* `liblse_compat.so` — оставляем в git как binary-blob, но добавляем в `.gitignore` для пересборки.

## 5. План работ (порядок, безопасный)

1. **Аудит-документ** (этот файл) ✅
2. **`.gitignore`** — добавить `__pycache__/`, `*.so` в `env/liblse_compat/`, `env/dist/`.
3. **`env/pkg/lib/rewrite.sh`** — новая библиотека, покрыть тестом в `selftest`.
4. **`env/overlay/bin/agy-fix-paths`** → переписать через `rewrite.sh`.
5. **`env/pkg/lib/util.sh:rewrite_prefix`** → переписать через `rewrite.sh`.
6. **`env/pkg/lib/deb.sh:deb_field`** → однопайплайновый awk.
7. **`env/pkg/lib/index.sh`** — удалить `index_get`/`index_best`, починить `index_files`.
8. **`env/pkg/pkg:cmd_install`** — транзакционная установка.
9. **`env/pkg/pkg:cmd_remove`** — безопасное удаление директорий.
10. **`env/overlay/bin/agy-daemon`** — shebang, config, recovery, logging.
11. **`env/overlay/bin/agy-notify`** — единственный `RISH_BIN`, чистый API.
12. **`env/tests/invariant`** — проверка симлинков.
13. **`env/tests/e3`** — разделение offline/online, защита от `/data/...`.
14. **`env/overlay/etc/profile.d/00-env.sh`** — убрать принудительный `-march`.
15. **`env/overlay/etc/antigravity/AGENTS.md`** — синхронизировать пути.
16. **Тесты**: `selftest`, `invariant`, `e3-offline` — все зелёные.
17. **Чек-лист**: см. п.16 ниже.

## 6. Чек-лист для PR

```sh
# 1. Синтаксис
bash -n env/pkg/pkg env/pkg/lib/*.sh env/overlay/bin/* env/tests/*
python3 -m py_compile env/overlay/bin/agy-daemon env/bootstrap/patch-prefix.py env/scripts/pack_bootstrap.py

# 2. Юнит-тесты (offline, без сети)
bash env/tests/selftest                       # должен быть 0 failures
ASSETS=env/dist bash env/tests/invariant      # после build.sh assets
bash bus/selftest                              # чтобы не сломать bus

# 3. Сборка активов
bash env/build.sh all
ls -la env/dist/env/{pkg,overlay,bootstrap}

# 4. Сквозной (online) — на устройстве/CI с сетью
bash env/tests/e3
```

Все четыре шага должны быть зелёными.
