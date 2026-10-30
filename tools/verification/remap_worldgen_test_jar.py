#!/usr/bin/env python3
"""Prepare one real Fabric dependency for offline Mojmap JUnit (never for distribution)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import zipfile


ASM_ARTIFACTS = {
    "asm": "org/objectweb/asm/ClassReader.class",
    "asm-tree": "org/objectweb/asm/tree/ClassNode.class",
    "asm-commons": "org/objectweb/asm/commons/ClassRemapper.class",
    "asm-util": "org/objectweb/asm/util/CheckClassAdapter.class",
    "asm-analysis": "org/objectweb/asm/tree/analysis/Analyzer.class",
}


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def only(root, pattern):
    paths = list(Path(root).glob(pattern))
    if len(paths) != 1:
        raise RuntimeError(f"Expected exactly one cached artifact: {root}/{pattern}; found {len(paths)}")
    return paths[0]


def cached_asm_classpath(modules):
    """Choose one complete ASM release, with no mixed modules or source JARs."""
    available = {}
    for artifact in ASM_ARTIFACTS:
        root = Path(modules) / "org.ow2.asm" / artifact
        available[artifact] = {entry.name for entry in root.iterdir()
                               if entry.is_dir() and re.fullmatch(r"\d+(?:\.\d+)*", entry.name)
                               and tuple(map(int, entry.name.split("."))) >= (9, 7, 1)
                               and list(entry.glob("*/" + artifact + "-" + entry.name + ".jar"))} if root.is_dir() else set()
    common = set.intersection(*available.values())
    if not common:
        raise RuntimeError("No complete matching ASM release >= 9.7.1 is cached: "
                           + "; ".join(name + "=" + ",".join(sorted(versions))
                                       for name, versions in available.items()))
    # TinyRemapper 0.13.0 requires ASM 9.7.1 or later. Loom may resolve a newer
    # release; select the newest complete numeric release already in its cache.
    version = max(common, key=lambda value: tuple(map(int, value.split("."))))
    paths = []
    for artifact, anchor in ASM_ARTIFACTS.items():
        path = only(Path(modules) / "org.ow2.asm" / artifact / version,
                    "*/" + artifact + "-" + version + ".jar")
        with zipfile.ZipFile(path) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
            attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
            if attributes.get("Implementation-Version") != version or anchor not in jar.namelist():
                raise RuntimeError("Cached ASM binary does not match " + artifact + " " + version + ": " + str(path))
        paths.append(path)
    return version, paths


def intermediary_linkages(data):
    """Inspect linkage constants; Mixin target annotation strings are inert in offline JUnit."""
    count = struct.unpack_from(">H", data, 8)[0]; offset = 10; index = 1
    utf8 = {}; references = []
    while index < count:
        tag = data[offset]; offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]; offset += 2
            utf8[index] = data[offset:offset + size]; offset += size
        elif tag in (3, 4): offset += 4
        elif tag in (5, 6): offset += 8; index += 1
        elif tag in (7, 16):
            references.append(struct.unpack_from(">H", data, offset)[0]); offset += 2
        elif tag in (8, 19, 20): offset += 2
        elif tag in (9, 10, 11, 17, 18): offset += 4
        elif tag == 12:
            references.append(struct.unpack_from(">H", data, offset + 2)[0]); offset += 4
        elif tag == 15: offset += 3
        else: raise ValueError(f"Unknown constant pool tag {tag}")
        index += 1
    return [utf8[index].decode("utf-8", errors="replace") for index in references
            if b"net/minecraft/class_" in utf8.get(index, b"")]


def prepare(source, output, minecraft, cache, java):
    source = Path(source).resolve(); output = Path(output).resolve()
    if source == output:
        raise ValueError("Offline remapping must preserve the original downloaded JAR")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as archive:
        intermediary = any(b"net/minecraft/class_" in archive.read(name)
                for name in archive.namelist() if name.endswith(".class"))
    mapping = None
    remapper = None
    if intermediary:
        loom = cache / "fabric-loom"
        mapping = only(loom / minecraft, "loom.mappings.*/mappings.tiny")
        game = only(loom / "minecraftMaven/net/minecraft/minecraft-merged-intermediary",
                    minecraft + "-*/minecraft-merged-intermediary-*.jar")
        modules = cache / "modules-2/files-2.1"
        cp = [only(modules / "net.fabricmc/tiny-remapper/0.13.0", "*/*.jar"),
              only(modules / "net.fabricmc/mapping-io/0.8.0", "*/*.jar")]
        asm_version, asm = cached_asm_classpath(modules)
        cp.extend(asm)
        remapper = {"asm_version": asm_version,
                    "classpath": [{"filename": path.name, "sha256": sha256(path)} for path in cp]}
        # TinyRemapper needs the source-namespace game hierarchy to remap inherited methods.
        # Work on a unique output; the CLI cannot safely replace an open existing ZIP.
        temporary = output.with_name(output.name + ".pending.jar")
        if temporary.exists(): temporary.unlink()
        subprocess.run([str(java), "-cp", os.pathsep.join(map(str, cp)),
                        "net.fabricmc.tinyremapper.Main", str(source), str(temporary),
                        str(mapping), "intermediary", "named", str(game)], check=True)
        temporary.replace(output)
    else:
        shutil.copyfile(source, output)
    with zipfile.ZipFile(output) as archive:
        leftovers = [name for name in archive.namelist() if name.endswith(".class")
                and intermediary_linkages(archive.read(name))]
    if leftovers:
        raise RuntimeError(f"Unmapped Minecraft references remain in {leftovers[:4]}")
    result = {"minecraft": minecraft, "source": str(source), "source_sha256": sha256(source),
              "junit_jar": str(output), "junit_sha256": sha256(output),
              "namespace": "named", "remapped": intermediary}
    if mapping is not None:
        result.update(mapping=str(mapping), mapping_sha256=sha256(mapping), remapper=remapper)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True); parser.add_argument("--output", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches")
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java") or "java"))
    args = parser.parse_args()
    print(json.dumps(prepare(args.input, args.output, args.minecraft, args.cache, args.java), indent=2))
