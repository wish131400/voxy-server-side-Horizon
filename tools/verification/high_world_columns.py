"""Inspect the packaged column limits and cache format identities."""
import argparse
import json
from pathlib import Path
import struct
import zipfile


def constant_fields(data):
    position = 0

    def take(size):
        nonlocal position
        value = data[position:position + size]
        if len(value) != size:
            raise ValueError("Truncated class file")
        position += size
        return value

    def number(size):
        return int.from_bytes(take(size), "big")

    if take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class file")
    take(4)
    pool = [None] * number(2)
    index = 1
    while index < len(pool):
        tag = number(1)
        if tag == 1:
            pool[index] = take(number(2)).decode("utf-8", errors="replace")
        elif tag == 3:
            pool[index] = struct.unpack(">i", take(4))[0]
        elif tag in (7, 8, 16, 19, 20):
            take(2)
        elif tag in (4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError("Unknown class constant tag: " + str(tag))
        index += 1
    take(6)
    take(2 * number(2))
    result = {}
    for _ in range(number(2)):
        take(2)
        name = pool[number(2)]
        take(2)
        for _ in range(number(2)):
            attribute = pool[number(2)]
            size = number(4)
            if attribute == "ConstantValue":
                if size != 2:
                    raise ValueError("Invalid ConstantValue attribute")
                result[name] = pool[number(2)]
            else:
                take(size)
    return result


def verify_high_world_columns(archive):
    expected = {
        "common/VSSConstants": {"MAX_COLUMN_SECTIONS": 256, "MAX_COLUMN_BYTES": 8 * 1024 * 1024,
                                "MAX_COLUMN_PART_BYTES": 2 * 1024 * 1024},
        "networking/payloads/VoxelColumnS2CPayload": {"MAX_REPLACEMENT_SECTION_COUNT": 256,
                                                   "MAX_TRANSFER_PARTS": 256, "MAX_SECTIONS_SIZE": 2 * 1024 * 1024},
        "networking/client/ClientColumnProcessor": {"MAX_SECTIONS_PER_COLUMN": 256},
        "networking/client/ClientLodPresenceCache": {"FILE_VERSION": 5},
    }
    for name, fields in expected.items():
        actual = constant_fields(archive.read("dev/xantha/vss/" + name + ".class"))
        for field, value in fields.items():
            if actual.get(field) != value:
                raise ValueError("Incorrect packaged high-world limit: " + name + "." + field)
    store = constant_fields(archive.read("dev/xantha/vss/networking/server/storage/PersistentColumnLodStore.class"))
    if store.get("MAX_COLUMN_BYTES") != 8 * 1024 * 1024:
        raise ValueError("Incorrect packaged persistent-column size limit")
    # Forge's existing index stores no section manifest; only the other ports
    # require the short-count index revision. Column bodies retain their schema.
    if store.get("FILE_VERSION_CURRENT") == 8 and store.get("INDEX_VERSION_CURRENT") != 4:
        raise ValueError("Incorrect packaged high-world region index format")
    return {"sections": 256, "columnBytes": 8 * 1024 * 1024,
            "fragmentBytes": 2 * 1024 * 1024, "presenceVersion": 5,
            "indexVersion": store.get("INDEX_VERSION_CURRENT")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jars", type=Path, nargs="+")
    arguments = parser.parse_args()
    for jar in arguments.jars:
        with zipfile.ZipFile(jar) as archive:
            result = verify_high_world_columns(archive)
        print(json.dumps({"jar": str(jar), **result}))
