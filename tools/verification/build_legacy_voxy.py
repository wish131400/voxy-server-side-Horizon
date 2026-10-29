"""Build the pinned community Fabric 1.21.1 Voxy source for real contract tests.

The result is a local validation dependency, not an upstream published release.
Only build metadata and dependency locations are patched; Java/GLSL sources stay
identical to the checksum-verified upstream commit. Java 21 is required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.request import Request, urlopen
import zipfile


COMMIT = "f308c254fafc23bdcc250ba3f1e5c6a7585f08d0"
SOURCE_URL = "https://api.github.com/repos/m3t4f1v3/voxy/zipball/" + COMMIT
SOURCE_SHA256 = "fc0a3bacf32cfa7510bbbdd5cf98fcede4f74b07689227333c9a9a2a012ca9f4"
NVIDIUM_URL = "https://api.github.com/repos/drouarb/nvidium/releases/assets/274184404"
NVIDIUM_NAME = "nvidium-0.4.1-beta9-1.21-1.21.1.jar"
NVIDIUM_SHA256 = "7cff31c04bf910dcb85eb4de06581d7a02219fd1d8ac9294b34fb3e9a56958e6"
FLASHBACK_URL = "https://cdn.modrinth.com/data/4das1Fjq/versions/rNCr1Rbs/Flashback-0.34.0-for-MC1.21.1.jar"
FLASHBACK_NAME = "Flashback-0.34.0-for-MC1.21.1.jar"
FLASHBACK_SHA256 = "518f1a1dfd34490c8b24003478873b5767cd26f0686ecaa47d068a9f0d451bfe"
OUTPUT_NAME = "voxy-0.2.14-alpha-1.21.1.jar"
# Two complete local builds with Oracle JDK 21.0.6 produced these exact bytes.
# Other operating systems / Java 21 patches must satisfy the source and content
# contracts below; their complete ZIP checksum is recorded rather than assumed.
REFERENCE_SHA256 = "be37347a559ddec4517f7f289515c4699ebe00f4c0f2930fe2ae918fcc80fc1b"
CLASS_INVENTORY_SHA256 = "99f41168bffabfd06476c12472954f08d9aeb44f629e955d6b5d9fb5ef717886"
RESOURCE_SHA256 = "7d61b3a37321852a5a75aef4db2d6404453485bd31cec783901a0a38921f1314"
NESTED_SHA256 = {
    "META-INF/jars/commons-pool2-2.12.0.jar": "20d4f943a6d018b0e3c62ede84ce89a785e34ceb5d0155789c3e61f6e78a2c8d",
    "META-INF/jars/jedis-5.1.0.jar": "677988068e3b1f0ace8e47bd4478f1d2b3140f93d7fa18087fdc56e9ddcf60a4",
    "META-INF/jars/lwjgl-lmdb-3.3.3-natives-linux.jar": "3aac70a9d156e6dbd0742182c3f134de3477118c31228ceac78bd8fd668e4be9",
    "META-INF/jars/lwjgl-lmdb-3.3.3-natives-windows.jar": "0090f59f870684956289db03a2a3e23a3e7c1003bb53692f102eff0f4cbcefc7",
    "META-INF/jars/lwjgl-lmdb-3.3.3.jar": "29bc92195ebe50a679550e4af466882d50e8204a93378e52b3f1b9c2e31ad1b2",
    "META-INF/jars/lwjgl-zstd-3.3.3-natives-linux.jar": "f98e1869794176b6191abd0d54a7ac80213d255bb4d42d67784d8f1c157033bd",
    "META-INF/jars/lwjgl-zstd-3.3.3-natives-windows.jar": "58bd6e35a553dad97d603338528213f7f9ba1173935514be7930c881d9010080",
    "META-INF/jars/lwjgl-zstd-3.3.3.jar": "7f77702b7b4f689ed07e5a193e271b27ce085134d53998ae61842fe13c39216b",
    "META-INF/jars/lz4-java-1.8.0.jar": "dd8b02512af33c0cf684f9b97fd5b893cd68739ce0fa7d231418df4198abab72",
    "META-INF/jars/rocksdbjni-10.2.1.jar": "31d376b19babccbdaa909e2b6cb463da9c62072239d3d9153e7f018a1424e44e",
    "META-INF/jars/xz-1.10.jar": "12b4b007e6ea54692fa986f242be7f7085a34a4b7c68d3b29071ca3ed2e84469",
}


def download(url, path, expected, accept="application/vnd.github+json"):
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
        return
    request = Request(url, headers={"User-Agent": "VSS-Horizon/Fabric-validation", "Accept": accept})
    with urlopen(request, timeout=120) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("Pinned dependency checksum mismatch: " + path.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Pinned upstream build contract changed: " + old)
    return source.replace(old, new)


def build(destination, work_directory=None, gradle_user_home=None):
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    result = destination / OUTPUT_NAME
    provenance = destination / "voxy-source-build.json"
    if result.is_file():
        digest = hashlib.sha256(result.read_bytes()).hexdigest()
        cached = json.loads(provenance.read_text(encoding="utf-8")) if provenance.is_file() else {}
        if digest == REFERENCE_SHA256 or (cached.get("sha256") == digest
                and cached.get("sourceSha256") == SOURCE_SHA256
                and cached.get("recipeSha256") == recipe_sha256()):
            entry = metadata(result)
            provenance.write_text(json.dumps(entry, indent=2) + "\n", encoding="utf-8")
            return entry
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    version = subprocess.run([java, "-version"], text=True, capture_output=True, check=True)
    if not re.search(r'version "21(?:\.|\")', version.stderr + version.stdout):
        raise ValueError("The pinned community Voxy build requires JAVA_HOME pointing to Java 21")
    work = Path(work_directory or destination.parent / "source-voxy-1.21.1").resolve()
    work.mkdir(parents=True, exist_ok=True)
    archive = work.parent / "source-voxy-1.21.1.zip"
    download(SOURCE_URL, archive, SOURCE_SHA256)
    with zipfile.ZipFile(archive) as source:
        prefix = source.namelist()[0].split("/")[0]
        for entry in source.infolist():
            relative = Path(entry.filename).relative_to(prefix)
            target = (work / relative).resolve()
            if not target.is_relative_to(work):
                raise ValueError("Source archive escapes its work directory")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(entry))
    nvidium = work / "external-inputs" / NVIDIUM_NAME
    download(NVIDIUM_URL, nvidium, NVIDIUM_SHA256, "application/octet-stream")
    flashback = work / "external-inputs" / FLASHBACK_NAME
    download(FLASHBACK_URL, flashback, FLASHBACK_SHA256, "application/java-archive")
    gradle = work / "build.gradle"
    original = gradle.read_text(encoding="utf-8")
    patched = replace_once(original, 'id \'fabric-loom\' version "1.11-SNAPSHOT"',
                           'id \'fabric-loom\' version "1.11.8"')
    # Upstream embeds current time and shells out to git. An archive checkout
    # instead records its verified commit and that commit's timestamp.
    patched, replacements = re.subn(
        r'def gitCommitHash = \{ ->.*?\n\}\n\ndef buildtime = \{System.currentTimeSeconds\(\)\}',
        f'def gitCommitHash = {{ -> "{COMMIT}" }}\n\ndef buildtime = {{1777809508L}}',
        patched, flags=re.S)
    if replacements != 1:
        raise ValueError("Pinned upstream build metadata contract changed")
    # Same published bytes as the upstream Ivy coordinate. The GitHub asset API
    # supports checksum verification and avoids depending on redirect/HEAD behavior.
    patched = replace_once(patched,
        'modCompileOnly "drouarb:nvidium:0.4.1-beta9:1.21-1.21.1@jar"',
        f'modCompileOnly files("external-inputs/{NVIDIUM_NAME}")')
    patched = replace_once(patched,
        'modCompileOnly("maven.modrinth:flashback:rNCr1Rbs")',
        f'modCompileOnly files("external-inputs/{FLASHBACK_NAME}")')
    gradle.write_text(patched, encoding="utf-8", newline="\n")
    build_init = Path(__file__).with_name("legacy-voxy-build.gradle").resolve()
    command = [str(work / "gradlew.bat")] if os.name == "nt" else ["bash", str(work / "gradlew")]
    command += ["-I", str(build_init), "remapJar", "--no-daemon", "--console=plain", "--max-workers=2"]
    if gradle_user_home:
        command += ["--gradle-user-home", str(Path(gradle_user_home).resolve())]
    environment = dict(os.environ, GITHUB_ACTIONS="true")
    print("Building community Voxy commit " + COMMIT, flush=True)
    subprocess.run(command, cwd=work, env=environment, check=True)
    artifact = work / "build/libs/voxy-0.2.14-alpha.jar"
    if not artifact.is_file():
        raise ValueError("Pinned Voxy remapJar did not produce the expected artifact")
    metadata(artifact)
    shutil.copyfile(artifact, result)
    entry = metadata(result)
    provenance.write_text(json.dumps(entry, indent=2) + "\n", encoding="utf-8")
    return entry


def recipe_sha256():
    return hashlib.sha256(Path(__file__).read_bytes()
                          + Path(__file__).with_name("legacy-voxy-build.gradle").read_bytes()).hexdigest()


def metadata(path):
    with zipfile.ZipFile(path) as jar:
        mod = json.loads(jar.read("fabric.mod.json"))
        if mod.get("id") != "voxy" or mod.get("depends", {}).get("minecraft") != ["1.21.1"]:
            raise ValueError("Built community Voxy has the wrong Minecraft target")
        if mod.get("custom", {}).get("commit") != COMMIT:
            raise ValueError("Built community Voxy has the wrong source commit")
        classes = sorted(name for name in jar.namelist() if name.endswith(".class"))
        class_inventory = hashlib.sha256(("\n".join(classes) + "\n").encode()).hexdigest()
        if class_inventory != CLASS_INVENTORY_SHA256:
            raise ValueError("Built community Voxy is missing expected production classes")
        if any(int.from_bytes(jar.read(name)[6:8], "big") != 65 for name in classes):
            raise ValueError("Built community Voxy has the wrong Java class version")
        resources = sorted(name for name in jar.namelist() if not name.endswith("/")
                           and not name.endswith(".class") and not name.startswith("META-INF/")
                           and name != "fabric.mod.json")
        lines = "".join(name + " " + hashlib.sha256(jar.read(name)).hexdigest() + "\n" for name in resources)
        if hashlib.sha256(lines.encode()).hexdigest() != RESOURCE_SHA256:
            raise ValueError("Built community Voxy shader, Mixin or access-widener resources changed")
        nested = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist()
                  if name.startswith("META-INF/jars/") and name.endswith(".jar")}
        if nested != NESTED_SHA256:
            raise ValueError("Built community Voxy nested dependency checksums changed")
    data = path.read_bytes()
    sha256 = hashlib.sha256(data).hexdigest()
    return {"kind": "source-build", "repository": "https://github.com/m3t4f1v3/voxy",
            "commit": COMMIT, "sourceSha256": SOURCE_SHA256, "filename": path.name,
            "java": 21, "loom": "1.11.8", "published": False,
            "sha256": sha256, "sha512": hashlib.sha512(data).hexdigest(),
            "referenceSha256": REFERENCE_SHA256, "byteIdenticalToLocalReference": sha256 == REFERENCE_SHA256,
            "referenceBuild": "Windows / Oracle JDK 21.0.6; two complete builds matched",
            "recipeSha256": recipe_sha256(), "classInventorySha256": class_inventory,
            "resourcesSha256": RESOURCE_SHA256, "nestedSha256": nested,
            "compileInputsSha256": {NVIDIUM_NAME: NVIDIUM_SHA256, FLASHBACK_NAME: FLASHBACK_SHA256},
            "buildPatches": ["pin Loom", "fixed commit and commit time metadata",
                             "checksum-verified original Nvidium and Flashback compile inputs",
                             "stable ZIP order and timestamps"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=Path("external/compat/1.21.1"))
    parser.add_argument("--work-directory", type=Path)
    parser.add_argument("--gradle-user-home", type=Path,
                        help="Isolate Loom caches when another Minecraft build is running")
    arguments = parser.parse_args()
    print(json.dumps(build(arguments.destination, arguments.work_directory, arguments.gradle_user_home), indent=2))
