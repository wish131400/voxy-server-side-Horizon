"""Fetch pinned Fabric releases for bytecode contracts and client smoke runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
import zipfile


RELEASES = {
    "1.20.1": {"iris": "s5eFLITc", "sodium": "OihdIimA"},
    "1.21.1": {"iris": "zsoi0dso", "sodium": "u1OEbNKx"},
    "1.21.11": {"voxy": "H3w2nVdU", "iris": "ka9PDlsN", "sodium": "NFkjnzWE"},
    "26.1": {"voxy": "tIs0IXaN", "iris": "MwcLS51S", "sodium": "uGvVQBnw"},
    "26.1.1": {"voxy": "tIs0IXaN", "iris": "MwcLS51S", "sodium": "uGvVQBnw"},
    "26.1.2": {"voxy": "Zt3LPI0b", "iris": "sZbVsl2Q", "sodium": "vf7UgZpC"},
    "26.2": {"voxy": "LzyXnE51", "iris": "gxZWWnKH", "sodium": "xJZxADzI"},
    "26.3": {"voxy": "iIrM8ZKQ", "iris": "vTN4NRGW", "sodium": "g6ju16VJ"},
}

LEGACY_VOXY = {
    "filename": "voxy-0.2.14-alpha-1.20.1.jar",
    "url": "https://api.github.com/repos/DA-57EVE/voxy-1.20.1-Fabric/releases/assets/454557570",
    "sha256": "0ba8f7d29195146d70a3373271b53f68a94803d2d8c31ca9e64ca2b305b5638b",
    "release": "https://github.com/DA-57EVE/voxy-1.20.1-Fabric/releases/tag/mod",
    "commit": "69b19feebf377dd2b6024398f66a6b36cc2515cc",
}


def fetch_legacy_voxy(destination):
    path = destination / LEGACY_VOXY["filename"]
    expected = LEGACY_VOXY["sha256"]
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        request = Request(LEGACY_VOXY["url"], headers={
            "User-Agent": "VSS-Horizon/Fabric-validation", "Accept": "application/octet-stream"})
        with urlopen(request, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Pinned Fabric 1.20.1 Voxy checksum mismatch")
        path.write_bytes(data)
    with zipfile.ZipFile(path) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata.get("id") != "voxy" or metadata.get("depends", {}).get("minecraft") != ["1.20.1"]:
            raise ValueError("Pinned community Voxy has the wrong Minecraft target")
        if metadata.get("custom", {}).get("commit") != LEGACY_VOXY["commit"]:
            raise ValueError("Pinned community Voxy has the wrong source commit")
        major = int.from_bytes(jar.read("me/cortex/voxy/client/core/VoxyRenderSystem.class")[6:8], "big")
        if major != 65:
            raise ValueError("Pinned community Voxy has the wrong Java class version")
    return dict(LEGACY_VOXY, kind="github-release", java=21, published=True,
                sha512=hashlib.sha512(path.read_bytes()).hexdigest())


def fetch(minecraft, destination, github_output=False):
    destination.mkdir(parents=True, exist_ok=True)
    result = {}
    if minecraft == "1.20.1":
        result["voxy"] = fetch_legacy_voxy(destination)
        print("voxy: " + str(destination / result["voxy"]["filename"]))
    elif minecraft == "1.21.1":
        from build_legacy_voxy import build
        result["voxy"] = build(destination)
        print("voxy: " + str(destination / result["voxy"]["filename"]))
    for mod, release in RELEASES[minecraft].items():
        request = Request("https://api.modrinth.com/v2/version/" + release,
                          headers={"User-Agent": "VSS-Horizon/Fabric-validation"})
        with urlopen(request, timeout=60) as response:
            metadata = json.load(response)
        if minecraft not in metadata["game_versions"] or "fabric" not in metadata["loaders"]:
            raise ValueError(f"Pinned {mod} release does not declare Fabric {minecraft}")
        primary = next(file for file in metadata["files"] if file["primary"])
        filename = primary["filename"]
        if Path(filename).name != filename or not filename.endswith(".jar"):
            raise ValueError("Invalid release filename")
        path = destination / filename
        expected = primary["hashes"]["sha512"]
        if not path.is_file() or hashlib.sha512(path.read_bytes()).hexdigest() != expected:
            with urlopen(Request(primary["url"], headers={"User-Agent": "VSS-Horizon/Fabric-validation"}),
                         timeout=120) as response:
                data = response.read()
            if hashlib.sha512(data).hexdigest() != expected:
                raise ValueError("Release checksum mismatch: " + filename)
            path.write_bytes(data)
        result[mod] = {"release": release, "filename": filename, "sha512": expected}
        print(f"{mod}: {path}")
    (destination / "releases.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    # A Gradle project properties file avoids shell quoting of '+' in filenames.
    (destination / "inputs.properties").write_text("".join(
        f"vss{mod.title()}Jar={Path(destination / entry['filename']).resolve().as_posix()}\n"
        for mod, entry in result.items()), encoding="utf-8")
    if github_output:
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            for mod, entry in result.items():
                output.write(f"{mod}_jar={(destination / entry['filename']).resolve().as_posix()}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True, choices=RELEASES)
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--github-output", action="store_true")
    options = parser.parse_args()
    fetch(options.minecraft, options.destination or Path("external/compat") / options.minecraft,
          options.github_output)
