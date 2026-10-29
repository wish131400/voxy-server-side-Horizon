"""Start an isolated localhost Fabric server, pre-generate one column, and stop."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import time


def run(root, timeout, minecraft):
    suffix = "" if minecraft == "1.21.11" else "-" + minecraft
    run_directory = "run/server-smoke" + suffix
    directory = root / run_directory
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n"
        "view-distance=2\nsimulation-distance=2\nmax-players=1\n"
        "level-name=smoke-world\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}\n'
        "enable-rcon=false\nenable-query=false\nspawn-protection=0\n", encoding="utf-8")
    command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")),
               "runServer", "--console=plain", "-PserverRunDir=" + run_directory,
               "-PmcTarget=" + minecraft]
    process = subprocess.Popen(command, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    lines = queue.Queue()
    def reader():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)
    threading.Thread(target=reader, daemon=True).start()
    started = time.monotonic()
    ready = False
    generated = False
    stopped = False
    build_directory = root / "build" if minecraft == "1.21.11" else root / "build" / minecraft
    log_path = build_directory / "server-smoke.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as output:
        while time.monotonic() - started < timeout:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            output.write(line)
            output.flush()
            if not ready and "Done (" in line:
                ready = True
                process.stdin.write("vss chunky start 4096 4096 0\n")
                process.stdin.flush()
            if ready and ("vss.command.chunky.completed" in line or "VSS pre-generation finished:" in line):
                generated = True
                process.stdin.write("vss chunky status\nvss stats\nstop\n")
                process.stdin.flush()
            if "Stopping server" in line:
                stopped = True
        else:
            if process.poll() is None:
                process.stdin.write("stop\n")
                process.stdin.flush()
    try:
        result = process.wait(timeout=45)
    except subprocess.TimeoutExpired:
        process.terminate()
        raise RuntimeError("Smoke server failed to stop; inspect " + str(log_path))
    columns = list((directory / "smoke-world/data/vss-column-cache").rglob("*.vcl"))
    report = {"minecraft": minecraft, "started": ready, "generationCompleted": generated, "savedColumns": len(columns),
              "stopped": stopped, "exitCode": result, "elapsedSeconds": round(time.monotonic() - started, 2)}
    (build_directory / "server-smoke.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not (ready and generated and columns and stopped and result == 0):
        raise RuntimeError("Fabric server smoke check failed; inspect " + str(log_path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--minecraft", default="1.21.11",
                        choices=["1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"])
    options = parser.parse_args()
    run(options.root.resolve(), options.timeout, options.minecraft)
