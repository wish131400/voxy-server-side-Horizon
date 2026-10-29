"""Prepare six pinned community Voxy inputs without bundling third-party mods.

Source builds require Java 21 and an isolated Gradle user home. Only build
metadata and the selected loader projects are patched; Java, shaders, Mixin
configuration and dependency declarations remain the upstream source bytes.
The original two baseline dependencies are intentionally owned by other tools.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
from urllib.request import Request, urlopen
import zipfile


VARIANTS = {
    "mihile-1.20.1": ("1.20.1", "mihile"),
    "m3-1.20.1": ("1.20.1", "m3"),
    "m3-1.21.1": ("1.21.1", "m3"),
    "tkettela-1.20.1": ("1.20.1", "tkettela"),
    "wu-bao-1.21.1": ("1.21.1", "wu-bao"),
    "water-fix-1.21.1": ("1.21.1", "water-fix"),
}
SOURCES = {
    "m3": {
        "repository": "https://github.com/m3t4f1v3/voxy",
        "commit": "42d5037c19e6a540c4b69fd4156be0487f961663", "timestamp": 1792621791,
        "sha256": "a752a7b0de412f0918aca2effa43e7bcbf0df0602779a79fe4b93c5083d2bc16",
    },
    "tkettela": {
        "repository": "https://github.com/tkettela/unofficial-backport-voxy",
        "commit": "db2371278a7926d222b8346eddb359e967e47435", "timestamp": 1792861673,
        "sha256": "7347bdda4473f9158796d5f74223a69f05504b0e0a92d9c712a9bccb4fd5d0e5",
    },
}
# Fixed content contracts supplement the checksum-verified source archives.
# ZIP container hashes may vary by Java patch/platform; production inventories,
# shaders/Mixin/access-widener resources and embedded libraries must not vary.
SOURCE_CONTENT = {
    "m3-1.20.1": ("6c1df5eb3f79db50e26d5ffe7ede20beb8d817de46bd621ab2a483699126404b",
                  "13ced9585f958cd6223d4e0a99cfe6f656973cb17abc85129c51b48cdfc8bc3b"),
    "m3-1.21.1": ("aba9028b22c12bcf49929c321425fa408d91a7447a2ff2becb4e96196e24e639",
                  "ec2cbfa11fb7d6c2faa458aa184c6929fd238a4273e91ee3d9385cde1b0f041a"),
    "tkettela-1.20.1": ("193e51766f0a6b7d4addf5e4e7a22fc14133dfe034adaa9ce63de0f42ee0495f",
                        "0495b1c10b8c31fffc6a2707e5cbd83ae97fde4eb9bcff07c440157e1047ad52"),
}
NESTED_INVENTORY = "ea2394640f82d9baee253a4485db10783eb798d4a9fcb8803377954fa9be1b91"
RELEASES = {
    "mihile": {
        "repository": "https://github.com/mihile/voxy",
        "release": "v0.2.20-1.20.1-port.9", "asset": 672434132,
        "filename": "voxy-0.2.20-beta+1.20.1-port.9-82188bda.jar",
        "sha256": "fd3d1097bf23667a5dfccc47bd652c4f023e322f8dbddb717ac2ec1a1c97a50c",
        "metadataCommit": "82188bda", "java": 17,
    },
    "wu-bao": {
        "repository": "https://github.com/wu-bao/voxy",
        "release": "v0.2.15-beta-1.21.1-fabric-backport", "asset": 618512354,
        "filename": "voxy-0.2.15-beta+1.21.1-fabric-backport.jar",
        "sha256": "0d820add75b63dc816236ee2643eae69f5ad911b135f14fca5c078e6e9a706d9",
        "metadataCommit": "aab0ab95", "java": 21,
    },
    "water-fix": {
        "repository": "https://github.com/mayjhon69669-sys/voxy-water-fix",
        "release": "v0.2.19-mc1.21.1-leaf-tint-fog-fade-test", "asset": 581183706,
        "filename": "voxy-0.2.19-beta-mc1.21.1-leaf-tint-fog-fade-test.jar",
        "sha256": "15025e14c4387e43525445641137b335262f63dde6288a014e0965d94e659b48",
        "metadataCommit": "<UnknownCommit>", "java": 21,
    },
}
MODRINTH = "https://cdn.modrinth.com/data/"
VENDORED = ("https://raw.githubusercontent.com/wu-bao/voxy/"
            "52057384cc0130762195248f80179580dc072acb/local-deps/")
MAVEN = "https://artifex.soh.gg/maven/m3t4f1v3/bp-voxy-packages/"
DEPENDENCIES = {
    "sodium120": {
        "filename": "sodium-fabric-0.5.13+mc1.20.1.jar",
        "url": MODRINTH + "AANobbMI/versions/OihdIimA/sodium-fabric-0.5.13%2Bmc1.20.1.jar",
        "sha256": "688c26029ce69f0b1f7cf936866656ca6723228ac46c4aef5b5f793b1e6abd22",
        "version": "0.5.13+mc1.20.1", "release": "OihdIimA",
    },
    "iris120": {
        "filename": "iris-1.7.6+mc1.20.1.jar",
        "url": MODRINTH + "YL57xq9U/versions/s5eFLITc/iris-1.7.6%2Bmc1.20.1.jar",
        "sha256": "9eb15e563e0c9ae6eff15b7863f8432dd290cf9f724c7373e9b15b14aa829ed5",
        "version": "1.7.6+mc1.20.1", "release": "s5eFLITc",
    },
    "sodium121alpha": {
        "filename": "sodium-fabric-0.8.12-alpha.3+mc1.21.1.jar",
        "url": MAVEN + "net/caffeinemc/sodium-fabric/0.8.12-alpha.3+mc1.21.1/sodium-fabric-0.8.12-alpha.3+mc1.21.1.jar",
        "sha256": "12ff5c835390af25aac16e3f1f3c38972e442dfbb8909e06698bc5f30450f7c0",
        "version": "0.8.12-alpha.3+mc1.21.1",
    },
    "sodium121": {
        "filename": "sodium-fabric-0.8.12+mc1.21.1.jar",
        "url": VENDORED + "sodium-fabric-0.8.12+mc1.21.1.jar",
        "sha256": "9ad11a616d1ca759867de8a5bbafcde5ab50b68dbd674110c7b90aeea251b793",
        "version": "0.8.12+mc1.21.1",
    },
    "iris121": {
        "filename": "iris-fabric-1.8.14-beta.1+mc1.21.1.jar",
        "url": VENDORED + "iris-fabric-1.8.14-beta.1+mc1.21.1.jar",
        "sha256": "0ceb694040b4628bf3fa02286cfe5f0db833195669ab07f8caa4001359763eff",
        "version": "1.8.14-beta.1+mc1.21.1",
    },
}


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def download(url, destination, expected, accept="application/octet-stream"):
    """Reject changed bytes before replacing a cached input."""
    destination = Path(destination)
    if destination.is_file() and sha256(destination) == expected:
        return destination
    request = Request(url, headers={"User-Agent": "VSS-Horizon/Community-validation",
                                    "Accept": accept})
    with urlopen(request, timeout=120) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("Pinned input checksum mismatch: " + destination.name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(destination)
    return destination


def extract_source(archive, destination):
    """Extract one GitHub root, rejecting links and cross-platform path escapes."""
    destination = Path(destination).resolve()
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        roots = {PurePosixPath(item.filename).parts[0] for item in entries}
        if len(roots) != 1:
            raise ValueError("Expected one source archive root")
        for item in entries:
            parts = PurePosixPath(item.filename).parts
            if ("\\" in item.filename or any(p in ("..", ".") or ":" in p for p in parts)
                    or PurePosixPath(item.filename).is_absolute()
                    or stat.S_ISLNK(item.external_attr >> 16)):
                raise ValueError("Unsafe source archive entry: " + item.filename)
            target = destination.joinpath(*parts[1:]).resolve()
            if not target.is_relative_to(destination):
                raise ValueError("Source archive escapes work directory")
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(item))


def replace_once(text, original, replacement):
    if text.count(original) != 1:
        raise ValueError("Pinned build contract changed: " + original)
    return text.replace(original, replacement)


def patch_build(work, source, minecraft):
    """Touch only Gradle build metadata and omit unrelated loader projects."""
    common = work / "build.common.gradle.kts"
    text = common.read_text(encoding="utf-8")
    text, count = re.subn(
        r'    val commit = try \{.*?    val buildtime = \(System.currentTimeMillis\(\) / 1000L\).toString\(\)',
        f'    val commit = "{source["commit"]}"\n    val buildtime = "{source["timestamp"]}"',
        text, flags=re.S)
    if count != 1:
        raise ValueError("Pinned build metadata contract changed")
    common.write_text(text, encoding="utf-8", newline="\n")
    settings = work / "settings.gradle.kts"
    text = settings.read_text(encoding="utf-8")
    # Retain both upstream Minecraft variants and the original active variant:
    # Stonecutter resource generation observes it even for an explicit subtask.
    text = replace_once(text, 'match("1.20.1", "fabric", "forge")', 'match("1.20.1", "fabric")')
    text = replace_once(text, 'match("1.21.1", "fabric", "neoforge")', 'match("1.21.1", "fabric")')
    settings.write_text(text, encoding="utf-8", newline="\n")


def metadata(path, mod_id, minecraft, version=None, commit=None, java=None):
    path = Path(path)
    with zipfile.ZipFile(path) as jar:
        mod = json.loads(jar.read("fabric.mod.json"))
        if mod.get("id") != mod_id:
            raise ValueError("Wrong mod identity: " + path.name)
        declared = mod.get("depends", {}).get("minecraft", [])
        if isinstance(declared, str):
            declared = [declared]
        if minecraft not in declared:
            raise ValueError("Wrong Minecraft target: " + path.name)
        if version and mod.get("version") != version:
            raise ValueError("Wrong mod version: " + path.name)
        if commit and mod.get("custom", {}).get("commit") != commit:
            raise ValueError("Wrong source commit: " + path.name)
        classes = sorted(n for n in jar.namelist() if n.endswith(".class") and not n.startswith("META-INF/versions/"))
        majors = sorted({int.from_bytes(jar.read(n)[6:8], "big") for n in classes})
        if not classes or (java and majors != [java + 44]):
            raise ValueError("Wrong Java bytecode target: " + path.name)
        if mod_id == "voxy":
            required = ["me/cortex/voxy/common/world/WorldEngine.class",
                        "me/cortex/voxy/client/core/VoxyRenderSystem.class", "voxy.accesswidener"]
            if any(n not in jar.namelist() for n in required):
                raise ValueError("Incomplete Voxy production content")
        resources = sorted(n for n in jar.namelist() if not n.endswith(("/", ".class"))
                           and not n.startswith("META-INF/") and n != "fabric.mod.json")
        nested = {n: hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist()
                  if n.startswith("META-INF/jars/") and n.endswith(".jar")}
        resource_lines = "".join(n + " " + hashlib.sha256(jar.read(n)).hexdigest() + "\n" for n in resources)
        inventory = hashlib.sha256(("\n".join(classes) + "\n").encode()).hexdigest()
    return {"filename": path.name, "sha256": sha256(path),
            "sha512": hashlib.sha512(path.read_bytes()).hexdigest(),
            "metadata": mod, "classCount": len(classes), "classMajors": majors,
            "classInventorySha256": inventory,
            "resourcesSha256": hashlib.sha256(resource_lines.encode()).hexdigest(),
            "nestedSha256": nested}


def recipe_sha256():
    return hashlib.sha256(Path(__file__).read_bytes()
                          + Path(__file__).with_name("community-voxy-build.gradle").read_bytes()).hexdigest()


def validate_source_content(info, key, minecraft):
    classes, resources = SOURCE_CONTENT[key + "-" + minecraft]
    if info["classInventorySha256"] != classes or info["resourcesSha256"] != resources:
        raise ValueError("Pinned source build production class/resource contract changed")
    nested = hashlib.sha256(json.dumps(info["nestedSha256"], sort_keys=True).encode()).hexdigest()
    if nested != NESTED_INVENTORY:
        raise ValueError("Pinned source build embedded libraries changed")


def build_source(key, minecraft, destination, work, gradle_user_home=None):
    source = SOURCES[key]
    result = destination / f"voxy-0.2.15-beta+{minecraft}-fabric.jar"
    provenance = destination / "voxy-source-build.json"
    recipe = recipe_sha256()
    if result.is_file() and provenance.is_file():
        cached = json.loads(provenance.read_text(encoding="utf-8"))
        if (cached.get("sha256") == sha256(result) and cached.get("sourceSha256") == source["sha256"]
                and cached.get("recipeSha256") == recipe):
            validate_source_content(metadata(result, "voxy", minecraft, commit=source["commit"], java=21), key, minecraft)
            return cached
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    version = subprocess.run([java, "-version"], text=True, capture_output=True, check=True)
    if not re.search(r'version "21(?:\.|\")', version.stdout + version.stderr):
        raise ValueError("Pinned Voxy source builds require Java 21")
    repository = source["repository"].removeprefix("https://github.com/")
    url = "https://api.github.com/repos/" + repository + "/zipball/" + source["commit"]
    archive = work.parent / (key + ".zip")
    download(url, archive, source["sha256"], "application/vnd.github+json")
    extract_source(archive, work)
    patch_build(work, source, minecraft)
    command = [str(work / "gradlew.bat")] if os.name == "nt" else ["bash", str(work / "gradlew")]
    command += ["-Dfabric.loom.ci=true", "-I", str(Path(__file__).with_name("community-voxy-build.gradle").resolve()),
                ":" + minecraft + "-fabric:clean", ":" + minecraft + "-fabric:remapJar",
                "--no-daemon", "--console=plain", "--max-workers=2"]
    build_home = Path(gradle_user_home).resolve() if gradle_user_home else work / ".gradle-user-home"
    command += ["--gradle-user-home", str(build_home)]
    subprocess.run(command, cwd=work, env=dict(os.environ, CI="true"), check=True)
    artifact = work / "versions" / (minecraft + "-fabric") / "build/libs" / result.name
    info = metadata(artifact, "voxy", minecraft, commit=source["commit"], java=21)
    validate_source_content(info, key, minecraft)
    shutil.copyfile(artifact, result)
    info.update({"kind": "source-build", "published": False, "repository": source["repository"],
                 "commit": source["commit"], "sourceUrl": url, "sourceSha256": source["sha256"],
                 "recipeSha256": recipe, "java": 21, "loom": "1.16.2", "stonecutter": "0.9.4",
                 "buildPatches": ["fixed commit and commit time metadata", "select Fabric loader projects",
                                  "stable ZIP order and timestamps", "Loom CI mode skips optional source downloads"]})
    provenance.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    return info


def write_inputs(destination, entries):
    lines = {mod: f"vss{mod.title()}Jar={(destination / entry['filename']).resolve().as_posix()}\n"
             for mod, entry in entries.items()}
    (destination / "inputs.properties").write_text("".join(lines.values()), encoding="utf-8")
    (destination / "inputs-no-iris.properties").write_text(
        "".join(line for mod, line in lines.items() if mod != "iris"), encoding="utf-8")


def validate_pairing(entries, minecraft):
    """Check the real Iris declaration, not a filename or an assumed API family."""
    sodium = entries["sodium"]["metadata"]["version"]
    expected_branch = "0.5." if minecraft == "1.20.1" else "0.8."
    if not sodium.startswith(expected_branch):
        raise ValueError("Wrong Sodium API family for community Voxy")
    if "iris" in entries:
        declaration = entries["iris"]["metadata"].get("depends", {}).get("sodium", [])
        if isinstance(declaration, str):
            declaration = [declaration]
        if expected_branch + "x" not in declaration:
            raise ValueError("Iris does not declare this Sodium API family")


def fetch(variant, base, source_directory=None, gradle_user_home=None):
    minecraft, key = VARIANTS[variant]
    destination = (Path(base) / variant).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    entries = {}
    if key in RELEASES:
        release = RELEASES[key]
        repository = release["repository"].removeprefix("https://github.com/")
        url = "https://api.github.com/repos/" + repository + "/releases/assets/" + str(release["asset"])
        path = download(url, destination / release["filename"], release["sha256"])
        entries["voxy"] = metadata(path, "voxy", minecraft, commit=release["metadataCommit"], java=release["java"])
        entries["voxy"].update({**release, "kind": "github-release", "published": True, "url": url})
    else:
        work = (Path(source_directory) if source_directory else Path(base) / "source-build") / variant
        entries["voxy"] = build_source(key, minecraft, destination, work.resolve(), gradle_user_home)
    combination = {"sodium": "sodium120", "iris": "iris120"} if minecraft == "1.20.1" else {
        "sodium": "sodium121alpha" if key == "m3" else "sodium121", "iris": "iris121"}
    for mod, dependency in combination.items():
        spec = DEPENDENCIES[dependency]
        path = download(spec["url"], destination / spec["filename"], spec["sha256"])
        entries[mod] = metadata(path, mod, minecraft, version=spec["version"])
        entries[mod].update({**spec, "kind": "fixed-binary"})
    validate_pairing(entries, minecraft)
    write_inputs(destination, entries)
    document = {"schemaVersion": 1, "variant": variant, "minecraft": minecraft, "mods": entries,
                "gameplayValidated": False, "externalModsBundled": False}
    for name in ("releases.json", "provenance.json"):
        (destination / name).write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return document


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=VARIANTS, action="append",
                        help="Repeat to select variants; omitted selects all six")
    parser.add_argument("--destination", type=Path, default=Path("external/community-compat"))
    parser.add_argument("--source-directory", type=Path)
    parser.add_argument("--gradle-user-home", type=Path)
    args = parser.parse_args()
    for selected in args.variant or VARIANTS:
        print(json.dumps(fetch(selected, args.destination, args.source_directory, args.gradle_user_home), indent=2))
