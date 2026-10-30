#!/usr/bin/env python3
"""Verify optional Fabric adapters against pinned real releases for one game target.

Downloaded and remapped JARs are offline test inputs only. Logs and evidence stay
under external/optional-compat; no dependency becomes part of the shipped mod.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

import fetch_equipment_mods
import fetch_ftb_compat_mods
import fetch_optional_compat_mods
from remap_worldgen_test_jar import prepare
from verify_release import source_digest


TARGETS = tuple(fetch_optional_compat_mods.PINNED)
NO_DFC_TARGETS = {"1.20.1", "26.3"}
NO_TECTONIC_SMOOTHER_TARGETS = {"1.20.1", "1.21.11"}
# Each supplied dependency has to execute its actual-release check. Passing
# unrelated fixture tests cannot conceal a disabled or misspelled input hook.
CONTRACTS = {
    "vssTrinketsJar": [("FabricEquipmentActualJarTest", "releasedTrinketsComponentMatchesSnapshotMethods")],
    "vssAccessoriesJar": [("FabricEquipmentActualJarTest", "releasedAccessoriesHolderMatchesItsFullClientSyncProtocol")],
    "vssFtbChunksJar": [("FtbActualJarContractTest", "actualFabricChunkBridgeMatchesOneSupportedPlatformContract")],
    "vssFtbEssentialsJar": [("FtbActualJarContractTest", "actualRandomTeleportKeepsItsRealQueriesWithoutTheOldFakeTerrainRedirect")],
    "vssFtbChunksNamedJar": [("FtbPlatformMixinApplicationTest", "actualPlatformHookIsAppliedAndCannotSilentlySkipItsTarget")],
    "vssNbtTreeRuntimeJar": [("OptionalWorldgenMixinApplicationTest", "rawFabricTreeMethodIsSelectedInProductionNamespace")],
    "vssNbtTreeJar": [("OptionalWorldgenMixinApplicationTest", "redirectsRealTreeFeaturesToPredictionTemplateManager"),
                      ("NbtTreePredictionTemplatesTest", "everyReleasedTreeFeatureHasExactlyTheRedirectedManagerChain")],
    "vssByepregenJar": [("ByePregenMixinApplicationTest", "predicateHookAppliesToTheActualReleasedOverload")],
    "vssCreateJar": [("CreateReleaseContractTest", "actualFabricContraptionExposesTheReadinessContract")],
    "vssC2meDfcJar": [("C2meDensityMemoCompatTest", "compiledCacheRestrictionDoesNotPreventPredictionDensityInitialization")],
    "vssFreeTerraForgedJar": [("FreeTerraForgedMixinApplicationTest", "nativeFilterHookAppliesToActualFabricWorldFilters"),
                             ("FreeTerraForgedCompatTest", "releasedApiAndRuntimeOnlyPresetRoundTrip")],
    "vssXaeroJars": [("XaeroActualJarContractTest", "write/save API:"),
                     ("XaeroActualJarContractTest", "terrain/save lifecycle:"),
                     ("XaeroActualJarContractTest", "texture cache readiness:")],
    "vssLostCitiesMatrix": [("LostCityReleaseMatrixTest", "lostcities-")],
    "vssTectonicJar": [("DensityFunctionSnapshotTest", "installedTectonicDefaultConfigFunctionsRoundTrip"),
                       ("TectonicPresetSnapshotTest", "overkillPresetRetainsExactScalingAtRootAndInsideDensityRecords")],
    "vssLithostitchedJar": [("DensityFunctionSnapshotTest", "installedLithostitchedAppliedGraphRoundTrips")],
}
ALWAYS_REQUIRED = [("CuriosCompatTest", ""), ("FabricEquipmentCompatTest", ""),
                   ("XaeroMapCompatQueueTest", ""), ("DensityMemoCompatTest", ""),
                   ("ForceLoadQueueTest", ""), ("FtbForceLoadIntegrationTest", ""),
                   ("FtbMinecraftMixinApplicationTest", "onlyTheUnusedEagerLoadIsReplacedInVanillaForcedTicketPersistence")]


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def gradle_command(root, minecraft, task, properties=None, windows=None):
    windows = os.name == "nt" if windows is None else windows
    launcher = [str(root / "gradlew.bat")] if windows else ["bash", str(root / "gradlew")]
    command = launcher + [task, "--console=plain", "-PmcTarget=" + minecraft]
    if task == "optionalCompatTest":
        # Gradle's task-local rerun avoids rebuilding all production inputs for
        # the second 1.21.1 API family while still replacing the test evidence.
        command.insert(len(launcher) + 1, "--rerun")
    return command + ["-P" + key + "=" + str(value) for key, value in sorted((properties or {}).items())]


def run_gradle(root, minecraft, task, properties, java, log, timeout):
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java.resolve().parent.parent)
    command = gradle_command(root, minecraft, task, properties)
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    return result.returncode


def extract_dfc(source, destination, allow_absent=False):
    """Use the DFC actually bundled in this C2ME release, never a loose cache JAR."""
    marker = "com/ishland/c2me/opts/dfc/common/ducks/IFastCacheLike.class"
    with zipfile.ZipFile(source) as outer:
        candidates = []
        for name in outer.namelist():
            if name.startswith("META-INF/jars/") and name.endswith(".jar") and "dfc" in name.lower():
                data = outer.read(name)
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    if marker in nested.namelist():
                        candidates.append((name, data))
    if not candidates and allow_absent:
        return None, {"container_sha256": digest(source), "status": "not_applicable",
                      "reason": "The pinned C2ME release does not bundle the DFC module"}
    if len(candidates) != 1:
        raise ValueError("Expected one C2ME bundled DFC implementation; found " + str(len(candidates)))
    member, data = candidates[0]
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / "c2me-dfc.jar"
    output.write_bytes(data)
    return output, {"container_sha256": digest(source), "member": member, "sha256": digest(output)}


def validate_inputs(minecraft, properties):
    for key, value in properties.items():
        if key in ("vssLostCitiesMatrix", "vssOptionalCompatManifest"):
            continue
        for path in str(value).split("|"):
            if not Path(path).is_file():
                raise ValueError("Missing actual dependency for " + key + ": " + path)
    if not {"vssXaeroJars", "vssC2meJar"}.issubset(properties):
        raise ValueError("Every target requires its actual Xaero and C2ME inputs")
    if minecraft not in NO_DFC_TARGETS and "vssC2meDfcJar" not in properties:
        raise ValueError("This target requires the actual bundled C2ME DFC input")
    if minecraft not in fetch_equipment_mods.RELEASES and any(
            key in properties for key in ("vssTrinketsJar", "vssAccessoriesJar")):
        raise ValueError("Equipment inputs are not audited for " + minecraft)
    ftb = {"vssFtbChunksJar", "vssFtbChunksNamedJar", "vssFtbEssentialsJar"}
    if minecraft in fetch_ftb_compat_mods.RELEASES:
        if not ftb.issubset(properties):
            raise ValueError("Missing audited FTB inputs for " + minecraft)
    elif ftb.intersection(properties):
        raise ValueError("FTB inputs are not audited for " + minecraft)
    expected = set(fetch_optional_compat_mods.PINNED[minecraft])
    required = {fetch_optional_compat_mods.PROPERTY_NAMES[project] for project in expected
                if project in fetch_optional_compat_mods.PROPERTY_NAMES}
    # The raw C2ME container is evidence; the executable check uses its DFC JAR.
    if not required.issubset(properties):
        raise ValueError("Missing pinned inputs: " + ", ".join(sorted(required - properties.keys())))
    if minecraft in fetch_equipment_mods.RELEASES and not {
            "vssTrinketsJar", "vssAccessoriesJar"}.issubset(properties):
        raise ValueError("Missing audited equipment inputs for " + minecraft)
    if "vssNbtTreeRuntimeJar" in properties and "vssNbtTreeJar" not in properties:
        raise ValueError("Tree release needs both raw-selector and named-application inputs")
    if "vssLostCitiesJar" in properties:
        directory = Path(properties.get("vssLostCitiesMatrix", ""))
        if not directory.is_dir() or not any(directory.glob("lostcities-*.jar")):
            raise ValueError("Lost Cities release matrix has no actual input")


def prepare_inputs(minecraft, directory, cache, java):
    raw = directory / "raw"
    entries = fetch_optional_compat_mods.fetch(minecraft, raw / "optional")
    expected = set(fetch_optional_compat_mods.PINNED[minecraft])
    if {entry["project"] for entry in entries} != expected:
        raise ValueError("Downloaded optional inputs do not match the pinned target matrix")
    common = {}
    terrain = []
    provenance = []

    def named(source, family):
        output = directory / "named" / family / source.name
        provenance.append(prepare(source, output, minecraft, cache, java))
        return output.resolve().as_posix()

    for entry in entries:
        project = entry["project"]
        source = Path(entry["path"])
        property_name = fetch_optional_compat_mods.PROPERTY_NAMES[project]
        if project == "c2me-fabric":
            common[property_name] = source.resolve().as_posix()
            dfc, nested = extract_dfc(source, raw / "c2me-dfc", allow_absent=minecraft in NO_DFC_TARGETS)
            if dfc is not None:
                common["vssC2meDfcJar"] = named(dfc, "c2me-dfc")
            provenance.append(dict(nested, kind="bundled-c2me-dfc"))
        elif project in ("freeterraforged", "reterraforged-legacy"):
            terrain.append((project, named(source, project)))
        else:
            named_path = named(source, project)
            common[property_name] = named_path
            if project == "oh-the-trees-youll-grow":
                common["vssNbtTreeRuntimeJar"] = source.resolve().as_posix()
                common["vssNbtTreeJar"] = named_path
            if project == "the-lost-cities":
                matrix = directory / "named" / "lost-cities-matrix"
                matrix.mkdir(parents=True, exist_ok=True)
                for old in matrix.glob("lostcities-*.jar"):
                    old.unlink()
                shutil.copyfile(common[property_name], matrix / ("lostcities-" + source.name))
                common["vssLostCitiesMatrix"] = matrix.resolve().as_posix()
    equipment = {}
    if minecraft in fetch_equipment_mods.RELEASES:
        equipment = fetch_equipment_mods.fetch(minecraft, raw / "equipment")
        if set(equipment) != {"trinkets", "accessories"}:
            raise ValueError("Incomplete equipment dependency set")
        for mod, release in equipment.items():
            common["vss" + mod.title() + "Jar"] = (raw / "equipment" / release["filename"]).resolve().as_posix()
    ftb = {}
    if minecraft in fetch_ftb_compat_mods.RELEASES:
        ftb = fetch_ftb_compat_mods.fetch(minecraft, raw / "ftb")
        if set(ftb) != {"chunks", "essentials"}:
            raise ValueError("Incomplete FTB dependency set")
        chunks = raw / "ftb" / ftb["chunks"]["filename"]
        common["vssFtbChunksJar"] = chunks.resolve().as_posix()
        common["vssFtbChunksNamedJar"] = named(chunks, "ftb")
        common["vssFtbEssentialsJar"] = (raw / "ftb" / ftb["essentials"]["filename"]).resolve().as_posix()
    runs = [(name, dict(common, vssFreeTerraForgedJar=path)) for name, path in terrain] or [("main", common)]
    if minecraft == "1.21.1" and {name for name, _ in terrain} != {"freeterraforged", "reterraforged-legacy"}:
        raise ValueError("1.21.1 requires separate FreeTerraForged and legacy ReTerraForged runs")
    for _, properties in runs:
        validate_inputs(minecraft, properties)
    write_json(directory / "inputs.json", {"minecraft": minecraft, "optional": entries,
               "equipment": equipment, "ftb": ftb, "preparation": provenance,
               "runs": [{"name": name, "properties": properties} for name, properties in runs]})
    return runs


def matches(case, contract):
    class_name, prefix = contract
    return case["class"].rsplit(".", 1)[-1] == class_name and case["name"].startswith(prefix)


def test_results(directory, properties, minecraft=None):
    cases = []
    suite_totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in sorted(directory.glob("TEST-*.xml")):
        for suite in ET.parse(path).getroot().iter("testsuite"):
            for key in suite_totals:
                suite_totals[key] += int(suite.get(key, "0"))
            for case in suite.findall("testcase"):
                status = next((status for status in ("failure", "error", "skipped") if case.find(status) is not None), "passed")
                item = case.find(status)
                cases.append({"class": case.get("classname", suite.get("name", "")),
                              "name": case.get("name", ""), "status": status,
                              "message": item.get("message", "") if item is not None else ""})
    required = ALWAYS_REQUIRED + [contract for key, contracts in CONTRACTS.items()
                                  if key in properties for contract in contracts]
    if minecraft == "26.3":
        required = [contract for contract in required if contract != ("DensityMemoCompatTest", "")]
        if "vssTectonicJar" in properties:
            required.append(("TectonicPresetSnapshotTest", "normalAndSmoothKeepReleasedFloatScalarAndDoubleVolumeOrdering"))
    elif "vssTectonicJar" in properties:
        required.append(("TectonicPresetSnapshotTest", "configuredPresetSurvivesSettingsCodecAndClientNoiseInitialization"))
    overkill = ("TectonicPresetSnapshotTest", "overkillPresetRetainsExactScalingAtRootAndInsideDensityRecords")
    if minecraft in NO_TECTONIC_SMOOTHER_TARGETS:
        required = [contract for contract in required if contract != overkill]
    missing = [{"class": contract[0], "namePrefix": contract[1]} for contract in required
               if not any(matches(case, contract) and case["status"] == "passed" for case in cases)]
    not_applicable = []
    if minecraft in NO_TECTONIC_SMOOTHER_TARGETS and "vssTectonicJar" in properties:
        capability = [case for case in cases if matches(case, overkill)]
        if not capability:
            missing.append({"class": overkill[0], "namePrefix": overkill[1],
                            "reason": "The legacy capability must execute or report its explicit absence"})
        not_applicable = [dict(case, reason="The pinned " + minecraft + " ConfigNoise has no smoother-scaling option")
                          for case in capability if case["status"] == "skipped"
                          and "no smoother-scaling option" in case["message"]]
    unexpected_skips = []
    for case in cases:
        if case["status"] != "skipped":
            continue
        allowed = any(key not in properties and matches(case, contract)
                      for key, contracts in CONTRACTS.items() for contract in contracts)
        if minecraft in NO_TECTONIC_SMOOTHER_TARGETS and "vssTectonicJar" in properties and matches(case, overkill):
            allowed = "no smoother-scaling option" in case["message"]
        if case["class"].endswith(".LostCityReleaseMatrixTest") and "vssLostCitiesMatrix" not in properties:
            allowed = True  # No factory children exist when this mod has no target release.
        if not allowed:
            unexpected_skips.append(case)
    passed = sum(case["status"] == "passed" for case in cases)
    failures = [case for case in cases if case["status"] in ("failure", "error")]
    return {**suite_totals, "passed": passed, "missingRequired": missing,
            "unexpectedSkips": unexpected_skips, "notApplicable": not_applicable, "details": cases,
            "status": "passed" if passed and not (missing or unexpected_skips or failures
                      or suite_totals["failures"] or suite_totals["errors"]) else "failed"}


def input_identity(properties):
    return {key: [{"filename": Path(path).name, "sha256": digest(path)} for path in str(value).split("|")]
            for key, value in properties.items() if key.endswith("Jar") or key == "vssXaeroJars"}


def results_directory(root, minecraft):
    build = root / "build" if minecraft == "1.21.11" else root / "build" / minecraft
    return build / "test-results" / "optionalCompatTest"


def run(root, minecraft, directory, cache, java, timeout=1200):
    output = directory / "validation"
    output.mkdir(parents=True, exist_ok=True)
    report = {"minecraft": minecraft, "startedAt": datetime.now(timezone.utc).isoformat(),
              "sourceSha256": source_digest(root), "gameplayValidated": False, "runs": [], "status": "failed"}
    try:
        code = run_gradle(root, minecraft, "testClasses", {}, java, output / "prepare.log", timeout)
        if code:
            raise RuntimeError("testClasses failed with exit code " + str(code))
        runs = prepare_inputs(minecraft, directory, cache, java)
        for name, properties in runs:
            stage = output / name
            stage.mkdir(parents=True, exist_ok=True)
            # Clear only this task's old XML: a compile failure must not inherit
            # a previous successful run or the other terrain API family's XML.
            result_dir = results_directory(root, minecraft)
            for folder in (result_dir, stage):
                for path in folder.glob("TEST-*.xml"):
                    path.unlink()
            code = run_gradle(root, minecraft, "optionalCompatTest", properties, java,
                              stage / "gradle.log", timeout)
            for path in result_dir.glob("TEST-*.xml"):
                shutil.copyfile(path, stage / path.name)
            results = test_results(stage, properties, minecraft)
            item = {"name": name, "exitCode": code, "inputs": input_identity(properties), "results": results,
                    "status": "passed" if code == 0 and results["status"] == "passed" else "failed"}
            write_json(stage / "result.json", item)
            report["runs"].append(item)
        report["sourceUnchanged"] = source_digest(root) == report["sourceSha256"]
        if report["sourceUnchanged"] and report["runs"] and all(item["status"] == "passed" for item in report["runs"]):
            report["status"] = "passed"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as error:
        report["error"] = str(error)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    write_json(output / "result.json", report)
    print(json.dumps({"minecraft": minecraft, "status": report["status"],
                      "report": str(output / "result.json")}), flush=True)
    return report["status"] == "passed"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True, choices=TARGETS)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" /
                        ("java.exe" if os.name == "nt" else "java"))
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches")
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--timeout", type=int, default=1200, help="Maximum seconds for each Gradle invocation")
    args = parser.parse_args()
    if not args.java.is_file():
        parser.error("Set JAVA_HOME or --java to an installed JDK Java executable")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    root = args.root.resolve()
    destination = (args.destination or root / "external/optional-compat" / args.minecraft).resolve()
    raise SystemExit(0 if run(root, args.minecraft, destination, args.cache.resolve(), args.java.resolve(), args.timeout) else 1)
