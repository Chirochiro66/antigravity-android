#!/data/data/com.agy/files/usr/bin/bash
# remove.sh -- safe package removal.
# Requires: db_exists, db_files, db_remove, log, warn, ensure_dirs, load_conf, die.

# cmd_remove NAME... -- remove installed packages. Sibling packages whose
# files live in the same directories are not affected: we collect the list
# of parent directories we unlink files in, then attempt rmdir only for
# those (and rmdir is a no-op on any non-empty directory).
cmd_remove() {
    ensure_dirs; load_conf
    [ "$#" -ge 1 ] || die "remove: need package name(s)"
    local name p dir dirs=()
    for name in "$@"; do
        if ! db_exists "$name"; then warn "not installed: $name"; continue; fi
        # First pass: unlink every file the package owns. Collect each parent
        # directory we touched (deduplicated) for a single safe cleanup pass
        # afterwards. Never rmdir during the loop -- rmdir can succeed while a
        # sibling package still has files in the same directory, and the
        # previous loop would happily delete that sibling's parent path.
        while IFS= read -r p; do
            [ -n "$p" ] || continue
            rm -f -- "$PREFIX/$p"
            dir="$(dirname -- "$p")"
            [ -n "$dir" ] && [ "$dir" != "." ] && dirs+=("$dir")
        done < <(cat -- "$(db_files "$name")" 2>/dev/null)
        db_remove "$name"
        log "removed: $name"
    done
    # Second pass: try to remove only directories that are now empty AND not
    # owned by any remaining installed package. We probe from the leaves
    # upwards, but a directory with any file in it (or a subdirectory we did
    # not clean) just stays -- rmdir is a no-op for non-empty directories.
    if [ "${#dirs[@]}" -gt 0 ]; then
        # Deduplicate, then iterate the deepest paths first.
        printf '%s\n' "${dirs[@]}" | sort -u | awk -F/ '{
            # Order by descending depth so rmdir bubbles from leaves up.
            printf "%d\t%s\n", NF, $0
        }' | sort -k1,1nr -k2 | cut -f2- | while IFS= read -r d; do
            [ -n "$d" ] || continue
            rmdir -- "$PREFIX/$d" 2>/dev/null || true
        done
    fi
}
