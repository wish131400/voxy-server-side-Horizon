"""Boundary tests for fixed external inputs; no network, game or VSS Gradle."""
import hashlib
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import fetch_community_voxy as recipe


class CommunityInputTests(unittest.TestCase):
    def test_changed_download_never_replaces_verified_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.jar"
            path.write_bytes(b"previous artifact")
            with patch.object(recipe, "urlopen", return_value=io.BytesIO(b"changed upstream")):
                with self.assertRaisesRegex(ValueError, "checksum"):
                    recipe.download("https://example.invalid/fixed", path, hashlib.sha256(b"fixed").hexdigest())
            self.assertEqual(path.read_bytes(), b"previous artifact")
            self.assertFalse(path.with_suffix(".jar.part").exists())

    def test_verified_cache_does_not_use_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.jar"
            path.write_bytes(b"fixed")
            with patch.object(recipe, "urlopen", side_effect=AssertionError("network")):
                self.assertEqual(recipe.download("https://example.invalid", path, recipe.sha256(path)), path)

    def test_archive_rejects_parent_absolute_windows_and_symbolic_paths(self):
        for filename in ["root/../../escape", "/root/escape", "root/C:/escape", "root/..\\escape", "root/link"]:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as directory:
                archive = Path(directory) / "source.zip"
                entry = zipfile.ZipInfo(filename)
                if filename.endswith("link"):
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                with zipfile.ZipFile(archive, "w") as target:
                    target.writestr(entry, "outside")
                with self.assertRaises(ValueError):
                    recipe.extract_source(archive, Path(directory) / "work")
                self.assertFalse((Path(directory) / "escape").exists())

    def test_archive_preserves_production_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "source.zip"
            with zipfile.ZipFile(archive, "w") as target:
                target.writestr("repo/src/Renderer.java", b"original production bytes\r\n")
            work = Path(directory) / "work"
            recipe.extract_source(archive, work)
            self.assertEqual((work / "src/Renderer.java").read_bytes(), b"original production bytes\r\n")

    def test_patch_only_changes_gradle_metadata_and_selected_projects(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            common = ('tasks.withType<Copy>().configureEach {\n'
                      '    val commit = try {\n        exec("git")\n    } catch (e: Exception) { "unknown" }\n'
                      '    val buildtime = (System.currentTimeMillis() / 1000L).toString()\n}\n')
            settings = ('match("1.20.1", "fabric", "forge")\n'
                        'match("1.21.1", "fabric", "neoforge")\n'
                        'vcsVersion = "1.21.1-fabric"\n')
            (work / "build.common.gradle.kts").write_text(common)
            (work / "settings.gradle.kts").write_text(settings)
            (work / "production.java").write_bytes(b"unchanged")
            recipe.patch_build(work, recipe.SOURCES["m3"], "1.20.1")
            self.assertEqual((work / "production.java").read_bytes(), b"unchanged")
            self.assertIn(recipe.SOURCES["m3"]["commit"], (work / "build.common.gradle.kts").read_text())
            self.assertEqual((work / "settings.gradle.kts").read_text(),
                             'match("1.20.1", "fabric")\nmatch("1.21.1", "fabric")\nvcsVersion = "1.21.1-fabric"\n')

    def test_embedded_native_or_shader_change_rejects_source_build(self):
        classes, resources = recipe.SOURCE_CONTENT["m3-1.21.1"]
        info = {"classInventorySha256": classes, "resourcesSha256": resources, "nestedSha256": {}}
        with self.assertRaisesRegex(ValueError, "embedded libraries"):
            recipe.validate_source_content(info, "m3", "1.21.1")
        info["resourcesSha256"] = "changed shader"
        with self.assertRaisesRegex(ValueError, "resource contract"):
            recipe.validate_source_content(info, "m3", "1.21.1")

    def test_changed_upstream_build_contract_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / "build.common.gradle.kts").write_text("different upstream build")
            with self.assertRaisesRegex(ValueError, "metadata contract"):
                recipe.patch_build(work, recipe.SOURCES["m3"], "1.20.1")

    def test_jar_rejects_wrong_target_identity_commit_and_java(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voxy.jar"
            mod = {"id": "voxy", "version": "0.2.15-beta", "depends": {"minecraft": ["1.20.1"]},
                   "custom": {"commit": "fixed"}}
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(mod))
                jar.writestr("me/cortex/voxy/common/world/WorldEngine.class", b"\xca\xfe\xba\xbe\0\0\0A")
                jar.writestr("me/cortex/voxy/client/core/VoxyRenderSystem.class", b"\xca\xfe\xba\xbe\0\0\0A")
                jar.writestr("voxy.accesswidener", "accessWidener v2 intermediary")
            self.assertEqual(recipe.metadata(path, "voxy", "1.20.1", commit="fixed", java=21)["classCount"], 2)
            for arguments in [("sodium", "1.20.1", None, None), ("voxy", "1.21.1", None, None),
                              ("voxy", "1.20.1", "wrong", None), ("voxy", "1.20.1", "fixed", 17)]:
                with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                    recipe.metadata(path, arguments[0], arguments[1], commit=arguments[2], java=arguments[3])

    def test_official_iris_for_sodium_six_is_rejected_for_backport_eight(self):
        entries = {"sodium": {"metadata": {"version": "0.8.12+mc1.21.1"}},
                   "iris": {"metadata": {"depends": {"sodium": ["0.6.x"]}}}}
        with self.assertRaisesRegex(ValueError, "does not declare"):
            recipe.validate_pairing(entries, "1.21.1")
        entries["iris"]["metadata"]["depends"]["sodium"] = ["0.8.x"]
        recipe.validate_pairing(entries, "1.21.1")
        del entries["iris"]
        recipe.validate_pairing(entries, "1.21.1")

    def test_no_iris_inputs_retain_exact_voxy_and_sodium(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            recipe.write_inputs(path, {name: {"filename": name + "+fixed.jar"} for name in ("voxy", "sodium", "iris")})
            normal = (path / "inputs.properties").read_text().splitlines()
            without = (path / "inputs-no-iris.properties").read_text().splitlines()
            self.assertEqual(without, [line for line in normal if not line.startswith("vssIrisJar=")])


if __name__ == "__main__":
    unittest.main()
