package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xantha.vss.api.worldgen.*;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.*;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;
import org.junit.jupiter.api.*;

class TerrainGeneratorSnapshotTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @AfterEach void cleanup() { for (var p : TerrainSnapshots.providers()) TerrainSnapshots.unregister(p.id()); }
    private static JsonObject snapshot(ChunkGenerator generator) throws Exception {
        var access = TerrainPredictionFixtures.access();
        var encoded = WorldgenCodecSnapshot.encodeGenerator(generator, access, new DensityFunctionReferences(),
                new WorldgenRegistryDependencies(access, List.of()));
        return JsonParser.parseString(new String(LodByteCompression.decompress(encoded.bytes(), encoded.compression(),
                encoded.rawSize(), 8 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Test void standardNoiseKeepsExistingDataAndDoesNotDuplicateItsCodec() throws Exception {
        var alias = new NoiseAlias();
        var root = snapshot(new NoiseBasedChunkGenerator(alias.getBiomeSource(), alias.generatorSettings()));
        assertEquals(TerrainPredictionPlan.Kind.NOISE_GRAPH, TerrainPredictionPlan.read(root).kind());
        assertTrue(root.has("settings")); assertTrue(root.has("biome_source"));
        assertFalse(root.has("vss_generator")); assertFalse(root.has("vss_unsupported_reason"));
    }
    @Test void finalVanillaNoiseKeepsItsGraphAndCustomColumnImplementationsRequireProjection() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isFinal(NoiseBasedChunkGenerator.class.getModifiers()));
        var vanilla = snapshot(TerrainPredictionFixtures.noise());
        assertEquals(TerrainPredictionPlan.Kind.NOISE_GRAPH, TerrainPredictionPlan.read(vanilla).kind());
        var changed = snapshot(new ChangedNoise());
        assertEquals(TerrainPredictionPlan.Kind.UNAVAILABLE, TerrainPredictionPlan.read(changed).kind());
        assertTrue(changed.get("vss_unsupported_reason").getAsString().contains("ChangedNoise"));
    }
    @Test void flatWaterAndSolidLayersAreCapturedBeforeTheyBecomeFeaturePlaceholders() throws Exception {
        var access = TerrainPredictionFixtures.access();
        var settings = new FlatLevelGeneratorSettings(Optional.empty(), access.lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS), List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(3, Blocks.STONE));
        settings.getLayersInfo().add(new FlatLayerInfo(2, Blocks.WATER)); settings.updateLayers();
        var flat = new FlatLevelSource(settings);
        flat.getBiomeGenerationSettings(settings.getBiome());
        var root = snapshot(flat);
        assertEquals(TerrainPredictionPlan.Kind.FLAT_LAYERS, TerrainPredictionPlan.read(root).kind());
        var layers = root.getAsJsonObject("flat_layers").getAsJsonArray("layers");
        assertEquals(2, layers.size());
        assertEquals("minecraft:water", layers.get(1).getAsJsonObject().getAsJsonObject("state").get("Name").getAsString());
    }
    @Test void explicitProjectionAllowsOneProviderToCoverCustomGeneratorsWithoutRunningThem() throws Exception {
        var projection = snapshot(TerrainPredictionFixtures.noise()); projection.remove(TerrainPredictionPlan.FIELD);
        projection.remove("vss_generator"); projection.remove("vss_generator_error");
        int[] calls = {0};
        TerrainSnapshots.register(new TerrainSnapshotProvider() {
            public String id() { return "example:common_framework"; }
            public Optional<TerrainSnapshot> capture(TerrainSnapshotContext context) {
                calls[0]++; return Optional.of(TerrainSnapshot.noise(id(), 2, true, projection));
            }
        });
        var root = snapshot(new ChangedNoise());
        assertEquals(1, calls[0]);
        assertEquals("example:common_framework", TerrainPredictionPlan.read(root).backend());
        assertFalse(root.has("vss_unsupported_reason"));
        assertEquals(projection.get("settings"), root.get("settings"));
    }
    @Test void priorityFailureAndCancellationHaveDifferentSemantics() {
        var context = new TerrainSnapshotContext(TerrainPredictionFixtures.noise(), TerrainPredictionFixtures.access(),
                new WorldgenRegistryDependencies(TerrainPredictionFixtures.access(), List.of()));
        TerrainSnapshots.register(provider("example:broken", 10, () -> { throw new IllegalAccessError("optional API changed"); }));
        TerrainSnapshots.register(provider("example:working", 1, () -> TerrainSnapshot.lod("example:working", 1, new JsonObject())));
        assertEquals("example:working", TerrainSnapshots.capture(context).orElseThrow().plan().backend());
        TerrainSnapshots.register(provider("example:cancelled", 20, () -> { throw new java.util.concurrent.CancellationException(); }));
        assertThrows(java.util.concurrent.CancellationException.class, () -> TerrainSnapshots.capture(context));
    }
    @Test void completeProjectionSurvivesAnUnavailableOriginalNoiseContext() throws Exception {
        var projection = snapshot(TerrainPredictionFixtures.noise()); projection.remove(TerrainPredictionPlan.FIELD);
        projection.remove("vss_generator"); projection.remove("vss_generator_error");
        TerrainSnapshots.register(provider("example:projection", 1, () -> TerrainSnapshot.noise("example:projection", 1, false, projection)));
        var root = snapshot(new BrokenSettingsNoise());
        assertEquals(TerrainPredictionPlan.Kind.NOISE_GRAPH, TerrainPredictionPlan.read(root).kind());
        assertEquals(projection.get("settings"), root.get("settings"));
        assertTrue(root.has("vss_generator_error"));
    }
    @Test void independentSourceSurvivesAnUnavailableOptionalNoiseContext() throws Exception {
        var parameters = new JsonObject(); parameters.addProperty("height", 110);
        TerrainSnapshots.register(provider("example:source", 1, () -> TerrainSnapshot.lod("example:source", 1, parameters)));
        var root = snapshot(new BrokenSettingsNoise());
        assertEquals(TerrainPredictionPlan.Kind.LOD_SOURCE, TerrainPredictionPlan.read(root).kind());
        assertEquals(110, root.getAsJsonObject("vss_lod_parameters").get("height").getAsInt());
        assertFalse(root.has("vss_unsupported_reason"));
    }
    @Test void optionalGeneratorCodecDoesNotSwallowCancellation() throws Exception {
        var projection = snapshot(TerrainPredictionFixtures.noise()); projection.remove(TerrainPredictionPlan.FIELD);
        projection.remove("vss_generator"); projection.remove("vss_generator_error");
        TerrainSnapshots.register(provider("example:projection", 1, () -> TerrainSnapshot.noise("example:projection", 1, false, projection)));
        assertThrows(java.util.concurrent.CancellationException.class, () -> snapshot(new NoiseAlias() {
            @Override protected com.mojang.serialization.Codec<? extends ChunkGenerator> codec() { throw new java.util.concurrent.CancellationException(); }
        }));
    }
    @Test void fatalVmFailureIsNotConvertedToUnavailableTerrain() {
        var fatal = new OutOfMemoryError("sentinel");
        TerrainSnapshots.register(provider("example:fatal", 1, () -> { throw fatal; }));
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> TerrainSnapshots.capture(new TerrainSnapshotContext(
                TerrainPredictionFixtures.noise(), TerrainPredictionFixtures.access(),
                new WorldgenRegistryDependencies(TerrainPredictionFixtures.access(), List.of())))));
    }
    private static TerrainSnapshotProvider provider(String id, int priority, java.util.function.Supplier<TerrainSnapshot> capture) {
        return new TerrainSnapshotProvider() {
            public String id() { return id; }
            public int priority() { return priority; }
            public Optional<TerrainSnapshot> capture(TerrainSnapshotContext ignored) { return Optional.of(capture.get()); }
        };
    }
    // NoiseBasedChunkGenerator is final in 1.21.11. A custom generator now
    // composes it instead of extending it; classification must not run it.
    static class NoiseAlias extends ChunkGenerator {
        NoiseAlias() { super(new FixedBiomeSource(TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS))); }
        public Holder<NoiseGeneratorSettings> generatorSettings() { return Holder.direct(settings()); }
        @Override protected com.mojang.serialization.Codec<? extends ChunkGenerator> codec() { return com.mojang.serialization.Codec.unit(this); }
        @Override public void applyCarvers(net.minecraft.server.level.WorldGenRegion region, long seed, RandomState random, net.minecraft.world.level.biome.BiomeManager biomes, net.minecraft.world.level.StructureManager structures, net.minecraft.world.level.chunk.ChunkAccess chunk, net.minecraft.world.level.levelgen.GenerationStep.Carving step) { throw new AssertionError("generation must not run"); }
        @Override public void buildSurface(net.minecraft.server.level.WorldGenRegion region, net.minecraft.world.level.StructureManager structures, RandomState random, net.minecraft.world.level.chunk.ChunkAccess chunk) { throw new AssertionError("generation must not run"); }
        @Override public void spawnOriginalMobs(net.minecraft.server.level.WorldGenRegion region) { throw new AssertionError("generation must not run"); }
        @Override public java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> fillFromNoise(java.util.concurrent.Executor executor, net.minecraft.world.level.levelgen.blending.Blender blender, RandomState random, net.minecraft.world.level.StructureManager structures, net.minecraft.world.level.chunk.ChunkAccess chunk) { throw new AssertionError("generation must not run"); }
        @Override public int getGenDepth() { return 384; }
        @Override public int getMinY() { return -64; }
        @Override public int getSeaLevel() { return 63; }
        @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heights, RandomState random) { throw new AssertionError("generation must not run"); }
        @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heights, RandomState random) { throw new AssertionError("generation must not run"); }
        @Override public void addDebugScreenInfo(List<String> lines, RandomState random, net.minecraft.core.BlockPos pos) { }
        private static NoiseGeneratorSettings settings() {
            var zero = DensityFunctions.zero();
            var density = DensityFunctions.yClampedGradient(-64, 320, 1, -1);
            return new NoiseGeneratorSettings(NoiseSettings.create(-64, 384, 1, 2), Blocks.STONE.defaultBlockState(),
                    Blocks.WATER.defaultBlockState(), new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, zero,
                            zero, zero, density, density, zero, zero, zero),
                    SurfaceRules.state(Blocks.STONE.defaultBlockState()), List.of(), 63, false, false, false, false);
        }
    }
    static class ChangedNoise extends NoiseAlias {
        @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heights, RandomState random) {
            throw new AssertionError("snapshot classification must never execute custom generation");
        }
    }
    static class BrokenSettingsNoise extends NoiseAlias {
        @Override protected com.mojang.serialization.Codec<? extends ChunkGenerator> codec() { throw new IllegalAccessError("optional noise context unavailable"); }
    }
}
