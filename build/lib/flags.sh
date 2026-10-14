#!/usr/bin/env bash
#
# flags.sh - command-line parsing for build.sh.
#
# Sourced, then parse_args "$@" is called before paths/config are loaded.
# Exports NO_PATCH / DRY_RUN / DEV_MODE so sourced phases see them.

parse_args() {
    NO_PATCH=0
    DRY_RUN=0
    DEV_MODE=0
    REGION_BYPASS=0
    REGION_BYPASS_EXPLICIT=0
    for arg in "$@"; do
        case "$arg" in
            --dev)                    DEV_MODE=1 ;;
            --no-patch)               NO_PATCH=1 ;;
            --dry-run)                DRY_RUN=1 ;;
            --region-bypass|--bypass) REGION_BYPASS=1; REGION_BYPASS_EXPLICIT=1 ;;
            --no-region-bypass)       REGION_BYPASS=0; REGION_BYPASS_EXPLICIT=1 ;;
            *) echo "unknown flag: $arg" >&2; exit 2 ;;
        esac
    done

    # --no-patch ships the vanilla (unpatched) core. That is a debug aid for the
    # dev edition only (docs/BUILD.md) and makes no sense together with the
    # patch-only dry run, so both combinations fail closed here.
    if [ "$NO_PATCH" -eq 1 ] && [ "$DEV_MODE" -ne 1 ]; then
        echo "--no-patch is allowed only with --dev" >&2
        exit 2
    fi
    if [ "$NO_PATCH" -eq 1 ] && [ "$DRY_RUN" -eq 1 ]; then
        echo "--no-patch cannot be combined with --dry-run" >&2
        exit 2
    fi

    # Dev edition always defaults to region bypass unless explicitly disabled.
    if [ "$DEV_MODE" -eq 1 ] && [ "$REGION_BYPASS_EXPLICIT" -eq 0 ]; then
        REGION_BYPASS=1
    fi

    export NO_PATCH DRY_RUN DEV_MODE REGION_BYPASS
}
