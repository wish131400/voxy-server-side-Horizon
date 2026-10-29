# VSS source-built Rust worldgen core

Horizon Fabric 0.3.6. The shared core originated in the VSS NeoForge project.
Minecraft-specific schema and algorithm changes are verified per target; see the
version status in the repository README rather than assuming all versions match.

The implementation follows the mapped Minecraft classes and effective runtime
registries. Offline oracles call the actual Minecraft methods. Saved-world
captures are supplementary regression data; they do not define the algorithm.

## Production integration

`ClientWorldgenProfileDecoder` selects `RustTerrainSampler` for supported noise
generators when the independently named `vss_native_core` library is available.
The worldgen interface is JNI ABI **6** (`RustWorldgenBackend`); the noise-only
probe keeps ABI 1 for validation. The release packages the same core for
**Windows x86_64, Linux x86_64/aarch64, macOS x86_64/aarch64**.
Unknown targets use the Java sampler. The only production native backend is this core.

ABI 3 adds `previewPoints`: grids below the full terrain axis use raw density
at 16-block vertical intervals and refine the detected crossing to 4 blocks.
Preview material selection runs the world surface rules with approximate depth
and no neighbour geometry. Aquifers, ores, structure blending and vegetation
are deferred to exact refinement. Flag bit 27 survives disk serialization;
exact grids reject these retained columns and resample them. Preview results
never enter exact native or Java point/chunk caches. Existing exact terrain
cache identity remains valid. The Java fallback uses the same explicit preview
stage and persisted approximation flag. It retains Java density/surface rules,
uses one stable preliminary-height anchor per surface cell, and omits preview
steepness scans. NoiseChunk-backed exact columns stop at the exterior through
Minecraft's original iterator. FreeTerraForged selects erosion by stage rather
than spacing and isolates approximate biome/material caches from exact work.

ABI 4 adds `displayPoints` for full-resolution display terrain before exact
surface decoration. It searches density-cell intervals on qualifying dry land,
retains original aquifer traversal for coasts and custom fluid routers, and
summarizes three surface materials using the original rules and local slope.
Compatible uniform 8x8 grids probe the perimeter and four centre points before
interpolating interiors. This is an error indicator, not a guaranteed maximum
error bound. Structures, stateful graphs, geometry-changing rules and special
surface biomes retain the exact fallback. Approximate records carry bits 26/27;
only exact fallback records may populate exact caches. Existing exact disk cache
identities stay valid, and exact decoration rejects display approximations. Work
contexts and display results have separate bounded caches.

ABI 5 adds `decorationProxy`, `decorationPoints` and `decorationQueryStats`.
Prediction vegetation requests lazy 4x4 pages instead of entire 16x16 neighbour
chunks. Explicit visual plant jobs can reuse the display cache; structures and
unknown feature implementations keep exact ground queries. Original `surfaceProxy`
semantics remain available for exact callers and differential benchmarks. Java
and native display vegetation share the same column records. Query statistics
are reported on demand rather than printed per feature. Final plant caches use
a separate policy bit; exact terrain caches remain unchanged.

ABI 6 adds `interiorColumns` and decoration policy 2 (complete lazy interior
columns). Nether surfaces evaluate every solid run, and supported cave features
share native edits with the Java compatibility stream. `columns` retains its
uncoated density semantics. Block definitions now carry default-state
replaceability for huge-fungus placement; absent metadata causes Java fallback
instead of guessing. The Java surface, decoration and structure regression fixtures verify these
boundaries separately from final in-game chunk generation.

The 2026-10-25 ABI 6 build also adds the optional `displayGrid` entry. It accepts
an 8x8 grid origin, spacing and a row-major 64-bit missing-cell mask, and writes
64 ten-int32 records. Java consumes only missing records and retains known exact
values. This preserves adaptive grid admission after partial Java cache reuse;
ineligible grids evaluate only missing points. Adaptive probes use consistent
display provenance even if an exact point is already cached, without writing
display approximations into exact caches. External ABI 6 libraries without this
symbol retain the existing sparse `displayPoints` fallback. Existing JNI entries,
the ABI number, network protocol and exact disk-cache identity are unchanged.

The production libraries are built by `.github/workflows/native.yml` on five
matching Windows, Linux and macOS runners. Each runner executes the native
algorithm regressions and the legacy/26.3 vegetation reference tests. Linux
artifacts are linked with pinned Zig 0.13.0 / cargo-zigbuild 0.23.4 for a glibc
2.28 baseline; macOS uses an explicit 11.0 deployment target.

`tools/verification/install_native_artifacts.py --run <successful-run-id>` checks
that the tested commit matches every local Rust source file before installing
all five libraries. `META-INF/vss-native-build.json` records the CI commit,
normalized Rust source digest and binary hashes. Release verification checks
these records against both the source tree and the packaged JAR.

`audit-native-package.py <jar> --output <report.json>` additionally inspects
architecture, exported JNI symbols, dependencies, Linux symbol versions and
macOS deployment/signature data (requires `lief==1.0.0`). Native runner tests do
not establish full Minecraft client compatibility on all operating systems.
Voxy's OpenGL requirements still apply; standard macOS drivers do not provide
the OpenGL 4.6 path required by current Voxy releases.

The source-built sampler performs noise, density interpolation, base columns,
aquifers, surface rules, biome lookup and grass/foliage/water tint calculations
in Rust. Java transfers registry/BlockState/colormap data and owns Minecraft
models, textures and GPU resources. Near sampling uses compact complete surface
chunks; sparse distant queries preserve the surface pass's neighbour/order
semantics. Predecessor rules which can remove the top to air retain the full
chunk path; solid-to-water rules preserve WORLD_SURFACE_WG height and do not
force distant samples to generate complete chunks. LOD spacing does not select a different material algorithm.

`RustVegetationStage` runs supported placed features in native block storage,
using the original global feature order, seed derivation and modifiers. A
5x5-chunk read neighbourhood has a 3x3-chunk write region, matching the existing
prediction context. Previously placed Java structure edits are uploaded before
vegetation. Native edits are validated and drained after each feature, so Java
compatibility features see the same ordered mutations. Unsupported codecs retain
the Java compatibility path. A failed native feature rolls back blocks and both
random streams before a compatibility retry.

The runtime diagnostics include `backend=vanilla-rust-abi2-r3`, `nativeFeatures`
and `compatibilityFeatures` in `/vssclient stats`. Ordinary native
initialization/fallback detail is logged only when `debugLogging` is enabled.

## Implemented modules

| Module | Calculation |
| --- | --- |
| `random.rs`, `noise.rs`, `blended_noise.rs` | Legacy/Xoroshiro/WorldgenRandom, positional and decoration seeds, Gaussian, Improved/Simplex/Perlin/Normal/BlendedNoise |
| `density.rs`, `climate.rs` | Density graph, spline, interpolation/cache contexts, Climate RTree, biome source |
| `lithostitched.rs` | Lithostitched FastNoise Lite configuration; additional density operators are in `density.rs` |
| `freeterraforged_filters.rs` | Prediction-owned erosion, smoothing, steepness, beach detection and correction |
| `freeterraforged_noise.rs` | FTF Perlin/Perlin2, Simplex/Simplex2, white and basic composition modules; incomplete FTF generator port |
| `terrain.rs`, `beard.rs` | Base columns, aquifers, ore veinifier, structure terrain adjustment from explicit pieces/junctions |
| `surface.rs` | SurfaceRules, SurfaceSystem, badlands bands/erosion and frozen-ocean geometry |
| `biome.rs` | Grass/leaf/water colors, overrides, swamp/dark forest, temperature, BiomeManager zoom and blend primitive |
| `providers.rs`, `vegetation.rs`, `vegetation/` | State/int providers, placed modifiers, ordinary and large trees, mangroves/cherry, grass/flowers/bamboo, giant mushrooms, vines/cocoa/ground/attached-leaf/bee-nest decorators |
| `decoration.rs`, `backend.rs`, `jni_worldgen.rs` | FeatureSorter, ordered transactions, surface caches, batch buffers, leased handles, cancellation |

The production state export enumerates each block's possible states, including
leaf distance/waterlogging, bamboo age/leaves/stage and double-plant halves.
CherryFoliagePlacer's vanilla codec encodes one field with the wrong getter;
`VanillaFeatureSnapshot` preserves the actual runtime corner-hole probability.
Bee nests use an explicit independent shuffle entropy stream, as vanilla's
`Collections.shuffle` does not use the terrain seed. Prediction volumes do not
create live bee block entities.

Epic Terrain's Y-dependent `cache_2d` now uses native NoiseChunk slice/cell
evaluation with batch CacheOnce counters and eager FlatCache initialization.
Tectonic/Lithostitched installation alone no longer forces Java. Unknown native
codecs still select the existing compatibility path. FreeTerraForged's full
continent/river/climate generator is **not yet native**. Particular Fabric
mod releases require their own actual-JAR compatibility validation.

Tree placement also runs vanilla sapling survival and the ordered
`StructureTemplate.updateShapeAtEdge` pass. Runtime state data includes face
support masks; Rust updates vines, carpets, propagules, snowy ground, cocoa and
double plants. Unknown neighbour-update hooks request Java compatibility and
roll back the native transaction rather than leaving partially updated trees.

## Validation

The legacy fixtures under `tests/fixtures/worldgen` and the numeric kernel
fixtures retain their original Minecraft 1.21.1 provenance. They protect the
inherited algorithms; they do not establish equivalence for newer Minecraft
versions. Fixture provenance and precision limits are documented in
`tests/fixtures/README.md`.

The separate `worldgen263` fixtures invoke the actual Minecraft 26.3 classes:
float density, base columns, terrain adjustment, providers, native vegetation
and modern feature compositions. Their exporters are
`tools/verification/Minecraft263NativeReference.java` and
`tools/verification/Minecraft263VegetationReference.java`. They compare complete
block states and subsequent random values, including nullable provider results
and obstructed placements. Java integration contracts also use the target
version's actual registry codecs and placement implementation.

These controlled-volume results do not prove final generated chunks match after
all carvers, structures and arbitrary mod features. Compilation, native/Java
regressions, client initialization and actual gameplay are recorded separately
in the repository version matrix.

## Build and reproduce

Use the repository Gradle wrapper. Minecraft 1.21.11 requires JDK 21; 26.x
requires JDK 25. Build the current native host library and run its regressions:

```text
cargo test --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml
cargo build --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml --lib
```

The older exporters in `reference.gradle` target the inherited 1.21.1 fixture
schema and need a matching Minecraft classpath. Do not regenerate those files
using a different Minecraft version. The 26.3 Java reference tools use the 26.3
compiled test runtime and actual Minecraft jar.

For generation throughput, run the `worldgen_bench` example. Its timings measure
native generation and cache access rather than Minecraft FPS or GPU cost.

## Remaining integration boundaries

This backend is a **surface prediction** system, not a complete replacement for
all Minecraft chunk statuses. Complete structure starts/references/jigsaw,
carvers, every decoration stage and arbitrary mod hooks are not all ported.
Beardifier accepts explicit structure contexts, but the production sampler does
not yet construct those contexts from vanilla structure starts. Existing Java
village/path/farmland/air-cut/water edits remain active.

Tree correctness depends on the supplied terrain/neighbourhood context. Exact
feature placement in controlled native volumes does not imply complete agreement
with a final vanilla chunk after carvers and every other feature. Exceptional
HashMap tree-bin collision order and wider real-world matrices remain additional
coverage work. Unsupported native features report compatibility requirements;
they are not silently replaced with guessed trees.
Dark-forest and mushroom-island chains include native huge-mushroom generation;
they are tested as complete placed features, together with regular and flower
forests, so a supported tree does not silently fall back because of its mushroom
selector branch.

Native work uses shared immutable state tables, compiled feature caching, chunk
work deduplication, bounded result volumes and compact caches. Eight concurrent
vegetation volumes bound transient native memory; this does not change the
existing overall worker policy. Surface/vegetation disk caching and dirty-column
invalidation retain the save-local `.vss` / per-server layout, with an independent
algorithm key to keep results from different terrain implementations separate.

See [VANILLA_PIPELINE.md](VANILLA_PIPELINE.md) for the original semantic mapping.
The Horizon migration scope and acceptance boundaries are in [PLAN.md](../../PLAN.md).

The reference export now uses format `vss-rust-reference-2` and invokes Minecraft Java directly. Historical format-1 exports remain readable by the validation tools; binary replay of the retired backend is removed.

Tectonic 3.0.26 changes the positional RNG name of `tectonic:parameter/*` to
`minecraft:*` in `NoisesMixin`, while keeping the original octave parameters.
The server now exports these as `noise_seed_aliases` in each generator snapshot;
Rust consumes the aliases only during noise construction. A standalone datapack
without the Tectonic mod does not receive this remapping. Compatibility with a particular Fabric Tectonic release requires its own runtime
validation; the inherited algorithm does not establish that result.

Biome routing preserves Blueprint slice order and the wrapped TerraBlender
region tree. Both the Java fallback and native core reconstruct that order;
a bare Blueprint codec is insufficient because it drops the live routing state.
The snapshot includes nested region metadata, and native capability checks reject
older cores that would ignore it. The generator bytes participate in cache identity.

Validation commands:

```text
cargo test --manifest-path tools/rust/vss-native-core/Cargo.toml --locked --release
```

Use the standard Gradle test task for Java regressions. Optional mod inputs are
forwarded by `tools/prediction/compat-tests.gradle`; real OpenGL tests require
the `fabricGpuContractTest` task and matching Voxy input. Tests needing captured worlds or third-party
jars remain opt-in. Local captures and timing reports are not included in Git.
