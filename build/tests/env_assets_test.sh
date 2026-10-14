#!/usr/bin/env bash
# Offline regression test for the bionic env bundle staged into the APK.
#
# Why this exists: EnvInstaller reads assets/env/manifest.json before it
# touches $PREFIX (EnvPaths.ASSET_MANIFEST -> EnvBundleManifest.load) and fails
# closed when it is missing. An APK staged without that asset installs the core
# runtime only: files/usr, `pkg` and `python3` never appear on the device.
#
# The test runs the real staging phase (build/phases/05b-env.sh) in a
# disposable directory and asserts the asset contract the Java layer requires,
# including the fail-closed paths (stale manifest, absent bundle).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail=0
ok()  { printf 'PASS  %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fail=$((fail + 1)); }

# stage <env_dist> <env_dir> <assets_dir> [log]
# Runs 05b-env.sh in a subshell (die() exits it) and returns its status.
stage() {
    local env_dist="$1" env_dir="$2" assets="$3" log="${4:-$TMP/stage.log}"
    mkdir -p "$assets"
    (
        export PROJECT="$ROOT"
        export ENV_DIR="$env_dir"
        export ENV_DIST="$env_dist"
        export STAGE_ASSETS="$assets"
        . "$ROOT/build/lib/log.sh"
        . "$ROOT/build/phases/05b-env.sh"
    ) >"$log" 2>&1
}

check_manifest_json() { # <manifest> <description>
    python3 - "$1" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
assert data["schema"] == 1, data
assert len(data["bundleId"]) == 64, data
assert len(data["bootstrapSha256"]) == 64, data
assert data["bootstrapSymlinkCount"] >= 1, data
PY
}

# --- 1. the checked-in bundle stages complete and self-consistent ------------
BUNDLE="$TMP/bundle"
cp -a "$ROOT/env/dist/env" "$BUNDLE"
ASSETS="$TMP/assets"

if stage "$BUNDLE" "$ROOT/env" "$ASSETS"; then
    ok "05b-env.sh staged $BUNDLE"
else
    bad "05b-env.sh failed to stage the bundle"
    sed 's/^/      /' "$TMP/stage.log"
fi

# Exactly the assets the Java layer opens; each one is load-bearing.
for asset in \
    env/manifest.json \
    env/bootstrap/bootstrap.tar.gz \
    env/pkg/pkg \
    env/overlay/bin/agy-fix-paths; do
    if [ -e "$ASSETS/$asset" ]; then
        ok "staged asset present: $asset"
    else
        bad "staged asset missing: $asset"
    fi
done

if python3 "$ROOT/env/scripts/make_manifest.py" --check "$ASSETS/env" >/dev/null 2>&1; then
    ok "staged manifest matches the staged bytes"
else
    bad "staged manifest does not match the staged bytes"
fi

if cmp -s "$BUNDLE/manifest.json" "$ASSETS/env/manifest.json"; then
    ok "staged manifest is byte-identical to the source bundle"
else
    bad "staged manifest differs from $BUNDLE/manifest.json"
fi

if check_manifest_json "$ASSETS/env/manifest.json"; then
    ok "staged manifest declares a usable bundle identity"
else
    bad "staged manifest is not a valid bundle identity"
fi

# --- 1b. the loose env/{pkg,overlay,bootstrap} layout stages a generated
#         manifest without a manifest.json in the source tree -----------------
LOOSE="$TMP/loose-env"
mkdir -p "$LOOSE/bootstrap" "$LOOSE/scripts"
cp -a "$ROOT/env/pkg" "$LOOSE/pkg"
cp -a "$ROOT/env/overlay" "$LOOSE/overlay"
cp -f "$ROOT/env/bootstrap/bootstrap.tar.gz" "$LOOSE/bootstrap/"
cp -f "$ROOT/env/scripts/make_manifest.py" "$LOOSE/scripts/"

if stage "$TMP/loose-dist" "$LOOSE" "$TMP/assets-loose"; then
    ok "05b-env.sh staged the loose env/ layout"
else
    bad "05b-env.sh failed to stage the loose env/ layout"
    sed 's/^/      /' "$TMP/stage.log"
fi

if cmp -s "$ASSETS/env/manifest.json" "$TMP/assets-loose/env/manifest.json"; then
    ok "loose layout produces the same bundle identity"
else
    bad "loose layout produced a different bundle identity"
fi

# --- 2. a stale source manifest must abort the build (fail-closed) ----------
STALE="$TMP/stale"
cp -a "$ROOT/env/dist/env" "$STALE"
python3 - "$STALE/manifest.json" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
data["bootstrapSymlinkCount"] = 1
with open(path, "w") as handle:
    handle.write(json.dumps(data, indent=2, sort_keys=True) + "\n")
PY

if stage "$STALE" "$ROOT/env" "$TMP/assets-stale" "$TMP/stale.log"; then
    bad "the staging phase accepted a stale env manifest"
elif grep -q "stale" "$TMP/stale.log"; then
    ok "stale env manifest rejected (fail-closed)"
else
    bad "stale env manifest rejected without the expected message"
    sed 's/^/      /' "$TMP/stale.log"
fi

if [ -e "$TMP/assets-stale/env/manifest.json" ] \
        && cmp -s "$STALE/manifest.json" "$TMP/assets-stale/env/manifest.json"; then
    bad "the stale manifest was still published into assets/env"
else
    ok "no stale manifest published into assets/env"
fi

# --- 3. an absent bundle must abort the build unless explicitly allowed -----
mkdir -p "$TMP/empty-dist" "$TMP/empty-env"
if stage "$TMP/empty-dist" "$TMP/empty-env" "$TMP/assets-none" "$TMP/none.log"; then
    bad "the staging phase accepted an absent env bundle"
elif grep -q "env bundle inputs missing" "$TMP/none.log"; then
    ok "absent env bundle rejected (fail-closed)"
else
    bad "absent env bundle rejected without the expected message"
    sed 's/^/      /' "$TMP/none.log"
fi

if AGY_ALLOW_MISSING_ENV=1 bash -c '
        export PROJECT="$1"; export ENV_DIR="$2"; export ENV_DIST="$3"
        export STAGE_ASSETS="$4" AGY_ALLOW_MISSING_ENV=1
        . "$1/build/lib/log.sh"
        . "$1/build/phases/05b-env.sh"
    ' _ "$ROOT" "$TMP/empty-env" "$TMP/empty-dist" "$TMP/assets-optout" \
        >"$TMP/optout.log" 2>&1; then
    ok "AGY_ALLOW_MISSING_ENV=1 skips the bundle explicitly"
else
    bad "AGY_ALLOW_MISSING_ENV=1 did not skip the absent bundle"
    sed 's/^/      /' "$TMP/optout.log"
fi

# --- 4. the APK-level verifier must accept a complete bundle and reject a
#         broken one (build/tools/verify_env_assets.py) -----------------------
make_fake_apk() { # <apk> <staged-assets-dir>
    python3 - "$1" "$2" <<'PY'
import os, sys, zipfile
apk, assets = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("AndroidManifest.xml", b"placeholder")
    archive.writestr("assets/runtime/manifest.json", b"{}")
    for base, _dirs, files in os.walk(assets):
        for name in files:
            path = os.path.join(base, name)
            archive.write(path, "assets/" + os.path.relpath(path, assets))
PY
}

rewrite_apk() { # <src-apk> <dst-apk> <mode>
    python3 - "$1" "$2" "$3" <<'PY'
import json, sys, zipfile
src, dst, mode = sys.argv[1], sys.argv[2], sys.argv[3]
manifest_entry = "assets/env/manifest.json"
with zipfile.ZipFile(src) as source, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as sink:
    for item in source.infolist():
        if mode == "drop-manifest" and item.filename == manifest_entry:
            continue
        data = source.read(item.filename)
        if mode == "tamper-sha" and item.filename == manifest_entry:
            manifest = json.loads(data.decode("utf-8"))
            manifest["bootstrapSha256"] = "0" * 64
            data = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
        sink.writestr(item, data)
PY
}

VERIFY="$ROOT/build/tools/verify_env_assets.py"
FAKE_APK="$TMP/agy-fake.apk"
make_fake_apk "$FAKE_APK" "$ASSETS"

if python3 "$VERIFY" "$FAKE_APK" --staged-env "$ASSETS/env" >"$TMP/verify.log" 2>&1; then
    ok "APK verifier accepts a complete assets/env bundle"
else
    bad "APK verifier rejected a complete assets/env bundle"
    sed 's/^/      /' "$TMP/verify.log"
fi

rewrite_apk "$FAKE_APK" "$TMP/agy-no-manifest.apk" drop-manifest
if python3 "$VERIFY" "$TMP/agy-no-manifest.apk" >"$TMP/verify-nomanifest.log" 2>&1; then
    bad "APK verifier accepted an APK without assets/env/manifest.json"
elif grep -q "env/manifest.json" "$TMP/verify-nomanifest.log"; then
    ok "APK without assets/env/manifest.json rejected (fail-closed)"
else
    bad "APK without the manifest was rejected without naming the asset"
    sed 's/^/      /' "$TMP/verify-nomanifest.log"
fi

rewrite_apk "$FAKE_APK" "$TMP/agy-tampered.apk" tamper-sha
if python3 "$VERIFY" "$TMP/agy-tampered.apk" >"$TMP/verify-tampered.log" 2>&1; then
    bad "APK verifier accepted a manifest that does not describe the bundle"
elif grep -q "does not describe" "$TMP/verify-tampered.log"; then
    ok "manifest/bundle mismatch rejected (fail-closed)"
else
    bad "manifest/bundle mismatch was rejected without the expected message"
    sed 's/^/      /' "$TMP/verify-tampered.log"
fi

if python3 "$VERIFY" "$FAKE_APK" --staged-env "$TMP/assets-loose/env" \
        >"$TMP/verify-match.log" 2>&1; then
    ok "packaged bundle matches an identical staged tree"
else
    bad "packaged bundle failed to match an identical staged tree"
    sed 's/^/      /' "$TMP/verify-match.log"
fi

cp -a "$ASSETS" "$TMP/assets-drifted"
printf '\n# drift\n' >> "$TMP/assets-drifted/env/pkg/pkg"
if python3 "$VERIFY" "$FAKE_APK" --staged-env "$TMP/assets-drifted/env" \
        >"$TMP/verify-drift.log" 2>&1; then
    bad "packaged/staged drift was not detected"
elif grep -q "differs from" "$TMP/verify-drift.log"; then
    ok "packaged/staged drift rejected (fail-closed)"
else
    bad "packaged/staged drift was rejected without the expected message"
    sed 's/^/      /' "$TMP/verify-drift.log"
fi

printf '== failures: %s ==\n' "$fail"
[ "$fail" = 0 ]
