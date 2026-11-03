"""Derive a Fabric dependency compatibility release from known artifacts.

Keeps the original code/source build identity. Records this transform separately
and checks that native libraries, executable instructions and resources stay
identical. Single Redirect.at arrays use the backwards compatible scalar form.
Use a full source build and verify_release.py when executable code has changed.
"""
import argparse
from copy import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

from verify_release import manifest_attributes
from mixin_annotation_baseline import scalar_redirect_at


CONFIG_FILES = ("gradle.properties", "gradle/minecraft-targets.gradle", "build.gradle",
                "fabric-legacy.gradle", "fabric-next.gradle", "src/main/resources/fabric.mod.json")
RECORD = "META-INF/vss-dependency-baseline.json"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def minima(root):
    properties = dict(re.findall(r"(?m)^([a-z_]+)=(.+)$", (root / "gradle.properties").read_text()))
    result = {}
    for target, row in re.findall(r"'([^']+)':\s*\[([^\n]+)\]", (root / "gradle/minecraft-targets.gradle").read_text()):
        fields = dict(re.findall(r"(\w+):\s*'([^']+)'", row))
        result[target] = {"fabricloader": ">=" + fields.get("loaderMin", properties["loader_min_version"].strip()),
                          "fabric-api": ">=" + fields["apiMin"]}
    return result


def append_manifest(original, attributes):
    # Preserve the exact original attribute bytes, including folded lines.
    # The main section must end with a blank line; continuation width is bytes.
    newline = b"\r\n" if b"\r\n" in original else b"\n"
    sections = original.split(newline + newline, 1)
    main = sections[0].rstrip(b"\r\n") + newline
    for key, value in attributes.items():
        data = (key + ": " + value).encode("ascii")
        main += data[:70] + newline
        for offset in range(70, len(data), 69):
            main += b" " + data[offset:offset + 69] + newline
    return main + newline + (sections[1] if len(sections) > 1 else b"")


def derive(original, destination, minimum, build_id, config_snapshot):
    original_sha = sha(original.read_bytes())
    with zipfile.ZipFile(original) as source:
        if source.testzip() is not None:
            raise ValueError("Corrupt base JAR: " + original.name)
        if any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I) for name in source.namelist()):
            raise ValueError("Signed base JAR needs a new source release")
        metadata = json.loads(source.read("fabric.mod.json"))
        if metadata["id"] != "vss" or original.name != "vss-" + metadata["version"] + ".jar":
            raise ValueError("Unexpected base mod identity")
        if RECORD in source.namelist():
            raise ValueError("Select the original code release, not an already derived JAR")
        before = {key: metadata["depends"][key] for key in minimum}
        metadata["depends"].update(minimum)
        original_manifest = source.read("META-INF/MANIFEST.MF")
        identity = manifest_attributes(original_manifest)
        record = {"schema": 1, "kind": "dependency-and-mixin-annotation-compatibility", "buildId": build_id,
                  "minecraft": metadata["depends"]["minecraft"], "baseJarSha256": original_sha,
                  "baseBuildId": identity["VSS-Build-Id"],
                  "baseSourceSha256": identity["VSS-Source-SHA256"],
                  "runtimeMinimumBefore": before, "runtimeMinimum": minimum,
                  "dependencyConfigSha256": config_snapshot}
        replacements = {
            "fabric.mod.json": (json.dumps(metadata, indent=2) + "\n").encode(),
            "META-INF/MANIFEST.MF": append_manifest(original_manifest, {
                "VSS-Dependency-Build-Id": build_id,
                "VSS-Dependency-Update": "metadata-and-mixin-annotations",
                "VSS-Base-Jar-SHA256": original_sha}),
            RECORD: (json.dumps(record, indent=2) + "\n").encode(),
        }
        annotation_changes = {}
        for name in source.namelist():
            if name.startswith("dev/xantha/vss/mixin/") and name.endswith(".class"):
                compatible, changed = scalar_redirect_at(source.read(name))
                if changed:
                    replacements[name] = compatible
                    annotation_changes[name] = changed
        record["mixinAnnotationChanges"] = annotation_changes
        record["methodCodeUnchanged"] = True
        replacements[RECORD] = (json.dumps(record, indent=2) + "\n").encode()
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
            output.comment = source.comment
            for item in source.infolist():
                output.writestr(copy(item), replacements.get(item.filename, source.read(item.filename)))
            output.writestr(RECORD, replacements[RECORD])
        with zipfile.ZipFile(destination) as final:
            if final.testzip() is not None or set(final.namelist()) != set(source.namelist()) | {RECORD}:
                raise ValueError("Derived archive inventory mismatch")
            for name in source.namelist():
                if name not in replacements and final.read(name) != source.read(name):
                    raise ValueError("Executable or resource changed: " + name)
                if name in annotation_changes and final.read(name) != scalar_redirect_at(source.read(name))[0]:
                    raise ValueError("Unexpected class transform: " + name)
            actual = json.loads(final.read("fabric.mod.json"))
            expected = json.loads(source.read("fabric.mod.json"))
            expected["depends"].update(minimum)
            if actual != expected:
                raise ValueError("Dependency-only metadata contract violated")
            final_identity = manifest_attributes(final.read("META-INF/MANIFEST.MF"))
            if any(final_identity.get(key) != value for key, value in identity.items()):
                raise ValueError("Original code build identity changed")
        record["unchangedClassCount"] = sum(name.endswith(".class") for name in source.namelist()) - len(annotation_changes)
        record["unchangedNativeLibraryCount"] = sum(name.startswith("META-INF/vss-natives/") for name in source.namelist()
                                                   if name.endswith((".dll", ".so", ".dylib")))
        return record


def publish(root, base, output, build_id):
    manifest = json.loads((base / "manifest.json").read_text())
    target_minima = minima(root)
    snapshot = {name: sha((root / name).read_bytes()) for name in CONFIG_FILES}
    release = {"schema": 1, "buildId": build_id, "kind": "dependency-and-mixin-annotation-compatibility",
               "createdAtUtc": datetime.now(timezone.utc).isoformat(),
               "configSha256": snapshot, "artifacts": []}
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as temporary:
        stage = Path(temporary)
        for artifact in manifest["artifacts"]:
            original = base / artifact["jar"]
            if sha(original.read_bytes()) != artifact["sha256"]:
                raise ValueError("Base JAR does not match its manifest: " + original.name)
            with zipfile.ZipFile(original) as jar:
                target = json.loads(jar.read("fabric.mod.json"))["depends"]["minecraft"]
            destination = stage / original.name
            record = derive(original, destination, target_minima[target], build_id, snapshot)
            artifact.update(sha256=sha(destination.read_bytes()), bytes=destination.stat().st_size,
                            runtimeMinimum=target_minima[target], dependencyUpdate=record)
            release["artifacts"].append({"jar": destination.name, **record, "sha256": artifact["sha256"]})
        manifest["dependencyUpdate"] = {key: value for key, value in release.items() if key != "artifacts"}
        manifest["createdAtUtc"] = release["createdAtUtc"]
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        (stage / "dependency-snapshot.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(base / "source-snapshot.json", stage / "source-snapshot.json")
        bundle = stage / ("vss-" + manifest["version"] + ".zip")
        with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as zip:
            for item in manifest["artifacts"]:
                name = item["jar"]
                target = item["dependencyUpdate"]["minecraft"]
                zip.write(stage / name, "fabric-" + target + "/" + name)
            for name in ("manifest.json", "source-snapshot.json", "dependency-snapshot.json"):
                zip.write(stage / name, name)
            zip.write(root / "LICENSE", "LICENSE")
            zip.write(root / "README.md", "README.md")
        names = [item["jar"] for item in manifest["artifacts"]] + [bundle.name]
        (stage / "SHA256SUMS.txt").write_text("".join(sha((stage / name).read_bytes()) + "  " + name + "\n" for name in names))
        # Validate every output before making any of the staged artifacts visible.
        for path in stage.iterdir():
            path.replace(output / path.name)
    return release


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--base-directory", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--build-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.build_id):
        parser.error("Build ID must be a simple identifier")
    result = publish(args.root.resolve(), args.base_directory.resolve(), args.output_directory.resolve(), args.build_id)
    print(json.dumps(result, indent=2))
