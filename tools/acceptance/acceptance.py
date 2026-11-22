"""Internal, bounded terrain acceptance. Control goes through MineClient Bridge MCP only.

No credentials, launch arguments, world saves or captured evidence belong in source control.
This module does not install mods, edit worlds, change thread counts or lower quality.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import shutil
import threading
import time
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
PHASES = ("cold-load", "warm-load", "movement", "prediction-toggle", "world-reentry", "terrain-biomes")


class Incomplete(RuntimeError):
    """Missing evidence is never a successful acceptance."""


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value):
        raise ValueError("Expected a bounded identifier")
    return value


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def inventory(root):
    """Read only filenames, checksums and mod metadata; never Java authentication arguments."""
    result = []
    for path in sorted((Path(root) / "mods").glob("*.jar")):
        row = {"file": path.name, "sha256": sha(path)}
        with zipfile.ZipFile(path) as archive:
            if "fabric.mod.json" in archive.namelist():
                metadata = json.loads(archive.read("fabric.mod.json"))
                row.update(id=metadata.get("id"), version=metadata.get("version"), depends=metadata.get("depends", {}))
            else:
                for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                    if name in archive.namelist():
                        # Python's standard TOML parser is available in the supported runtime (3.11+).
                        import tomllib
                        metadata = tomllib.loads(archive.read(name).decode("utf-8"))
                        row["mods"] = [{"id": m.get("modId"), "version": m.get("version")} for m in metadata.get("mods", [])]
                        break
            if "META-INF/MANIFEST.MF" in archive.namelist():
                text = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace").replace("\r\n ", "")
                row["build"] = dict(re.findall(r"^(VSS-[\w-]+): (.*)$", text, re.M))
                version = re.search(r"^Implementation-Version: (.*)$", text, re.M)
                if version:
                    row["build"]["Implementation-Version"] = version.group(1).strip()
        result.append(row)
    return result


class Mcp:
    """NDJSON JSON-RPC client, without shell input or logging private tool arguments."""
    def __init__(self, command):
        if not isinstance(command, list) or not command or not all(isinstance(v, str) for v in command):
            raise ValueError("MCP command must be an argument array")
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.messages = queue.Queue()
        self.lock = threading.Lock()
        self.next_id = 0
        threading.Thread(target=self._read, daemon=True).start()
        self.rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                "clientInfo": {"name": "vss-internal-acceptance", "version": "1"}})
        self.process.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
        self.process.stdin.flush()
        available = {t["name"] for t in self.rpc("tools/list", {})["tools"]}
        if not {"minecraft_client_status", "minecraft_client_frame", "minecraft_client_input", "minecraft_client_query"} <= available:
            raise Incomplete("MineClient Bridge MCP tools are missing")

    def _read(self):
        try:
            while True:
                line = self.process.stdout.readline(64 * 1024 * 1024 + 1)
                if not line:
                    raise Incomplete("MCP process ended")
                if len(line) > 64 * 1024 * 1024 or not line.endswith(b"\n"):
                    raise Incomplete("MCP response exceeded its bound")
                self.messages.put(json.loads(line))
        except Exception:
            self.messages.put({"transportError": True})

    def rpc(self, method, params, timeout=30):
        with self.lock:
            return self._rpc(method, params, timeout)

    def _rpc(self, method, params, timeout):
        self.next_id += 1
        request_id = self.next_id
        self.process.stdin.write((json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}) + "\n").encode())
        self.process.stdin.flush()
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                message = self.messages.get(timeout=max(.01, end - time.monotonic()))
            except queue.Empty:
                raise Incomplete("MCP operation timed out") from None
            if message.get("transportError"):
                raise Incomplete("MCP transport failed")
            if message.get("id") != request_id:
                continue
            if "error" in message:
                raise Incomplete("MCP rejected an operation")
            return message["result"]
        raise Incomplete("MCP operation timed out")

    def call(self, name, arguments, timeout=30):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments}, timeout)
        if result.get("isError"):
            # Do not echo errors from external launchers, which may contain private paths/arguments.
            raise Incomplete("Bridge operation failed: " + name)
        if name == "minecraft_client_frame":
            return result
        for item in result.get("content", []):
            if item.get("type") == "text":
                return json.loads(item["text"])
        raise Incomplete("Bridge returned no structured evidence")

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=3)


def bridge_state(mcp, run_id):
    return mcp.call("minecraft_client_query", {"run_id": run_id, "kind": "state", "radius": 1})["result"]


def command(mcp, run_id, text):
    return mcp.call("minecraft_client_input", {"run_id": run_id, "kind": "command", "command": text})


def perf(root, pid):
    session = read(Path(root) / "debug/vss-performance/session.json")
    if session.get("host") != "127.0.0.1" or session.get("pid") != pid:
        raise Incomplete("Performance session does not belong to this client")
    request = urllib.request.Request("http://127.0.0.1:" + str(int(session["port"])) + "/report",
                                     headers={"Authorization": "Bearer " + session["token"]})
    with urllib.request.urlopen(request, timeout=5) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    if len(data) > 64 * 1024 * 1024:
        raise Incomplete("Performance report exceeds read limit")
    return json.loads(data)


def validate_sample(sample, run_id, pid, label, now=None):
    identity = sample.get("acceptance", {})
    if identity.get("runId") != run_id or identity.get("processId") != pid or not identity.get("enabled"):
        raise Incomplete("Acceptance identity is stale or was not enabled")
    if sample.get("label") != label or not finite(sample.get("sampleMonotonicNanos")):
        raise Incomplete("No new interval for this phase")
    sampled = datetime.fromisoformat(sample["time"].replace("Z", "+00:00")).timestamp()
    age = (time.time() if now is None else now) - sampled
    if not -2 <= age <= 5:
        raise Incomplete("Performance sample is stale")
    if sample.get("paused") or not sample.get("frameCount", 0):
        raise Incomplete("No unpaused game frames")
    if not sample.get("windowActive") and not identity.get("backgroundAllowed"):
        raise Incomplete("Unfocused capture was not explicitly enabled")


def loading_metrics(samples, fixed=True):
    """Only comparable observations count; unknown/zero demand cannot prove completion."""
    result = {"firstReadySeconds": None, "coverage80Seconds": None, "coverage95Seconds": None,
              "target95Seconds": None, "stalledSeconds": 0, "comparable": True}
    if not samples:
        return result
    first = samples[0].get("captureStartedNanos", samples[0]["sampleMonotonicNanos"]) if fixed else samples[0]["sampleMonotonicNanos"]
    result["timeOrigin"] = "capture-start-after-world-present" if fixed and "captureStartedNanos" in samples[0] else "first-observed-interval"
    totals, scene, last_progress, last_changed = None, None, None, first
    for s in samples:
        now = s["sampleMonotonicNanos"]
        elapsed = (now - first) / 1e9
        p = s.get("progress", {})
        identity = (s.get("epoch"), s.get("dimension"), tuple(s.get("camera", [])), s.get("cameraYaw"),
                    s.get("cameraPitch"), s.get("predictionDistanceBlocks"), tuple(s.get("resolution", [])))
        counts = tuple(p.get(k) for k in ("coverageTotal", "targetTotal", "surfaceTotal"))
        if fixed and scene is not None and (identity != scene or counts != totals):
            result["comparable"] = False
        scene, totals = identity, counts
        if s.get("loading", {}).get("readyTiles", 0) > 0 and result["firstReadySeconds"] is None:
            result["firstReadySeconds"] = elapsed
        if p.get("fresh") is not True:
            continue
        for name, prefix, threshold in (("coverage80Seconds", "coverage", .8),
                                        ("coverage95Seconds", "coverage", .95), ("target95Seconds", "target", .95)):
            total, ready = p.get(prefix + "Total"), p.get(prefix + "Ready")
            if finite(total) and finite(ready) and total > 0 and ready / total >= threshold and result[name] is None:
                result[name] = elapsed
        progress = tuple(p.get(k) for k in ("coverageReady", "targetReady", "surfaceReady"))
        complete = all(p.get(prefix + "Total", 0) <= p.get(prefix + "Ready", 0)
                       for prefix in ("coverage", "target", "surface"))
        if progress != last_progress or complete:
            last_progress, last_changed = progress, now
        else:
            result["stalledSeconds"] = max(result["stalledSeconds"], (now - last_changed) / 1e9)
    if not result["comparable"]:
        for key in ("coverage80Seconds", "coverage95Seconds", "target95Seconds"):
            result[key] = None
    return result


def compare_probe(document, limits, expected=None):
    if document.get("status") != "complete":
        return {"status": "incomplete", "reason": "probe did not complete"}
    checked, failures, missing = [], [], 0
    expected = expected or {}
    for point in document.get("points", []):
        ref = expected.get((point["x"], point["z"]), point if point.get("realLoaded") else {})
        if "error" in point or "realSurfaceY" not in ref or "predictedSurfaceY" not in point:
            missing += 1
            continue
        error = abs(point["predictedSurfaceY"] - ref["realSurfaceY"])
        row = {"x": point["x"], "z": point["z"], "heightError": error,
               "biomeMatch": point.get("predictedBiome") == ref.get("realBiome")}
        resident = point.get("resident", {})
        if resident and not resident.get("captured") and resident.get("x") == point["x"] and resident.get("z") == point["z"]:
            row["residentError"] = abs(resident["surfaceY"] - ref["realSurfaceY"])
        else:
            # A spatially reduced neighbouring column or captured real data cannot prove prediction accuracy.
            missing += 1
        checked.append(row)
        tolerance = limits.get("residentHeightToleranceByStep", {}).get(str(resident.get("step")), limits["maxHeightError"])
        if error > limits["maxHeightError"] or not row["biomeMatch"] or row.get("residentError", 0) > tolerance:
            failures.append(row)
    status = "failed" if failures else "incomplete" if len(checked) < limits["minPoints"] or missing else "passed"
    return {"status": status, "checked": len(checked), "missing": missing, "failures": failures,
            "maxHeightError": max((r["heightError"] for r in checked), default=None)}


def summary(samples):
    frames = sum(s.get("frameCount", 0) for s in samples)
    duration = sum(s.get("frameDurationNanos", 0) for s in samples)
    return {"intervals": len(samples), "fps": frames * 1e9 / duration if duration else None,
            "worstIntervalP95Ms": max((s.get("frameP95Ms", 0) for s in samples), default=None),
            "peakHeapBytes": max((s.get("heapUsedBytes", 0) for s in samples), default=None),
            "publishedTiles": sum(s.get("rates", {}).get("publishedTilesPerSecond", 0) * s.get("intervalSeconds", 0) for s in samples)}


def observe(mcp, run_id, root, pid, label, seconds, output, activity=None, initial_samples=None):
    command(mcp, run_id, "vssclient prediction perf mark " + label)
    activity_errors = []
    stop_activity = threading.Event()
    worker = None
    if activity:
        def work():
            try: activity(stop_activity)
            except Exception as failure: activity_errors.append(failure)
        worker = threading.Thread(target=work, daemon=True)
        worker.start()
    rows = list(initial_samples or [])
    seen = {s["sampleMonotonicNanos"] for s in rows}
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            report = perf(root, pid)
            sample = report.get("latest", {})
            validate_sample(sample, run_id, pid, label)
            key = sample["sampleMonotonicNanos"]
            if key not in seen and report.get("sampling"):
                seen.add(key); rows.append(sample)
                write(output, rows)
        except (OSError, Incomplete, KeyError, ValueError):
            pass
        time.sleep(.5)
    if worker:
        stop_activity.set()
        worker.join(timeout=32)
        if worker.is_alive() or activity_errors:
            raise Incomplete("Movement route did not complete within its measured interval")
    if len(rows) < max(2, int(seconds * .5)):
        raise Incomplete("Insufficient fresh intervals; check pause/focus/background FPS limits")
    return rows


def frame(mcp, run_id, path):
    result = mcp.call("minecraft_client_frame", {"run_id": run_id})
    for item in result.get("content", []):
        if item.get("type") == "image" and item.get("mimeType") == "image/png":
            data = base64.b64decode(item["data"], validate=True)
            if not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > 32 * 1024 * 1024:
                raise Incomplete("Invalid framebuffer image")
            Path(path).write_bytes(data)
            return {"file": Path(path).name, "sha256": sha(path), "review": "pending"}
    raise Incomplete("No framebuffer image returned")


def actions(mcp, run_id, entries, stop=None):
    """Use existing MCP inputs; never implement a second keyboard/mouse injector."""
    for action in entries:
        if stop and stop.is_set():
            raise Incomplete("Route was cancelled at its phase boundary")
        action = dict(action)
        duration = action.pop("holdSeconds", None)
        if duration is not None:
            if action.get("kind") != "key" or not finite(duration) or not 0 < duration <= 10:
                raise ValueError("Only a named mapping can be held for up to 10 seconds")
            action["action"] = "press"
            try:
                mcp.call("minecraft_client_input", {"run_id": run_id, **action})
                until = time.monotonic() + duration
                while time.monotonic() < until:
                    if stop and stop.is_set():
                        raise Incomplete("Held input was cancelled at its phase boundary")
                    bridge_state(mcp, run_id)
                    time.sleep(min(.5, max(0, until - time.monotonic())))
            finally:
                mcp.call("minecraft_client_input", {"run_id": run_id, **action, "action": "release"})
        else:
            if action.get("kind") == "look":
                action.setdefault("relative", False)
            mcp.call("minecraft_client_input", {"run_id": run_id, **action})


def validate_inventory(rows, profile, case):
    by_hash = {r["sha256"]: r for r in rows}
    if any(m["sha256"] not in by_hash for m in case["terrainMods"]):
        raise Incomplete("Target terrain mod hashes do not match the test case")
    vss = [r for r in rows if r.get("build", {}).get("VSS-Build-Id")]
    suffix = "-" + profile["loader"] + "-" + profile["minecraft"]
    if len(vss) != 1 or not vss[0]["build"].get("Implementation-Version", "").endswith(suffix):
        raise Incomplete("VSS artifact does not match the selected platform and game version")
    return vss[0]


def run_phase(mcp, profile, case, phase, session, output, reference=None):
    run_id = safe_id(session["runId"])
    root = Path(session["runtimeRoot"]).resolve()
    args = {"run_id": run_id, "prepared_root": session["preparedRoot"]}
    launched = False
    result = {"phase": phase, "runId": run_id, "status": "incomplete", "visual": []}
    output.mkdir(parents=True, exist_ok=False)
    try:
        mcp.call("minecraft_client_launch", args, timeout=180)
        launched = True
        status = mcp.call("minecraft_client_status", {"run_id": run_id})
        pid = status["session"]["process_id"]
        bridge = status["bridge"]
        if bridge.get("process_id") != pid or bridge.get("run_id") != run_id or Path(bridge["runtime_root"]).resolve() != root:
            raise Incomplete("Bridge runtime root does not match the fixture")
        result["processId"] = pid
        result["inventory"] = inventory(root)
        result["vssArtifact"] = validate_inventory(result["inventory"], profile, case)
        deadline = time.monotonic() + 120
        while not bridge.get("in_world") and time.monotonic() < deadline:
            time.sleep(.5)
            bridge = mcp.call("minecraft_client_status", {"run_id": run_id})["bridge"]
        if not bridge.get("in_world"):
            raise Incomplete("Prepared quick-play fixture did not enter its world")
        # World creation and mod/seed/preset selection belong to the prepared fixture.
        # Auto capture starts inside the client; preserve its cold-loading history.
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                if perf(root, pid).get("capturing"):
                    break
            except (OSError, ValueError, Incomplete):
                pass
            time.sleep(.5)
        else:
            raise Incomplete("Explicit acceptance auto capture was not enabled at launch")
        before = bridge_state(mcp, run_id)
        result["initialState"] = before
        initial_epoch = perf(root, pid).get("latest", {}).get("epoch")
        if phase == "world-reentry":
            if not case.get("leaveWorld") or not case.get("enterWorld"):
                raise Incomplete("Fixture has no verified leave/enter GUI sequence")
            actions(mcp, run_id, case["leaveWorld"])
            if mcp.call("minecraft_client_status", {"run_id": run_id})["bridge"].get("in_world"):
                raise Incomplete("Client never left its world")
            actions(mcp, run_id, case["enterWorld"])
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if mcp.call("minecraft_client_status", {"run_id": run_id})["bridge"].get("in_world"):
                    break
                time.sleep(.5)
            else:
                raise Incomplete("Client did not reenter its world")
            result["sameProcessReentry"] = True
            after = bridge_state(mcp, run_id)
            if after["world"]["dimension"] != before["world"]["dimension"]:
                raise Incomplete("Reentry returned to a different dimension")
        elif phase == "prediction-toggle":
            command(mcp, run_id, "vssclient prediction acceptance-mode prediction-off")
            off = observe(mcp, run_id, root, pid, phase + "-off", 5, output / "off-samples.json")
            if any(s.get("predictionEnabled") for s in off):
                raise Incomplete("Prediction-off mode was not observed")
            result["visual"].append(frame(mcp, run_id, output / "prediction-off.png"))
            command(mcp, run_id, "vssclient prediction acceptance-mode loading")
        seconds = case["phaseSeconds"].get(phase, 30)
        activity = (lambda stop: actions(mcp, run_id, case["movement"], stop)) if phase == "movement" else None
        initial_samples = []
        if phase in ("cold-load", "warm-load"):
            initial_report = perf(root, pid)
            latest_epoch = initial_report.get("latest", {}).get("epoch")
            for s in initial_report.get("samples", []):
                identity = s.get("acceptance", {})
                if identity.get("processId") == pid and identity.get("runId") == run_id and s.get("epoch") == latest_epoch:
                    initial_samples.append(s)
        rows = observe(mcp, run_id, root, pid, phase, seconds, output / "samples.json", activity, initial_samples)
        if phase == "movement":
            after = bridge_state(mcp, run_id)
            a, b = before["player"], after["player"]
            distance = math.sqrt(sum((a[k] - b[k]) ** 2 for k in ("x", "y", "z")))
            result["movementDistance"] = distance
            if distance < case["limits"]["minMovementBlocks"]:
                raise Incomplete("Movement inputs did not produce the required displacement")
        if phase == "world-reentry" and initial_epoch is not None and rows[0].get("epoch") == initial_epoch:
            raise Incomplete("Same-process reentry did not establish a new performance epoch")
        result["performance"] = summary(rows)
        result["loading"] = loading_metrics(rows, fixed=phase in ("cold-load", "warm-load"))
        if any(s.get("mode") != "loading" for s in rows):
            raise Incomplete("Expected loading mode was not continuously observed")
        for sample in rows:
            for key, expected in case["expected"].items():
                if sample.get(key) != expected:
                    raise Incomplete("Scenario setting does not match: " + key)
        if result["performance"]["worstIntervalP95Ms"] > case["limits"]["maxFrameP95Ms"]:
            result["status"] = "failed"; result["reason"] = "Frame-time limit exceeded"; return result
        if any(s.get("loading", {}).get("failed", 0) for s in rows):
            result["status"] = "failed"; result["reason"] = "Prediction generation failures observed"; return result
        if any(s.get("renderer", {}).get("renderState", {}).get("failed") for s in rows):
            result["status"] = "failed"; result["reason"] = "Prediction renderer reported failure"; return result
        result["visual"].append(frame(mcp, run_id, output / "final.png"))
        if phase == "terrain-biomes":
            command(mcp, run_id, "vssclient prediction perf mark diagnostic-probe")
            checks = []
            for i, spec in enumerate(case["probes"]):
                probe_id = "probe-" + str(i)
                path = root / "debug/vss-acceptance" / run_id / (probe_id + ".json")
                if path.exists():
                    raise Incomplete("Stale probe evidence already exists")
                command(mcp, run_id, "vssclient prediction acceptance " + probe_id + " " +
                        " ".join(str(spec[k]) for k in ("x", "z", "side", "step")))
                deadline = time.monotonic() + 90
                while not path.exists() and time.monotonic() < deadline:
                    time.sleep(.5)
                if not path.exists():
                    raise Incomplete("Bounded geometry probe did not return evidence")
                document = read(path)
                if document.get("acceptance", {}).get("processId") != pid or document.get("acceptance", {}).get("runId") != run_id:
                    raise Incomplete("Probe evidence belongs to another session")
                write(output / (probe_id + ".json"), document)
                if str(document.get("seed")) != str(case["seed"]):
                    raise Incomplete("Probe world seed does not match the case")
                expected = None
                if reference:
                    if reference["profileFingerprint"] != document.get("profileFingerprint") or reference["dimension"] != document.get("dimension"):
                        raise Incomplete("Reference worldgen inputs differ from this prediction profile")
                    expected = {(p["x"], p["z"]): p for p in reference["points"]}
                checks.append(compare_probe(document, case["limits"], expected))
            result["geometry"] = checks
            if any(c["status"] != "passed" for c in checks):
                result["status"] = "failed" if any(c["status"] == "failed" for c in checks) else "incomplete"
                return result
        if phase in ("cold-load", "warm-load"):
            if not result["loading"]["comparable"] or result["loading"]["coverage95Seconds"] is None or result["loading"]["target95Seconds"] is None:
                raise Incomplete("No comparable 95% coverage and refinement evidence")
            if result["loading"]["coverage95Seconds"] > case["limits"]["maxCoverage95Seconds"] or result["loading"]["target95Seconds"] > case["limits"]["maxTarget95Seconds"]:
                result["status"] = "failed"; result["reason"] = "Loading deadline exceeded"; return result
            if phase == "warm-load" and not any(s.get("loading", {}).get("diskHits", 0) or s.get("loading", {}).get("meshRestores", 0) for s in rows):
                raise Incomplete("Warm run did not demonstrate cache reuse")
        if result["loading"]["stalledSeconds"] > case["limits"]["maxStallSeconds"]:
            result["status"] = "failed"
            result["reason"] = "Loading progress stalled beyond the scenario limit"
        else:
            result["status"] = "awaiting-visual-review"
        return result
    except Exception as failure:
        result["reason"] = str(failure) if isinstance(failure, (Incomplete, ValueError)) else type(failure).__name__
        return result
    finally:
        if launched:
            try:
                mcp.call("minecraft_client_input", {"run_id": run_id, "kind": "release_all"})
                closed = mcp.call("minecraft_client_close", {"run_id": run_id}, timeout=60)
                if closed.get("process_id") != pid or closed.get("process_exited") is not True:
                    raise Incomplete("Exact game process exit was not confirmed")
                result["cleanup"] = "bridge-confirmed-close"
            except Exception:
                result["cleanup"] = "incomplete"
                result["status"] = "incomplete"
        write(output / "result.json", result)


def validate_case(case):
    safe_id(case["id"])
    if case.get("schema") != 1 or case.get("disposableWorld") is not True:
        raise ValueError("Acceptance requires a schema-1 disposable test world")
    if not case.get("terrainMods") or case.get("seed") is None or not case.get("preset") or "REPLACE" in case["preset"]:
        raise ValueError("Specify target terrain mods, exact seed and preset")
    if any(not re.fullmatch(r"[a-fA-F0-9]{64}", m.get("sha256", "")) or "REPLACE" in m.get("id", "") for m in case["terrainMods"]):
        raise ValueError("Pin exact terrain mod IDs and SHA-256 hashes")
    if not case.get("probes") or not case.get("movement"):
        raise ValueError("Fixture needs geometry coordinates and movement actions")
    if not 1 <= case["limits"]["minPoints"] <= 64 or not 0 <= case["limits"]["maxHeightError"] <= 16:
        raise ValueError("Invalid geometry acceptance limits")
    for p in case["probes"]:
        if not all(isinstance(p.get(k), int) and not isinstance(p[k], bool) for k in ("x", "z", "side", "step")):
            raise ValueError("Probe coordinates must be integers")
        if not 1 <= p["side"] <= 8 or not 1 <= p["step"] <= 256:
            raise ValueError("Probe exceeds the runtime bounds")
    for phase in PHASES:
        if not 5 <= case["phaseSeconds"].get(phase, 30) <= 300:
            raise ValueError("Each phase must last 5..300 seconds")


def evaluate(results, reviews):
    checks = []
    for phase in PHASES:
        r = results.get(phase, {})
        status = r.get("status", "incomplete")
        if status == "awaiting-visual-review":
            visual = r.get("visual", [])
            if any(reviews.get(v["sha256"], {}).get("status") == "failed" for v in visual):
                checks.append({"phase": phase, "status": "failed"})
                continue
            passed = bool(visual) and all(reviews.get(v["sha256"], {}).get("status") == "passed"
                                         and reviews[v["sha256"]].get("observations") for v in visual)
            status = "passed" if passed else "incomplete"
        checks.append({"phase": phase, "status": status})
    return {"status": "failed" if any(c["status"] == "failed" for c in checks)
            else "passed" if all(c["status"] == "passed" for c in checks) else "incomplete", "phases": checks}


def transfer_warm_cache(cold, warm, relative_paths):
    """Called after MCP confirmed exact cold PID exit. Copy only named fixture subtrees."""
    source_root, dest_root = Path(cold).resolve(), Path(warm).resolve()
    if source_root == dest_root or source_root in dest_root.parents or dest_root in source_root.parents:
        raise ValueError("Warm fixture must be a separate one-shot runtime")
    proof = []
    for relative in relative_paths:
        rel = Path(relative)
        if rel.is_absolute() or ".." in rel.parts or not rel.parts or rel.parts[0] not in {"saves", ".vss", "voxy"}:
            raise ValueError("Only explicit test-save/cache relative paths can be transferred")
        source, dest = (source_root / rel).resolve(), (dest_root / rel).resolve()
        if source_root not in source.parents or dest_root not in dest.parents or not source.exists() or dest.exists():
            raise ValueError("Warm cache target must be absent and source must belong to its runtime")
        if source.is_dir():
            files = [p for p in source.rglob("*") if p.is_file()]
            if any(p.is_symlink() or source_root not in p.resolve().parents for p in source.rglob("*")):
                raise ValueError("Fixture cache contains external links")
            shutil.copytree(source, dest)
        else:
            files = [source]; dest.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(source, dest)
        for p in files:
            copied = dest / p.relative_to(source) if source.is_dir() else dest
            original_hash = sha(p)
            if sha(copied) != original_hash:
                raise Incomplete("Warm cache checksum changed while copying")
            proof.append({"path": str(rel / p.relative_to(source)) if source.is_dir() else str(rel), "sha256": original_hash})
    if not proof:
        raise Incomplete("Warm fixture contains no transferred cache/save data")
    return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("catalog")
    plan = sub.add_parser("plan"); plan.add_argument("target"); plan.add_argument("output", type=Path)
    run = sub.add_parser("run"); run.add_argument("target"); run.add_argument("case", type=Path)
    run.add_argument("sessions", type=Path); run.add_argument("mcp_command", type=Path); run.add_argument("output", type=Path)
    final = sub.add_parser("evaluate"); final.add_argument("output", type=Path); final.add_argument("reviews", type=Path)
    reference = sub.add_parser("reference"); reference.add_argument("probe", type=Path); reference.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.action == "catalog":
        print(json.dumps([read(p) for p in sorted((HERE / "profiles").glob("*.json"))], ensure_ascii=False, indent=2)); return
    if args.action == "evaluate":
        results = {p.parent.name: read(p) for p in args.output.glob("*/result.json")}
        outcome = evaluate(results, read(args.reviews))
        write(args.output / "acceptance-result.json", outcome)
        print(json.dumps(outcome, ensure_ascii=False)); return
    if args.action == "reference":
        probe = read(args.probe)
        if probe.get("status") != "complete": raise Incomplete("Reference probe is incomplete")
        points = [{k: p[k] for k in ("x", "z", "realSurfaceY", "realBlock", "realBiome")}
                  for p in probe["points"] if p.get("realLoaded") and "realSurfaceY" in p and "realBiome" in p]
        if not points: raise Incomplete("No loaded real terrain in reference probe")
        write(args.output, {"schema": 1, "seed": probe["seed"], "dimension": probe["dimension"],
                            "profileFingerprint": probe["profileFingerprint"], "sourceProbeSha256": sha(args.probe), "points": points})
        print("Exported loaded-world reference; use a fresh prediction-only fixture for resident checks"); return
    if not re.fullmatch(r"(?:neoforge|forge|fabric)-[0-9]+(?:\.[0-9]+){1,2}", args.target):
        raise ValueError("Unknown target identifier")
    profile = read(HERE / "profiles" / (args.target + ".json"))
    if args.action == "plan":
        write(args.output, profile); print("Wrote version-specific internal workflow"); return
    case = read(args.case); validate_case(case)
    sessions = read(args.sessions)
    if set(sessions) != set(PHASES):
        raise ValueError("One exact prepared session is required for every phase")
    if len({s["runId"] for s in sessions.values()}) != len(PHASES):
        raise ValueError("Prepared sessions must have distinct run IDs")
    roots = [Path(s["runtimeRoot"]).resolve() for s in sessions.values()]
    if len(set(roots)) != len(PHASES): raise ValueError("Each phase requires its own runtime root")
    reference = read(case["reference"]) if case.get("reference") else None
    if reference and str(reference.get("seed")) != str(case["seed"]): raise Incomplete("Reference seed does not match case")
    args.output.mkdir(parents=True, exist_ok=False)
    write(args.output / "case.json", case); write(args.output / "workflow.json", profile)
    mcp = Mcp(read(args.mcp_command))
    results = {}
    try:
        for phase in PHASES:
            print("Running " + profile["target"] + ": " + phase, flush=True)
            if phase == "warm-load":
                if results["cold-load"].get("cleanup") != "bridge-confirmed-close":
                    results[phase] = {"status": "incomplete", "reason": "cold client did not close"}
                    continue
                try:
                    cold_root, warm_root = sessions["cold-load"]["runtimeRoot"], sessions[phase]["runtimeRoot"]
                    if inventory(cold_root) != inventory(warm_root): raise Incomplete("Cold and warm mod inventories differ")
                    proof = transfer_warm_cache(cold_root, warm_root, sessions[phase].get("cachePaths", []))
                    write(args.output / "warm-transfer.json", proof)
                except (Incomplete, ValueError, OSError) as failure:
                    results[phase] = {"status": "incomplete", "reason": type(failure).__name__}
                    write(args.output / phase / "result.json", results[phase]); continue
            results[phase] = run_phase(mcp, profile, case, phase, sessions[phase], args.output / phase, reference)
    finally:
        mcp.close()
        write(args.output / "acceptance-result.json", evaluate(results, {}))
    print("Evidence recorded; inspect frames and supply their SHA-256-bound visual verdicts")


if __name__ == "__main__":
    main()
