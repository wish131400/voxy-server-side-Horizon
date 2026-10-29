"""Run the isolated development client and verify its title-screen report."""
import argparse
import json
import os
from pathlib import Path
import subprocess

from fetch_compat_mods import RELEASES, fetch


def run(root, minecraft):
    inputs = root / "external/compat" / minecraft
    fetch(minecraft, inputs)
    run_dir = root / "run" / ("client-smoke-" + minecraft)
    run_dir.mkdir(parents=True, exist_ok=True)
    options = run_dir / "options.txt"
    if not options.exists():
        options.write_text("onboardAccessibility:false\nlang:en_us\ntutorialStep:none\n"
                           "maxFps:60\npauseOnLostFocus:false\nsoundCategory_master:0.0\n",
                           encoding="utf-8")
    command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")),
               "runClientSmoke", "--console=plain", "-PmcTarget=" + minecraft]
    command += ["-P" + line for line in (inputs / "inputs.properties").read_text().splitlines() if line]
    output = root / "build" if minecraft == "1.21.11" else root / "build" / minecraft
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "client-smoke.json"
    report_path.unlink(missing_ok=True)
    with (output / "client-smoke.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                timeout=420, check=False)
    if not report_path.is_file():
        raise RuntimeError("Client produced no smoke report; inspect " + str(output / "client-smoke.log"))
    report = json.loads(report_path.read_text())
    print(json.dumps({"minecraft": minecraft, "exitCode": result.returncode,
                      "status": report["status"], "reason": report["reason"],
                      "gameplayValidated": report["gameplayValidated"]}, indent=2))
    if result.returncode != 0 or report["status"] != "passed":
        raise RuntimeError("Client smoke failed; inspect " + str(output / "client-smoke.log"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=RELEASES, required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    run(args.root.resolve(), args.minecraft)
