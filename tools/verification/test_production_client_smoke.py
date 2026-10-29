"""Offline launch-input boundaries; these tests do not start Minecraft."""
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import production_client_smoke as smoke


class ProductionLaunchInputTests(unittest.TestCase):
    def test_native_architectures_do_not_collide_on_windows_x64(self):
        with patch.object(smoke.platform, "machine", return_value="AMD64"):
            self.assertTrue(smoke.native_matches_machine("org.lwjgl:lwjgl:3.3.3:natives-windows"))
            self.assertFalse(smoke.native_matches_machine("org.lwjgl:lwjgl:3.3.3:natives-windows-arm64"))
            self.assertFalse(smoke.native_matches_machine("org.lwjgl:lwjgl:3.3.3:natives-windows-x86"))
        with patch.object(smoke.platform, "machine", return_value="aarch64"):
            self.assertTrue(smoke.native_matches_machine("org.lwjgl:lwjgl:3.3.3:natives-windows-arm64"))
            self.assertFalse(smoke.native_matches_machine("org.lwjgl:lwjgl:3.3.3:natives-windows"))

    def test_ordered_mojang_os_rules_and_disabled_launcher_features(self):
        with patch.object(smoke, "os_name", return_value="windows"):
            self.assertTrue(smoke.allowed(None))
            self.assertFalse(smoke.allowed([{"action": "allow", "os": {"name": "linux"}}]))
            self.assertFalse(smoke.allowed([{"action": "allow"}, {"action": "disallow", "os": {"name": "windows"}}]))
            self.assertFalse(smoke.allowed([{"action": "allow", "features": {"is_demo_user": True}}]))
            self.assertTrue(smoke.allowed([{"action": "allow", "features": {"is_demo_user": False}}]))

    def test_checksum_failure_preserves_preexisting_runtime_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "runtime.jar"
            target.write_bytes(b"previous")
            with patch.object(smoke, "urlopen", return_value=io.BytesIO(b"wrong")):
                with self.assertRaisesRegex(ValueError, "checksum"):
                    smoke.obtain("https://example.invalid/fixed", target, hashlib.sha256(b"fixed").hexdigest())
            self.assertEqual(target.read_bytes(), b"previous")

    def test_maven_coordinate_cannot_escape_runtime_library_directory(self):
        for name in ["net.fabricmc:../loader:0.20.1", "net.fabricmc:loader:C:/escape", "bad"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                smoke.coordinate_path(name)
        self.assertEqual(smoke.coordinate_path("net.fabricmc:intermediary:1.21.1"),
                         "net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar")

    def test_native_escape_and_conflicting_binary_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            native = base / "natives"
            native.mkdir()
            archive = base / "input.jar"
            with zipfile.ZipFile(archive, "w") as jar:
                jar.writestr("../escape.dll", b"bad")
            with self.assertRaisesRegex(ValueError, "path escape"):
                smoke.safe_native_extract(archive, native)
            self.assertFalse((base / "escape.dll").exists())
            with zipfile.ZipFile(archive, "w") as jar:
                jar.writestr("freetype.dll", b"one architecture")
            smoke.safe_native_extract(archive, native)
            with zipfile.ZipFile(archive, "w") as jar:
                jar.writestr("freetype.dll", b"different architecture")
            with self.assertRaisesRegex(ValueError, "Conflicting native"):
                smoke.safe_native_extract(archive, native)

    def test_extra_input_properties_cannot_modify_production_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inputs.properties"
            path.write_text("vssVoxyJar=a.jar\nvssSodiumJar=b.jar\njava.library.path=unexpected\n")
            with self.assertRaisesRegex(ValueError, "Unexpected production dependency"):
                smoke.read_inputs(path)

    def test_named_or_replaced_vss_artifact_is_rejected_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vss.jar"
            def write(namespace):
                with zipfile.ZipFile(path, "w") as jar:
                    jar.writestr("fabric.mod.json", '{"id":"vss","accessWidener":"vss.accesswidener"}')
                    jar.writestr("vss.accesswidener", "accessWidener v2 " + namespace + "\n")
                    jar.writestr("META-INF/MANIFEST.MF", "VSS-Minecraft: 1.20.1\n")
            write("named")
            with self.assertRaisesRegex(ValueError, "remapped to intermediary"):
                smoke.validate_remapped_vss(path, "1.20.1")
            write("intermediary")
            expected = smoke.digest(path)
            smoke.validate_remapped_vss(path, "1.20.1", expected)
            with self.assertRaisesRegex(ValueError, "Minecraft manifest"):
                smoke.validate_remapped_vss(path, "1.21.1", expected)
            path.write_bytes(path.read_bytes() + b"replaced")
            with self.assertRaisesRegex(ValueError, "replaced"):
                smoke.validate_remapped_vss(path, "1.20.1", expected)


if __name__ == "__main__":
    unittest.main()
