"""Install five tested CI libraries and record their exact Rust source identity."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


LIBRARIES = {
    "windows-x86_64": "vss_native_core.dll",
    "linux-x86_64": "libvss_native_core.so",
    "linux-aarch64": "libvss_native_core.so",
    "macos-x86_64": "libvss_native_core.dylib",
    "macos-aarch64": "libvss_native_core.dylib",
}


def native_source_files(root):
    core = root / "tools/rust/vss-native-core"
    files = [core / "Cargo.toml", core / "Cargo.lock"]
    files += list((core / "src").rglob("*.rs"))
    if (core / "build.rs").is_file():
        files.append(core / "build.rs")
    return sorted(files, key=lambda p: p.relative_to(root).as_posix())


def native_source_digest(root):
    digest = hashlib.sha256()
    for path in native_source_files(root):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
        digest.update(b"\0")
    return digest.hexdigest()


def install(root, run_id):
    def command(*args):
        return subprocess.check_output(args, cwd=root)

    run = json.loads(command("gh", "run", "view", str(run_id), "--json", "headSha,conclusion,url,workflowName"))
    if run["conclusion"] != "success" or run["workflowName"] != "Native prediction core":
        raise ValueError("Expected a successful native workflow run")
    commit = run["headSha"]
    for path in native_source_files(root):
        relative = path.relative_to(root).as_posix()
        expected = command("git", "show", commit + ":" + relative).replace(b"\r\n", b"\n")
        if path.read_bytes().replace(b"\r\n", b"\n") != expected:
            raise ValueError("Local Rust source differs from tested CI commit: " + relative)
    files = {}
    destination = root / "src/main/resources/META-INF/vss-natives"
    with tempfile.TemporaryDirectory(prefix="vss-natives-") as temporary:
        staging = Path(temporary)
        command("gh", "run", "download", str(run_id), "--dir", str(staging))
        sources = []
        for platform, name in LIBRARIES.items():
            source = staging / ("native-" + platform) / name
            data = source.read_bytes()
            if b"find_top_surface" not in data:
                raise ValueError("Native library is missing the supported height router: " + platform)
            sources.append((source, destination / platform / name))
            files[platform + "/" + name] = hashlib.sha256(data).hexdigest()
        # Validate every input before replacing any bundled library.
        for source, target in sources:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    report = {"schema": 1, "commit": commit, "workflowUrl": run["url"],
              "sourceSha256": native_source_digest(root), "libraries": files}
    (destination.parent / "vss-native-build.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=int, required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    install(args.root.resolve(), args.run)
