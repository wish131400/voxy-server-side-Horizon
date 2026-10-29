"""Run real published Fabric mods against the ordinary production game runtime.

First let Gradle export remapped VSS/probe artifacts with the independent
production-client-smoke.gradle init script. This runner never invokes Gradle,
changes dependency metadata, disables refmaps or launches a development runtime.
All inputs and reports are local validation artifacts, not redistributable mods.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
from urllib.request import Request, urlopen
import zipfile


MINECRAFT = {
    "1.20.1": ("https://piston-meta.mojang.com/v1/packages/fff64d6821a97cf12ce5f16df58dd5db0ed210b3/1.20.1.json",
               "fff64d6821a97cf12ce5f16df58dd5db0ed210b3"),
    "1.21.1": ("https://piston-meta.mojang.com/v1/packages/018296bcb207bea6fd25b05f08eee3942dcec406/1.21.1.json",
               "018296bcb207bea6fd25b05f08eee3942dcec406"),
}
FABRIC_PROFILE = {
    "1.20.1": "ae29f92a15304f3fe78f26fe7347f6d14240aee00149bf4b0cbd97628be0680a",
    "1.21.1": "ed7e24009112a09b3b489d6c13f972f7adca73bd7a2a726361ce4e9a7f3a15ef",
}
FABRIC_ARTIFACTS = {
    "net.fabricmc:fabric-loader:0.20.1": "d0c5a082e07410aaff9de7ab196aa6b4236002870f9ba3ad5c1260fcce5110ad",
    "net.fabricmc:intermediary:1.20.1": "caa3fd87dd353fc826a109a2d16f886d6519b47422e3a77cfa0ba8be5829a21e",
    "net.fabricmc:intermediary:1.21.1": "6059157dfb4a536ec151697004f74f6004b7d346b289c2f89849e0e585aa36fa",
}
FABRIC_API = {
    "1.20.1": ("0.92.12+1.20.1", "4197ff4fbdac13cffccd267c1bc59e9fbabb2b5683a9d5f8023f4b5ea16a1c1e"),
    "1.21.1": ("0.116.17+1.21.1", "79ac44b40780acbd884b34c50be1e39af682847e5f5cb3b1fddeeaa768dce800"),
}


def digest(path, algorithm="sha256"):
    return hashlib.new(algorithm, Path(path).read_bytes()).hexdigest()


def obtain(url, path, expected, algorithm="sha256", candidates=()):
    path = Path(path)
    if path.is_file() and digest(path, algorithm) == expected:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    for candidate in candidates:
        candidate = Path(candidate)
        if candidate.is_file() and digest(candidate, algorithm) == expected:
            shutil.copyfile(candidate, path)
            return path
    with urlopen(Request(url, headers={"User-Agent": "VSS-Horizon/Production-smoke"}), timeout=90) as response:
        data = response.read()
    if hashlib.new(algorithm, data).hexdigest() != expected:
        raise ValueError("Production runtime input checksum mismatch: " + path.name)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(path)
    return path


def os_name():
    return {"Windows": "windows", "Darwin": "osx", "Linux": "linux"}[platform.system()]


def allowed(rules):
    """Apply Mojang's ordered allow/disallow rules with launcher features off."""
    result = not rules
    for rule in rules or []:
        system = rule.get("os", {})
        matches = (not system.get("name") or system["name"] == os_name())
        if system.get("arch"):
            matches = matches and re.search(system["arch"], platform.machine()) is not None
        if system.get("version"):
            matches = matches and re.search(system["version"], platform.version()) is not None
        matches = matches and all(not enabled for enabled in rule.get("features", {}).values())
        if matches:
            result = rule["action"] == "allow"
    return result


def coordinate_path(name):
    parts = name.split(":")
    if len(parts) not in (3, 4) or any(not part or ".." in part or "/" in part or "\\" in part for part in parts):
        raise ValueError("Invalid Maven coordinate")
    group, artifact, version = parts[:3]
    classifier = "-" + parts[3] if len(parts) == 4 else ""
    return group.replace(".", "/") + f"/{artifact}/{version}/{artifact}-{version}{classifier}.jar"


def native_matches_machine(name):
    """Mojang lists several LWJGL architectures under the same OS rule."""
    if ":natives-" not in name:
        return True
    classifier = name.split(":")[-1]
    machine = platform.machine().lower()
    arm = machine in ("aarch64", "arm64")
    x86 = machine in ("x86", "i386", "i686")
    if "arm64" in classifier or "aarch64" in classifier:
        return arm
    if classifier.endswith("-x86"):
        return x86
    return not arm and not x86


def safe_native_extract(jar_path, destination):
    with zipfile.ZipFile(jar_path) as jar:
        for item in jar.infolist():
            name = PurePosixPath(item.filename)
            if (item.is_dir() or name.suffix.lower() not in (".dll", ".so", ".dylib", ".jnilib")
                    or item.filename.startswith("META-INF/")):
                continue
            if name.is_absolute() or ".." in name.parts or "\\" in item.filename or ":" in item.filename:
                raise ValueError("Native archive path escape")
            # LWJGL resolves libraries by basename; selected platform JARs only.
            target = destination / name.name
            data = jar.read(item)
            if target.exists() and target.read_bytes() != data:
                raise ValueError("Conflicting native library: " + name.name)
            target.write_bytes(data)


def prepare_runtime(minecraft, base, gradle_home):
    runtime = Path(base).resolve()
    runtime.mkdir(parents=True, exist_ok=True)
    gradle_home = Path(gradle_home).resolve()
    url, expected = MINECRAFT[minecraft]
    metadata_path = obtain(url, runtime / "minecraft.json", expected, "sha1",
                           [gradle_home / "caches/fabric-loom" / minecraft / "minecraft-info.json"])
    metadata = json.loads(metadata_path.read_text())
    profile_path = obtain(f"https://meta.fabricmc.net/v2/versions/loader/{minecraft}/0.20.1/profile/json",
                          runtime / "fabric-profile.json", FABRIC_PROFILE[minecraft])
    profile = json.loads(profile_path.read_text())
    if profile.get("inheritsFrom") != minecraft or profile.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient":
        raise ValueError("Wrong production Fabric launch profile")
    download = metadata["downloads"]["client"]
    client = obtain(download["url"], runtime / "minecraft-client.jar", download["sha1"], "sha1",
                    [gradle_home / "caches/fabric-loom" / minecraft / "minecraft-client.jar"])
    classpath = [client]
    natives = runtime / "lwjgl-natives"
    natives.mkdir(exist_ok=True)
    libraries = runtime / "libraries"
    for library in metadata["libraries"]:
        if not allowed(library.get("rules")) or not native_matches_machine(library["name"]):
            continue
        downloads = library.get("downloads", {})
        selected = []
        if "artifact" in downloads:
            selected.append((downloads["artifact"], ":natives-" in library["name"]))
        native = library.get("natives", {}).get(os_name())
        if native:
            native = native.replace("${arch}", "64" if "64" in platform.machine() else "32")
            selected.append((downloads["classifiers"][native], True))
        for artifact, native_artifact in selected:
            target = libraries / artifact["path"]
            candidates = list((gradle_home / "caches/modules-2/files-2.1").glob(
                library["name"].split(":")[0] + "/" + library["name"].split(":")[1] + "/"
                + library["name"].split(":")[2] + "/*/" + target.name))
            obtain(artifact["url"], target, artifact["sha1"], "sha1", candidates)
            classpath.append(target)
            if native_artifact:
                safe_native_extract(target, natives)
    for library in profile["libraries"]:
        relative = coordinate_path(library["name"])
        expected = library.get("sha256") or FABRIC_ARTIFACTS[library["name"]]
        target = obtain(library["url"] + relative, libraries / relative, expected)
        classpath.append(target)
    assets = gradle_home / "caches/fabric-loom/assets"
    index = metadata["assetIndex"]
    index_path = obtain(index["url"], assets / "indexes" / (index["id"] + ".json"), index["sha1"], "sha1",
                        list((assets / "indexes").glob("*-" + index["id"] + ".json")))
    objects = json.loads(index_path.read_text())["objects"]

    def asset(item):
        value = item["hash"]
        return obtain("https://resources.download.minecraft.net/" + value[:2] + "/" + value,
                      assets / "objects" / value[:2] / value, value, "sha1")

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(asset, objects.values()))
    version, expected = FABRIC_API[minecraft]
    name = f"fabric-api-{version}.jar"
    api = obtain(f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{version}/{name}", runtime / name, expected)
    return {"mainClass": profile["mainClass"], "classpath": classpath, "natives": natives,
            "assets": assets, "assetIndex": index["id"], "fabricApi": api,
            "jvmArguments": profile.get("arguments", {}).get("jvm", []),
            "profileSha256": digest(profile_path), "minecraftMetadataSha1": digest(metadata_path, "sha1")}


def read_inputs(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            if key not in ("vssVoxyJar", "vssSodiumJar", "vssIrisJar"):
                raise ValueError("Unexpected production dependency property: " + key)
            result[key] = Path(value).resolve()
    if not all(key in result for key in ("vssVoxyJar", "vssSodiumJar")):
        raise ValueError("Production smoke needs real Voxy and Sodium inputs")
    return result


def validate_remapped_vss(path, minecraft, expected=None):
    path = Path(path)
    if expected and digest(path) != expected:
        raise ValueError("Exported VSS artifact was replaced after validation")
    with zipfile.ZipFile(path) as jar:
        mod = json.loads(jar.read("fabric.mod.json"))
        if mod.get("id") != "vss":
            raise ValueError("Wrong production VSS identity")
        header = jar.read(mod["accessWidener"]).decode().splitlines()[0].split()
        if header[-1] != "intermediary":
            raise ValueError("Production VSS access widener must be remapped to intermediary")
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        if "VSS-Minecraft: " + minecraft not in manifest:
            raise ValueError("Wrong production VSS Minecraft manifest")


def run(minecraft, artifacts_path, inputs_path, base, gradle_home, java, without_iris=False,
        runtime_directory=None):
    artifacts = json.loads(Path(artifacts_path).read_text())
    if artifacts["minecraft"] != minecraft:
        raise ValueError("Wrong remapped smoke artifact target")
    validate_remapped_vss(artifacts["vssJar"], minecraft, artifacts.get("vssSha256"))
    if artifacts.get("probeSha256") and digest(artifacts["probeJar"]) != artifacts["probeSha256"]:
        raise ValueError("Exported smoke probe was replaced after validation")
    root = Path(base).resolve()
    runtime = prepare_runtime(minecraft, runtime_directory or root / "runtime", gradle_home)
    game = root / ("game-normal" if without_iris else "game-iris")
    mods = game / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    # Refuse unexpected leftovers instead of loading a different mod collection.
    if any(mods.iterdir()):
        expected_names = {"vss.jar", "vss-smoke.jar", "voxy.jar", "sodium.jar", "iris.jar", "fabric-api.jar"}
        if any(path.name not in expected_names for path in mods.iterdir()):
            raise ValueError("Unexpected files in isolated production smoke mods directory")
    selected = {"vss": Path(artifacts["vssJar"]), "vss-smoke": Path(artifacts["probeJar"]),
                "fabric-api": runtime["fabricApi"]}
    for key, path in read_inputs(inputs_path).items():
        if key == "vssIrisJar" and without_iris:
            continue
        selected[{"vssVoxyJar": "voxy", "vssSodiumJar": "sodium", "vssIrisJar": "iris"}[key]] = path
    for old in mods.glob("*.jar"):
        if old.stem not in selected:
            old.unlink()
    identities = {}
    for name, source in selected.items():
        source = source.resolve()
        with zipfile.ZipFile(source) as jar:
            mod = json.loads(jar.read("fabric.mod.json"))
            expected = "vss_smoke" if name == "vss-smoke" else name
            if mod.get("id") != expected:
                raise ValueError("Wrong isolated smoke mod identity: " + name)
        shutil.copyfile(source, mods / (name + ".jar"))
        identities[name] = {"filename": source.name, "sha256": digest(source), "version": mod["version"]}
    (game / "options.txt").write_text(
        "onboardAccessibility:false\nlang:en_us\ntutorialStep:none\nmaxFps:60\npauseOnLostFocus:false\nsoundCategory_master:0.0\n",
        encoding="utf-8")
    report_path = root / ("client-smoke-normal.json" if without_iris else "client-smoke-iris.json")
    report_path.unlink(missing_ok=True)
    required = ["vss", "voxy", "sodium", "fabric-api"] + (["iris"] if "iris" in selected else [])
    command = [str(java)] + runtime["jvmArguments"] + ["-Xmx3G", "-Dfabric.development=false", "-Dfabric.side=client",
               "-Djava.library.path=" + str(runtime["natives"]),
               "-Dorg.lwjgl.librarypath=" + str(runtime["natives"]),
               "-Dvss.smoke.report=" + str(report_path), "-Dvss.smoke.requiredMods=" + ",".join(required),
               "-cp", os.pathsep.join(str(path) for path in runtime["classpath"]), runtime["mainClass"],
               "--username", "VSSSmoke", "--version", minecraft, "--gameDir", str(game),
               "--assetsDir", str(runtime["assets"]), "--assetIndex", runtime["assetIndex"],
               "--uuid", "00000000-0000-4000-8000-000000000001", "--accessToken", "0", "--userType", "legacy",
               "--versionType", "release", "--width", "854", "--height", "480"]
    if os_name() == "osx":
        command.insert(1, "-XstartOnFirstThread")
    log_path = report_path.with_suffix(".log")
    result = {"minecraft": minecraft, "runtime": "production", "development": False,
              "gameplayValidated": False, "irisInstalled": "iris" in selected,
              "mods": identities, "fabricProfileSha256": runtime["profileSha256"],
              "minecraftMetadataSha1": runtime["minecraftMetadataSha1"]}
    try:
        with log_path.open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=game, stdout=log, stderr=subprocess.STDOUT, timeout=240, check=False)
        result["exitCode"] = completed.returncode
    except subprocess.TimeoutExpired:
        result.update(exitCode=None, timeout=True)
    result["probe"] = json.loads(report_path.read_text()) if report_path.exists() else None
    result["passed"] = result.get("exitCode") == 0 and bool(result["probe"] and result["probe"]["status"] == "passed")
    (root / ("result-normal.json" if without_iris else "result-iris.json")).write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True, choices=MINECRAFT)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--inputs", required=True, type=Path)
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--runtime-directory", type=Path,
                        help="Reuse checksum-verified official runtime files between isolated mod collections")
    parser.add_argument("--gradle-user-home", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    default_java = (Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
                    if os.environ.get("JAVA_HOME") else Path(shutil.which("java") or "java"))
    parser.add_argument("--java", type=Path, default=default_java)
    parser.add_argument("--without-iris", action="store_true")
    args = parser.parse_args()
    result = run(args.minecraft, args.artifacts, args.inputs, args.destination, args.gradle_user_home,
                 args.java, args.without_iris, args.runtime_directory)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
