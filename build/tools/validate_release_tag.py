#!/usr/bin/env python3
"""Fail if an APK release tag does not match its configured app version."""

import re
import sys


def read_env(path):
    values = {}
    with open(path, encoding="utf-8") as env_file:
        for line in env_file:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def main(argv):
    if len(argv) != 3:
        sys.stderr.write("usage: validate_release_tag.py <tag> <app.env>\n")
        return 2
    tag, config_path = argv[1:]
    version = read_env(config_path).get("APP_VERSION_NAME", "")
    expected = "v" + version
    if not re.fullmatch(r"v[0-9]+(?:\.[0-9]+){1,2}(?:[-+][0-9A-Za-z.-]+)?", tag):
        sys.stderr.write("release tag must be a version tag such as v2.3.0\n")
        return 1
    if tag != expected:
        sys.stderr.write(
            "release tag %s does not match configured app version %s\n" % (tag, expected)
        )
        return 1
    print("release tag matches app version: %s" % tag)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
