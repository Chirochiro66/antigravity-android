#!/data/data/com.agy/files/usr/bin/bash
# rewrite.sh -- atomic prefix-rewrite shared by pkg install and agy-fix-paths.
#
# Source this file from any tool that needs to repoint legacy /data/data
# and modern /data/user/<id> app-private usr paths to the active $PREFIX.
# After sourcing, `rewrite_paths [TARGETS...]` is available.
#
# Idempotent. Text-only (binary files with NUL bytes are skipped by grep -I).
# Atomic per file (tmp + mv), so a running process keeps its open inode.
# Returns 0 always; the number of files rewritten is in REWRITE_COUNT.
#
# Requires: $PREFIX exported. grep, sed, stat, cmp, mv on PATH.

: "${PREFIX:?PREFIX must be exported by the AGY shell profile or pkg wrapper}"

# Anchor and pattern are module-private; expose only the function.
_REWRITE_PATTERN='/data/(data|user/[0-9]+)/[^/]*/files/usr'

# rewrite_paths [TARGET...] -- rewrite in place. Empty arg list means $PREFIX.
# Sets REWRITE_COUNT to the number of files changed and prints a one-line
# summary on stdout. Set REWRITE_QUIET=1 to suppress the summary.
rewrite_paths() {
    local f esc tmp mode
    local -a targets files
    if [ "$#" -gt 0 ]; then
        targets=("$@")
    else
        targets=("$PREFIX")
    fi
    # Escape sed-replacement metacharacters in $PREFIX. We also pick a
    # delimiter (default: `|`) that does NOT appear in the pattern; the
    # rewrite regex itself contains `|` for alternation, so we switch to `@`
    # which is legal in any path or pattern we expect to see in practice.
    esc="$(printf '%s' "$PREFIX" | sed 's/[\\&@#%]/\\&/g')"
    # LC_ALL=C keeps grep/sed byte-accurate even on non-C locales where a
    # multi-byte UTF-8 char could otherwise look like a digit or a slash.
    # We materialise the grep result into an array (instead of piping into
    # a `while read` loop) so the loop runs in the current shell and the
    # $count variable survives. The pipe-to-while pattern would otherwise
    # fork a subshell, and every increment to $count would die there.
    mapfile -d '' -t files < <(LC_ALL=C grep -rIlZ -E -e "$_REWRITE_PATTERN" \
        -- "${targets[@]}" 2>/dev/null)
    local count=0
    for f in "${files[@]}"; do
        [ -n "$f" ] || continue
        tmp="$f.rewrt.$$"
        if LC_ALL=C sed -E "s@$_REWRITE_PATTERN@$esc@g" "$f" > "$tmp" 2>/dev/null; then
            if cmp -s "$f" "$tmp"; then
                rm -f "$tmp"
            else
                mode="$(stat -c '%a' "$f" 2>/dev/null)" || mode=""
                [ -n "$mode" ] && chmod "$mode" "$tmp" 2>/dev/null
                mv -f "$tmp" "$f"
                count=$((count + 1))
            fi
        else
            rm -f "$tmp"
        fi
    done
    REWRITE_COUNT="$count"
    if [ "${REWRITE_QUIET:-0}" != "1" ]; then
        printf 'rewrote %d file(s) -> %s\n' "$count" "$PREFIX"
    fi
}
