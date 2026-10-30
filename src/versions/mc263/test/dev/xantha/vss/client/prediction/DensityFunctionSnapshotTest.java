package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import net.minecraft.core.Holder;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DensityFunctionSnapshotTest {
    @BeforeAll
    static void bootstrap() {
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        dev.xantha.vss.testutil.TectonicCodecFixtures.register();
    }

    @Test
    void nestedDirectHoldersRoundTripWithoutChangingDensity() {
        DensityFunction slope = DensityFunctions.yClampedGradient(-64, 320, 1.0f, -1.0f);
        DensityFunction nested = new DensityFunctions.HolderHolder(Holder.direct(
                new DensityFunctions.HolderHolder(Holder.direct(slope))));
        DensityFunction graph = DensityFunctions.add(nested, DensityFunctions.constant(0.25f));
        assertThrows(UnsupportedOperationException.class,
                () -> DensityFunctions.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow());
        assertRoundTrip(graph);
    }

    @Test
    void appliedModifierGraphIsCapturedInsteadOfOriginalCodec() {
        DensityFunction original = DensityFunctions.constant(-0.5f);
        DensityFunction modified = DensityFunctions.add(
                DensityFunctions.yClampedGradient(-64, 320, 2, -2), original);
        DensityFunction wrapper = new AppliedModifier(original, modified);
        assertRoundTrip(wrapper);
    }

    @Test
    void appliedNoiseSettingsPreserveSeededDensityAndRemainBounded() {
        var lookup = VanillaRegistries.createWorldLookup();
        var original = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops,
                DensityFunctionSnapshot.applied(original)).getOrThrow();
        assertTrue(encoded.toString().length() < 2_000_000, "expanded router must fit the network budget");
        var decoded = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        var noises = lookup.lookupOrThrow(Registries.NOISE);
        var expected = RandomState.create(noises, 123456789L, original);
        var actual = RandomState.create(noises, 123456789L, decoded);
        for (int i = 0; i < 64; i++) {
            int x = i * 71 - 2048, y = i * 6 - 64, z = -i * 37;
            assertEquals(expected.sampleBlockValueUncached(original.noiseRouter().finalDensity(), x, y, z),
                    actual.sampleBlockValueUncached(decoded.noiseRouter().finalDensity(), x, y, z));
            assertEquals(expected.sampleBlockValueUncached(original.noiseRouter().chunkSurfaceLevel(), x, y, z),
                    actual.sampleBlockValueUncached(decoded.noiseRouter().chunkSurfaceLevel(), x, y, z));
        }
        assertEquals(original.materialRule(), decoded.materialRule());
    }

    @Test
    void repeatedAppliedGraphUsesBoundedSharedRegistryReferences() {
        var base = DensityFunctions.yClampedGradient(-64, 320, 0.5f, -0.5f);
        DensityFunction graph = base;
        for (int i = 0; i < 16; i++) graph = DensityFunctions.add(graph, graph);
        graph = graph.clamp(-0.75f, 0.75f);
        var expanded = DensityFunctions.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow();
        assertTrue(expanded.toString().length() > 8 * 1024 * 1024,
                "fixture must reproduce the generator snapshot raw-size failure");
        var references = new DensityFunctionReferences();
        var compact = references.compact(expanded);
        assertTrue(compact.toString().length() + references.definitions().toString().length() < 16_384);
        assertTrue(compact.getAsJsonObject().get("input").isJsonPrimitive(),
                "26.3 Clamp.input uses DensityFunction.CODEC and may reference the shared registry");
        var root = new com.google.gson.JsonObject();
        root.add("noises", new com.google.gson.JsonObject());
        root.add("density_functions", references.definitions());
        var registries = ClientWorldgenRegistries.decode(root, net.minecraft.core.RegistryAccess.EMPTY, java.util.List.of());
        var decoded = DensityFunctions.DIRECT_CODEC.parse(registries.ops(), compact).getOrThrow();
        for (int y : new int[]{-64, 127, 128, 129, 320}) {
            assertEquals(sample(graph, -16, y, 31), sample(decoded, -16, y, 31));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
    void installedLithostitchedAppliedGraphRoundTrips() throws Exception {
        var url = java.nio.file.Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader())) {
            Class<?> type;
            try {
                type = loader.loadClass("dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction");
            } catch (ClassNotFoundException legacyRelease) {
                type = loader.loadClass("dev.worldgen.lithostitched.worldgen.densityfunction.MergedDensityFunction");
            }
            var original = DensityFunctions.constant(-0.5f);
            DensityFunction nested = new DensityFunctions.HolderHolder(Holder.direct(
                    new DensityFunctions.HolderHolder(Holder.direct(original))));
            var full = DensityFunctions.add(nested, DensityFunctions.yClampedGradient(-64, 320, 2, -2));
            var graph = (DensityFunction) type.getConstructor(DensityFunction.class, DensityFunction.class,
                    DensityFunction.class).newInstance(original, original, full);
            assertRoundTrip(graph);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
    void installedTectonicDefaultConfigFunctionsRoundTrip() throws Exception {
        var url = java.nio.file.Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader())) {
            String prefix = "dev.worldgen.tectonic.worldgen.densityfunction.";
            Class<?> constantType = loader.loadClass(prefix + "ConfigConstant");
            DensityFunction constant = (DensityFunction) constantType.getConstructor(float.class).newInstance(0.375f);
            assertRoundTrip(constant);
            try {
                var clamp = (DensityFunction) loader.loadClass(prefix + "ConfigClamp")
                        .getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class)
                        .newInstance(DensityFunctions.yClampedGradient(-64, 320, 2, -2),
                                DensityFunctions.constant(-0.5f), constant);
                assertRoundTrip(clamp);
            } catch (ClassNotFoundException legacyRelease) {
                assertRoundTrip(DensityFunctions.add(constant, DensityFunctions.yClampedGradient(-64, 320, 2, -2)));
            }
            var noise = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.NOISE)
                    .getOrThrow(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS);
            Class<?> noiseType = loader.loadClass(prefix + "ConfigNoise");
            DensityFunction configured = (DensityFunction) noiseType
                    .getConstructor(Holder.class, DensityFunction.class,
                            DensityFunction.class, float.class, float.class, float.class, boolean.class)
                    .newInstance(noise, constant, DensityFunctions.zero(), 0.13f, 1.25f, -0.8f, false);
            assertRoundTrip(configured);
            var applied = DensityFunctionSnapshot.applied(configured);
            var ops = VanillaRegistries.createWorldLookup().createSerializationContext(JsonOps.INSTANCE);
            var encoded = DensityFunctions.DIRECT_CODEC.encodeStart(ops, applied).getOrThrow();
            var restored = DensityFunctions.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
            for (int i = -16; i <= 16; i++) {
                int x = i * 137, y = i * 11, z = -i * 79;
                assertEquals(sample(configured, x, y, z), sample(applied, x, y, z), "retain seeded compile context");
                assertEquals(sample(configured, x, y, z), sample(restored, x, y, z), "restore seeded noise after decoding");
            }
        }
    }

    private static void assertRoundTrip(DensityFunction graph) {
        var ops = VanillaRegistries.createWorldLookup().createSerializationContext(JsonOps.INSTANCE);
        var encoded = DensityFunctions.DIRECT_CODEC.encodeStart(ops,
                DensityFunctionSnapshot.applied(graph)).getOrThrow();
        var decoded = DensityFunctions.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        for (int y = -64; y <= 320; y += 7) {
            assertEquals(sample(graph, -131, y, 257), sample(decoded, -131, y, 257));
        }
    }

    private record AppliedModifier(DensityFunction original, DensityFunction full)
            implements DensityFunction {
        @Override public DensitySampler compileSampler(CompileContext context) { return full.compileSampler(context); }
        @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return rule.rewrite(full); }
        @Override public Interval range() { return full.range(); }
        @Override public int domainAxes() { return full.domainAxes(); }
        @Override public MapCodec<? extends DensityFunction> codec() {
            throw new AssertionError("The source-only codec must not be used for an applied snapshot");
        }
    }

    private static net.minecraft.core.HolderGetter<net.minecraft.world.level.levelgen.synth.NormalNoise> noises;
    private static synchronized net.minecraft.core.HolderGetter<net.minecraft.world.level.levelgen.synth.NormalNoise> noiseLookup() {
        if (noises == null) noises = net.minecraft.data.registries.VanillaRegistries.createWorldLookup()
                .lookupOrThrow(net.minecraft.core.registries.Registries.NOISE);
        return noises;
    }

    private static float sample(DensityFunction function, int x, int y, int z) {
        var noises = noiseLookup();
        var zero = DensityFunctions.zero();
        var router = new net.minecraft.world.level.levelgen.NoiseRouter(zero, zero, zero, zero, zero, zero, zero, function);
        return RandomState.create(noises, 78123L, false, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                63, router).sampleBlockValueUncached(function, x, y, z);
    }
}
