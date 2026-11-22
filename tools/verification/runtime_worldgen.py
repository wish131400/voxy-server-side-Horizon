"""Check integrated runtime-worldgen and cache paths in a completed artifact."""

def verify_runtime_worldgen(archive, references):
    prefix = "dev/xantha/vss/"
    required = [
        ("networking/server/session/WorldgenCodecSnapshot", "common/worldgen/BiolithWorldgenContext", "capture"),
        ("networking/server/session/WorldgenCodecSnapshot", "common/worldgen/WorldgenRuntimeHooks", "changed"),
        ("client/prediction/ClientWorldgenProfileDecoder", "client/prediction/BiolithBiomeSource", "<init>"),
        ("client/prediction/BiolithBiomeSource", "common/worldgen/BiolithWorldgenContext", "additionalBiomes"),
        ("client/prediction/BiolithBiomeSource", "common/worldgen/BiolithWorldgenContext", "restore"),
        ("client/prediction/RustTerrainSampler", "client/prediction/PredictionSurfaceStage", "<init>"),
        ("client/prediction/PredictionSurfaceTerrainSampler", "client/prediction/PredictionSurfaceStage", "<init>"),
        ("client/prediction/PredictionDiskCache", "client/prediction/PredictionVoxelCache", "writeAndDigest"),
        ("client/prediction/PredictionPlacementExecutor", "client/prediction/PredictionIdentityPlans", "get"),
        ("client/prediction/PredictionTileManager", "client/prediction/PredictionSurfaceRefinement", "basicPass"),
        ("client/prediction/PredictionTileManager", "client/prediction/PredictionSurfaceRefinement", "completeGridSlice"),
        # Partial terrain is cacheable; quality ordering prevents later downgrades.
        ("client/prediction/PredictionDiskCache", "client/prediction/PredictionSurfaceRefinement", "quality"),
        ("client/prediction/PredictionDiskCache", "client/prediction/PredictionVoxelCache", "quality"),
        ("client/prediction/PredictionVoxelCache", "client/prediction/PredictionVoxelData", "surfaceRefinement"),
        ("client/prediction/PredictionFarDecorations", "client/prediction/ClientTerrainSampler", "supportsFarDecorations"),
        ("client/prediction/PredictionSurfaceTerrainSampler", "client/prediction/PredictionChunkTerrain", "<init>"),
        ("client/prediction/PredictionSurfaceTerrainSampler", "client/prediction/PredictionSurfaceStage", "refineChunk"),
        ("client/prediction/RustTerrainSampler", "client/prediction/PredictionSurfaceStage", "refineChunk"),
        ("client/prediction/PredictionSurfaceRefinement", "client/prediction/ClientTerrainSampler", "refineSurfaceChunk"),
        ("client/prediction/PredictionVoxySource", "client/prediction/PredictionVoxyColumns", "retain"),
        ("client/prediction/PredictionFarDecoration", "client/prediction/PredictionSharedCache", "statistics"),
        ("client/prediction/PredictionFarDecoration", "client/prediction/PredictionFarPlacementCount", "distantDensity"),
    ]
    for owner, target, method in required:
        calls = references(archive.read(prefix + owner + ".class"))
        if not any(ref[:2] == (prefix + target, method) for entries in calls.values() for ref in entries):
            raise ValueError("Missing runtime worldgen integration: " + owner + " -> " + target + "." + method)

    calls = references(archive.read(prefix + "client/prediction/RustTerrainSampler.class"))
    fill = calls.get("fillNativeChunk", [])
    # Some class parsers key methods by (name, descriptor).
    if not fill:
        fill = [ref for key, entries in calls.items() if isinstance(key, tuple) and key[0] == "fillNativeChunk" for ref in entries]
    if not any(ref[:2] == (prefix + "client/prediction/RustWorldgenBackend", "columns") for ref in fill):
        raise ValueError("Full Java surface stage must consume raw native occupancy")
    if not any(ref[:2] == (prefix + "client/prediction/RustWorldgenBackend", "rawChunk") for ref in fill):
        raise ValueError("Shared native chunk fill missing from runtime surface stage")
    if any(ref[:2] == (prefix + "client/prediction/RustWorldgenBackend", "interiorColumns") for ref in fill):
        raise ValueError("Duplicate native material stage before Java surface hooks")
