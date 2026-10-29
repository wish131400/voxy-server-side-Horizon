"""Verify and archive a Fabric release inside the independent Horizon project."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile

from verify_release import verify, source_files, source_digest


def publish(source, build_id, targets):
    source = source.resolve()
    properties = dict(line.split("=", 1) for line in (source / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    version = properties["vss_version"]
    targets = targets or [properties["minecraft_version"]]
    jars = [source / "lib" / ("vss-" + version + "-fabric-" + target + ".jar") for target in targets]
    artifacts = [verify(jar, "fabric", build_id, source, target) for jar, target in zip(jars, targets)]
    snapshot = {p.relative_to(source).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in source_files(source)}
    manifest = {"version": version, "buildId": build_id, "minecraft": targets,
                "sourceSha256": source_digest(source),
                "createdAtUtc": datetime.now(timezone.utc).isoformat(), "artifacts": artifacts}
    # Keep previous verified outputs recoverable and never touch the sibling ports.
    archive_dir = source / "lib" / "archives" / build_id
    archive_dir.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(dir=source / "lib") as temporary:
        staging = Path(temporary)
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        (staging / "source-snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")
        bundle = staging / ("vss-" + version + ".zip")
        with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as output:
            for jar, target in zip(jars, targets):
                output.write(jar, "fabric-" + target + "/" + jar.name)
            output.write(staging / "manifest.json", "manifest.json")
            output.write(staging / "source-snapshot.json", "source-snapshot.json")
            output.write(source / "LICENSE", "LICENSE")
            output.write(source / "README.md", "README.md")
        import shutil
        for path in [*jars, bundle, staging / "manifest.json", staging / "source-snapshot.json"]:
            shutil.copy2(path, archive_dir / path.name)
        bundle.replace(source / "lib" / bundle.name)
        (staging / "manifest.json").replace(source / "lib" / "manifest.json")
        (staging / "source-snapshot.json").replace(source / "lib" / "source-snapshot.json")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--minecraft", action="append", choices=["1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"])
    args = parser.parse_args()
    if not args.build_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_." for c in args.build_id):
        parser.error("Build ID must be a simple directory name")
    publish(args.source, args.build_id, args.minecraft)
