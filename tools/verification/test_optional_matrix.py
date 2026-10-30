"""Evidence-runner regressions; do not download dependencies or launch Gradle."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

import optional_matrix as matrix


SETTINGS_CONTRACT = ("TectonicPresetSnapshotTest", "configuredPresetSurvivesSettingsCodecAndClientNoiseInitialization")
FLOAT_CONTRACT = ("TectonicPresetSnapshotTest", "normalAndSmoothKeepReleasedFloatScalarAndDoubleVolumeOrdering")
MAIN_TARGETS = ("1.20.1", "1.21.1", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2")


def xml_report(path, contracts, skipped=()):
    suite = ET.Element("testsuite", tests=str(len(contracts)), failures="0", errors="0", skipped=str(len(skipped)))
    for index, (class_name, name) in enumerate(contracts):
        case = ET.SubElement(suite, "testcase", classname="fixture." + class_name, name=name + "()")
        if index in skipped:
            ET.SubElement(case, "skipped")
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


class OptionalMatrixTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = self.root / "external/optional-compat/target"
        self.java = self.root / "jdk/bin/java"
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"java fixture")

    def jar(self, name):
        path = self.directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(name.encode())
        return path

    def minimal_inputs(self, minecraft="26.1.1"):
        properties = {key: self.jar(key + ".jar").as_posix() for key in (
            "vssC2meJar", "vssC2meDfcJar", "vssXaeroJars")}
        properties["vssTectonicJar"] = self.jar("tectonic.jar").as_posix()
        self.assertEqual({"c2me-fabric", "xaeros-world-map", "tectonic"}, set(matrix.fetch_optional_compat_mods.PINNED[minecraft]))
        return properties

    def contracts(self, properties, minecraft="26.1.1"):
        contracts = matrix.ALWAYS_REQUIRED + [item for key, values in matrix.CONTRACTS.items() if key in properties for item in values]
        if "vssTectonicJar" in properties:
            contracts.append(FLOAT_CONTRACT if minecraft == "26.3" else SETTINGS_CONTRACT)
        return contracts

    def test_platform_commands_preserve_paths_and_property_values_as_arguments(self):
        root = Path("folder with spaces")
        command = matrix.gradle_command(root, "1.21.1", "optionalCompatTest", {"vssXaeroJars": "one file.jar|two file.jar"}, windows=False)
        self.assertEqual(["bash", str(root / "gradlew"), "optionalCompatTest", "--rerun"], command[:4])
        self.assertIn("-PvssXaeroJars=one file.jar|two file.jar", command)
        windows = matrix.gradle_command(root, "26.3", "testClasses", windows=True)
        self.assertEqual(str(root / "gradlew.bat"), windows[0])
        self.assertNotIn("--rerun", windows)

    def test_expected_actual_dependency_cannot_be_skipped_behind_passing_fixtures(self):
        properties = self.minimal_inputs()
        contracts = self.contracts(properties)
        xml_report(self.directory / "TEST-all.xml", contracts, skipped=(len(contracts) - 1,))
        result = matrix.test_results(self.directory, properties)
        self.assertEqual("failed", result["status"])
        self.assertTrue(result["missingRequired"])
        self.assertTrue(result["unexpectedSkips"])

    def test_omitted_actual_test_class_is_a_failure(self):
        properties = self.minimal_inputs()
        contracts = [item for item in self.contracts(properties) if item[0] != "C2meDensityMemoCompatTest"]
        xml_report(self.directory / "TEST-all.xml", contracts)
        result = matrix.test_results(self.directory, properties)
        self.assertEqual("failed", result["status"])
        self.assertIn("C2meDensityMemoCompatTest", {item["class"] for item in result["missingRequired"]})

    def test_known_unavailable_mod_skips_are_allowed(self):
        properties = self.minimal_inputs()
        skipped = matrix.CONTRACTS["vssTrinketsJar"] + [("LostCityReleaseMatrixTest", "installedReleaseContracts")]
        contracts = self.contracts(properties) + skipped
        xml_report(self.directory / "TEST-all.xml", contracts, skipped=tuple(range(len(contracts) - len(skipped), len(contracts))))
        result = matrix.test_results(self.directory, properties)
        self.assertEqual("passed", result["status"])
        self.assertEqual(2, result["skipped"])

    def test_unknown_fixture_skips_are_not_silently_accepted(self):
        properties = self.minimal_inputs()
        contracts = self.contracts(properties) + [("UnexpectedTest", "notImplemented")]
        xml_report(self.directory / "TEST-all.xml", contracts, skipped=(len(contracts) - 1,))
        self.assertEqual("failed", matrix.test_results(self.directory, properties)["status"])

    def test_xml_failure_counts_cannot_be_concealed_by_passing_cases(self):
        properties = self.minimal_inputs()
        path = self.directory / "TEST-all.xml"
        xml_report(path, self.contracts(properties))
        tree = ET.parse(path)
        tree.getroot().set("failures", "1")
        tree.write(path)
        self.assertEqual("failed", matrix.test_results(self.directory, properties)["status"])

    def test_263_only_exempts_the_retired_density_memo_test(self):
        properties = self.minimal_inputs()
        contracts = [item for item in self.contracts(properties, "26.3") if item[0] != "DensityMemoCompatTest"]
        path = self.directory / "TEST-all.xml"
        xml_report(path, contracts)
        self.assertEqual("passed", matrix.test_results(self.directory, properties, "26.3")["status"])
        self.assertEqual("failed", matrix.test_results(self.directory, properties, "26.2")["status"])
        contracts = [item for item in contracts if item[0] != "FabricEquipmentCompatTest"]
        xml_report(path, contracts)
        result = matrix.test_results(self.directory, properties, "26.3")
        self.assertEqual("failed", result["status"])
        self.assertIn("FabricEquipmentCompatTest", {item["class"] for item in result["missingRequired"]})

    def test_supplied_tectonic_can_never_hide_behind_unrelated_passing_cases(self):
        properties = self.minimal_inputs()
        contracts = self.contracts(properties)
        path = self.directory / "TEST-all.xml"
        tectonic = matrix.CONTRACTS["vssTectonicJar"] + [SETTINGS_CONTRACT]
        skipped = tuple(i for i, contract in enumerate(contracts) if contract in tectonic)
        xml_report(path, contracts, skipped=skipped)
        result = matrix.test_results(self.directory, properties, "26.1.1")
        self.assertEqual("failed", result["status"])
        self.assertEqual(3, len(result["missingRequired"]))
        self.assertEqual(3, len(result["unexpectedSkips"]))

    def test_tectonic_settings_codec_must_be_present_for_every_main_target(self):
        properties = self.minimal_inputs()
        path = self.directory / "TEST-all.xml"
        for minecraft in MAIN_TARGETS:
            with self.subTest(minecraft=minecraft):
                contracts = [item for item in self.contracts(properties, minecraft) if item != SETTINGS_CONTRACT]
                xml_report(path, contracts)
                result = matrix.test_results(self.directory, properties, minecraft)
                self.assertEqual("failed", result["status"])
                self.assertEqual([{"class": SETTINGS_CONTRACT[0], "namePrefix": SETTINGS_CONTRACT[1]}], result["missingRequired"])

    def test_tectonic_settings_codec_cannot_be_skipped_for_any_main_target(self):
        properties = self.minimal_inputs()
        path = self.directory / "TEST-all.xml"
        for minecraft in MAIN_TARGETS:
            with self.subTest(minecraft=minecraft):
                contracts = self.contracts(properties, minecraft)
                xml_report(path, contracts, skipped=(contracts.index(SETTINGS_CONTRACT),))
                result = matrix.test_results(self.directory, properties, minecraft)
                self.assertEqual("failed", result["status"])
                self.assertEqual(SETTINGS_CONTRACT[1], result["missingRequired"][0]["namePrefix"])
                self.assertEqual(1, len(result["unexpectedSkips"]))

    def test_263_requires_its_float_contract_without_the_main_settings_contract(self):
        properties = self.minimal_inputs()
        contracts = self.contracts(properties, "26.3")
        self.assertNotIn(SETTINGS_CONTRACT, contracts)
        path = self.directory / "TEST-all.xml"
        xml_report(path, contracts)
        self.assertEqual("passed", matrix.test_results(self.directory, properties, "26.3")["status"])
        xml_report(path, [item for item in contracts if item != FLOAT_CONTRACT])
        result = matrix.test_results(self.directory, properties, "26.3")
        self.assertEqual("failed", result["status"])
        self.assertEqual([{"class": FLOAT_CONTRACT[0], "namePrefix": FLOAT_CONTRACT[1]}], result["missingRequired"])

    def test_legacy_tectonic_only_allows_the_explicit_absent_overkill_capability(self):
        properties = self.minimal_inputs()
        contracts = self.contracts(properties)
        overkill = matrix.CONTRACTS["vssTectonicJar"][1]
        index = contracts.index(overkill)
        path = self.directory / "TEST-all.xml"
        xml_report(path, contracts, skipped=(index,))
        tree = ET.parse(path)
        tree.getroot().findall("testcase")[index].find("skipped").set(
            "message", "Assumption failed: This released legacy ConfigNoise has no smoother-scaling option")
        tree.write(path)
        for minecraft in ("1.20.1", "1.21.11"):
            with self.subTest(minecraft=minecraft):
                result = matrix.test_results(self.directory, properties, minecraft)
                self.assertEqual("passed", result["status"])
                self.assertEqual(1, len(result["notApplicable"]))
        self.assertEqual("failed", matrix.test_results(self.directory, properties, "1.21.1")["status"])
        tree.getroot().findall("testcase")[index].find("skipped").set("message", "Unexplained skip")
        tree.write(path)
        for minecraft in ("1.20.1", "1.21.11"):
            with self.subTest(minecraft=minecraft):
                self.assertEqual("failed", matrix.test_results(self.directory, properties, minecraft)["status"])

    def test_legacy_smoother_absence_never_waives_the_real_default_settings_test(self):
        properties = self.minimal_inputs()
        path = self.directory / "TEST-all.xml"
        overkill = matrix.CONTRACTS["vssTectonicJar"][1]
        for minecraft in ("1.20.1", "1.21.11"):
            for skip in (False, True):
                with self.subTest(minecraft=minecraft, settings_skipped=skip):
                    contracts = self.contracts(properties, minecraft)
                    if not skip:
                        contracts.remove(SETTINGS_CONTRACT)
                    skipped = [contracts.index(overkill)]
                    if skip:
                        skipped.append(contracts.index(SETTINGS_CONTRACT))
                    xml_report(path, contracts, skipped=skipped)
                    tree = ET.parse(path)
                    tree.getroot().findall("testcase")[contracts.index(overkill)].find("skipped").set(
                        "message", "Assumption failed: This released legacy ConfigNoise has no smoother-scaling option")
                    tree.write(path)
                    result = matrix.test_results(self.directory, properties, minecraft)
                    self.assertEqual("failed", result["status"])
                    self.assertEqual(SETTINGS_CONTRACT[1], result["missingRequired"][0]["namePrefix"])
                    self.assertEqual(1, len(result["notApplicable"]))

    def test_legacy_tectonic_missing_capability_probe_is_not_explicit_not_applicable(self):
        properties = self.minimal_inputs()
        contracts = [item for item in self.contracts(properties) if item != matrix.CONTRACTS["vssTectonicJar"][1]]
        xml_report(self.directory / "TEST-all.xml", contracts)
        for minecraft in ("1.20.1", "1.21.11"):
            with self.subTest(minecraft=minecraft):
                result = matrix.test_results(self.directory, properties, minecraft)
                self.assertEqual("failed", result["status"])
                self.assertEqual([], result["notApplicable"])

    def test_legacy_tectonic_cannot_skip_its_actual_default_config_contract(self):
        properties = self.minimal_inputs()
        contracts = self.contracts(properties)
        index = contracts.index(matrix.CONTRACTS["vssTectonicJar"][0])
        xml_report(self.directory / "TEST-all.xml", contracts, skipped=(index,))
        for minecraft in ("1.20.1", "1.21.11"):
            with self.subTest(minecraft=minecraft):
                self.assertEqual("failed", matrix.test_results(self.directory, properties, minecraft)["status"])

    def test_missing_input_is_rejected_before_test_execution(self):
        properties = self.minimal_inputs()
        Path(properties["vssXaeroJars"]).unlink()
        with self.assertRaisesRegex(ValueError, "Missing actual dependency"):
            matrix.validate_inputs("26.1.1", properties)

    def test_ftb_is_not_implicitly_claimed_for_neighboring_releases(self):
        properties = self.minimal_inputs()
        properties["vssFtbChunksJar"] = self.jar("ftb.jar").as_posix()
        with self.assertRaisesRegex(ValueError, "FTB inputs are not audited"):
            matrix.validate_inputs("26.1.1", properties)

    def test_bundled_dfc_extraction_uses_only_this_c2me_release(self):
        source = self.directory / "c2me.jar"
        source.parent.mkdir(parents=True)
        inner = self.directory / "inner.jar"
        with zipfile.ZipFile(inner, "w") as jar:
            jar.writestr("com/ishland/c2me/opts/dfc/common/ducks/IFastCacheLike.class", b"fixture")
        with zipfile.ZipFile(source, "w") as jar:
            jar.writestr("META-INF/jars/c2me-opts-dfc-test.jar", inner.read_bytes())
        output, evidence = matrix.extract_dfc(source, self.directory / "dfc")
        self.assertEqual(inner.read_bytes(), output.read_bytes())
        self.assertEqual("META-INF/jars/c2me-opts-dfc-test.jar", evidence["member"])
        self.assertEqual(matrix.digest(source), evidence["container_sha256"])

    def test_missing_dfc_fails_instead_of_skipping_c2me_contract(self):
        source = self.directory / "c2me.jar"
        source.parent.mkdir(parents=True)
        with zipfile.ZipFile(source, "w") as jar:
            jar.writestr("fabric.mod.json", "{}")
        with self.assertRaisesRegex(ValueError, "Expected one C2ME"):
            matrix.extract_dfc(source, self.directory / "dfc")

    def test_audited_c2me_without_dfc_records_not_applicable(self):
        source = self.directory / "c2me.jar"
        source.parent.mkdir(parents=True)
        with zipfile.ZipFile(source, "w") as jar:
            jar.writestr("fabric.mod.json", "{}")
        output, evidence = matrix.extract_dfc(source, self.directory / "dfc", allow_absent=True)
        self.assertIsNone(output)
        self.assertEqual("not_applicable", evidence["status"])
        properties = self.minimal_inputs()
        properties.pop("vssC2meDfcJar")
        with self.assertRaisesRegex(ValueError, "requires the actual bundled"):
            matrix.validate_inputs("26.1.1", properties)

    def test_12111_uses_its_actual_default_build_directory(self):
        self.assertEqual(self.root / "build/test-results/optionalCompatTest", matrix.results_directory(self.root, "1.21.11"))
        self.assertEqual(self.root / "build/26.3/test-results/optionalCompatTest", matrix.results_directory(self.root, "26.3"))

    def prepare(self, minecraft):
        entries = []
        for project in matrix.fetch_optional_compat_mods.PINNED[minecraft]:
            source = self.jar(project + ".jar")
            entries.append({"project": project, "path": str(source)})
        equipment = {name: {"filename": self.jar(name + ".jar").name} for name in ("trinkets", "accessories")}
        ftb = {name: {"filename": self.jar("ftb-" + name + ".jar").name} for name in ("chunks", "essentials")}

        def fetch_family(version, destination, values):
            destination.mkdir(parents=True, exist_ok=True)
            for value in values.values():
                (destination / value["filename"]).write_bytes(b"release")
            return values

        def prepare(source, output, version, cache, java):
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(source.read_bytes())
            return {"namespace": "named", "source": str(source), "junit_jar": str(output)}

        with patch.object(matrix.fetch_optional_compat_mods, "fetch", return_value=entries), \
             patch.object(matrix.fetch_equipment_mods, "fetch", side_effect=lambda version, directory: fetch_family(version, directory, equipment)) as equipment_fetch, \
             patch.object(matrix.fetch_ftb_compat_mods, "fetch", side_effect=lambda version, directory: fetch_family(version, directory, ftb)) as ftb_fetch, \
             patch.object(matrix, "prepare", side_effect=prepare), \
             patch.object(matrix, "extract_dfc", return_value=(self.jar("dfc.jar"), {"kind": "fixture"})):
            runs = matrix.prepare_inputs(minecraft, self.directory, self.root / "caches", self.java)
        return runs, equipment_fetch.call_count, ftb_fetch.call_count

    def test_1211_runs_both_terrain_families_and_keeps_tree_namespaces_separate(self):
        runs, equipment_calls, ftb_calls = self.prepare("1.21.1")
        self.assertEqual({"freeterraforged", "reterraforged-legacy"}, {name for name, _ in runs})
        self.assertEqual((1, 1), (equipment_calls, ftb_calls))
        for name, properties in runs:
            self.assertIn(name, properties["vssFreeTerraForgedJar"])
            self.assertNotEqual(properties["vssNbtTreeJar"], properties["vssNbtTreeRuntimeJar"])
            self.assertIn("/named/", properties["vssNbtTreeJar"])
            self.assertIn("vssFtbChunksNamedJar", properties)

    def test_2611_does_not_download_unaudited_ftb_or_equipment(self):
        runs, equipment_calls, ftb_calls = self.prepare("26.1.1")
        self.assertEqual((0, 0), (equipment_calls, ftb_calls))
        self.assertEqual(["main"], [name for name, _ in runs])
        self.assertNotIn("vssNbtTreeJar", runs[0][1])
        self.assertIn("/named/tectonic/", runs[0][1]["vssTectonicJar"])

    def run_fake(self, minecraft="26.1.1", code=0, write=True, digests=("same", "same")):
        properties = self.minimal_inputs()
        runs = [("first", properties), ("second", properties)]

        def gradle(root, version, task, inputs, java, log, timeout):
            log.write_text("test invocation", encoding="utf-8")
            if task == "optionalCompatTest":
                if write:
                    xml_report(root / "build" / version / "test-results" / task / "TEST-all.xml", self.contracts(inputs, version))
                return code
            return 0

        with patch.object(matrix, "source_digest", side_effect=digests), \
             patch.object(matrix, "prepare_inputs", return_value=runs), \
             patch.object(matrix, "run_gradle", side_effect=gradle):
            return matrix.run(self.root, minecraft, self.directory, self.root / "caches", self.java)

    def test_separate_runs_retain_independent_xml_and_dependency_identity(self):
        self.assertTrue(self.run_fake())
        for name in ("first", "second"):
            stage = self.directory / "validation" / name
            self.assertTrue((stage / "TEST-all.xml").is_file())
            result = json.loads((stage / "result.json").read_text())
            self.assertEqual("passed", result["status"])
            self.assertEqual(64, len(result["inputs"]["vssXaeroJars"][0]["sha256"]))

    def test_compile_or_test_failure_cannot_reuse_stale_successful_xml(self):
        old = self.root / "build/26.1.1/test-results/optionalCompatTest/TEST-stale.xml"
        xml_report(old, self.contracts(self.minimal_inputs()))
        self.assertFalse(self.run_fake(code=1, write=False))
        result = json.loads((self.directory / "validation/result.json").read_text())
        self.assertTrue(all(item["results"]["passed"] == 0 for item in result["runs"]))
        self.assertFalse(old.exists())

    def test_source_changes_invalidate_otherwise_passing_evidence(self):
        self.assertFalse(self.run_fake(digests=("before", "after")))
        report = json.loads((self.directory / "validation/result.json").read_text())
        self.assertFalse(report["sourceUnchanged"])
        self.assertFalse(report["gameplayValidated"])


if __name__ == "__main__":
    unittest.main()
