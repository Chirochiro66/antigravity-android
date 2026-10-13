#!/usr/bin/env bash
# Offline safety checks for build flags; no Android SDK or signing secrets needed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLAGS="$ROOT/build/lib/flags.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

parse_args_in_subshell() {
    bash -c 'source "$1"; shift; parse_args "$@"' _ "$FLAGS" "$@" 2>"$TMP/stderr"
}
expect_rejected() {
    local expected="$1"; shift
    local status=0
    parse_args_in_subshell "$@" || status=$?
    if [ "$status" -ne 2 ] || ! grep -Fq -- "$expected" "$TMP/stderr"; then
        printf 'FAIL: expected rejected flags (%s), status=%s\n' "$*" "$status" >&2
        cat "$TMP/stderr" >&2
        exit 1
    fi
}

expect_rejected "--no-patch is allowed only with --dev" --no-patch
expect_rejected "cannot be combined with --dry-run" --dev --no-patch --dry-run
parse_args_in_subshell --dev --no-patch
parse_args_in_subshell --dry-run
printf 'build flag selftest: OK\n'
