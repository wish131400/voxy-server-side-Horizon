"""Fetch pinned Fabric releases for bytecode contracts and client smoke runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen


RELEASES = {
    "1.21.11": {"voxy": "H3w2nVdU", "iris": "ka9PDlsN", "sodium": "NFkjnzWE"},
    "26.1": {"voxy": "tIs0IXaN", "iris": "MwcLS51S", "sodium": "uGvVQBnw"},
    "26.1.1": {"voxy": "tIs0IXaN", "iris": "MwcLS51S", "sodium": "uGvVQBnw"},
    "26.1.2": {"voxy": "Zt3LPI0b", "iris": "sZbVsl2Q", "sodium": "vf7UgZpC"},
    "26.2": {"voxy": "LzyXnE51", "iris": "gxZWWnKH", "sodium": "xJZxADzI"},
    "26.3": {"voxy": "iIrM8ZKQ", "iris": "vTN4NRGW", "sodium": "g6ju16VJ"},
}


def fetch(minecraft, destination, github_output=False):
    destination.mkdir(parents=True, exist_ok=True)
    result = {}
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
