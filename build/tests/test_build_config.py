import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class BuildConfigTest(unittest.TestCase):
    @staticmethod
    def read_config(path):
        values = {}
        with open(path, encoding="utf-8") as source:
            for line in source:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
        return values

    def run_tool(self, tool, *args, env=None):
        subprocess.run([sys.executable, os.path.join(ROOT, tool), *args],
                       check=True, cwd=ROOT, env=env)

    def test_manifest_release_and_dev_are_generated_from_config(self):
        config = self.read_config(os.path.join(ROOT, "config/app.env"))
        template = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
        with tempfile.TemporaryDirectory() as temp:
            for edition, expected_package, expected_debug in (
                    ("release", config["PKG"], "false"),
                    ("dev", config["PKG_DEV"], "true")):
                output = os.path.join(temp, edition + ".xml")
                self.run_tool(
                    "build/tools/prepare_manifest.py", template, output,
                    config["PKG"], config["PKG_DEV"], config["MIN_API"],
                    config["TARGET_SDK"], config["APP_VERSION_CODE"],
                    config["APP_VERSION_NAME"], edition,
                )
                root = ET.parse(output).getroot()
                application = root.find("application")
                uses_sdk = root.find("uses-sdk")
                self.assertEqual(root.get("package"), expected_package)
                self.assertEqual(root.get(ANDROID_NS + "versionName"),
                                 config["APP_VERSION_NAME"])
                self.assertEqual(root.get(ANDROID_NS + "versionCode"),
                                 config["APP_VERSION_CODE"])
                self.assertEqual(uses_sdk.get(ANDROID_NS + "minSdkVersion"),
                                 config["MIN_API"])
                self.assertEqual(uses_sdk.get(ANDROID_NS + "targetSdkVersion"),
                                 config["TARGET_SDK"])
                self.assertEqual(application.get(ANDROID_NS + "debuggable"), expected_debug)
                activities = {
                    node.get(ANDROID_NS + "name"): node
                    for node in application.findall("activity")
                }
                self.assertEqual(
                    activities["com.agy.ui.MainActivity"].get(ANDROID_NS + "exported"),
                    "false",
                )
                for launcher in ("com.agy.ui.launcher.LauncherDarkActivity",
                                 "com.agy.ui.launcher.LauncherLightActivity"):
                    self.assertEqual(activities[launcher].get(ANDROID_NS + "exported"),
                                     "true")
                receiver = application.find("receiver")
                self.assertEqual(receiver.get(ANDROID_NS + "exported"), "false")
                permission = expected_package + ".permission.INTERNAL_BROADCAST"
                names = {node.get(ANDROID_NS + "name") for node in root}
                self.assertIn(permission, names)

    def test_build_config_edition_and_version(self):
        # gen_android_config.py takes <app.env> <outdir> only; the edition lives
        # in the environment (DEV_MODE is exported by build/lib/flags.sh, and
        # build/phases/00-dev.sh renders the dev label/port and manifest).
        config_path = os.path.join(ROOT, "config/app.env")
        config = self.read_config(config_path)
        with tempfile.TemporaryDirectory() as temp:
            self.run_tool("build/tools/gen_android_config.py", config_path, temp,
                          env=dict(os.environ, DEV_MODE="1"))
            generated = os.path.join(temp, "com/agy/BuildConfig.java")
            with open(generated, encoding="utf-8") as source:
                content = source.read()
            self.assertIn('APP_VERSION_NAME="%s"' % config["APP_VERSION_NAME"], content)
            self.assertIn("APP_VERSION_CODE=%s" % config["APP_VERSION_CODE"], content)
            self.assertIn("PORT_DEV=%s" % config["PORT_DEV"], content)
            self.assertIn("TARGET_SDK=%s" % config["TARGET_SDK"], content)
            self.assertIn("DEBUG=true", content)

            self.run_tool("build/tools/gen_android_config.py", config_path, temp,
                          env=dict(os.environ, DEV_MODE="0"))
            with open(generated, encoding="utf-8") as source:
                content = source.read()
            self.assertIn("PORT=%s" % config["PORT"], content)
            self.assertIn("DEBUG=false", content)

    def test_release_tag_must_match_app_version(self):
        config = os.path.join(ROOT, "config/app.env")
        version = self.read_config(config)["APP_VERSION_NAME"]
        self.run_tool("build/tools/validate_release_tag.py", "v" + version, config)
        for invalid in ("v99.0.0", version, "release-" + version):
            with self.subTest(tag=invalid), self.assertRaises(subprocess.CalledProcessError):
                subprocess.run(
                    [sys.executable,
                     os.path.join(ROOT, "build/tools/validate_release_tag.py"),
                     invalid, config],
                    check=True, cwd=ROOT,
                )

    def test_pinned_core_certificate_matches_bundled_binary(self):
        cert_path = os.path.join(ROOT, "app/src/main/res/raw/agy_core_cert.pem")
        archive_path = os.path.join(ROOT, "core/language_server.tar.gz")
        with open(cert_path, "rb") as source:
            certificate = source.read()
        overlap = len(certificate) - 1
        tail = b""
        with tarfile.open(archive_path, "r:gz") as archive:
            member = archive.getmember("language_server")
            stream = archive.extractfile(member)
            self.assertIsNotNone(stream)
            with stream:
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    block = tail + chunk
                    if certificate in block:
                        return
                    tail = block[-overlap:]
        self.fail("pinned core certificate was not found in the bundled core binary")


if __name__ == "__main__":
    unittest.main()
