"""Check the final release JAR for integrated fixes, not just its source tree.

This is an artifact presence/reference check, not a substitute for regression
tests or a live game run. Run after remapJar (1.x) or jar (26.x).
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import zipfile
from install_native_artifacts import native_source_digest, LIBRARIES


def manifest_attributes(data):
    """Read exact main attributes, including standard folded manifest lines."""
    lines = []
    for line in data.decode("utf-8").splitlines():
        if not line:
            break
        if line.startswith(" "):
            if not lines:
                raise ValueError("Manifest continuation without an attribute")
            lines[-1] += line[1:]
        else:
            lines.append(line)
    result = {}
    for line in lines:
        name, separator, value = line.partition(": ")
        if not separator or name in result:
            raise ValueError("Invalid or duplicate manifest attribute: " + name)
        result[name] = value
    return result


def class_references(data):
    """Read real field/method and instanceof instructions, excluding unused entries.

    This establishes that a packaged implementation calls its target APIs. It
    does not establish execution, control flow correctness or rendering parity.
    """
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
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, number(2))
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, number(2), number(2))
        elif tag in (3, 4):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError("Unknown constant pool tag: " + str(tag))
        index += 1
    take(6)
    take(2 * number(2))

    def attributes():
        return [(pool[number(2)], take(number(4))) for _ in range(number(2))]

    for _ in range(number(2)):
        take(6)
        attributes()
    references = {}
    for _ in range(number(2)):
        number(2)
        method, descriptor = pool[number(2)], pool[number(2)]
        entries = references.setdefault((method, descriptor), set())
        for name, value in attributes():
            if name != "Code":
                continue
            code = value[8:8 + int.from_bytes(value[4:8], "big")]
            offset = 0
            while offset < len(code):
                opcode = code[offset]
                if 178 <= opcode <= 185:
                    member = pool[int.from_bytes(code[offset + 1:offset + 3], "big")]
                    owner = pool[pool[member[1]][1]]
                    signature = pool[member[2]]
                    entries.add((owner, pool[signature[1]], pool[signature[2]]))
                elif opcode == 193:
                    target = pool[int.from_bytes(code[offset + 1:offset + 3], "big")]
                    entries.add((pool[target[1]], "<instanceof>", None))
                if opcode in (170, 171):
                    aligned = (offset + 4) & ~3
                    if opcode == 170:
                        low = int.from_bytes(code[aligned + 4:aligned + 8], "big", signed=True)
                        high = int.from_bytes(code[aligned + 8:aligned + 12], "big", signed=True)
                        offset = aligned + 12 + 4 * (high - low + 1)
                    else:
                        pairs = int.from_bytes(code[aligned + 4:aligned + 8], "big")
                        offset = aligned + 8 + 8 * pairs
                elif opcode == 196:
                    offset += 6 if code[offset + 1] == 132 else 4
                elif opcode in (185, 186, 200, 201):
                    offset += 5
                elif opcode == 197:
                    offset += 4
                elif opcode in (17, 19, 20, 132, 187, 189, 192, 193, 198, 199) or 153 <= opcode <= 168 or 178 <= opcode <= 184:
                    offset += 3
                elif opcode in (16, 18, 169, 188) or 21 <= opcode <= 25 or 54 <= opcode <= 58:
                    offset += 2
                else:
                    offset += 1
    return references


def verify_version_paths(read_class, names, minecraft):
    """Assert the actual density and depth implementations for each target."""
    prefix = "dev/xantha/vss/client/prediction/"

    def calls(cls, method, owner, name, descriptor=None):
        references = class_references(read_class(cls))
        if not any(owner == entry[0] and name == entry[1]
                   and (descriptor is None or descriptor == entry[2])
                   for (declared, _), entries in references.items()
                   if method is None or declared == method for entry in entries):
            raise ValueError(f"Missing implementation call: {cls}.{method or '*'} -> {owner}.{name}")

    # Check executed instructions in the helper used by tick, rather than an
    # unused constant-pool entry left over from an earlier implementation.
    state = "ClientPredictionState"
    calls(state, "tick", prefix + state, "currentResourceAtlasToken")
    if minecraft in ("1.20.1", "1.21.1"):
        calls(state, "currentResourceAtlasToken", "net/minecraft/class_310", "method_1549",
              "(Lnet/minecraft/class_2960;)Ljava/util/function/Function;")
        calls(state, "currentResourceAtlasToken", "net/minecraft/class_1059", "field_5275")
        calls(state, "currentResourceAtlasToken", "java/util/function/Function", "apply")
    elif minecraft == "1.21.11":
        calls(state, "currentResourceAtlasToken", "net/minecraft/class_310", "method_72703")
        calls(state, "currentResourceAtlasToken", "net/minecraft/class_10725", "field_56382")
    else:
        calls(state, "currentResourceAtlasToken", "net/minecraft/client/Minecraft", "getAtlasManager")
        calls(state, "currentResourceAtlasToken", "net/minecraft/data/AtlasIds", "BLOCKS")

    if minecraft == "26.3":
        retired = ("DensityMemo", "DensityGraphCompiler", "DensityCompilerSupport", "DensityCompilation",
                   "PredictionRawDensity", "PredictionVanillaDensity", "PredictionDensityOrder")
        for cls in retired:
            if any(name == prefix + cls + ".class" or name.startswith(prefix + cls + "$") for name in names):
                raise ValueError("Retired double density compiler packaged for 26.3: " + cls)
        density = "net/minecraft/world/level/levelgen/densityfunction/"
        calls("PredictionDensityContext", "bind", "net/minecraft/world/level/levelgen/RandomState", "getSampler",
              f"(L{density}DensityFunction;)L{density}DensitySampler;")
        calls("PredictionDensityContext", "context", "java/lang/ThreadLocal", "get")
        calls("PredictionDensityContext", "context", density + "SamplerContext", "builder")
        calls("PredictionDensityContext", "context", density + "SamplerContext$Builder", "enableCaches")
        calls("PredictionDensityContext", "close", "java/lang/ThreadLocal", "remove")
        calls("PredictionDensityContext$1", "sampleValue", density + "DensitySampler", "sampleValue",
              f"(L{density}SamplerContext;III)F")
        calls("PredictionDensityContext$1", "sampleVolume", density + "DensitySampler", "sampleVolume")
        for cls in ("PredictionClimateSampler", "ClientTerrainSampler"):
            calls(cls, "<init>", prefix + "PredictionDensityContext", "bind")
    else:
        for cls in ("PredictionClimateSampler", "ClientTerrainSampler"):
            calls(cls, "<init>", prefix + "DensityMemo", "wrapRoots")
            calls(cls, "<init>", prefix + "DensityCompilation", "<init>")
        calls("DensityCompilation", "compile", prefix + "DensityGraphCompiler", "compile")
        if prefix + "PredictionDensityContext.class" in names:
            raise ValueError("Minecraft 26.3 density context packaged for " + minecraft)

    cls = "PredictionNormalDepthBridge"
    calls(cls, "sourceZeroToOne", "org/lwjgl/opengl/GL11", "glGetInteger", "(I)I")
    calls(cls, "bind", "org/lwjgl/opengl/GL20", "glUniform2f", "(IFF)V")
    calls("PredictionFogBridge", "patch", prefix + cls, "patch")
    if minecraft.startswith("1."):
        bridge = read_class(cls)
        if b"VssDestinationDepthTransform" in bridge:
            raise ValueError(minecraft + " must retain its actual forward [-1,1] destination projection")
        if b"clamp(depth * 0.5 + 0.5, 0.0, 1.0 - 1.0 / 16777215.0)" not in bridge:
            raise ValueError("Missing " + minecraft + " destination depth shader mapping")
    else:
        calls(cls, "bind", "com/mojang/blaze3d/systems/RenderSystem", "getDevice")
        calls(cls, "bind", "org/lwjgl/opengl/GL20", "glUniform4f", "(IFFFF)V")
        calls(cls, "bind", "org/lwjgl/opengl/GL11", "glDepthFunc", "(I)V")
        if minecraft in ("26.2", "26.3"):
            device = "com/mojang/renderpearl/api/device/" if minecraft == "26.3" else "com/mojang/blaze3d/systems/"
            calls(cls, "bind", device + "GpuDevice", "getDeviceInfo", f"()L{device}DeviceInfo;")
            calls(cls, "bind", device + "DeviceInfo", "isZZeroToOne", "()Z")
        else:
            calls(cls, "bind", "com/mojang/blaze3d/systems/GpuDevice", "isZZeroToOne", "()Z")
        depth = "com/mojang/renderpearl/api/pipeline/" if minecraft == "26.3" else "com/mojang/blaze3d/pipeline/"
        compare = depth if minecraft == "26.3" else "com/mojang/blaze3d/platform/"
        calls(cls, "bind", depth + "DepthStencilState", "DEFAULT")
        calls(cls, "bind", depth + "DepthStencilState", "depthTest")
        calls(cls, "bind", compare + "CompareOp", "GREATER_THAN_OR_EQUAL")


def verify_worldgen_interface_paths(read_class, minecraft):
    """Check packaged worldgen call sites and markers, without asserting execution."""
    prefix = "dev/xantha/vss/"

    def calls(cls, method, owner, member):
        references = class_references(read_class(cls))
        if not any(owner == entry[0] and member == entry[1]
                   for (declared, _), entries in references.items()
                   if method is None or declared == method for entry in entries):
            raise ValueError(f"Missing worldgen implementation reference: {cls}.{method or '*'} -> {owner}.{member}")

    snapshot = "networking/server/session/WorldgenCodecSnapshot"
    registries = "client/prediction/ClientWorldgenRegistries"
    dependencies = "common/worldgen/WorldgenRegistryDependencies"
    tags = "common/worldgen/WorldgenRegistryTags"
    decoder = "client/prediction/ClientWorldgenProfileDecoder"
    read_class(snapshot, "registry_tags")
    read_class(registries, "registry_tags")
    calls(snapshot, "encodeRegistries", prefix + dependencies, "encodeTags")
    calls(dependencies, None, prefix + tags, "encode")
    calls(registries, "decode", prefix + registries, "bindSnapshotTags")
    calls(registries, "bindSnapshotTags", prefix + tags, "bind")
    # Both ownership lookups must still be present at the binding call site.
    calls(registries, "bindSnapshotTags", prefix + registries, "snapshotValues")
    calls(registries, "bindSnapshotTags", prefix + registries, "snapshotRegistries")

    end_snapshot = "networking/server/session/TerraBlenderEndSnapshot"
    end_source = "client/prediction/TerraBlenderEndBiomeSource"
    end_routing = "common/worldgen/TerraBlenderEndRouting"
    calls("networking/server/session/WorldgenProfileBuilder", "build", prefix + snapshot, "withRuntimeBiomeSource")
    calls(snapshot, "withRuntimeBiomeSource", prefix + end_snapshot, "capture")
    read_class(snapshot, "vss_terrablender_end", "Required TerraBlender End routing could not be captured")
    read_class(end_snapshot, "terrablender.worldgen.IExtendedTheEndBiomeSource", "tbInitialized", "density_coordinates")
    # Cache-first initialization shares biome decoding with full sampler setup.
    # Verify the complete call chain rather than the pre-extraction call site.
    calls(decoder, "decodeJavaSampler", prefix + decoder, "decodeBiomeSource")
    calls(decoder + "$Session", "decode", prefix + decoder, "decodeBiomeSource")
    calls(decoder, "decodeBiomeSource", prefix + end_source, "<init>")
    calls("client/prediction/TerraBlenderBackend", "open", prefix + end_source, "<init>")
    calls(end_source, "<init>", prefix + end_routing, "<init>")
    # 26.3 samples in a resolver lambda; older targets implement getNoiseBiome.
    calls(end_source, None, prefix + end_routing, "sample")

    lod = "client/prediction/PredictionLodSampler"
    read_class(lod, ":capture-palette-r2:")
    calls(lod, "captureBiomeIndex", prefix + lod, "sourceBiomeIndices")
    calls(lod, "cacheBiomes", prefix + lod, "sourceBiomeNames")
    calls("client/prediction/ClientCaptureExtractor", "extractColumn",
          prefix + "client/prediction/ClientTerrainSampler", "captureBiomeIndex")

    read_class(decoder, "Required TerraBlender region routing unavailable", "vss_terrablender_end")
    calls(decoder, "decodeBiomeSource", prefix + "client/prediction/TerraBlenderBackend", "buildRoutingSource")
    native = "client/prediction/RustTerrainSampler"
    read_class(native, "Native library lacks required TerraBlender routing", "vss_terrablender_end")
    calls(native, "open", prefix + native, "terrablenderRouting")
    calls(native, "open", prefix + "client/prediction/RustWorldgenBackend", "close")

    density = "common/worldgen/DensityFunctionSnapshot"
    calls(snapshot, None, prefix + density, "applied")
    if minecraft == "26.3":
        read_class(density, "dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedFunction", "full")
        calls(density, "applied", "java/lang/Class", "getMethod")
        calls(density, "applied", "java/lang/reflect/Method", "invoke")
        calls(density, "applied", prefix + density, "applied")
    else:
        calls(density, "applied", prefix + density, "UNWRAP")
        calls(density, None, prefix + density, "legacyTectonic")
        if not any(entry[1] == "<instanceof>" for entries in class_references(read_class(density)).values()
                   for entry in entries):
            raise ValueError("Applied density graph snapshot lacks its holder-unwrapping instruction")

    fixes = ["worldgen-registry-tag-snapshot-binding", "terrablender-end-routing-snapshot-replay",
             "lod-provider-capture-palette-r2", "required-biome-routing-fallback-guard",
             "applied-density-graph-snapshot"]
    if minecraft == "26.3":
        better_end = "client/prediction/BetterEndCompat$BetterEndSurface"
        read_class(better_end, "islandCellSize", "BetterEnd returned an invalid island interpolation grid")
        calls(better_end, "open", "java/lang/Class", "getMethod")
        calls(better_end, "open", "java/lang/reflect/Method", "invoke")
        calls(better_end, "<init>", prefix + better_end, "scaleXZ")
        calls(better_end, "<init>", prefix + better_end, "scaleY")
        fixes.append("betterend-26.3-island-cell-size")
    return fixes


def source_files(root):
    paths = [p for p in (root / "src/main").rglob("*") if p.is_file()]
    paths += [p for p in (root / "src/versions").rglob("*") if p.is_file()]
    paths += [root / p for p in ("build.gradle", "fabric-next.gradle", "fabric-legacy.gradle", "settings.gradle", "gradle.properties",
                               "gradle/minecraft-targets.gradle")]
    return sorted(paths, key=lambda p: p.relative_to(root).as_posix())


def source_digest(root):
    digest = hashlib.sha256()
    for path in source_files(root):
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def verify(jar, platform, build_id, source, minecraft=None):
    def require(condition, message):
        if not condition:
            raise ValueError(message)

    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip() is None, "Corrupt JAR")
        names = set(archive.namelist())
        require(not any(n.endswith(".class") and "/me/cortex/voxy/" in "/" + n for n in names),
                "External Voxy classes packaged")
        # VSS resolves Java dependencies from the loader rather than embedding
        # other mods. Its own five platform native libraries are checked below.
        require(not any(n.lower().endswith(".jar") for n in names),
                "External nested dependency JAR packaged")
        manifest = manifest_attributes(archive.read("META-INF/MANIFEST.MF"))
        require(manifest.get("VSS-Build-Id") == build_id, "Wrong build ID")
        properties = dict(re.findall(r"(?m)^([a-z_]+)=(.+)$", (source / "gradle.properties").read_text()))
        minecraft = minecraft or properties["minecraft_version"].strip()
        version = properties["vss_version"].strip() + "-fabric-" + minecraft
        require(platform == "fabric", "Horizon publishes Fabric artifacts only")
        require(jar.name == "vss-" + version + ".jar", "Wrong standard JAR name")
        require(manifest.get("Implementation-Version") == version, "Wrong implementation version")
        metadata = json.loads(archive.read("fabric.mod.json"))
        require(not metadata.get("jars"), "External nested dependency declared in Fabric metadata")
        require(metadata["id"] == "vss" and metadata["version"] == version, "Wrong Fabric metadata")
        require(metadata["depends"]["minecraft"] == minecraft, "Wrong Minecraft target")
        require(manifest.get("VSS-Minecraft") == minecraft, "Wrong Minecraft build identity")
        target_index = ["1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3", "1.21.1", "1.20.1"].index(minecraft)
        require(manifest.get("VSS-Protocol") == str(54 + target_index), "Wrong target protocol")
        require(manifest.get("VSS-Column-Schema") == str(6 + target_index), "Wrong target column schema")
        require(not any(n in names for n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")), "Old loader metadata packaged")
        for entries in metadata["entrypoints"].values():
            for entry in entries:
                require(entry.replace(".", "/") + ".class" in names, "Missing Fabric entrypoint: " + entry)
        require(metadata["accessWidener"] in names, "Missing access widener")
        major = 44 + (17 if minecraft == "1.20.1" else 21 if minecraft.startswith("1.") else 25)
        for name in names:
            if name.endswith(".class"):
                require(int.from_bytes(archive.read(name)[6:8], "big") == major,
                        "Wrong packaged Java version: " + name)

        def check_class(name, *symbols):
            data = archive.read("dev/xantha/vss/" + name + ".class")
            require(int.from_bytes(data[6:8], "big") == major, "Wrong Java version: " + name)
            for symbol in symbols:
                require(symbol.encode() in data, "Missing reference: " + name + ": " + symbol)
            return data

        check_class("config/VSSServerConfig", "singleplayerGenerationConcurrencyLimit", "effectiveGenerationConcurrencyLimitPerPlayer")
        check_class("networking/server/generation/ChunkGenerationService", "effectiveGenerationConcurrencyLimitPerPlayer")
        check_class("networking/server/session/PlayerSessionManager", "effectiveGenerationConcurrencyLimitPerPlayer")
        check_class("compat/ModCompat", "replayRealXaeroColumn")
        check_class("compat/XaeroMapCompat", "replayRealColumn", "tryOfferReplay")
        check_class("networking/client/IntegratedPregenImporter", "replayRealXaeroColumn")
        check_class("networking/server/generation/ChunkyGenerationService", "retryImport")

        feature_snapshot = class_references(check_class("common/worldgen/VanillaFeatureSnapshot"))
        cherry_owner = ("net/minecraft/class_8178" if minecraft.startswith("1.") else
                        "net/minecraft/world/level/levelgen/feature/foliageplacers/CherryFoliagePlacer")
        cherry_field = "field_42844" if minecraft.startswith("1.") else "cornerHoleChance"
        require(any((cherry_owner, cherry_field, "F") in entries
                    for (method, _), entries in feature_snapshot.items() if method == "correct"),
                "Cherry foliage snapshot does not read the target's actual remapped field")
        verify_version_paths(lambda name: check_class("client/prediction/" + name), names, minecraft)
        worldgen_interface_fixes = verify_worldgen_interface_paths(check_class, minecraft)
        check_class("client/prediction/PredictionClimateSampler", "FabricBiomeCompat", "copyClimateContext")
        check_class("client/prediction/FabricBiomeCompat", "net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks", "fabric_getSeed", "fabric_setSeed")
        check_class("client/prediction/PredictionBiomeCache", "LookupFailure", "sample")
        check_class("client/prediction/PredictionBiomeCache$Points", "xs", "ys", "zs", "values")
        check_class("client/prediction/PredictionBiomeCache$Local", "PredictionBiomeCache$Points")
        check_class("client/prediction/PredictionBiomeCache$Stripe", "referenced", "next", "get", "put")
        check_class("compat/VoxyPendingSections", "poll", "promote", "priority", "view")
        check_class("compat/VoxyPredictionBridge", "newSingleThreadScheduledExecutor", "updateBatches", "emptyWithdrawals")
        check_class("compat/VoxyPredictionBridge$Context", "nextNotification", "meshBacklog", "mayHavePrediction")
        check_class("compat/VoxyRenderAccess", "meshBacklog")
        check_class("client/prediction/PredictionRetirementQueue", "submit", "drain", "Command", "active")
        check_class("client/prediction/PredictionVoxySource", "removeLater", "mayHavePrediction", "pendingRetirements", "retiring")
        check_class("client/prediction/PredictionTileManager", "removeLater", "reclaimAdmissionTarget")
        check_class("compat/VoxySectionData", "contentIdentity", "faceIdentity")
        check_class("compat/VoxySectionSignature", "compact")
        check_class("compat/VoxySectionSignature", "face", "EMPTY_FACE")
        check_class("compat/VoxyPredictionBridge$Delivered", "realFaces")
        check_class("compat/VoxyPredictionBridge$Context", "realFaces", "face")
        check_class("client/prediction/PredictionVoxySource", "protectedBytes", "trimProtected", "retainedBytes")
        check_class("client/prediction/PredictionVegetation", "restoreForRendering")
        check_class("client/prediction/PredictionVegetation$CachedTile", "complete", "tile")
        check_class("client/prediction/PredictionTileManager", "surfaceCacheAttempts", "restoreForRendering")
        bridge_context=check_class("compat/VoxyPredictionBridge$Context", "notificationIdentity", "earlyUnchangedNotifications", "revokeInput", "revokeNeighborInputs")
        require(b"realRevision" not in bridge_context, "Global native revision still invalidates unrelated reuse")
        check_class("client/prediction/PredictionVoxySource", "notificationIdentity", "cachedNotificationView", "NOT_CACHED", "readFace")
        terrain_change=class_references(check_class("client/prediction/PredictionTerrainChange", "compare", "ordered"))
        liquid_type="net/minecraft/class_2404" if minecraft.startswith("1.") else "net/minecraft/world/level/block/LiquidBlock"
        require(any((liquid_type,"<instanceof>",None) in entries
                    for (method,_),entries in terrain_change.items() if method=="compare"),
                "Terrain interval comparison omitted liquid surface remainders")
        check_class("client/prediction/PredictionVoxySource", "PredictionTerrainChange", "sameFeatureBiomes", "terrainRangeReductions")
        check_class("client/prediction/PredictionVegetation", "END_CLIMATE_SETTINGS_VERSION",
                    "REGISTRY_CONTEXT_SETTINGS_VERSION", "LookupFailure")
        registries = class_references(check_class("client/prediction/ClientWorldgenRegistries"))
        builtin_owner = ("net/minecraft/class_7923" if minecraft.startswith("1.") else
                         "net/minecraft/core/registries/BuiltInRegistries")
        builtin_field = "field_41167" if minecraft.startswith("1.") else "REGISTRY"
        require(any(owner == builtin_owner and member == builtin_field
                    for (method, _), entries in registries.items() if method == "<init>"
                    for owner, member, _ in entries),
                "Static worldgen registry fallback is not prepared during codec initialization")
        encoder = class_references(check_class("networking/server/session/WorldgenCodecSnapshot", "preferred_biomes"))
        rings_owner = ("net/minecraft/class_6871" if minecraft.startswith("1.") else
                       "net/minecraft/world/level/levelgen/structure/placement/ConcentricRingsStructurePlacement")
        rings_method = "method_41631" if minecraft.startswith("1.") else "preferredBiomes"
        require(any(owner == rings_owner and member == rings_method
                    for (method, _), entries in encoder.items() if method == "encodeEntry"
                    for owner, member, _ in entries),
                "Stronghold biome preference is not read by the production snapshot encoder")
        check_class("client/prediction/PredictionSurfaceStructures", "LookupFailure")
        inset = check_class("compat/VoxySurfaceHeights", "patchShader", "// VSS world-space model inset")
        for retired in (b"vssSurfaceDrop", b"HEIGHT_BITS", b"org/lwjgl/system/MemoryUtil"):
            require(retired not in inset, "Retired post-cull geometry path: " + retired.decode())
        for method in (b"correct", b"encode"):
            # Class-file UTF8 constants carry a two-byte length; exclude longer local names.
            require(len(method).to_bytes(2, "big") + method not in inset,
                    "Retired mesh method: " + method.decode())
        require(not any(n.startswith("dev/xantha/vss/compat/VoxySurfaceHeights$") for n in names),
                "Stale native geometry accessor packaged")
        for cls in ("compat/VoxySectionData", "compat/VoxyPredictionBridge", "client/prediction/PredictionVoxySource"):
            require(b"surfaceHeights" not in check_class(cls), "Retired height metadata: " + cls)
        bridge = check_class("compat/VoxyPredictionBridge", "levelUnloaded", "confirmedPredictionColumns")
        require(b"VoxySurfaceHeights" not in bridge, "Native mesh is still post-processed")
        check_class("mixin/voxy/PredictionVoxyTraversalShaderMixin", "VoxySurfaceHeights", "patchShader")
        check_class("client/prediction/ClientPredictionState", "LEVEL_GENERATION", "levelUnloaded")
        check_class("client/prediction/ClientPredictionState", "retireUnusedSampler", "VSS prediction cache ready:", "VSS prediction generation ready:")
        check_class("client/prediction/PredictionCacheSampler", "Cache context cannot generate terrain", "cacheBiomes", "templates", "floating")
        check_class("client/prediction/PredictionCacheStorage", "openExisting")
        if minecraft.startswith("1."):
            check_class("client/prediction/PredictionCacheStorage", "prediction/minecraft-"+minecraft+"/surface-v1")
        check_class("client/prediction/ClientWorldgenProfileDecoder$Session", "PredictionCacheSampler", "cacheContext")
        check_class("client/prediction/PredictionTileManager", "generationReady", "acceptsGeneration", "enableGeneration")
        check_class("client/prediction/PredictionResources", "retireUnusedSampler")
        check_class("client/prediction/PredictionVoxelCache", "java/security/DigestInputStream", "voxel record has trailing data")
        check_class("networking/client/VSSClientNetworking", "onClientLevelUnload")
        check_class("compat/VoxyCompat$LocalSectionIndex", "settledRegions")
        check_class("compat/VoxyIngestTracker", "realColumnSettled")
        check_class("compat/VoxyPredictionBridge$Context", "hasSkyLight")
        check_class("compat/VoxyCapacityPlan", "initialCapacity", "capacity")
        check_class("compat/VoxyTraversalCapacity", "getCurrentMaxNodeId", "beforeAdd", "beforeTraversal", "ensureRoots", "ensureQueues")
        check_class("compat/VoxyTraversalCapacity$1", "glCopyNamedBufferSubData", "free")
        traversal_shader = check_class("compat/VoxyTraversalShader", "// VSS bounded traversal queues", "nodeQueueSource.length()", "enqueueSelfForRender")
        require(b"atomicCompSwap" not in traversal_shader, "Serial GPU reservation retry loop packaged")
        check_class("mixin/voxy/VoxyTraversalCapacityMixin", "vss$initialRootArray", "vss$initialQueues", "beforeTraversal")
        check_class("mixin/voxy/VoxyTraversalQueueShaderMixin", "VoxyTraversalShader")
        capacity_mixins = json.loads(archive.read("vss.compat.mixins.json"))["client"]
        require(all(name in capacity_mixins for name in ("voxy.VoxyTraversalCapacityMixin", "voxy.VoxyTraversalQueueShaderMixin")),
                "Capacity mixins absent from runtime configuration")
        check_class("client/prediction/PredictionVoxySource", "lightingBuilds")
        check_class("client/prediction/PredictionTileManager", "invalidatedSurfaces")
        check_class("client/prediction/PredictionCaptureHandoff", "await", "committed", "blocked", "reindex", "retired")
        check_class("client/prediction/PredictionTileManager", "awaitExactColumns", "requiresExactCapture", "retryCapture", "captureHandoff", "retireExactColumns")
        check_class("networking/client/VSSClientNetworking", "dirtyColumnTimestamp", "expectsAuthoritativeColumns")
        check_class("networking/client/LodRequestManager", "onDirtyColumnsRetired")
        check_class("client/prediction/PredictionVoxelData", "terrainRevision")
        check_class("client/prediction/PredictionVoxelData", "cacheContentIdentity")
        check_class("client/prediction/PredictionVoxySource$Tile", "reuseContent")
        check_class("client/prediction/PredictionVoxySource", "vegetationVisible")
        check_class("client/prediction/PredictionTileManager", "surfaceFallbackReady")
        check_class("client/prediction/PredictionVoxyBoxes", "changedBounds")
        check_class("client/prediction/PredictionDiskCache", "voxelWrites", "unchangedVoxelWrites", "contentIdentity")
        check_class("client/prediction/PredictionVoxelCache", "FEATURE_PAGE_SIZE", "contentIdentity", "voxel feature page invalid")
        check_class("compat/VoxySectionSignature", "SHA-256", "faces")
        check_class("compat/VoxyPredictionBridge$Delivered", "changes")
        check_class("compat/VoxyPredictionBridge$Context", "notificationContent", "delivered", "unchangedNotifications")
        check_class("client/prediction/PredictionParentLod$Proof", "terrainRevision")
        check_class("client/prediction/PredictionParentLod$Input", "uses")
        check_class("client/prediction/PredictionTileManager", "terrainCellAxis")
        check_class("client/prediction/PredictionTileManager", "PredictionTileStates", "tileStates")
        check_class("client/prediction/PredictionWorkOrder", "coveragePriority")
        check_class("client/prediction/PredictionSchedulePreparation$Facts", "coveragePriority")
        check_class("client/prediction/PredictionPlanPreparation", "nativeCoverage")
        check_class("client/prediction/PredictionVoxyWorkPreparation", "coveragePriority")
        check_class("client/prediction/PredictionDiskCache", "resetTerrainProbes", "maximumWaitNanos")
        check_class("client/prediction/PredictionTileManager", "coveragePriority", "resetTerrainProbes")
        check_class("client/prediction/PredictionTileStates", "AtomicLongArray", "membership", "generation")
        check_class("client/prediction/PredictionInputSnapshots", "SetSource", "MapSource")
        check_class("client/prediction/PredictionSchedulePreparation$Facts", "projected", "distance", "priority")
        check_class("client/prediction/PredictionLoadingPreparation$Calculation", "Facts", "reads")
        check_class("client/prediction/PredictionVegetationBlocks", "positions", "palette", "table")
        check_class("client/prediction/PredictionVegetation$Tile", "forEachVoxel", "order", "cells")
        check_class("client/prediction/PredictionVegetationDisplayCache", "PredictionVegetationBlocks$Builder", "bytes")
        check_class("client/prediction/PredictionVoxySource", "forEachVoxel")
        check_class("client/prediction/PredictionVoxySource$VolumeScratch", "mappedRuns", "mappedRunCount", "materials", "clear")
        check_class("client/prediction/PredictionVoxyMaterials", "reset", "clear", "table", "lastValue")
        require("dev/xantha/vss/client/prediction/PredictionVoxyMaterials$Key.class" not in names,
                "Stale object material pair key packaged")
        check_class("compat/VoxySectionData", "BOUNDARIES", "MAX_BOUNDARIES")
        check_class("compat/VoxyNotificationScratch", "ThreadLocal", "borrow", "close")
        check_class("compat/VoxyPredictionBridge$Context", "VoxyNotificationScratch")
        check_class("client/prediction/PredictionVoxyBoxes", "retainVegetation", "geometryHash", "sameGeometry")
        check_class("client/prediction/PredictionVoxyLighting", "propagate")
        check_class("client/prediction/PredictionVoxelCache", ":floating-columns-r1")
        check_class("client/prediction/PredictionExteriorColumns", "enrichFloating")
        check_class("compat/VoxySubdivisionWaiters", "needsSamples")
        check_class("client/prediction/PredictionInputSnapshots$TrackedSet", "changed", "shards", "ShardSet")
        check_class("client/prediction/PredictionInputSnapshots$TrackedMap", "changed", "shards", "ShardMap")
        shards = check_class("client/prediction/PredictionInputSnapshots", "shard")
        require(bytes.fromhex("0385ebca6b") in shards and bytes.fromhex("03c2b2ae35") in shards,
                "Missing independent shard hash mixing constants")
        check_class("client/prediction/PredictionVoxyFeatures", "intersect", "visit", "quickSort", "bounds")
        check_class("client/prediction/PredictionVoxyBoxes", "coordinate", "WeakReference", "pageStorage")
        check_class("client/prediction/PredictionVoxyBoxes", "CONTENTS", "shareContents", "index")
        check_class("client/prediction/PredictionVoxyFeatures", "partition", "compare")
        check_class("client/prediction/PredictionDiskCache$Shared", "surfaceInvalidations")
        check_class("client/prediction/PredictionDiskCache", "invalidated", "deleteSurfaces")
        check_class("client/prediction/PredictionRegionStorage", "deleteColdRegion", "deleteSurfaces")
        check_class("client/prediction/PredictionVoxySource", "columnClock", "FACE_BUFFERS", "compactFaceCopy")
        check_class("client/prediction/PredictionVoxyBoxes$Data", "index", "limits")
        check_class("client/prediction/PredictionVoxyBoxes", "append", "indexBytes")
        check_class("client/prediction/PredictionDetailTrace", "dirtyAgeSeconds", "demandAgeSeconds", "lastEvent")
        check_class("client/prediction/PredictionVoxySource", "copyFace", "COLUMN_SCRATCH", "VOLUME_SCRATCH", "COLUMN_PROBE")
        check_class("compat/VoxyPredictionBridge", "NEIGHBOR_BUFFER", "copyFace")
        check_class("client/prediction/PredictionCapturedTerrain", "decoding", "coalescedReads", "generation")
        check_class("client/prediction/PredictionCapturedTerrain$Summary", "columns")
        check_class("client/prediction/PredictionDecorationLevel", "LocalPlacementFailure")
        check_class("client/prediction/PredictionVegetation", "localFailures", "LocalPlacementFailure")
        check_class("client/prediction/PredictionTileManager", "detailPipelineSnapshot", "oldestIncomplete", "visibleRemaining")
        require("dev/xantha/vss/client/prediction/PredictionVoxyFeatures$Node.class" not in names, "Object feature tree packaged")
        check_class("client/prediction/PredictionVoxyDetailQueue", "maintainResidency", "dormant")
        check_class("client/prediction/PredictionVoxySource", "selectOwners", "featureIndex")
        check_class("client/prediction/PredictionVoxySource$ColumnKey", "PredictionKeyHash")
        check_class("client/prediction/PredictionMemoryBudget", "unobservedReleaseBytes", "refreshCollections")
        check_class("common/StrictLodFrontier", "blockedStage")
        check_class("compat/VoxySectionPruner", "completedAdditions", "prune")
        check_class("compat/VoxySectionVersions", "additions")
        check_class("compat/VoxyNodeChildren", "emptyNonRoot")
        check_class("compat/VoxyPredictionBridge$Context", "VoxySectionPruner")
        check_class("compat/VoxyPredictionBridge", "VoxyNodeChildren")
        check_class("compat/VoxyPredictionBridge", "copyNeighborFaces", "mergeRealFace", "SnapshotTarget")
        check_class("compat/VoxySectionData", "copyFace", "VoxyFaceData")
        check_class("client/prediction/PredictionVoxySource", "reducedColumn", "copySection", "mappedFaces",
                    "unchangedPublications", "retainedColumnPublications", "changedTerrain")
        check_class("client/prediction/PredictionVoxyColumnRuns$Builder", "arraycopy", "binarySearch")
        check_class("client/prediction/PredictionVoxyColumnRuns$Coverage", "covers", "put")
        check_class("client/prediction/PredictionVoxySource$CachedColumn", "referenced")
        check_class("client/prediction/PredictionVoxySource", "columnSecondChances")
        check_class("client/prediction/PredictionVoxySource", "columnPageBuilds", "featureContributors", "publicationChange")
        check_class("client/prediction/PredictionVoxySource$VolumeScratch", "columnPages", "featureContributors")
        check_class("client/prediction/PredictionVoxyColumnRuns$Page", "offsets", "write")
        check_class("client/prediction/PredictionVoxyColumnRuns$PageBuilder", "append")
        check_class("client/prediction/PredictionVoxyChange", "precedes", "bitCount")
        check_class("client/prediction/PredictionVoxyChange", "computeIfAbsent", "horizontal", "peerVisits")
        check_class("client/prediction/PredictionSectionIndex", "references", "levels", "intersect", "collect")
        check_class("client/prediction/PredictionVoxySource", "cachedPositions", "changeInput", "changeCurrent", "prepareInvalidation", "refreshDetails")
        check_class("client/prediction/PredictionTileManager", "voxyDemand", "voxyDemandRefill", "PredictionVoxySource$DetailInput")
        check_class("client/prediction/PredictionVoxyViewDemand", "collect", "projectedSize", "hasProjection")
        check_class("client/prediction/PredictionVoxyHorizon", "changes", "intersects", "affected", "clips")
        check_class("client/prediction/PredictionVoxySource", "PredictionVoxyViewDemand", "observedView", "viewDemandBuilds",
                    "PredictionVoxyHorizon", "windowUpdates", "updateWindow")
        check_class("client/prediction/PredictionVoxyDetailQueue", "observe")
        check_class("client/prediction/PredictionWorkView", "hasProjection", "atan")
        check_class("client/prediction/PredictionRenderer", "projection", "scoping", "ageSeconds")
        check_class("compat/VoxySectionSignature", "asLongBuffer", "java/nio/LongBuffer", "SHA-256")
        check_class("client/prediction/PredictionInputSnapshots$Union", "buckets", "snapshot")
        check_class("client/prediction/PredictionTileManager", "PredictionInputSnapshots$Union", "keys")
        check_class("client/prediction/RustWorldgenDocument", "vss_dimension_bounds")
        for native in [n for n in names if n.startswith("META-INF/vss-natives/") and n.endswith((".dll", ".so", ".dylib"))]:
            require(b"vss_dimension_bounds" in archive.read(native), "Native missing dimension bounds: " + native)

        check_class("mixin/voxy/PredictionVoxyNeighborSourceMixin", "copyNeighborFaces", "VoxyFluidSurface", "vss$packFluidHeight", "vss$liquidTransitions")

        check_class("compat/VoxyPredictionBridge", "updatePredictionState")
        check_class("compat/VoxyPredictionBridge$Context", "withdrawals", "withdraw")
        require(any(n.startswith("dev/xantha/vss/compat/VoxyRenderAccess$") and n.endswith(".class")
                    and b"submitGeometryResult" in archive.read(n) and b"empty" in archive.read(n) for n in names),
                "Missing native empty geometry adapter")
        check_class("client/prediction/ClientPredictionState", "updatePredictionState")
        cpu_budget = check_class("client/prediction/PredictionCpuBudget", "generationWaiting", "generationWaitAt", "mode=configured", "detailLimit")
        require(not any(marker in cpu_budget for marker in (b"getCpuLoad", b"getProcessCpuLoad", b"METRIC_SAMPLER", b"DETAIL_TRICKLE_NANOS", b"RECOVERY_NANOS", b"pressure")),
                "Machine-wide CPU throttling still packaged")
        require("dev/xantha/vss/client/prediction/PredictionCpuBudget$ThrottleLevel.class" not in names,
                "Stale CPU throttle class packaged")
        require("dev/xantha/vss/client/prediction/PredictionCpuBudget$MetricSample.class" not in names,
                "Stale CPU sampler class packaged")
        priority = check_class("networking/client/PredictionGenerationPriority", "shared-", "detail-ready")
        require(b"exclusive" not in priority and b"STALL" not in priority, "Exclusive prediction generation gate packaged")
        requests = check_class("networking/client/LodRequestManager", "reserveLocalGeneration")
        require(b"localGenerationLimit" not in requests, "Non-atomic generation admission still packaged")

        fluid = check_class("compat/VoxyFluidSurface", "top", "pack", "transitions", "meshTransitions")
        # 1.21.11 remapJar uses the pinned intermediary mapping for LiquidBlock;
        # 26.x artifacts retain Mojang names. Require the actual type instruction
        # in both admission paths, not a named string left in the constant pool.
        liquid = "net/minecraft/class_2404" if minecraft.startswith("1.") else "net/minecraft/world/level/block/LiquidBlock"
        fluid_references = class_references(fluid)
        for method in ("body", "top"):
            require(any((liquid, "<instanceof>", None) in entries
                        for (declared, _), entries in fluid_references.items() if declared == method),
                    "Missing liquid type check: VoxyFluidSurface." + method + " -> " + liquid)
        check_class("compat/VoxySurfaceHeights", "// VSS liquid surface remainder", "vssLiquidDrop")
        check_class("compat/VoxySurfaceHeights", "normalizeLegacyFluidInset", "LEGACY_FLUID_INSET")
        check_class("mixin/voxy/StrictVoxyIngestPlugin", "submittedSnapshot", "submit")
        check_class("mixin/voxy/VoxyRenderDistanceMixin", "vss$projectionFarPlane",
                    "vss$viewportFarPlane" if minecraft == "26.3" else "vss$projectionRangeFarPlane")
        check_class("compat/VoxyIngestTracker", "ENGINE_COLUMNS", "completion")
        check_class("compat/VoxyCompat", "confirmNativeColumn")
        check_class("client/prediction/PredictionVoxySource", "VoxyFluidSurface")
        check_class("mixin/voxy/StrictVoxyVisibilityMixin", "observeUnifiedView")
        check_class("client/prediction/PredictionRenderer", "observeUnifiedView", "width", "height")
        check_class("client/prediction/PredictionViewObservation", "height", "selectionScale")
        check_class("client/prediction/PredictionVoxyDetailQueue", "deferredEntries", "deferredOrder", "rebalance", "membershipRevision")
        check_class("client/prediction/PredictionVoxySource", "detailSnapshotRevision", "detailWaiting", "revision")
        state = check_class("client/prediction/ClientPredictionState", "setNativeDetailLoading")
        require(b"updateViewFocus" not in state, "Independent scope raycast still scheduled")
        check_class("client/prediction/PredictionPlanPreparation$Input", "nativeDemand")
        check_class("client/prediction/PredictionPlanPreparation", "bootstrap")
        require("dev/xantha/vss/mixin/voxy/IrisVoxyPredictionMixin.class" not in names, "Retired Iris observer packaged")
        enabled = set()
        for name in names:
            if name.endswith(".mixins.json"):
                config = json.loads(archive.read(name))
                compatibility = config.get("compatibilityLevel", "JAVA_8")
                require(re.fullmatch(r"JAVA_\d+", compatibility) is not None
                        and int(compatibility.removeprefix("JAVA_")) <= major - 44,
                        "Mixin requires a newer Java runtime than the target: " + name)
                for cls in config.get("mixins", []) + config.get("client", []) + config.get("server", []):
                    path = config["package"].replace(".", "/") + "/" + cls.replace(".", "/") + ".class"
                    require(path in names, "Missing configured mixin: " + path)
                    enabled.add(path)
        require("dev/xantha/vss/mixin/voxy/PredictionVoxyTraversalShaderMixin.class" in enabled,
                "Model inset shader mixin is not configured")
        for mixin in ("NormalRenderPipelineDepthMaskMixin", "PredictionFogShaderMixin"):
            require("dev/xantha/vss/mixin/voxy/" + mixin + ".class" in enabled,
                    "Normal depth bridge mixin is not configured: " + mixin)
        if minecraft == "1.20.1":
            for mixin in ("VoxyServerLodClientMixin", "VoxyServerLodServerMixin", "VoxyServerLodPlayerStateMixin"):
                require("dev/xantha/vss/mixin/voxy/" + mixin + ".class" in enabled,
                        "Native server LOD arbitration is not configured: " + mixin)
            check_class("compat/VoxyServerLodClientCompat", "beginNativeColumn", "ingestNativeSection", "nativeColumnVersionCurrent")
            check_class("compat/VoxyServerLodServerCompat", "reconcile", "releaseOutgoing")
        require(not any(n.endswith("Test.class") or n.startswith("xaero/") for n in names),
                "Test or foreign fixture classes packaged")
        require(not any(n.startswith("dev/xantha/vss/smoke/") for n in names),
                "Development-only client smoke classes packaged")
        check_class("VSSMod", "net/fabricmc/api/ModInitializer")
        check_class("client/VSSClientBootstrap", "net/fabricmc/api/ClientModInitializer")
        require(manifest.get("VSS-Platform") == "fabric", "Missing Fabric build identity")
        # Loom remaps production classes from Mojang to intermediary names, so
        # comparing these bytes directly with named javac output is invalid.
        # Check the input archive separately before comparing the source digest.
        digest = source_digest(source)
        require(manifest.get("VSS-Source-SHA256") == digest, "Source tree differs from packaged snapshot")
        natives = {}
        for name in sorted(names):
            if name.startswith("META-INF/vss-natives/") and name.endswith((".dll", ".so", ".dylib")):
                require(archive.read(name) == (source / "src/main/resources" / name).read_bytes(),
                        "Native library differs from source: " + name)
                require(b"find_top_surface" in archive.read(name), "Stale native library without the new height router: " + name)
                natives[name] = hashlib.sha256(archive.read(name)).hexdigest()
        require(len(natives) == 5, "Expected all five native libraries")
        native_build = json.loads(archive.read("META-INF/vss-native-build.json"))
        require(native_build["sourceSha256"] == native_source_digest(source),
                "Rust source differs from the tested native libraries")
        expected_native_paths = {"META-INF/vss-natives/" + platform + "/" + filename
                                 for platform, filename in LIBRARIES.items()}
        require(set(natives) == expected_native_paths, "Unexpected native platform paths")
        require({name.removeprefix("META-INF/vss-natives/"): value for name, value in natives.items()}
                == native_build["libraries"], "Native CI checksums differ from packaged libraries")
    return {"jar": jar.name, "platform": platform, "buildId": build_id,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "bytes": jar.stat().st_size, "nativeSha256": natives,
            "worldgenInterfaceCheck": {"kind": "artifact-presence-reference", "gameplayValidated": False},
            "integratedFixes": ["view-priority-background-notifications", "asynchronous-budgeted-retirement", "off-lock-snapshot-disposal", "native-empty-fast-withdrawal", "steady-state-lock-bypass", "configured-shared-cpu-admission", "two-stage-warm-cache-startup", "in-place-native-backend-promotion", "streamed-voxel-content-digest", "stale-prepared-backend-retirement", "native-border-consumer-evidence", "byte-bounded-section-admission", "independent-partial-surface-cache-restore", "early-identical-input-notification", "answer-budget-biome-cache", "terrain-interval-publication", "shared-quart-neighborhoods", "persistent-fair-generation-turn", "community-explicit-far-plane", "community-delegated-ingest-completion", "engine-scoped-real-column-settlement", "community-native-inset-preservation", "legacy-fluid-inset-normalization",
                                *worldgen_interface_fixes,
                                *(["native-server-stream-ownership", "native-column-success-receipts", "native-column-timestamp-order"] if minecraft == "1.20.1" else []),
                                "static-worldgen-codec-context", "inline-stronghold-biome-preference", "registry-context-surface-cache", "versioned-resource-atlas-token", "horizon-planned-root-capacity", "resident-node-traversal-capacity", "non-spinning-gpu-queue-bounds", "two-phase-dirty-capture-handoff", "bounded-capture-retry", "dirty-retention-buffer-fetch", "retired-refresh-barrier", "seeded-fabric-end-biomes", "failed-biome-surface-rejection", "end-decoration-cache-repair", "compact-column-pages", "contributing-feature-query", "native-owner-publication",
                                "layout-plan-domain-reset", "partial-vegetation-retention", "terrain-only-parent-proof",
                                "retained-deferred-detail-demand", "view-priority-readmission", "completion-driven-detail-refill", "dimension-write-bounds", "reverse-coarse-column-coverage", "second-chance-column-cache", "incremental-candidate-union", "exact-display-biome-reuse", "prediction-display-withdrawal", "warm-toggle-reactivation", "concurrent-real-generation", "atomic-generation-admission", "auxiliary-viewport-observer-guard", "native-detail-demand", "authoritative-viewport", "spatial-bootstrap-only", "liquid-surface-remainders", "native-liquid-transition-walls", "native-lod-topology", "world-space-model-inset", "nether-lighting",
                                "end-floating-columns", "empty-node-sample-readmission",
                                "dimension-transition", "real-air-authority",
                                "incremental-snapshots", "scheduled-range-pruning",
                                "frontier-blocker-probe", "empty-child-wakeup", "native-neighbor-faces",
                                "reusable-coarse-column-runs", "direct-section-destination", "bounded-face-cache",
                                "content-aware-publication", "local-terrain-invalidation", "independent-snapshot-shards", "feature-bounds-index", "projected-tile-ownership", "mixed-column-footprints", "batched-gc-observation", "incremental-plan-candidates",
                                "coordinate-surface-invalidation", "slot-local-cold-deletion", "allocation-free-column-clock",
                                "compact-neighbor-planes", "lazy-shared-page-index", "per-revision-detail-trace", "display-parent-reduction", "stable-terrain-publication",
                                "precise-final-voxel-notification", "paged-complete-vegetation-cache",
                                "deduplicated-fast-voxel-save", "reclaim-preserves-parent-inputs",
                                "shared-tile-state-pages", "reused-scheduling-facts", "packed-vegetation-display",
                                "reusable-material-workspace", "bounded-section-boundaries", "reused-notification-faces",
                                "partial-vegetation-lod-union", "complete-surface-eviction-fallback",
                                "active-projected-sample-demand", "zoom-sensitive-view-refresh", "prediction-horizon-mask",
                                "indexed-horizon-invalidation",
                                "off-lock-publication-evidence", "shared-horizontal-change-evidence", "spatial-cache-invalidation",
                                "asynchronous-native-demand-handoff", "bulk-exact-section-signatures", "bounded-middle-cover", "first-terrain-priority", "bounded-cache-probe-wait", "offscreen-retirement-without-generation"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--platform", choices=["fabric"], required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--minecraft")
    args = parser.parse_args()
    print(json.dumps(verify(args.jar, args.platform, args.build_id, args.source, args.minecraft), indent=2))
