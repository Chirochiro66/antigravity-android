# Итог рефакторинга env/

**Дата:** 2026-10-14
**Ветка:** `arena/f1ea3530-antigravity-android`
**Базовая точка:** `6d05c02` (старый HEAD до merge)

## Что было сделано

### 0. Merge dev (1 коммит)

Подтянул 9 коммитов из `dev` (`4515c97`). Это v2.3.0 hardening, POSIX daemon, seed packages, env manifest. Все AA-конфликты разрешены в пользу dev (это ваш эталон).

### 1. Фаза 1 — баги и атомарность (1 коммит `8e7e48b`)

| # | Что | Где |
|---|---|---|
| A | `install_one` стал **транзакционным**: extract + rewrite в `$PKG_ROOT/staging/<name>`, потом `cp -a` в `$PREFIX` только при успехе. Partial install больше не мусорит в `$PREFIX`. | `env/pkg/pkg` |
| B | `cmd_remove` **безопасен для соседей**: rmdir делается только для директорий, которые действительно пусты. Чужие файлы в shared-директориях не теряются. | `env/pkg/lib/remove.sh` (вынесено из `pkg`) |
| D | `rewrite_prefix` теперь `tmp + atomic mv` (как `agy-fix-paths`), а не `sed -i` (который ломал читателей). | `env/pkg/lib/rewrite.sh` |
| F | `agy-fix-paths` и `rewrite_prefix` — **один источник**: новая `env/pkg/lib/rewrite.sh`, оба сценария source'ят её. | `env/pkg/lib/rewrite.sh`, `env/overlay/bin/agy-fix-paths` |
| L | Убран принудительный `-march=armv8-a` из `00-env.sh` (глушил native LSE на armv8.1+). | `env/overlay/etc/profile.d/00-env.sh` |

**Латентный баг найден и исправлен:** `sed` delimiter `|` ломал regex с alternation (внутри шаблона `/data/(data|user/...)/...` есть `|`). Переключился на `@`.

### 2. Фаза 2 — DRY и упрощение (1 коммит `8d0198e`)

| # | Что |
|---|---|
| G | `index_tsvs` и вызовы `index_files` в `cmd_list`/`cmd_search` — `find -print0` + `while read`, безопасны для путей с пробелами. |
| H | `choose_dep` — один POSIX-only awk pass вместо 4 отдельных `printf|sed|awk` (закрыто работает на mawk и BusyBox). Также нашёл латентный баг: `tr '|' '\n'` без trailing newline ронял последний alternative в `while read` — починил `printf '%s\n'`. |
| O | `install_one` — `IFS=$'\t' read` для ver/arch/fn/sha вместо четырёх `printf|cut` (уже сделано в Фазе 1, задокументировано). |
| Q | `agy-notify` — больше нет фоллбека на `cmd notification` (он не работает под app-uid), `die` с понятной ошибкой. Также починил unquoted `$LOGO_CACHE` в rish-c. |

### 3. Фаза 3 — тесты + ещё один латентный баг (1 коммит `f619f35`)

| Что |
|---|
| +5 проверок в `selftest` (теперь **41 PASS**): rewrite idempotency, shebang repointing, install_one fail-closed (нет staging leak), cmd_remove sibling safety, agy-notify post forwarding. |
| **`cmd_remove` пил `.files` путь** вместо его содержимого (старая ошибка до merge) — `db_files` это **функция-путь**, а не stream. Тест это поймал. |
| `cmd_remove` вынесена в `env/pkg/lib/remove.sh` (была в `pkg` рядом с main, нельзя было source'ить отдельно). |

## Итог

| Метрика | До (6d05c02) | После (f619f35) |
|---|---|---|
| Коммитов в ветке | 1 | 4 (merge + 3 фазы) |
| `selftest` | 30 PASS | **41 PASS** |
| `invariant` | 5 PASS | 7 PASS |
| `bus/selftest` | 12 PASS | 12 PASS |
| `env/tests/fixtures` без изменений | | |
| Файлов новых в `env/pkg/lib/` | 0 | +2 (`rewrite.sh`, `remove.sh`) |
| Латентных багов найдено | — | 3 (sed delimiter, missing trailing `\n`, `db_files` path-vs-stream) |
| Строк в `env/pkg/pkg` (install_one + cmd_remove) | ~100 | ~70 |

Все тесты зелёные на момент `f619f35`. `e3` (требует aarch64) не запускался — нужно на устройстве.
