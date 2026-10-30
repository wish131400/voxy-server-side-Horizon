#!/usr/bin/env python3
"""Download fixed real Fabric releases for optional-mod contract verification."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from urllib.request import Request, urlopen
import zipfile


PINNED = {
    "1.20.1": {
        "tectonic": {
            "filename": "tectonic-3.0.17-fabric-1.20.1.jar",
            "release": "mz3wlQKd",
            "sha512": "9a11047ed0e981bb60c230d00ec5d88bf3968119df808e9c55678cab94f8fa5005cdd916799dfd641e0ad60a1b937b4554c71c19d7b3d0f61b94f5135cbf377a"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc1.20.1-0.2.0+alpha.11.18.jar",
            "release": "fyt7FtgA",
            "sha256": "f97579cde7dc6837b30e7287b1da0e04eb31f77edc714d6adabbfdc5649833b9"
        },
        "create-fabric": {
            "filename": "create-fabric-6.0.8.1+build.1744-mc1.20.1.jar",
            "release": "HAqwA6X1",
            "sha512": "6edaddb93bc87bf8204376d3ceddd3e3dfec1d716556a5925802f2ade59ce5a660ded50088fa94188842ff83fc29445363dfa5d423e425b1574092833b6fa896"
        },
        "oh-the-trees-youll-grow": {
            "filename": "Oh-The-Trees-Youll-Grow-fabric-1.20.1-1.7.3.jar",
            "release": "kcYZL6Gx",
            "sha512": "cd4b0ea817cc885e09795a2d58a5e36aaf5ab8b331ed4b6f020d9680bbca8fb1c1c64abacd56470a25e3892374f35e57696a94c4541cf4574f37a5ccf39eef17"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-1.20.1-1.47.0.jar",
            "release": "rjV8OOdS",
            "sha256": "d536deb5d07f00543ef385fb3377d99f7808146ce81985731e9dad2543b70077"
        }
    },
    "1.21.1": {
        "tectonic": {
            "filename": "tectonic-3.0.28-fabric-21.1.jar",
            "release": "WdDiEKMn",
            "sha512": "a15a85fb5c6d134d0967fa3ccd6797fa28348b69937b65b2a3bcd6173609e84ae97bd8400eb01013167ceca3434bf97216e879d4b0277c007f6deda7aa076afd"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc1.21.1-0.4.0-alpha.0.30.jar",
            "release": "KxcgERSN",
            "sha256": "f135f582a97249c23dc650190135c9b7851331cb321ee13ad268d8bd802d3911"
        },
        "freeterraforged": {
            "filename": "freeterraforged-1.0.0-fabric-1.21.1.jar",
            "release": "WUfPzqnU",
            "sha512": "68cbaf1305d4ef2198b3ae66f3e53742ecdaffc767977c545dfbe2d9d8cd0a2db114b7498a42443ac64d2515036799b27f8c982fa4d8650a29457cfcf6345264"
        },
        "oh-the-trees-youll-grow": {
            "filename": "Oh-The-Trees-Youll-Grow-fabric-1.21.1-5.3.5.jar",
            "release": "7nMg7s9h",
            "sha512": "e0a762fda1e6284ad8941965b9771575781664dc758954f4cfd1ea730832d2a2b9167f53c5db95d20e5cf8928d02fa356f22b65d986d38eedcc5fe1ee95a4c4c"
        },
        "reterraforged-legacy": {
            "filename": "reterraforged-0.0.6005-fabric-1.21.1.jar",
            "release": "https://github.com/ETcodehome/FreeTerraForged/releases/tag/1.21.1_V0.0.6005",
            "sha256": "0eb0baa327050412bb169ac50fd8b2efbd39684b3d846dd612adb44f0d4fb60e",
            "url": "https://github.com/ETcodehome/FreeTerraForged/releases/download/1.21.1_V0.0.6005/reterraforged-0.0.6005-fabric-1.21.1.jar",
            "game_versions": [
                "1.21.1"
            ],
            "mod_id": "reterraforged",
            "mod_version": "0.0.6005",
            "minecraft_dependency": ">=1.21.1"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-1.21.1-1.47.0.jar",
            "release": "XIR7GzC3",
            "sha256": "3e8211e02681f8bc17e1a066b3fcffddfa969cff03f48a5400016f587ba5f8f2"
        }
    },
    "1.21.11": {
        "tectonic": {
            "filename": "tectonic-3.0.19-fabric-1.21.11.jar",
            "release": "7olSYFxL",
            "sha512": "ce0643d45aac7b5e3f3a32fc01928371d97612a9dc6e8df721bf215bf90afa021b8e8d7e19c0d00060c3c98591aec90d2ea51f594fdc0012051f20657fae85ef"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc1.21.11-0.4.0-alpha.0.28.jar",
            "release": "j1RWyU0M",
            "sha256": "8a12da4d4ac50ddaf4849b5685c3bc4b2832442cc02f97f1be038cf855469a1e"
        },
        "oh-the-trees-youll-grow": {
            "filename": "Oh-The-Trees-Youll-Grow-fabric-1.21.11-9.1.2.jar",
            "release": "kxVZMwso",
            "sha512": "7122db85072f5bbd5811e9043288bfda594a9ef974085648e729684b109118d6a79205757ba5276f020693a2508aed9db2bcff025dbea37a19f9e0f69fca04a7"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-1.21.11-1.47.2.jar",
            "release": "ksfmc45B",
            "sha256": "0509be09e85cc11fb7cf7130fbe79887fb5af80eee240567a976316fa949133f"
        }
    },
    "26.1": {
        "tectonic": {
            "filename": "tectonic-3.0.28-fabric-26.1.jar",
            "release": "5v9Cz8zS",
            "sha512": "778631c9397fb50d114949c41fc42403ff1a7440ac7f42a4d591b26a8dabba7ae29c162deb83c25e2416076e0b781ed74c90d6516ccd278ce56df1cba2186b9b"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc26.1-0.3.7+alpha.0.61.jar",
            "release": "tKXPZtpT",
            "sha256": "592ec11dfe9d03a906a4a257f79b5bad7209d5c205b8c5b00b8ae04c769f9812"
        },
        "oh-the-trees-youll-grow": {
            "filename": "ohthetreesyoullgrow-fabric-26.1-10.0.4.jar",
            "release": "LRUWvYct",
            "sha512": "aca1cc6edcacdf263aebdbec06cac547969bb434a87d57113dec2224893113704cbce7ee8e6aa76c43e95398ad8f9429ac74278d7362f51497c03101c90520ff"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-26.1.2-1.47.2.jar",
            "release": "ZpUBkoFm",
            "sha256": "72892ea6f3bce322063d4c970792d1127704583f5470e477932bbf357eb74af0"
        }
    },
    "26.1.1": {
        "tectonic": {
            "filename": "tectonic-3.0.28-fabric-26.1.jar",
            "release": "5v9Cz8zS",
            "sha512": "778631c9397fb50d114949c41fc42403ff1a7440ac7f42a4d591b26a8dabba7ae29c162deb83c25e2416076e0b781ed74c90d6516ccd278ce56df1cba2186b9b"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc26.1.1-0.3.7+alpha.0.63.jar",
            "release": "wGnmDPvI",
            "sha256": "03d85ddd9b7e718e45e283998cc9a6ff8ce58a6a1fd0029327e4070583b41a9c"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-26.1.2-1.47.2.jar",
            "release": "ZpUBkoFm",
            "sha256": "72892ea6f3bce322063d4c970792d1127704583f5470e477932bbf357eb74af0"
        }
    },
    "26.1.2": {
        "tectonic": {
            "filename": "tectonic-3.0.28-fabric-26.1.jar",
            "release": "5v9Cz8zS",
            "sha512": "778631c9397fb50d114949c41fc42403ff1a7440ac7f42a4d591b26a8dabba7ae29c162deb83c25e2416076e0b781ed74c90d6516ccd278ce56df1cba2186b9b"
        },
        "byepregen": {
            "filename": "byepregen-fabric-26.1.2-1.1.2.5.jar",
            "release": "ou2DOwxV",
            "sha512": "f45b1b2df1abad63561e00769c4c2d66a89cbb90b05efc56e33a5621917cc87059af2253ff4757376b5ffd6748f09f293e48d422153732e839a9a0f222478019"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc26.1.2-0.4.0-alpha.0.65.jar",
            "release": "to1sDRnG",
            "sha256": "c6bd67adcfb7b8acbecfd56d83758b97d8aa735ae6640f64214b5db237dff135"
        },
        "oh-the-trees-youll-grow": {
            "filename": "ohthetreesyoullgrow-fabric-26.1.2-10.1.4.jar",
            "release": "HuAuazQv",
            "sha512": "ca4fce402515c3eb9ee0ec8768e2ec3b733add1a7bf0625aa1aa06636c0588994dd4e5b01be4fa6451abb072f4735d7ed5291bf17c105ff4d6a5fce1b9e7a399"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-26.1.2-1.47.2.jar",
            "release": "ZpUBkoFm",
            "sha256": "72892ea6f3bce322063d4c970792d1127704583f5470e477932bbf357eb74af0"
        }
    },
    "26.2": {
        "tectonic": {
            "filename": "tectonic-3.0.28-fabric-26.2.jar",
            "release": "tGbplDzo",
            "sha512": "c653ee42fe48c1be9512fa7791e8495095ae01cb607555202714bbb95cb49da272128bb295e8f8c262b63739e7ca2de4bba9196e1738b06012b498d220b55acf"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc26.2-0.4.2-alpha.0.60.jar",
            "release": "4wjXxrDQ",
            "sha256": "6d9c3fd764c0e3dd94da59ab9bbd2aff7ca91b15cc235c418f5553e51d71ceed"
        },
        "oh-the-trees-youll-grow": {
            "filename": "ohthetreesyoullgrow-fabric-26.2-11.0.4.jar",
            "release": "gGlQ28kL",
            "sha512": "68169c0e6a50210e8f59d1f664d08645c4dc01ea78bada273b3900381d45e9072f0d997bc849f61cb67bcb749f985259dd67dff0498197be0787b258c2af7c9c"
        },
        "the-lost-cities": {
            "filename": "lostcities-fabric-26.2-26.2-11.0.3-fabric.jar",
            "release": "CwCaAjVk",
            "sha512": "d90c6b06edd6e791e3eeaf717b4b5a7fd6adf8f86ea2d43ea377968e99a10d4da9ee6506900278f14cc2758230c296b6cf82dbdf08690a1a1611ec84fd7c18ac"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-26.2-1.47.2.jar",
            "release": "W8qSt5Gs",
            "sha256": "e2d352426f8cea6887205a68e4d23a27da55e4cfb0e98125c7d3c57dc66543fa"
        }
    },
    "26.3": {
        "tectonic": {
            "filename": "tectonic-3.0.32-fabric-26.3.jar",
            "release": "tRBGDcCT",
            "sha512": "bf1e39b3dfaba524d2111572279816df3c60c6e90831c9ae8e6c13f9f5770223a30a6a3986f061f57e7ebe4cdb008cc30cb3a44d7816e2c12ba795a3b4ae79bb"
        },
        "byepregen": {
            "filename": "byepregen-fabric-26.3-1.1.3.5.jar",
            "release": "BYmtS3gU",
            "sha512": "e8dc2afbb0c3dfd6c0ce5723be79520d852aeb7361880f8fd90c62e4e2ce44be628fbe5eaec0357e5cbccd9d9c54e511505b6566f5044f38f2c40032dc3c427e"
        },
        "c2me-fabric": {
            "filename": "c2me-fabric-mc26.3-0.4.2-alpha.0.112.jar",
            "release": "B5Koj7Vo",
            "sha256": "c2595720b701aa93f152a3edf381f74ab9d5b909758adb97d76a6ba1ba2e24f8"
        },
        "oh-the-trees-youll-grow": {
            "filename": "ohthetreesyoullgrow-fabric-26.3-12.0.3.jar",
            "release": "crCmSAwm",
            "sha512": "738a41a197b2bf72af73fd4badbc9565759eb10ade670cc6e2d66111d4b590f7840ba464c0df910b1bc62edf5baa6a754862c7f5694a148de4a4026be473dc3d"
        },
        "the-lost-cities": {
            "filename": "lostcities-fabric-26.3-26.3-12.0.0-fabric.jar",
            "release": "xuEuPjKk",
            "sha512": "1ca1eddb0a17dce009b41b8cc849fccc5a86771df15dc16f3de204e90aea1a7da4c2498e9c6de813f963286bd5e11db4b7448ba842c8ac3e7dbf0a708ebc943e"
        },
        "xaeros-world-map": {
            "filename": "xaeroworldmap-fabric-26.3-1.47.2.jar",
            "release": "VM8lNSSM",
            "sha256": "815fbfd7da1e0c3b6a7a05227b7b342c7857fe6ab0fe42f072e14f8dfc19e1f8"
        }
    }
}

PROPERTY_NAMES = {
    "tectonic": "vssTectonicJar",
    "reterraforged-legacy": "vssFreeTerraForgedJar",
    "create-fabric": "vssCreateJar",
    "byepregen": "vssByepregenJar",
    "oh-the-trees-youll-grow": "vssNbtTreeRuntimeJar",
    "the-lost-cities": "vssLostCitiesJar",
    "freeterraforged": "vssFreeTerraForgedJar",
    "xaeros-world-map": "vssXaeroJars",
    "c2me-fabric": "vssC2meJar",
}
MOD_IDS = {
    "tectonic": "tectonic",
    "reterraforged-legacy": "reterraforged",
    "create-fabric": "create",
    "byepregen": "byepregen",
    "oh-the-trees-youll-grow": "ohthetreesyoullgrow",
    "the-lost-cities": "lostcities",
    "freeterraforged": "freeterraforged",
    "xaeros-world-map": "xaeroworldmap",
    "c2me-fabric": "c2me",
}


def request(url):
    return urlopen(Request(url, headers={"User-Agent": "VSS-Horizon/optional-compat-verification"}), timeout=120)


def release_file(minecraft, project, pin):
    if "url" in pin:
        # These are verified targets, not a general evaluator for Fabric's version predicates.
        # The legacy jar declares >=1.21.1; that alone cannot certify later game versions.
        if minecraft not in pin["game_versions"]:
            raise ValueError("Pinned GitHub release does not declare verified target: " + minecraft)
        metadata = {
            "id": pin["release"],
            "version_number": pin["mod_version"],
            "game_versions": pin["game_versions"],
        }
        primary = {"filename": pin["filename"], "url": pin["url"], "hashes": {"sha256": pin["sha256"]}}
        return metadata, primary

    with request("https://api.modrinth.com/v2/version/" + pin["release"]) as response:
        metadata = json.load(response)
    if metadata.get("id") != pin["release"]:
        raise ValueError("Release ID does not match requested input: " + project)
    # A filename can contain 26.1.2 while explicitly supporting 26.1 and 26.1.1.
    # Only exact entries in the upstream game_versions list establish that contract.
    loaders = metadata.get("loaders")
    versions = metadata.get("game_versions")
    if (not isinstance(loaders, list) or "fabric" not in loaders
            or not isinstance(versions, list) or minecraft not in versions):
        raise ValueError("Release does not declare Fabric " + minecraft + ": " + project)
    with request("https://api.modrinth.com/v2/project/" + project) as response:
        identity = json.load(response)
    if identity.get("id") != metadata.get("project_id") or not identity.get("id"):
        raise ValueError("Release belongs to another project: " + project)
    files = metadata.get("files")
    if not isinstance(files, list) or not files:
        raise ValueError("Release has no download files: " + project)
    primary = next((entry for entry in files if entry.get("primary")), files[0])
    return metadata, primary


def validate_filename(filename):
    # Explicitly reject both Windows and POSIX separators even when run on another OS.
    if (not isinstance(filename, str) or not filename.endswith(".jar")
            or any(character in filename for character in ("/", "\\", ":"))
            or any(ord(character) < 32 for character in filename)):
        raise ValueError("Invalid release filename")


def validate_fabric_metadata(data, project, pin):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            mod = json.loads(jar.read("fabric.mod.json"))
    except (zipfile.BadZipFile, KeyError, json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError("Artifact has no valid Fabric metadata: " + project) from error
    if (not isinstance(mod, dict) or not isinstance(mod.get("id"), str) or not mod["id"]
            or not isinstance(mod.get("version"), str) or not mod["version"]):
        raise ValueError("Artifact has invalid Fabric identity: " + project)
    expected_id = pin.get("mod_id", MOD_IDS.get(project))
    if expected_id is not None and mod["id"] != expected_id:
        raise ValueError("Artifact has unexpected Fabric mod ID: " + project)
    if "mod_version" in pin and mod["version"] != pin["mod_version"]:
        raise ValueError("Artifact has unexpected Fabric version: " + project)
    if "minecraft_dependency" in pin:
        depends = mod.get("depends")
        if not isinstance(depends, dict) or depends.get("minecraft") != pin["minecraft_dependency"]:
            raise ValueError("Artifact has unexpected Fabric Minecraft dependency: " + project)
    return mod


def validate_checksums(data, expected, description):
    for algorithm in ("sha256", "sha512"):
        if algorithm in expected and hashlib.new(algorithm, data).hexdigest() != expected[algorithm]:
            raise ValueError(description + " checksum mismatch")


def fetch(minecraft, destination, projects=None, releases=None):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    if minecraft not in PINNED and not releases:
        raise ValueError("No pinned optional mods for Minecraft " + minecraft)
    pins = dict(PINNED.get(minecraft, {}))
    for project, release in (releases or {}).items():
        pins[project] = {"release": release}
    if projects:
        missing = set(projects) - pins.keys()
        if missing:
            raise ValueError("No pinned Fabric release for target: " + ", ".join(sorted(missing)))
        pins = {project: pins[project] for project in projects}
    output = []
    for project, pin in sorted(pins.items()):
        metadata, primary = release_file(minecraft, project, pin)
        filename = primary["filename"]
        validate_filename(filename)
        if "filename" in pin and filename != pin["filename"]:
            raise ValueError("Pinned release filename changed: " + project)
        path = destination / filename
        hashes = primary["hashes"]
        if not any(algorithm in hashes for algorithm in ("sha256", "sha512")):
            raise ValueError("Release has no supported checksum: " + project)
        data = path.read_bytes() if path.is_file() else None
        cache_matches = data is not None and all(
            hashlib.new(algorithm, data).hexdigest() == hashes[algorithm]
            for algorithm in ("sha256", "sha512") if algorithm in hashes)
        if not cache_matches:
            with request(primary["url"]) as response:
                data = response.read()
        validate_checksums(data, hashes, "Release download for " + project)
        validate_checksums(data, pin, "Pinned release for " + project)
        mod = validate_fabric_metadata(data, project, pin)
        if not cache_matches:
            # Validate bytes before replacing an existing input, including its Fabric identity.
            partial = path.with_suffix(".jar.part")
            try:
                partial.write_bytes(data)
                partial.replace(path)
            finally:
                partial.unlink(missing_ok=True)
        output.append({"minecraft": minecraft, "project": project, "version_id": metadata["id"],
                       "version": metadata["version_number"], "filename": filename,
                       "url": primary["url"], "sha256": hashlib.sha256(data).hexdigest(),
                       "sha512": hashlib.sha512(data).hexdigest(), "mod_id": mod["id"],
                       "path": str(path.resolve()), "game_versions": metadata["game_versions"]})
        print(project + ": " + filename)
    (destination / "inputs.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    # Raw releases on intermediary Minecraft targets require remapping before runtime JUnit.
    # Newer releases may already be named; this downloader does not transform either kind.
    property_projects = {PROPERTY_NAMES[entry["project"]] for entry in output if entry["project"] in PROPERTY_NAMES}
    ambiguous = {name for name in property_projects if sum(PROPERTY_NAMES.get(entry["project"]) == name for entry in output) > 1}
    ambiguity_comments = "".join("# Specify " + name + " separately for: " + ", ".join(
        entry["project"] for entry in output if PROPERTY_NAMES.get(entry["project"]) == name) + "\n"
        for name in sorted(ambiguous))
    (destination / "raw-inputs.properties").write_text(ambiguity_comments + "".join(
        PROPERTY_NAMES[entry["project"]] + "=" + Path(entry["path"]).as_posix() + "\n"
        for entry in output if entry["project"] in PROPERTY_NAMES and PROPERTY_NAMES[entry["project"]] not in ambiguous), encoding="utf-8")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--project", action="append")
    parser.add_argument("--release", action="append", default=[], metavar="PROJECT=VERSION_ID")
    args = parser.parse_args()
    overrides = dict(value.split("=", 1) for value in args.release)
    fetch(args.minecraft, args.destination or Path("external/optional-compat") / args.minecraft,
          args.project, overrides)
