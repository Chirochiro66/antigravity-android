#!/usr/bin/env bash
#
# 09b-verify.sh - verify the signature and the packaged assets of the APK.
#
# The env bundle check is fail-closed on purpose: EnvInstaller reads
# assets/env/manifest.json (EnvPaths.ASSET_MANIFEST) before it touches $PREFIX
# and refuses to install without it, so an APK packaged without a complete,
# self-describing assets/env tree leaves the device on the base runtime only
# (no files/usr, no pkg/python3). See build/tools/verify_env_assets.py.

log "[9b/9] apksigner verify + asset checks"
apksigner verify --verbose "$OUT_APK"

# fail-closed: assets/env must be complete and the packaged manifest must
# describe the packaged bytes (bootstrap sha256 + symlink count, overlay/pkg
# tree digests, bundleId) and match the staged tree byte-for-byte.
python3 "$BUILD_TOOLS_DIR/verify_env_assets.py" "$OUT_APK" \
    --staged-env "${ENV_ASSETS:-$STAGE_ASSETS/env}" \
    || die "packaged assets/env bundle is incomplete or inconsistent"

log "BUILD OK"
ls -l "$OUT_APK"
du -h "$OUT_APK"
