#!/usr/bin/env bash
#
# 05-web-bus.sh - build the web bundles, then stage web/ and bus/ as assets.
#
# web/build.sh (owned by the web layer) generates web/generated/**. We run it
# first so the staged tree matches the freshly generated sources, then enforce
# the byte-for-byte invariant. Build-only files (web/src/**, web/build.sh,
# web/src.manifest) are pruned from the APK, not shipped.

log "[5/9] assets/web + bus"
bash "$WEB_DIR/build.sh" || die "web/build.sh failed"
cp -a "$WEB_DIR" "$STAGE_ASSETS/web"
# prune build-only inputs from the shipped assets
rm -rf "$STAGE_ASSETS/web/src" "$STAGE_ASSETS/web/build.sh" "$STAGE_ASSETS/web/patch_main.py" "$STAGE_ASSETS/web/src.manifest"
if [ -d "$BUS_DIR" ]; then
    cp -a "$BUS_DIR" "$STAGE_ASSETS/bus"
    log "  bus/ copied"
else
    log "  bus/ absent, skipped"
fi
# The upstream main.js is build input only; the APK contains its patched copy
# at generated/main.js. Verify all other staged files and that generated copy.
rm -f "$STAGE_ASSETS/web/main.js"
if ! diff -r --exclude=src --exclude=build.sh --exclude=patch_main.py \
        --exclude=src.manifest --exclude=main.js \
        "$WEB_DIR" "$STAGE_ASSETS/web" >/dev/null 2>&1; then
    die "invariant failed: staged web assets differ from generated source tree"
fi
cmp -s "$WEB_DIR/generated/main.js" "$STAGE_ASSETS/web/generated/main.js" \
    || die "invariant failed: generated main.js differs in staged assets"
log "  invariant ok: staged web assets match generated source tree"
