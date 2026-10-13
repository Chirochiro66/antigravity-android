#!/usr/bin/env python3
"""Generate the build manifest from config/app.env values."""

import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
INTERNAL_PERMISSION = "com.agy.permission.INTERNAL_BROADCAST"
ET.register_namespace("android", ANDROID_NS)


def android(name):
    return "{%s}%s" % (ANDROID_NS, name)


def main(argv):
    if len(argv) != 10:
        sys.stderr.write(
            "usage: prepare_manifest.py <template> <output> <pkg> <pkg_dev> "
            "<min_api> <target_sdk> <version_code> <version_name> <edition>\n"
        )
        return 2

    template, output, pkg, pkg_dev, min_api, target_sdk, version_code, version_name, edition = argv[1:]
    if edition not in ("dev", "release"):
        sys.stderr.write("edition must be dev or release\n")
        return 2
    try:
        min_api = int(min_api)
        target_sdk = int(target_sdk)
        version_code = int(version_code)
    except ValueError:
        sys.stderr.write("min_api, target_sdk and version_code must be integers\n")
        return 2
    if min_api < 1 or target_sdk < min_api or version_code < 1:
        sys.stderr.write("invalid SDK/version configuration\n")
        return 2

    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    tree = ET.parse(template, parser=parser)
    root = tree.getroot()
    package_name = pkg_dev if edition == "dev" else pkg
    root.set("package", package_name)
    root.set(android("versionCode"), str(version_code))
    root.set(android("versionName"), version_name)

    uses_sdk = root.find("uses-sdk")
    if uses_sdk is None:
        uses_sdk = ET.Element("uses-sdk")
        root.insert(0, uses_sdk)
    uses_sdk.set(android("minSdkVersion"), str(min_api))
    uses_sdk.set(android("targetSdkVersion"), str(target_sdk))

    application = root.find("application")
    if application is None:
        sys.stderr.write("manifest template has no application element\n")
        return 2
    application.set(android("debuggable"), "true" if edition == "dev" else "false")

    permission = package_name + ".permission.INTERNAL_BROADCAST"
    for node in root:
        if node.tag in ("permission", "uses-permission"):
            if node.get(android("name")) == INTERNAL_PERMISSION:
                node.set(android("name"), permission)

    if hasattr(ET, "indent"):
        ET.indent(tree, space="    ")
    tree.write(output, encoding="utf-8", xml_declaration=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
