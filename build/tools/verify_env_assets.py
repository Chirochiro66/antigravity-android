#!/usr/bin/env python3
"""Verify that the bionic env bundle packaged into an APK is complete and usable.

The app deploys ``assets/env/**`` into its private ``files/usr`` prefix on first
launch (``com.agy.install.EnvInstaller``). It reads ``assets/env/manifest.json``
before anything else and fails closed when the asset is missing or does not
describe the shipped bundle, which leaves the installed app on the base runtime
only: no ``files/usr``, no ``pkg``, no ``python3``.

Usage:
    verify_env_assets.py <apk> [--staged-env DIR] [--make-manifest PATH]

Every check is fail-closed; the first violation exits non-zero with a message
naming the asset and the expected state.
"""

from __future__ import annotations

import argparse
import gzip
import importlib.util
import json
import os
import pathlib
import re
import sys
import tempfile
import zipfile

ENV_PREFIX = "assets/env/"
BOOTSTRAP_ENTRY = ENV_PREFIX + "bootstrap/bootstrap.tar.gz"
MANIFEST_ENTRY = ENV_PREFIX + "manifest.json"
# The assets EnvInstaller/EnvPaths opens besides the manifest.
REQUIRED_ENTRIES = (
    BOOTSTRAP_ENTRY,
    MANIFEST_ENTRY,
    ENV_PREFIX + "pkg/pkg",
    ENV_PREFIX + "overlay/bin/agy-fix-paths",
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
PACKAGE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9+._-]*$")
SUPPORTED_SCHEMA = 1


def default_make_manifest() -> str:
    root = pathlib.Path(__file__).resolve().parents[2]
    return str(root / "env" / "scripts" / "make_manifest.py")


def fail(message: str) -> None:
    raise SystemExit(message)


def load_make_manifest(path: str):
    """Import env/scripts/make_manifest.py so both sides use one algorithm."""
    spec = importlib.util.spec_from_file_location("agy_make_manifest", path)
    if spec is None or spec.loader is None:
        fail("cannot load env manifest tool: " + path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def extract_env_tree(apk: str, destination: pathlib.Path) -> list[str]:
    """Extract assets/env/** into ``destination`` and return the entry names."""
    tree = destination / "env"
    with zipfile.ZipFile(apk) as archive:
        entries = [name for name in archive.namelist() if name.startswith(ENV_PREFIX)]
        if not entries:
            fail("APK ships no assets/env/** at all: the device cannot deploy $PREFIX")
        for name in entries:
            target = tree / name[len(ENV_PREFIX):]
            if name.endswith("/"):
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(name) as source, open(target, "wb") as sink:
                while True:
                    block = source.read(1 << 20)
                    if not block:
                        break
                    sink.write(block)
        # fail-closed: legacy aapt strips a trailing ".gz" from asset names and
        # stores the decompressed payload; 08-package.sh re-adds the real gzip
        # entry. Any other bootstrap entry means name and format disagree.
        strays = [name for name in entries
                  if name.startswith(ENV_PREFIX + "bootstrap/") and name != BOOTSTRAP_ENTRY]
        if strays:
            fail("unexpected bootstrap asset entries (name/format mismatch): %r" % (strays,))
    return entries


def check_bootstrap(apk: str) -> int:
    """The packaged bootstrap must be a real gzip archive named .tar.gz."""
    with zipfile.ZipFile(apk) as archive:
        data = archive.read(BOOTSTRAP_ENTRY)
    if data[:2] != b"\x1f\x8b":
        fail("packaged bootstrap is not gzip (magic=%r): %s" % (data[:2], BOOTSTRAP_ENTRY))
    try:
        gzip.decompress(data)
    except OSError as error:
        fail("packaged bootstrap is not a readable gzip archive: %s" % error)
    return len(data)


def read_packaged_manifest(apk: str) -> dict:
    with zipfile.ZipFile(apk) as archive:
        try:
            raw = archive.read(MANIFEST_ENTRY)
        except KeyError:
            fail("APK is missing %s: the device cannot deploy $PREFIX" % MANIFEST_ENTRY)
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail("%s is not valid JSON: %s" % (MANIFEST_ENTRY, error))
    if not isinstance(manifest, dict):
        fail("%s must be a JSON object" % MANIFEST_ENTRY)
    return manifest


def check_manifest_contract(manifest: dict) -> None:
    """Mirror the fail-closed parsing in com.agy.install.EnvBundleManifest."""
    schema = manifest.get("schema")
    if schema != SUPPORTED_SCHEMA:
        fail("unsupported env manifest schema: %r" % (schema,))
    for field in ("bundleId", "bootstrapSha256", "overlaySha256", "pkgSha256"):
        value = manifest.get(field)
        if not isinstance(value, str) or not SHA256.match(value):
            fail("invalid env manifest field: " + field)
    symlinks = manifest.get("bootstrapSymlinkCount")
    if not isinstance(symlinks, int) or isinstance(symlinks, bool) or symlinks < 1:
        fail("env manifest bootstrapSymlinkCount must be >= 1: %r" % (symlinks,))
    packages = manifest.get("seedPackages")
    if not isinstance(packages, list):
        fail("env manifest seedPackages must be a list")
    if len(set(packages)) != len(packages):
        fail("env manifest seedPackages contains duplicates")
    for name in packages:
        if not isinstance(name, str) or not PACKAGE.match(name):
            fail("invalid seed package name in env manifest: %r" % (name,))


def check_against_packaged_bytes(manifest: dict, tree: pathlib.Path, module) -> None:
    """Recompute the bundle identity from the packaged bytes and compare."""
    expected = module.make_manifest(tree)
    differences = {
        key: (manifest.get(key), value)
        for key, value in expected.items()
        if manifest.get(key) != value
    }
    if differences:
        details = "; ".join(
            "%s packaged=%r recomputed=%r" % (key, pair[0], pair[1])
            for key, pair in sorted(differences.items())
        )
        fail("packaged %s does not describe the packaged bundle (%s)"
             % (MANIFEST_ENTRY, details))


def check_staged_match(staged_env: pathlib.Path, tree: pathlib.Path) -> None:
    """The packaged tree must equal the staged assets/env tree byte-for-byte."""
    differences: list[str] = []
    for base, _dirs, files in os.walk(tree):
        for name in files:
            packaged = pathlib.Path(base) / name
            relative = packaged.relative_to(tree)
            staged = staged_env / relative
            if not staged.is_file():
                differences.append("packaged but not staged: " + relative.as_posix())
            elif packaged.read_bytes() != staged.read_bytes():
                differences.append("content differs: " + relative.as_posix())
    if differences:
        fail("packaged assets/env differs from %s: %s"
             % (staged_env, "; ".join(differences[:5])))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Verify the packaged assets/env bundle.")
    parser.add_argument("apk")
    parser.add_argument("--staged-env", default=None,
                        help="staged assets/env tree (adds a byte-for-byte comparison)")
    parser.add_argument("--make-manifest", default=default_make_manifest(),
                        help="env/scripts/make_manifest.py used for the identity check")
    args = parser.parse_args(argv[1:])

    if not os.path.isfile(args.apk):
        fail("apk not found: " + args.apk)
    if not os.path.isfile(args.make_manifest):
        fail("env manifest tool not found: " + args.make_manifest)
    staged_env = pathlib.Path(args.staged_env) if args.staged_env else None
    if staged_env is not None and not staged_env.is_dir():
        fail("staged assets/env not found: " + args.staged_env)

    with tempfile.TemporaryDirectory(prefix="agy-env-verify-") as work:
        tree = pathlib.Path(work) / "env"
        entries = extract_env_tree(args.apk, pathlib.Path(work))

        missing = [name for name in REQUIRED_ENTRIES if name not in entries]
        if missing:
            fail("APK env bundle is incomplete, missing: %r" % (missing,))

        manifest = read_packaged_manifest(args.apk)
        check_manifest_contract(manifest)
        size = check_bootstrap(args.apk)
        module = load_make_manifest(args.make_manifest)
        check_against_packaged_bytes(manifest, tree, module)
        if staged_env is not None:
            check_staged_match(staged_env, tree)

    print("[verify] %s: %d env entries, bootstrap %d bytes, bundle=%s"
          % (os.path.basename(args.apk), len(entries), size, manifest["bundleId"]))
    print("[verify] packaged assets/env/manifest.json matches the packaged bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
