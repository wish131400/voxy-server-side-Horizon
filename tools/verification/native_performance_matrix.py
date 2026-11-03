"""Compare supplied native libraries in isolated JVMs using identical worldgen inputs.

Example: python tools/verification/native_performance_matrix.py --java-home JDK_DIR
  --case before OLD_LIBRARY WORLDGEN_JSON --case after NEW_LIBRARY WORLDGEN_JSON
  --output EXTERNAL_RESULT_JSON

Worldgen inputs must be exported from their actual game version. A fixed biome
and shared block palette isolate the core, not the entire gameplay pipeline.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--case", action="append", nargs=3, required=True,
                        metavar=("LABEL", "LIBRARY", "DOCUMENT"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=7)
    parser.add_argument("--steps", default="1,4,16,64")
    parser.add_argument("--mode", choices=["points", "grid", "partial-points", "partial-grid", "exact-points"], default="points")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--origin", type=int, nargs=2, default=[100000, 100000])
    args = parser.parse_args()
    steps = [int(v) for v in args.steps.split(",")]
    if args.rounds < 1 or not steps or any(v < 1 for v in steps):
        parser.error("positive rounds and spacings are required")
    if args.mode.endswith("grid") and any(v > 4 for v in steps):
        parser.error("displayGrid only supports spacing 1..4")
    labels = [label for label, _, _ in args.case]
    if len(set(labels)) != len(labels):
        parser.error("case labels must be unique")
    java_home = Path(args.java_home) if args.java_home else None
    suffix = ".exe" if os.name == "nt" else ""
    def executable(name):
        value = java_home / "bin" / (name + suffix) if java_home else shutil.which(name)
        if not value or not Path(value).is_file():
            parser.error("provide a JDK through --java-home or JAVA_HOME")
        return str(value)
    root = Path(__file__).resolve().parents[2]
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    results = {"scope": "CPU/JNI only; excludes game scheduling, Java features, GPU, disk and rendering",
               "mode": args.mode, "seed": args.seed, "origin": args.origin, "cases": []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="vss-native-bench-") as directory:
        subprocess.run([executable("javac"), "-d", directory,
                        str(root / "src/main/java/dev/xantha/vss/client/prediction/RustWorldgenBackend.java"),
                        str(root / "tools/verification/nativebench/NativePerformanceBench.java")], check=True)
        for label, library, document in args.case:
            library, document = Path(library).resolve(), Path(document).resolve()
            run = subprocess.run([executable("java"), "--enable-native-access=ALL-UNNAMED",
                                  "-cp", directory, "NativePerformanceBench", str(library), str(document),
                                  args.mode, str(args.rounds), args.steps, str(args.seed), *map(str, args.origin)],
                                 capture_output=True, text=True)
            rows = [json.loads(line) for line in run.stdout.splitlines() if line.startswith("{")]
            if run.returncode or len(rows) != args.rounds * len(steps):
                raise RuntimeError(f"{label} did not complete: {run.stderr}\n{run.stdout}")
            summary = []
            for step in steps:
                group = [row for row in rows if row["step"] == step]
                checksums = {row["checksum"] for row in group}
                if len(checksums) != 1:
                    raise RuntimeError(f"{label} spacing {step} changed output across rounds")
                summary.append({"step": step, "checksum": next(iter(checksums)),
                                **{metric: statistics.median(row[metric] for row in group)
                                   for metric in ["coldMs", "cachedMs", "createMs"]}})
            results["cases"].append({"label": label, "librarySha256": sha(library),
                                     "documentSha256": sha(document), "summary": summary, "rows": rows})
            args.output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
            print(label, [(row["step"], round(row["coldMs"], 3)) for row in summary], flush=True)


if __name__ == "__main__":
    main()
