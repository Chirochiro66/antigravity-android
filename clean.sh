#!/usr/bin/env bash
#
# clean.sh - Быстрая очистка кэша, артефактов сборки и APK
# Приводит проект Antigravity в чистый исходный вид.
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "🧹 Очистка проекта Antigravity..."

# 1. Очистка через git (по .gitignore), если git доступен
if command -v git >/dev/null 2>&1 && [ -d "$PROJECT_DIR/.git" ]; then
    export GIT_CONFIG_NOSYSTEM=1
    if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/libexec/git-core" ]; then
        export GIT_EXEC_PATH="$PREFIX/libexec/git-core"
    elif [ -d "/data/user/0/com.agy.dev/files/usr/libexec/git-core" ]; then
        export GIT_EXEC_PATH="/data/user/0/com.agy.dev/files/usr/libexec/git-core"
    fi
    git -C "$PROJECT_DIR" clean -fdX
fi

# 2. Дополнительная прямая очистка артефактов сборки
# Папка build (сохраняем исходные директории lib, phases, tools, tests)
rm -rf "$PROJECT_DIR"/build/apk \
       "$PROJECT_DIR"/build/dex \
       "$PROJECT_DIR"/build/gen \
       "$PROJECT_DIR"/build/obj \
       "$PROJECT_DIR"/build/res \
       "$PROJECT_DIR"/build/staging \
       "$PROJECT_DIR"/build/*.apk \
       "$PROJECT_DIR"/build/AndroidManifest.xml 2>/dev/null || true

# Папка dist (сохраняем .gitkeep)
if [ -d "$PROJECT_DIR/dist" ]; then
    find "$PROJECT_DIR/dist" -mindepth 1 ! -name ".gitkeep" -exec rm -rf {} + 2>/dev/null || true
fi

# Временные файлы шины и веб-интерфейса
rm -rf "$PROJECT_DIR/bus/run" "$PROJECT_DIR/web/generated" "$PROJECT_DIR/scratch" 2>/dev/null || true

# Python кэш и временные файлы
find "$PROJECT_DIR" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find "$PROJECT_DIR" -type f \( -name "*.pyc" -o -name "*.pyo" -o -name "*.log" -o -name "*.tmp" \) -delete 2>/dev/null || true

echo "✨ Проект успешно очищен и приведен в исходный вид!"
