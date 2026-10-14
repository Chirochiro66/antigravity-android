#!/usr/bin/env python3
"""Create or verify the deterministic manifest for a staged env bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tarfile
from pathlib import Path

SCHEMA = 1


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_digest(root: Path) -> str:
    """Hash relative names and bytes, independent of mtimes and host paths."""
    digest = hashlib.sha256()
    files = sorted(path for path in root.rglob("*") if path.is_file())
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(relative)
        digest.update(b"\0")
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def read_seed_packages(root: Path) -> list[str]:
    path = root / "seed-packages.txt"
    if not path.is_file():
        return []
    packages = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.partition("#")[0].strip()
        if not line:
            continue
        if not all(ch.isalnum() or ch in "+.-_" for ch in line):
            raise ValueError(f"invalid seed package name: {line!r}")
        packages.append(line)
    return sorted(set(packages))


def make_manifest(root: Path) -> dict:
    bootstrap = root / "bootstrap" / "bootstrap.tar.gz"
    overlay = root / "overlay"
    pkg = root / "pkg"
    for required in (bootstrap, overlay, pkg):
        if not required.exists():
            raise FileNotFoundError(f"required env bundle input is missing: {required}")

    with tarfile.open(bootstrap, "r:gz") as archive:
        symlink_count = sum(member.issym() for member in archive)

    identity = {
        "schema": SCHEMA,
        "bootstrapSha256": sha256_file(bootstrap),
        "bootstrapSymlinkCount": symlink_count,
        "overlaySha256": tree_digest(overlay),
        "pkgSha256": tree_digest(pkg),
        "seedPackages": read_seed_packages(root),
    }
    canonical = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {**identity, "bundleId": hashlib.sha256(canonical).hexdigest()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify an existing manifest")
    parser.add_argument("root", type=Path, help="staged env directory")
    args = parser.parse_args()

    root = args.root.resolve()
    manifest_path = root / "manifest.json"
    expected = json.dumps(make_manifest(root), indent=2, sort_keys=True) + "\n"
    if args.check:
        if not manifest_path.is_file():
            raise SystemExit(f"manifest missing: {manifest_path}")
        actual = manifest_path.read_text(encoding="utf-8")
        if actual != expected:
            raise SystemExit(f"manifest is stale: {manifest_path}")
        print(f"[env.manifest] verified {manifest_path}")
    else:
        manifest_path.write_text(expected, encoding="utf-8")
        print(f"[env.manifest] bundle={json.loads(expected)['bundleId']} -> {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
