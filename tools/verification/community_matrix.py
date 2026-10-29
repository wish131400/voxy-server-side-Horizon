"""Run real community Voxy contracts in isolated processes and retain each result.

Inputs are prepared by fetch_community_voxy.py. Third-party mods and runtime
reports stay outside the source tree's distributable contents.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

from verify_release import source_digest


VARIANTS = {
    "mihile-1.20.1": "1.20.1",
    "m3-1.20.1": "1.20.1",
    "m3-1.21.1": "1.21.1",
    "tkettela-1.20.1": "1.20.1",
    "wu-bao-1.21.1": "1.21.1",
    "water-fix-1.21.1": "1.21.1",
}
TASKS = {"contracts": "fabricContractTest", "gpu": "fabricGpuContractTest", "title": "runClientSmoke"}


def inputs(directory):
    entries = {}
    for line in (directory / "inputs.properties").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            name, value = line.split("=", 1)
            entries[name] = value
    for required in ("vssVoxyJar", "vssSodiumJar"):
        if required not in entries or not Path(entries[required]).is_file():
            raise ValueError("Missing real dependency: " + required + " in " + str(directory))
    return entries


def test_results(directory):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    cases = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.findall("testcase"):
            for status in ("failure", "error", "skipped"):
                item = case.find(status)
                if item is not None:
                    cases.append({"class": case.get("classname"), "name": case.get("name"),
                                  "status": status, "message": item.get("message", "")})
    return {**totals, "details": cases}


def run(root, variant, stage, base, without_iris=False):
    minecraft = VARIANTS[variant]
    directory = base / variant
    dependencies = inputs(directory)
    if without_iris:
        dependencies.pop("vssIrisJar", None)
    output = directory / "validation" / (stage + ("-normal" if without_iris else ""))
    output.mkdir(parents=True, exist_ok=True)
    for previous in output.glob("TEST-*.xml"):
        previous.unlink()
    task = TASKS[stage]
    command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), task,
               "--console=plain", "-PmcTarget=" + minecraft]
    command += ["-P" + key + "=" + value for key, value in dependencies.items()]
    report = {"variant": variant, "minecraft": minecraft, "stage": stage,
              "startedAt": datetime.now(timezone.utc).isoformat(),
              "sourceSha256": source_digest(root),
              "gameplayValidated": False,
              "irisInstalled": "vssIrisJar" in dependencies,
              "inputs": {key: {"filename": Path(path).name,
                                 "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest()}
                         for key, path in dependencies.items() if key.endswith("Jar")}}
    if stage == "title":
        run_dir = directory / "run-title"
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "options.txt").write_text(
            "onboardAccessibility:false\nlang:en_us\ntutorialStep:none\nmaxFps:60\n"
            "pauseOnLostFocus:false\nsoundCategory_master:0.0\n", encoding="utf-8")
        title_report = output / "client-smoke.json"
        title_report.unlink(missing_ok=True)
        command += ["-PvssSmokeRunDir=" + os.path.relpath(run_dir, root).replace(os.sep, "/"),
                    "-PvssSmokeReport=" + title_report.as_posix()]
    else:
        # The test task's external inputs participate in Gradle's input snapshot.
        # Explicit task rerun also gives every variant a fresh independent report.
        command.append("--rerun")
    try:
        with (output / "gradle.log").open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                       timeout=900 if stage == "contracts" else 480, check=False)
        report["exitCode"] = completed.returncode
        if stage == "title":
            report["probe"] = json.loads(title_report.read_text(encoding="utf-8")) if title_report.exists() else None
            passed = completed.returncode == 0 and report["probe"] and report["probe"].get("status") == "passed"
        else:
            result_dir = root / "build" / minecraft / "test-results" / task
            if completed.returncode == 0 or "Task :" + task in (output / "gradle.log").read_text(encoding="utf-8", errors="replace"):
                for path in result_dir.glob("TEST-*.xml"):
                    shutil.copy2(path, output / path.name)
            report["results"] = test_results(output)
            result = report["results"]
            passed = completed.returncode == 0 and result["tests"] > result["skipped"] and not (result["errors"] or result["failures"])
        report["sourceUnchanged"] = source_digest(root) == report["sourceSha256"]
        report["status"] = "passed" if passed and report["sourceUnchanged"] else "failed"
    except (OSError, ValueError, subprocess.TimeoutExpired) as failure:
        report["status"] = "failed"
        report["error"] = str(failure)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("variant", "stage", "status")} |
                     {"results": report.get("results"), "report": str(output / "report.json")}), flush=True)
    return report["status"] == "passed"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--variant", action="append", choices=VARIANTS)
    parser.add_argument("--stage", action="append", choices=TASKS)
    parser.add_argument("--without-iris", action="store_true", help="Run the title probe with Sodium and Voxy only")
    args = parser.parse_args()
    if args.without_iris and args.stage != ["title"]:
        parser.error("--without-iris requires exactly --stage title")
    root = args.root.resolve()
    base = (args.inputs or root / "external/community-compat").resolve()
    success = True
    for variant in args.variant or VARIANTS:
        for stage in args.stage or ("contracts",):
            success = run(root, variant, stage, base, args.without_iris) and success
    raise SystemExit(0 if success else 1)
