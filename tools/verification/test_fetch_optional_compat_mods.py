"""Validate published optional-mod input contracts without network or Gradle."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import fetch_optional_compat_mods as recipe


def fabric_jar(mod_id="xaeroworldmap", version="1.47.2", minecraft=">=26.1 <26.2.0"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as jar:
        jar.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": mod_id, "version": version,
            "depends": {"minecraft": minecraft},
        }))
    return output.getvalue()


def fixture(project="xaeros-world-map", filename="xaeroworldmap-fabric-26.1.2-1.47.2.jar",
            versions=None, data=None):
    if data is None:
        data = fabric_jar(recipe.MOD_IDS[project])
    digest = hashlib.sha256(data).hexdigest()
    pin = {"release": "fixed-release", "filename": filename, "sha256": digest}
    metadata = {
        "id": pin["release"], "project_id": "project-id", "version_number": "1.47.2",
        "loaders": ["fabric"], "game_versions": versions or ["26.1", "26.1.1", "26.1.2"],
        "files": [{"filename": filename, "url": "https://example.invalid/" + project,
                   "primary": True, "hashes": {"sha256": digest}}],
    }
    return pin, metadata, data


class OptionalInputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name)
        self.calls = []

    def network(self, releases):
        responses = {}
        for project, (pin, metadata, data) in releases.items():
            responses["https://api.modrinth.com/v2/version/" + pin["release"]] = json.dumps(metadata).encode()
            responses["https://api.modrinth.com/v2/project/" + project] = json.dumps({"id": "project-id"}).encode()
            responses[metadata["files"][0]["url"]] = data

        def request(url):
            self.calls.append(url)
            if url not in responses:
                raise AssertionError("Unexpected download: " + url)
            return io.BytesIO(responses[url])

        return patch.object(recipe, "request", side_effect=request)

    def fetch(self, releases, target="26.1", **arguments):
        pins = {project: values[0] for project, values in releases.items()}
        with patch.object(recipe, "PINNED", {target: pins}), self.network(releases):
            return recipe.fetch(target, self.destination, **arguments)

    def test_shared_release_uses_declared_versions_instead_of_filename(self):
        releases = {"xaeros-world-map": fixture()}
        for target in ("26.1", "26.1.1", "26.1.2"):
            with self.subTest(target=target):
                inputs = self.fetch(releases, target)
                self.assertEqual(inputs[0]["minecraft"], target)
                self.assertEqual(inputs[0]["game_versions"], ["26.1", "26.1.1", "26.1.2"])
                self.assertEqual(inputs[0]["filename"], releases["xaeros-world-map"][0]["filename"])

    def test_similar_version_strings_do_not_establish_compatibility(self):
        for target, declared in (("26.1", ["26.1.2"]), ("1.21.1", ["1.21.11"]),
                                 ("26.1", "26.1.2")):
            with self.subTest(target=target, declared=declared):
                releases = {"xaeros-world-map": fixture(versions=declared)}
                with self.assertRaisesRegex(ValueError, "does not declare Fabric"):
                    self.fetch(releases, target)
                self.assertFalse(any(self.destination.glob("*.jar")))

    def test_wrong_release_loader_and_project_are_rejected(self):
        for field, value in (("id", "another-release"), ("loaders", ["neoforge"]),
                             ("project_id", "another-project")):
            with self.subTest(field=field):
                pin, metadata, data = fixture()
                metadata[field] = value
                with self.assertRaises(ValueError):
                    self.fetch({"xaeros-world-map": (pin, metadata, data)})
                self.assertFalse(any(self.destination.glob("*.jar")))

    def test_invalid_artifact_never_replaces_existing_file(self):
        for data, message in ((b"not a jar", "valid Fabric metadata"),
                              (fabric_jar("wrong-mod"), "Fabric mod ID")):
            with self.subTest(message=message):
                pin, metadata, data = fixture(data=data)
                path = self.destination / pin["filename"]
                previous = fabric_jar()
                path.write_bytes(previous)
                with self.assertRaisesRegex(ValueError, message):
                    self.fetch({"xaeros-world-map": (pin, metadata, data)})
                self.assertEqual(path.read_bytes(), previous)
                self.assertFalse(path.with_suffix(".jar.part").exists())

    def test_download_and_pinned_checksum_failures_preserve_existing_file(self):
        for change in ("download", "pin"):
            with self.subTest(change=change):
                pin, metadata, data = fixture()
                path = self.destination / pin["filename"]
                path.write_bytes(b"existing artifact")
                if change == "download":
                    data = b"unexpected upstream bytes"
                else:
                    pin["sha256"] = "0" * 64
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    self.fetch({"xaeros-world-map": (pin, metadata, data)})
                self.assertEqual(path.read_bytes(), b"existing artifact")

    def test_cache_still_checks_metadata_without_downloading_jar(self):
        pin, metadata, data = fixture()
        (self.destination / pin["filename"]).write_bytes(data)
        inputs = self.fetch({"xaeros-world-map": (pin, metadata, data)})
        self.assertEqual(inputs[0]["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(len(self.calls), 2)
        self.assertFalse(any(url.startswith("https://example.invalid/") for url in self.calls))
        metadata["game_versions"] = ["26.1.2"]
        with self.assertRaisesRegex(ValueError, "does not declare Fabric"):
            self.fetch({"xaeros-world-map": (pin, metadata, data)})

    def test_release_override_keeps_upstream_project_target_and_hash_checks(self):
        pin, metadata, data = fixture()
        override = "explicit-new-release"
        metadata["id"] = override
        request_pin = dict(pin, release=override)
        releases = {"xaeros-world-map": (request_pin, metadata, data)}
        with patch.object(recipe, "PINNED", {"26.1": {"xaeros-world-map": pin}}), self.network(releases):
            inputs = recipe.fetch("26.1", self.destination, releases={"xaeros-world-map": override})
        self.assertEqual(inputs[0]["version_id"], override)

    def test_cross_platform_and_properties_filename_injection_is_rejected(self):
        for filename in ("../outside.jar", "..\\outside.jar", "/outside.jar", "C:\\outside.jar",
                         "C:outside.jar", "injected\nvssExtraJar=outside.jar"):
            with self.subTest(filename=filename):
                with self.assertRaisesRegex(ValueError, "Invalid release filename"):
                    self.fetch({"xaeros-world-map": fixture(filename=filename)})
                self.assertFalse(any(self.destination.iterdir()))

    def test_legacy_github_target_is_an_explicit_contract(self):
        pin = copy.deepcopy(recipe.PINNED["1.21.1"]["reterraforged-legacy"])
        data = fabric_jar("reterraforged", "0.0.6005", ">=1.21.1")
        pin["sha256"] = hashlib.sha256(data).hexdigest()
        with patch.object(recipe, "PINNED", {"1.21.1": {"reterraforged-legacy": pin}}), \
                patch.object(recipe, "request", return_value=io.BytesIO(data)):
            inputs = recipe.fetch("1.21.1", self.destination)
        self.assertEqual(inputs[0]["game_versions"], ["1.21.1"])
        # A broad >= declaration does not mean the verified pin covers later versions.
        with patch.object(recipe, "PINNED", {"1.21.11": {"reterraforged-legacy": pin}}), \
                patch.object(recipe, "request", side_effect=AssertionError("network")):
            with self.assertRaisesRegex(ValueError, "verified target"):
                recipe.fetch("1.21.11", self.destination)

    def test_legacy_github_metadata_cannot_change_behind_fixed_input(self):
        baseline = recipe.PINNED["1.21.1"]["reterraforged-legacy"]
        for mod_id, version, depends in (("another", "0.0.6005", ">=1.21.1"),
                                          ("reterraforged", "new", ">=1.21.1"),
                                          ("reterraforged", "0.0.6005", ">=1.21.11")):
            with self.subTest(mod_id=mod_id, version=version, depends=depends):
                pin = copy.deepcopy(baseline)
                data = fabric_jar(mod_id, version, depends)
                pin["sha256"] = hashlib.sha256(data).hexdigest()
                with patch.object(recipe, "PINNED", {"1.21.1": {"reterraforged-legacy": pin}}), \
                        patch.object(recipe, "request", return_value=io.BytesIO(data)):
                    with self.assertRaisesRegex(ValueError, "unexpected Fabric"):
                        recipe.fetch("1.21.1", self.destination)

    def test_output_identifies_raw_artifacts_and_preserves_property_conflicts(self):
        releases = {
            "freeterraforged": fixture("freeterraforged", "freeterraforged.jar"),
            "reterraforged-legacy": fixture("reterraforged-legacy", "reterraforged.jar"),
            "byepregen": fixture("byepregen", "byepregen.jar"),
        }
        # A distinct version ID is needed for each independent upstream response.
        for project, (pin, metadata, _) in releases.items():
            pin["release"] = metadata["id"] = project + "-release"
        inputs = self.fetch(releases)
        properties = (self.destination / "raw-inputs.properties").read_text()
        self.assertIn("# Specify vssFreeTerraForgedJar separately for:", properties)
        self.assertNotIn("\nvssFreeTerraForgedJar=", properties)
        self.assertIn("\nvssByepregenJar=", properties)
        self.assertFalse((self.destination / "named-inputs.properties").exists())
        self.assertEqual(json.loads((self.destination / "inputs.json").read_text()), inputs)
        self.assertEqual({entry["mod_id"] for entry in inputs}, {"freeterraforged", "reterraforged", "byepregen"})

    def test_tectonic_uses_fixed_fabric_identity_hash_and_property(self):
        release = fixture("tectonic", "tectonic-test-fabric.jar")
        inputs = self.fetch({"tectonic": release})
        self.assertEqual("tectonic", inputs[0]["mod_id"])
        self.assertEqual(release[0]["sha256"], inputs[0]["sha256"])
        self.assertIn("vssTectonicJar=", (self.destination / "raw-inputs.properties").read_text())
        wrong = fixture("tectonic", "tectonic-wrong.jar", data=fabric_jar("another"))
        with self.assertRaisesRegex(ValueError, "unexpected Fabric mod ID"):
            self.fetch({"tectonic": wrong})

    def test_tectonic_pin_exists_for_every_exact_target(self):
        expected = {"1.20.1": "mz3wlQKd", "1.21.1": "WdDiEKMn", "1.21.11": "7olSYFxL",
                    "26.1": "5v9Cz8zS", "26.1.1": "5v9Cz8zS", "26.1.2": "5v9Cz8zS",
                    "26.2": "tGbplDzo", "26.3": "tRBGDcCT"}
        for minecraft, release in expected.items():
            with self.subTest(minecraft=minecraft):
                pin = recipe.PINNED[minecraft]["tectonic"]
                self.assertEqual(release, pin["release"])
                self.assertEqual(128, len(pin["sha512"]))
                self.assertIn("fabric", pin["filename"])
        self.assertEqual("vssTectonicJar", recipe.PROPERTY_NAMES["tectonic"])


if __name__ == "__main__":
    unittest.main()
