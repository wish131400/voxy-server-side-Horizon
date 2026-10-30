"""Fetch pinned Fabric equipment releases for optional compatibility contracts."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen
from zipfile import ZipFile


RELEASES = {
    "1.20.1": {
        "trinkets": ("AHxQGtuC", "bedf97c87c5e556416410267108ad358b32806448be24ef8ae1a79ac63b78b48b9c851c00c845b8aedfc7805601385420716b9e65326fdab21340e8ba3cc4274"),
        "accessories": ("mee7rWXz", "0192a74390679bc294922281dd021fa0686303dbfda25c86b0d5f5771b392221854816927b8865fe7c29100110a16b460dba2d6218c3202b150ff75515425ce6"),
    },
    "1.21.1": {
        "trinkets": ("JagCscwi", "3ea846c945a0559696501ff65b373c8ee8fd9b394604e9910b4ed710c3e07cadc674a615a2c3b385951a42253a418201975df951b3100053ed39afadc70221c9"),
        "accessories": ("YZ9RWTNl", "4b83f2f00db833ac7dc7cabe2f74c9df7067c35170b9e7aeb794a85038f7b34bc4d40ce92e16e142358c5cf4b6f3ca3866155592c4dc80beb818f6e28bfa9799"),
    },
}


def fetch(minecraft, destination):
    destination.mkdir(parents=True, exist_ok=True)
    result = {}
    for mod, (release, expected) in RELEASES[minecraft].items():
        request = Request("https://api.modrinth.com/v2/version/" + release,
                          headers={"User-Agent": "VSS-Horizon/equipment-compatibility"})
        with urlopen(request, timeout=60) as response:
            metadata = json.load(response)
        if minecraft not in metadata["game_versions"] or "fabric" not in metadata["loaders"]:
            raise ValueError("Pinned equipment release targets the wrong platform")
        file = next(value for value in metadata["files"] if value["primary"])
        if file["hashes"]["sha512"] != expected:
            raise ValueError("Pinned equipment release checksum has changed")
        filename = file["filename"]
        if Path(filename).name != filename or not filename.endswith(".jar"):
            raise ValueError("Invalid release filename")
        path = destination / filename
        if not path.is_file() or hashlib.sha512(path.read_bytes()).hexdigest() != expected:
            with urlopen(Request(file["url"], headers={"User-Agent": "VSS-Horizon/equipment-compatibility"}), timeout=60) as response:
                data = response.read()
            if hashlib.sha512(data).hexdigest() != expected:
                raise ValueError("Equipment release download checksum mismatch")
            path.write_bytes(data)
        with ZipFile(path) as jar:
            fabric = json.loads(jar.read("fabric.mod.json"))
            if fabric["id"] != mod:
                raise ValueError("Unexpected Fabric equipment mod identity")
        result[mod] = {"release": release, "version": metadata["version_number"], "filename": filename,
                       "sha512": expected, "url": file["url"]}
        print(mod + ": " + str(path))
    (destination / "releases.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (destination / "inputs.properties").write_text("".join(
        f"vss{mod.title()}Jar={Path(destination / entry['filename']).resolve().as_posix()}\n"
        for mod, entry in result.items()), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True, choices=RELEASES)
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    fetch(args.minecraft, args.destination or Path("external/equipment-compat") / args.minecraft)
