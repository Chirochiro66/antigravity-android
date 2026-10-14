# Build & Packaging Guide: Antigravity for Android

The APK is assembled by `bash build.sh`; this repository does not use Gradle. A full APK build needs an Android build-tools installation, a Java JDK, the pinned Android/R8 jars, and the ARM64 runtime inputs under `core/` and `env/`.

## Host requirements

| Tool | Purpose |
| --- | --- |
| `aapt`, `zipalign`, `apksigner` | Android resources, APK alignment, signing and verification |
| `javac`, `java` | Compile Java sources and run D8 from `tools/r8.jar` |
| `python3` | Build configuration, patching, archive and asset checks |
| `bash`, `zip`, `unzip`, `gzip`, `file`, `sha256sum`, `mktemp` | Packaging and integrity checks |

`tools/android.jar` and `tools/r8.jar` are verified against the SHA-256 values in `config/paths.env`. Update those pins only when intentionally replacing the corresponding build tool.

## Build inputs

By default, `config/paths.env` resolves vendor inputs from this checkout:

- `core/language_server.tar.gz` (or an unpacked `core/language_server`)
- `core/glibc/`, `core/certs/`, `core/seed/`, and `core/tools/`
- `env/pkg/`, `env/overlay/`, and `env/bootstrap/bootstrap.tar.gz`
- `tools/android.jar` and `tools/r8.jar`

Set `AGY_HOME` or individual path variables from `config/paths.env` to use an external immutable vendor tree. Keep release secrets outside the checkout.

## Signing identities

- `bash build.sh --dev` uses the development identity at `tools/debug.keystore` (or `AGY_DEV_KEYSTORE`). If missing, the build creates a local dev key. Dev builds are not production releases.
- Release builds **never fall back** to the debug keystore. They require `AGY_RELEASE_KEYSTORE`, `AGY_RELEASE_KEY_ALIAS`, `AGY_RELEASE_STORE_PASS`, and `AGY_RELEASE_KEY_PASS`.
- CI additionally requires the protected `AGY_RELEASE_CERT_SHA256` secret and compares it to the certificate exported from the configured release keystore. Set it to the SHA-256 fingerprint of the signing certificate used by the existing published APKs, verified independently with `apksigner verify --print-certs <apk>`.

The repository contains a tracked debug keystore and the historical release workflow used repository signing inputs. A newly generated release key can make an APK impossible to install as an update over an existing release. Do not configure a new signing identity or publish until the certificate fingerprint and any required Android signing-key migration have been checked against the currently distributed APKs. The public debug keystore must not be used as a release-key fallback.

## Build modes

```bash
# Production standard and region-bypass builds (release signing secrets required)
bash build.sh
bash build.sh --region-bypass

# Isolated development package (com.agy.dev)
bash build.sh --dev

# Debug-only vanilla core (must be combined with --dev)
bash build.sh --dev --no-patch

# Patch dry run; does not produce an APK
bash build.sh --dry-run
```

The app version and Android package versions are configured in `config/app.env`. A published release tag must be `v<APP_VERSION_NAME>`; CI validates this before building or publishing.

## Verification

Run the offline suites without Android SDK tools:

```bash
python3 -m unittest discover -s patches/tests -v
python3 -m unittest discover -s build/tests -v
bash build/tests/selftest.sh
bash build/tests/env_assets_test.sh
bash bus/selftest
bash env/tests/selftest
bash web/tests/selftest.sh
```

`build/tests/env_assets_test.sh` runs the real `05b-env.sh` staging phase in a
disposable directory and asserts the `assets/env` contract the app depends on,
including the fail-closed paths (stale manifest, absent bundle) and the
APK-level verifier `build/tools/verify_env_assets.py`.

Check environment asset staging in a disposable directory:

```bash
tmp="$(mktemp -d)"
ASSETS="$tmp/env" bash env/build.sh all
ASSETS="$tmp/env" bash env/tests/invariant
rm -rf "$tmp"
```

`bash web/build.sh` regenerates ignored files under `web/generated/`; it patches a generated copy of `web/main.js`, leaving the upstream source unchanged. The APK build independently checks that staged web assets match the generated tree.

## Env bundle asset contract (`assets/env/**`)

The bionic userspace ships inside the APK and is deployed to the app-private
`files/usr` prefix on first launch. Every asset below is load-bearing:

| Asset | Consumer | Behavior when missing |
| --- | --- | --- |
| `env/manifest.json` | `EnvBundleManifest.load()` (`EnvPaths.ASSET_MANIFEST`) | hard failure: the prefix is never installed |
| `env/bootstrap/bootstrap.tar.gz` | `BootstrapExtractor` (symlinks preserved) | hard failure |
| `env/pkg/**` | `PkgInstaller` (`$PREFIX/libexec/pkg` + `bin/pkg` shim) | hard failure |
| `env/overlay/**` | `OverlayCopier` (`bin/`, `etc/`, `lib/`) | hard failure |

`build/phases/05b-env.sh` regenerates `assets/env/manifest.json` from the staged
bytes with `env/scripts/make_manifest.py`, and dies when the manifest of the
source bundle (`env/dist/env/manifest.json`) disagrees with those bytes — a
stale or missing manifest is exactly what leaves an installed app on the base
runtime, with no `usr/bin`, `pkg` or `python3` on the device. A missing
`env/` bundle aborts the build unless `AGY_ALLOW_MISSING_ENV=1` is set.

`build/phases/09b-verify.sh` then runs `build/tools/verify_env_assets.py`, which
recomputes the bundle identity (bootstrap SHA-256 and symlink count, `pkg`/`overlay`
tree digests, `bundleId`) from the bytes inside the finished APK, checks the
field contract that `EnvBundleManifest` enforces on device, and compares the
packaged tree with the staged one.

## Pipeline

The orchestrator runs `build/phases/*.sh` in lexical order:

1. Generate build constants and an edition-specific Android manifest.
2. Stage and patch the core binary, then verify the patch states.
3. Assemble glibc/runtime assets and generate the runtime SHA-256 manifest.
4. Build and stage web, bus and Bionic environment assets (`assets/env/manifest.json`
   included and verified against the staged bundle).
5. Generate `R.java`, compile Java, run D8, package, align and sign the APK.
6. Verify the APK signature and that the packaged `assets/env` bundle is complete
   and describes itself.

## Troubleshooting

- **`aapt`/`zipalign`/`apksigner` not found:** Install Android SDK Build Tools and add the binaries to `PATH`.
- **`android.jar` or `r8.jar` SHA-256 mismatch:** Restore the pinned file or review and intentionally update its configured checksum.
- **Release signing fails:** Confirm all four `AGY_RELEASE_*` inputs are set and that the keystore alias and passwords are correct. CI also checks `AGY_RELEASE_CERT_SHA256`.
- **Runtime integrity check fails on device:** Check storage errors and confirm the packaged runtime manifest matches the staged files.
- **The device only has the base runtime (no `usr/bin`, `pkg` or `python3`):** the installed APK was packaged without a complete `assets/env` bundle. `EnvInstaller` refuses to build `$PREFIX` when `assets/env/manifest.json` is missing or stale and keeps the core running, so look for `env install failed ...` in `files/logs/env.log` (included in the in-app "Copy Logs" export) or under the `EnvInstallScheduler` tag in logcat. Rebuild with `bash env/build.sh assets && bash build.sh --dev`; the staging phase and `build/tests/env_assets_test.sh` now fail closed on both cases.
