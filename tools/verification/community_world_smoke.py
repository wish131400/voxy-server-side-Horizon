"""Run the separate world probe against an isolated production Fabric server/client.

Gradle must first exportCommunityWorldSmokeArtifacts with the companion init script.
This runner never invokes Gradle and never attaches to a user's running game.
Only Minecraft 1.20.1 and 1.21.1 are supported. All worlds, configuration and reports
are kept under a new validation directory; all child processes are stopped on exit.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import queue
import shutil
import socket
import subprocess
import threading
import time
import uuid
import zipfile

from production_client_smoke import digest, obtain, os_name, prepare_runtime, read_inputs, validate_remapped_vss


def prepare_server_runtime(minecraft, runtime_directory, client_runtime):
    directory = Path(runtime_directory).resolve()
    metadata = json.loads((directory / "minecraft.json").read_text())
    download = metadata["downloads"]["server"]
    bundler = obtain(download["url"], directory / "minecraft-server-bundler.jar", download["sha1"], "sha1")
    extracted = directory / "server-bundler-contents"
    classpath = []
    with zipfile.ZipFile(bundler) as archive:
        for category in ("versions", "libraries"):
            entries = archive.read("META-INF/" + category + ".list").decode("utf-8").splitlines()
            for entry in entries:
                checksum, identity, relative = entry.split("\t")
                path = PurePosixPath(relative)
                if path.is_absolute() or ".." in path.parts or "\\" in relative or ":" in relative:
                    raise ValueError("Unsafe official server bundle path")
                target = extracted / category / Path(*path.parts)
                if not target.is_file() or digest(target) != checksum:
                    data = archive.read("META-INF/" + category + "/" + relative)
                    if hashlib.sha256(data).hexdigest() != checksum:
                        raise ValueError("Official server bundle entry checksum mismatch: " + identity)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                classpath.append(target)
    # The ordinary Fabric server launcher performs the same bundler extraction.
    # Reuse its immutable loader/intermediary/ASM libraries, not the Minecraft client JAR.
    for path in client_runtime["classpath"]:
        relative = Path(path).relative_to(directory).as_posix()
        if relative.startswith(("libraries/net/fabricmc/", "libraries/org/ow2/asm/")):
            classpath.append(path)
    if not any("fabric-loader" in path.name for path in classpath):
        raise ValueError("Fabric loader missing from isolated server classpath")
    return classpath


def install_mod(source, destination, expected, expected_sha256=None):
    source = Path(source).resolve()
    source_sha256 = digest(source)
    if expected_sha256 and source_sha256 != expected_sha256:
        raise ValueError("Exported world-smoke artifact was replaced: " + expected)
    with zipfile.ZipFile(source) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
    if metadata.get("id") != expected:
        raise ValueError("Wrong isolated mod identity: expected " + expected)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if digest(destination) != source_sha256:
        raise ValueError("World-smoke mod changed while being copied: " + expected)
    return {"filename": source.name, "sha256": source_sha256, "version": metadata["version"]}


def java_argfile(path, arguments):
    def quote(value):
        value = str(value)
        if "\n" in value or "\r" in value or "\0" in value:
            raise ValueError("Unexpected control character in Java launch argument")
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    Path(path).write_text("\n".join(quote(value) for value in arguments) + "\n", encoding="utf-8")
    return "@" + str(Path(path).resolve())


def choose_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def stop_process(process, console_stop=False):
    if process is None or process.poll() is not None:
        return
    if console_stop and process.stdin:
        try:
            process.stdin.write("stop\n"); process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass
    try:
        process.wait(timeout=20 if console_stop else 5)
        return
    except subprocess.TimeoutExpired:
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill(); process.wait(timeout=10)


def run(options):
    minecraft = options.minecraft
    artifacts = json.loads(options.artifacts.read_text())
    if artifacts["minecraft"] != minecraft:
        raise ValueError("World probe artifact target mismatch")
    if not artifacts.get("vssSha256") or not artifacts.get("probeSha256"):
        raise ValueError("World probe needs checksum-bearing artifacts; re-export with community_world_smoke.gradle")
    validate_remapped_vss(artifacts["vssJar"], minecraft, artifacts["vssSha256"])
    if digest(artifacts["probeJar"]) != artifacts["probeSha256"]:
        raise ValueError("Exported world-smoke probe was replaced after validation")
    runtime_directory = options.runtime_directory.resolve()
    runtime = prepare_runtime(minecraft, runtime_directory, options.gradle_user_home)
    server_classpath = prepare_server_runtime(minecraft, runtime_directory, runtime)
    # Always use a fresh child folder. No user world/configuration is loaded or removed.
    directory = options.destination.resolve() / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True)
    server = directory / "server"
    client = directory / "client"
    for game in (server, client):
        (game / "mods").mkdir(parents=True)
        (game / "config").mkdir()
    identities = {}
    for game in (server, client):
        identities[game.name + ":vss"] = install_mod(artifacts["vssJar"], game / "mods/vss.jar", "vss", artifacts["vssSha256"])
        install_mod(runtime["fabricApi"], game / "mods/fabric-api.jar", "fabric-api")
    identities["client:probe"] = install_mod(artifacts["probeJar"], client / "mods/vss-world-smoke.jar", "vss_world_smoke", artifacts["probeSha256"])
    for key, source in read_inputs(options.inputs).items():
        if key == "vssIrisJar" and options.without_iris:
            continue
        name = {"vssVoxyJar": "voxy", "vssSodiumJar": "sodium", "vssIrisJar": "iris"}[key]
        identities["client:" + name] = install_mod(source, client / "mods" / (name + ".jar"), name)
    port = choose_port()
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nmax-players=1\n"
        "view-distance=2\nsimulation-distance=2\nlevel-name=smoke-world\nlevel-seed=8675309\n"
        "level-type=minecraft:normal\ngenerate-structures=true\nspawn-protection=0\n"
        "gamemode=spectator\nforce-gamemode=true\nspawn-animals=false\nspawn-monsters=false\n"
        "enable-rcon=false\nenable-query=false\nnetwork-compression-threshold=256\n", encoding="utf-8")
    (server / "config/vss-server-config.json").write_text(json.dumps({
        "enabled": True, "enablePredictionSync": True, "enableChunkGeneration": True,
        "lodDistanceChunks": 12, "generationConcurrencyLimitGlobal": 2,
        "generationConcurrencyLimitPerPlayer": 2, "debugLogging": True,
    }, indent=2) + "\n", encoding="utf-8")
    (client / "options.txt").write_text(
        "onboardAccessibility:false\nlang:en_us\ntutorialStep:none\nmaxFps:60\nrenderDistance:2\n"
        "pauseOnLostFocus:false\nsoundCategory_master:0.0\n", encoding="utf-8")
    report_path = directory / "world-smoke.json"
    server_arguments = ["-Xmx3G", "-Dfabric.development=false", "-Dfabric.side=server",
                        "-cp", os.pathsep.join(map(str, server_classpath)),
                        "net.fabricmc.loader.impl.launch.knot.KnotServer", "--nogui"]
    client_arguments = list(runtime["jvmArguments"]) + [
        "-Xmx3G", "-Dfabric.development=false", "-Dfabric.side=client",
        "-Djava.library.path=" + str(runtime["natives"]),
        "-Dorg.lwjgl.librarypath=" + str(runtime["natives"]),
        "-Dvss.worldSmoke.report=" + str(report_path),
        f"-Dvss.worldSmoke.address=127.0.0.1:{port}", f"-Dvss.worldSmoke.timeout={options.timeout}",
        "-cp", os.pathsep.join(map(str, runtime["classpath"])), runtime["mainClass"],
        "--username", "VSSWorldSmoke", "--version", minecraft, "--gameDir", str(client),
        "--assetsDir", str(runtime["assets"]), "--assetIndex", runtime["assetIndex"],
        "--uuid", "00000000-0000-4000-8000-000000000002", "--accessToken", "0", "--userType", "legacy",
        "--versionType", "release", "--width", "854", "--height", "480"]
    if os_name() == "osx": client_arguments.insert(0, "-XstartOnFirstThread")
    server_command = [str(options.java), java_argfile(directory / "server.args", server_arguments)]
    client_command = [str(options.java), java_argfile(directory / "client.args", client_arguments)]
    launch = {"minecraft": minecraft, "runtime": "production", "development": False,
              "mods": identities, "gameplayValidated": False, "visualValidated": False,
              "fabricProfileSha256": runtime["profileSha256"], "minecraftMetadataSha1": runtime["minecraftMetadataSha1"],
              "port": port, "timeoutSeconds": options.timeout}
    (directory / "launch.json").write_text(json.dumps(launch, indent=2) + "\n")
    if options.prepare_only:
        return {"prepared": True, "directory": str(directory), "serverCommand": server_command, "clientCommand": client_command}
    server_process = client_process = None
    events = queue.Queue()
    ready = generated = marker_changed = second_marker_changed = stopped = False
    started = time.monotonic()
    setup_sent = None
    marker_attempt = 0.0
    client_started = None
    report = None
    error = None
    server_log = (directory / "server.log").open("w", encoding="utf-8")
    client_log = (directory / "client.log").open("w", encoding="utf-8")
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    try:
        server_process = subprocess.Popen(server_command, cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                          stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                          creationflags=creationflags)
        def read_server():
            for line in server_process.stdout:
                server_log.write(line); server_log.flush(); events.put(line)
            events.put(None)
        reader = threading.Thread(target=read_server, daemon=True)
        reader.start()
        def send(command):
            if server_process.poll() is not None: raise RuntimeError("Isolated server exited before probe completion")
            server_process.stdin.write(command + "\n"); server_process.stdin.flush()
        deadline = started + options.timeout + 180
        while time.monotonic() < deadline:
            now = time.monotonic()
            try:
                line = events.get(timeout=0.25)
            except queue.Empty:
                line = ""
            if line is None and server_process.poll() is not None:
                break
            if not ready and "Done (" in line:
                ready = True; setup_sent = now
                send("setworldspawn 0 150 0\ngamerule spawnRadius 0\ngamerule doDaylightCycle false\n"
                     "time set noon\nforceload add 160 0 176 0")
            if ready and not marker_changed and now - setup_sent > 2 and now - marker_attempt > 2:
                marker_attempt = now
                send("setblock 160 200 0 minecraft:diamond_block")
            if ready and not marker_changed and ("Changed the block at 160, 200, 0" in line
                                                 or "commands.setblock.success" in line):
                marker_changed = True
                send("save-all flush\nvss chunky start 160 0 0")
            if marker_changed and ("vss.command.chunky.completed" in line or "VSS pre-generation finished:" in line):
                generated = True
            if generated and client_process is None:
                client_process = subprocess.Popen(client_command, cwd=client, stdout=client_log, stderr=subprocess.STDOUT,
                                                  creationflags=creationflags)
                client_started = now
            progress_path = directory / "progress-2-warming.json"
            if client_process is not None and not second_marker_changed and progress_path.exists():
                try: progress = json.loads(progress_path.read_text())
                except (OSError, ValueError): progress = {}
                if progress.get("session") == 2 and progress.get("stage") == "warming":
                    send("setblock 176 200 0 minecraft:emerald_block\nsave-all flush\nvss chunky start 176 0 0")
                    second_marker_changed = True
            if client_process is not None and client_process.poll() is not None:
                break
            if client_started and now - client_started > options.timeout + 30:
                error = "client_world_probe_timeout"; break
            if "Stopping server" in line: stopped = True
        else:
            error = "isolated_server_or_world_probe_timeout"
    except Exception as failure:
        error = repr(failure)
    finally:
        stop_process(client_process)
        stop_process(server_process, True)
        if "reader" in locals(): reader.join(timeout=5)
        server_log.close(); client_log.close()
    if report_path.exists():
        report = json.loads(report_path.read_text())
    client_errors = [line.strip() for line in (directory / "client.log").read_text(
        encoding="utf-8", errors="replace").splitlines()
        if "VSS prediction tile build failed" in line or "Mixin apply failed" in line]
    result = {**launch, "directory": str(directory), "serverStarted": ready, "firstMarkerChanged": marker_changed,
              "initialColumnGenerationCompleted": generated, "secondMarkerChanged": second_marker_changed,
              "serverExitCode": server_process.returncode if server_process else None,
              "clientExitCode": client_process.returncode if client_process else None,
              "elapsedSeconds": round(time.monotonic() - started, 2), "probe": report}
    if error: result["error"] = error
    if client_errors:
        result["clientErrors"] = client_errors
    result["passed"] = bool(not error and not client_errors and generated and second_marker_changed and report
                            and report["status"] == "passed" and result["clientExitCode"] == 0
                            and result["serverExitCode"] == 0)
    (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=("1.20.1", "1.21.1"), required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--runtime-directory", type=Path, required=True)
    parser.add_argument("--gradle-user-home", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    parser.add_argument("--without-iris", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=600)
    arguments = parser.parse_args()
    if not 60 <= arguments.timeout <= 900: parser.error("--timeout must be between 60 and 900 seconds")
    result = run(arguments)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result.get("passed") or result.get("prepared") else 1)
