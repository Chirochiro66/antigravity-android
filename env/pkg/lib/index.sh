#!/data/data/com.agy/files/usr/bin/bash
# index.sh -- Packages index helpers.
# Requires PKG_LIB, PKG_INDEX, deb_gt (version.sh).

# index_parse FILE -> TSV (see index.awk for columns)
index_parse() { awk -f "$PKG_LIB/index.awk" "$1"; }

# index_rebuild FILE.Packages -> writes FILE.tsv
index_rebuild() {
    [ -f "$1" ] || return 1
    awk -f "$PKG_LIB/index.awk" "$1" > "${1%.Packages}.tsv"
}

index_rebuild_all() {
    local f
    for f in "$PKG_INDEX"/repo.*.Packages; do
        [ -e "$f" ] || continue
        index_rebuild "$f"
    done
}

# Use find -print0-friendly helper: stream the paths instead of letting `ls`
# split on whitespace or pass through a shell glob. Callers iterate with
# `while IFS= read -r tsv; do ... done < <(index_tsvs)`.
index_tsvs() { find "$PKG_INDEX" -maxdepth 1 -type f -name 'repo.*.tsv' 2>/dev/null; }

# index_lookup NAME -> "repo.N<TAB>line" of the highest version (all repos)
index_lookup() {
    local name="$1" tsv base line v best="" bestver=""
    while IFS= read -r tsv; do
        [ -n "$tsv" ] || continue
        base="$(basename "$tsv" .tsv)"
        while IFS= read -r line; do
            v="$(printf '%s' "$line" | cut -f2)"
            if [ -z "$bestver" ] || deb_gt "$v" "$bestver"; then
                bestver="$v"; best="$base"$'\t'"$line"
            fi
        done < <(awk -F'\t' -v n="$name" '$1 == n' "$tsv")
    done < <(index_tsvs)
    [ -n "$best" ] && printf '%s\n' "$best"
}

# index_provider NAME -> "repo.N<TAB>line" of a package whose Provides has NAME
index_provider() {
    local name="$1" tsv base line
    while IFS= read -r tsv; do
        [ -n "$tsv" ] || continue
        base="$(basename "$tsv" .tsv)"
        while IFS= read -r line; do
            printf '%s\t%s\n' "$base" "$line"; return 0
        done < <(awk -F'\t' -v n="$name" '{
            c = split($7, a, ",")
            for (i = 1; i <= c; i++) { gsub(/ /, "", a[i]); if (a[i] == n) { print; exit } }
        }' "$tsv")
    done < <(index_tsvs)
}

# E1 compat
index_get() { index_parse "$1" | awk -F'\t' -v n="$2" -v c="$3" '$1 == n { print $c }' | tail -n 1; }
index_best() { index_parse "$1" | awk -F'\t' -v n="$2" '$1 == n'; }
