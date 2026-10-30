"""Select matching cached remapper libraries without Gradle or network access."""
from pathlib import Path
import tempfile
import unittest
import zipfile

import remap_worldgen_test_jar as remapper


class RemapperLibrariesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.modules = Path(temporary.name)

    def binary(self, artifact, version, declared=None, anchor=True, classifier="", cache_hash="hash"):
        directory = self.modules / "org.ow2.asm" / artifact / version / cache_hash
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / (artifact + "-" + version + classifier + ".jar")
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: "
                         + (version if declared is None else declared) + "\r\n\r\n")
            if anchor:
                jar.writestr(remapper.ASM_ARTIFACTS[artifact], b"binary fixture")
        return path

    def release(self, version):
        return [self.binary(artifact, version) for artifact in remapper.ASM_ARTIFACTS]

    def test_missing_local_991_still_uses_a_complete_installed_loom_release(self):
        expected = self.release("9.9")
        version, paths = remapper.cached_asm_classpath(self.modules)
        self.assertEqual("9.9", version)
        self.assertEqual(expected, paths)

    def test_partial_newer_release_cannot_mix_with_older_modules(self):
        expected = self.release("9.9")
        self.binary("asm", "9.9.1")
        self.binary("asm-tree", "9.9.1")
        version, paths = remapper.cached_asm_classpath(self.modules)
        self.assertEqual("9.9", version)
        self.assertEqual(expected, paths)

    def test_newest_complete_release_uses_numeric_version_order(self):
        self.release("9.9.1")
        expected = self.release("9.10")
        self.assertEqual(("9.10", expected), remapper.cached_asm_classpath(self.modules))

    def test_source_and_javadoc_jars_are_never_runtime_candidates(self):
        expected = self.release("9.9")
        for artifact in remapper.ASM_ARTIFACTS:
            self.binary(artifact, "9.9", anchor=False, classifier="-sources", cache_hash="sources")
            self.binary(artifact, "9.9", anchor=False, classifier="-javadoc", cache_hash="javadoc")
        self.assertEqual(("9.9", expected), remapper.cached_asm_classpath(self.modules))

    def test_no_matching_set_is_an_actionable_failure(self):
        for index, artifact in enumerate(remapper.ASM_ARTIFACTS):
            self.binary(artifact, "9.9." + str(index))
        with self.assertRaisesRegex(RuntimeError, "No complete matching ASM release"):
            remapper.cached_asm_classpath(self.modules)

    def test_releases_older_than_tiny_remappers_dependency_are_rejected(self):
        self.release("9.6")
        with self.assertRaisesRegex(RuntimeError, ">= 9.7.1"):
            remapper.cached_asm_classpath(self.modules)

    def test_mislabelled_module_cannot_pass_as_a_matching_release(self):
        self.release("9.9.1")
        self.binary("asm-tree", "9.9.1", declared="9.9")
        with self.assertRaisesRegex(RuntimeError, "does not match asm-tree 9.9.1"):
            remapper.cached_asm_classpath(self.modules)

    def test_missing_binary_class_cannot_pass_as_the_module(self):
        self.release("9.9.1")
        self.binary("asm-commons", "9.9.1", anchor=False)
        with self.assertRaisesRegex(RuntimeError, "does not match asm-commons 9.9.1"):
            remapper.cached_asm_classpath(self.modules)

    def test_conflicting_duplicate_cache_binaries_fail_instead_of_choosing_one(self):
        self.release("9.9")
        self.binary("asm", "9.9", cache_hash="different")
        with self.assertRaisesRegex(RuntimeError, "Expected exactly one cached artifact"):
            remapper.cached_asm_classpath(self.modules)


if __name__ == "__main__":
    unittest.main()
