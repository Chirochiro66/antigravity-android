#!/data/data/com.agy/files/usr/bin/bash
# resolver.sh -- strict dependency closure and install-order resolution.
# Requires index_lookup, index_provider, db_exists and deb_vercmp.

# ver_satisfies VER OP WANT
ver_satisfies() {
    local c; c="$(deb_vercmp "$1" "$3")"
    case "$2" in
        ">=")     [ "$c" != "-1" ] ;;
        "<=")     [ "$c" != "1" ] ;;
        "="|"==") [ "$c" = "0" ] ;;
        ">>"|">") [ "$c" = "1" ] ;;
        "<<"|"<") [ "$c" = "-1" ] ;;
        *)        return 0 ;;
    esac
}

valid_package_name() {
    [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9+._-]*$ ]]
}

# choose_dep TERM -> package line (no repository prefix) or empty
choose_dep() {
    local term="$1" out="" alt nm op ver line repo_pkg tab=$'\t'
    # Split the term on '|' first; parse each alternative's name/operator/
    # version with a single awk so we don't fork four processes per
    # alternative. Then index_lookup / index_provider do the heavy lifting.
    while IFS= read -r alt; do
        # One awk pass extracts the three fields and prints them tab-separated.
        # Uses POSIX-only awk (no gawk-only match(s,re,arr)); matches mawk and
        # the BusyBox awk shipped in the Android userspace.
        IFS=$'\t' read -r nm op ver < <(
            printf '%s' "$alt" | awk '
                {
                    gsub(/^ +| +$/, "")
                    if ($0 == "") exit
                    # name = everything up to the first ":", "[" or "(".
                    p = index($0, ":");  if (p == 0) p = length($0) + 1
                    q = index($0, "[");  if (q == 0) q = length($0) + 1
                    r = index($0, "(");  if (r == 0) r = length($0) + 1
                    n = p; if (q < n) n = q; if (r < n) n = r
                    nm = substr($0, 1, n - 1)
                    gsub(/ +$/, "", nm)
                    # Constraint = the "(op ver)" group, if any.
                    op = ""; ver = ""
                    rest = substr($0, n)
                    p = index(rest, "("); q = index(rest, ")")
                    if (p > 0 && q > p) {
                        c = substr(rest, p + 1, q - p - 1)
                        sp = index(c, " ")
                        if (sp > 0) { op = substr(c, 1, sp - 1); ver = substr(c, sp + 1) }
                        else { op = c }
                    }
                    print nm "\t" op "\t" ver
                }
            '
        )
        [ -n "$nm" ] || continue
        line="$(index_lookup "$nm")"
        if [ -z "$line" ]; then
            line="$(index_provider "$nm")"
        fi
        [ -n "$line" ] || continue
        # Strip the "repo.N\t" prefix to get just the package fields.
        line="${line#*"$tab"}"
        if [ -n "$ver" ]; then
            ver_satisfies "$(printf '%s' "$line" | cut -f2)" "$op" "$ver" || continue
        fi
        out="$line"; break
    done < <(printf '%s\n' "$term" | tr '|' '\n')
    [ -n "$out" ] && printf '%s\n' "$out"
}

# resolve NAME... -> complete install order, dependencies first. Fails closed
# for invalid names, unresolved dependencies, and cycles; no partial order leaks.
resolve() {
    local work="$PKG_ROOT/tmp/resolve.$$" name entry line provider rootname failed=0 tab=$'\t'
    rm -rf "$work"; mkdir -p "$work"
    for name in "$@"; do
        if ! valid_package_name "$name"; then
            warn "invalid package name: $name"
            failed=1; break
        fi
        db_exists "$name" && continue
        entry="$(index_lookup "$name")"
        if [ -z "$entry" ]; then
            provider="$(index_provider "$name")"
            if [ -z "$provider" ]; then
                warn "unknown package or provider: $name"
                failed=1; break
            fi
            line="${provider#*"$tab"}"; rootname="$(printf '%s' "$line" | cut -f1)"
        else
            line="${entry#*"$tab"}"; rootname="$(printf '%s' "$line" | cut -f1)"
        fi
        if ! r_visit "$rootname" "$work"; then
            failed=1; break
        fi
    done
    if [ "$failed" -eq 0 ] && [ -f "$work/order" ]; then
        cat "$work/order"
    fi
    rm -rf "$work"
    [ "$failed" -eq 0 ]
}

r_visit() {
    local name="$1" work="$2" line deps term cand cname tab=$'\t'
    valid_package_name "$name" || { warn "invalid package name in index: $name"; return 1; }
    [ -f "$work/done/$name" ] && return 0
    db_exists "$name" && return 0
    [ -f "$work/doing/$name" ] && { warn "dependency cycle at: $name"; return 1; }
    line="$(index_lookup "$name")"; line="${line#*"$tab"}"
    [ -n "$line" ] || { warn "unknown package in dependency closure: $name"; return 1; }
    mkdir -p "$work/done" "$work/doing"
    : > "$work/doing/$name"
    deps="$(printf '%s' "$line" | cut -f5) $(printf '%s' "$line" | cut -f6)"
    while IFS= read -r term; do
        term="$(printf '%s' "$term" | sed 's/^ *//; s/ *$//')"
        [ -n "$term" ] || continue
        cand="$(choose_dep "$term")"
        if [ -z "$cand" ]; then
            warn "cannot resolve dependency: $term (for $name)"
            rm -f "$work/doing/$name"
            return 1
        fi
        cname="$(printf '%s' "$cand" | cut -f1)"
        if ! r_visit "$cname" "$work"; then
            rm -f "$work/doing/$name"
            return 1
        fi
    done <<EOF
$(printf '%s' "$deps" | tr ',' '\n')
EOF
    rm -f "$work/doing/$name"
    : > "$work/done/$name"
    printf '%s\n' "$name" >> "$work/order"
}
