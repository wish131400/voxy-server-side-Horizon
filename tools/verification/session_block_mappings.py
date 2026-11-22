"""Check that a production JAR executes cache lookups through its session snapshot."""


def verify_session_block_mappings(archive, class_references):
    prefix = "dev/xantha/vss/client/prediction/"

    def require(condition, message):
        if not condition:
            raise ValueError("Session block mapping: " + message)

    def references(cls):
        return class_references(archive.read(prefix + cls + ".class"))

    snapshot = archive.read(prefix + "PredictionBlockMappings.class")
    require(b"capture" in snapshot and b"java/util/Map" in snapshot, "missing immutable snapshot")
    for cls, method, target, called in [
        ("PredictionDiskCache", "encodeTerrain", "PredictionBlockMappings", "name"),
        ("PredictionDiskCache", "decodeTerrain", "PredictionBlockMappings", "id"),
        ("PredictionCapturedTerrain", "writeSamples", "PredictionBlockMappings", "name"),
        ("PredictionCapturedTerrain", "readSamples", "PredictionBlockMappings", "id"),
        ("PredictionCacheMappings", "blockName", "PredictionBlockMappings", "name"),
        ("PredictionCacheMappings", "blockId", "PredictionBlockMappings", "id"),
        ("PredictionCacheMappings", "<init>", "PredictionBlockMappings", "capture"),
    ]:
        refs = references(cls)
        require(any(ref[:2] == (prefix + target, called)
                    for (name, _), entries in refs.items() if name == method for ref in entries),
                cls + "." + method + " does not use " + target + "." + called)
    for cls, symbol in [("PredictionDiskCache", b"blockMappings"),
                        ("PredictionSampleStore", b"blockName"),
                        ("PredictionSampleStore", b"blockId"),
                        ("PredictionBackendSelection", b"session-block-mappings-r1:")]:
        require(symbol in archive.read(prefix + cls + ".class"), "missing " + cls + ": " + symbol.decode())
    require(b"blockIds" not in archive.read(prefix + "PredictionMaterialPalette.class"),
            "static numeric material palette is still present")
