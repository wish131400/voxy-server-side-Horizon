#!/usr/bin/env python3
"""Fetch pinned, unmodified Fabric FTB releases for optional compatibility checks."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen
import zipfile

# Official FTB Maven artifacts; update a version only with its original-file checksum.
RELEASES = {
    "1.20.1": {
        "chunks": {
            "artifact": "ftb-chunks-fabric",
            "version": "2001.3.8",
            "filename": "ftb-chunks-fabric-2001.3.8.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-fabric/2001.3.8/ftb-chunks-fabric-2001.3.8.jar",
            "sha256": "f1a15d9e8bb71d9e69a51106ff1b58a8fe7621cb3f935a819f5a7419c029d2df"
        },
        "essentials": {
            "artifact": "ftb-essentials-fabric",
            "version": "2001.2.4",
            "filename": "ftb-essentials-fabric-2001.2.4.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-essentials-fabric/2001.2.4/ftb-essentials-fabric-2001.2.4.jar",
            "sha256": "846a3101b1fd5cb2084eeaf695fdbdd022f0b82b564c88c34e55cd00fcd17e0b"
        }
    },
    "1.21.1": {
        "chunks": {
            "artifact": "ftb-chunks-fabric",
            "version": "2101.1.22",
            "filename": "ftb-chunks-fabric-2101.1.22.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-fabric/2101.1.22/ftb-chunks-fabric-2101.1.22.jar",
            "sha256": "6b783610069b64f7b28aa0675a707b3cf171364b6647e0c7b406cf6ca7674215"
        },
        "essentials": {
            "artifact": "ftb-essentials-fabric",
            "version": "2101.1.10",
            "filename": "ftb-essentials-fabric-2101.1.10.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-essentials-fabric/2101.1.10/ftb-essentials-fabric-2101.1.10.jar",
            "sha256": "9e85afd6a77ed7aaae770d6d3e019fcc753c0e286aa6328558b03bf0c66ca12e"
        }
    },
    "1.21.11": {
        "chunks": {
            "artifact": "ftb-chunks-fabric",
            "version": "2111.1.1",
            "filename": "ftb-chunks-fabric-2111.1.1.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-fabric/2111.1.1/ftb-chunks-fabric-2111.1.1.jar",
            "sha256": "b4a30aeaf832ec31f787f31581d713b9f57e47c7bdfe665ac87617c046d26271"
        },
        "essentials": {
            "artifact": "ftb-essentials-fabric",
            "version": "2111.1.1",
            "filename": "ftb-essentials-fabric-2111.1.1.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-essentials-fabric/2111.1.1/ftb-essentials-fabric-2111.1.1.jar",
            "sha256": "2b33211494012ac7194d8fbf471c06cadf294931b1b02a15727da515c03ce7fd"
        }
    },
    "26.1.2": {
        "chunks": {
            "artifact": "ftb-chunks-fabric",
            "version": "26.1.2.9",
            "filename": "ftb-chunks-fabric-26.1.2.9.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-fabric/26.1.2.9/ftb-chunks-fabric-26.1.2.9.jar",
            "sha256": "2c68cb3f524f7b04d137e40241ad6c8ae7f6cc97bf6380214e149790037a5277"
        },
        "essentials": {
            "artifact": "ftb-essentials-fabric",
            "version": "26.1.2.5",
            "filename": "ftb-essentials-fabric-26.1.2.5.jar",
            "url": "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-essentials-fabric/26.1.2.5/ftb-essentials-fabric-26.1.2.5.jar",
            "sha256": "191e77c939e8f6e14d6309db19b19c3b807150a4a7bd8d13876c9df58f8644e3"
        }
    }
}


def fetch(minecraft, destination):
    destination.mkdir(parents=True, exist_ok=True)
    result = {}
    for kind, release in RELEASES[minecraft].items():
        path = destination / release["filename"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != release["sha256"]:
            request = Request(release["url"], headers={"User-Agent": "Mozilla/5.0 VSS-Horizon-compat"})
            with urlopen(request, timeout=60) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != release["sha256"]:
                raise ValueError("FTB release checksum mismatch: " + release["filename"])
            path.write_bytes(data)
        with zipfile.ZipFile(path) as jar:
            metadata = json.loads(jar.read("fabric.mod.json"))
            if metadata.get("id") != ("ftbchunks" if kind == "chunks" else "ftbessentials"):
                raise ValueError("Unexpected Fabric mod identity: " + path.name)
        result[kind] = dict(release, minecraft=minecraft, fabric_dependencies=metadata.get("depends", {}))
        print(kind + ": " + str(path))
    (destination / "ftb-releases.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    properties = {"vssFtbChunksJar": destination / result["chunks"]["filename"],
                  "vssFtbEssentialsJar": destination / result["essentials"]["filename"]}
    (destination / "ftb-inputs.properties").write_text("".join(
        name + "=" + path.resolve().as_posix() + "\n" for name, path in properties.items()), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True, choices=RELEASES)
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    fetch(args.minecraft, args.destination or Path("external/ftb-compat") / args.minecraft)
