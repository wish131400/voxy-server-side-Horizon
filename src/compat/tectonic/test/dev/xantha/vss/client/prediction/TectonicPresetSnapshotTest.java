package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static dev.xantha.vss.testutil.DensityCodecFixtures.CODEC;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Uses the released preset and ConfigNoise evaluator, not a reimplementation of its flags. */
@EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
class TectonicPresetSnapshotTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        dev.xantha.vss.testutil.TectonicCodecFixtures.register();
    }

    static URLClassLoader releasedLoader(Path directory) throws Exception {
        var release = Path.of(System.getProperty("vss.tectonicJar"));
        var urls = new ArrayList<java.net.URL>();
        urls.add(release.toUri().toURL());
        // Fabric normally loads the bundled Apollib. Offline URLClassLoader needs it explicitly.
        try (var jar = new JarFile(release.toFile())) {
            var nested = jar.stream().filter(entry -> entry.getName().startsWith("META-INF/jars/")
                    && entry.getName().endsWith(".jar")).toList();
            for (int i = 0; i < nested.size(); i++) {
                var path = directory.resolve("bundled-" + i + ".jar");
                try (var input = jar.getInputStream(nested.get(i))) { Files.copy(input, path); }
                urls.add(path.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(java.net.URL[]::new), TectonicPresetSnapshotTest.class.getClassLoader());
    }

    static DensityFunction presetNoise(ClassLoader loader, String key, DensityFunction.NoiseHolder noise) throws Exception {
        var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise");
        var constructor = java.util.Arrays.stream(type.getConstructors())
                .filter(value -> value.getParameterCount() == 7).findFirst();
        assumeTrue(constructor.isPresent(), "This released legacy ConfigNoise has no smoother-scaling option");
        var preset = loader.loadClass("dev.worldgen.tectonic.config.ConfigPresets").getField("OVERKILL").get(null);
        var experimental = preset.getClass().getField("experimental").get(preset);
        assertTrue(experimental.getClass().getField("alternateErosionScaling").getBoolean(experimental));
        assertTrue(experimental.getClass().getField("alternateContinentsScaling").getBoolean(experimental));
        var state = preset.getClass().getMethod("getNoiseState", String.class).invoke(preset, key);
        assertTrue(state.getClass().getField("smootherScaling").getBoolean(state), key + " must use the real preset flag");
        return (DensityFunction) constructor.orElseThrow().newInstance(noise,
                DensityFunctions.yClampedGradient(-96, 384, -1.3, 1.1), DensityFunctions.constant(-0.375),
                state.getClass().getField("scale").getDouble(state),
                state.getClass().getField("multiplier").getDouble(state),
                state.getClass().getField("offset").getDouble(state), true);
    }

    @Test void overkillPresetRetainsExactScalingAtRootAndInsideDensityRecords(@TempDir Path directory) throws Exception {
        try (var loader = releasedLoader(directory)) {
            var parameters = new NormalNoise.NoiseParameters(-3, 1.0, 0.25, 0.5);
            var noise = new DensityFunction.NoiseHolder(Holder.direct(parameters),
                    NormalNoise.create(new XoroshiroRandomSource(78123L), parameters));
            for (String key : new String[]{"continents", "erosion"}) {
                var configured = presetNoise(loader, key, noise);
                for (var graph : java.util.List.of(configured,
                        DensityFunctions.add(DensityFunctions.constant(0.125), configured).clamp(-2, 2))) {
                    var captured = DensityFunctionSnapshot.applied(graph);
                    var encoded = CODEC.encodeStart(JsonOps.INSTANCE, captured).result().orElseThrow();
                    var restored = CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow()
                            .mapAll(new DensityFunction.Visitor() {
                                public DensityFunction apply(DensityFunction value) { return value; }
                                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) { return noise; }
                            });
                    for (int[] point : new int[][]{{-17, -96, 31}, {0, 0, 0}, {8191, 128, -8193},
                            {-29_999_999, 383, 29_999_999}, {1, -1, -1}}) {
                        var context = new DensityFunction.SinglePointContext(point[0], point[1], point[2]);
                        var expected = graph.compute(context);
                        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(captured.compute(context)),
                                "capture " + key + java.util.Arrays.toString(point));
                        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(restored.compute(context)),
                                "decode " + key + java.util.Arrays.toString(point));
                    }
                }
            }
            var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise");
            var negative = (DensityFunction) java.util.Arrays.stream(type.getConstructors())
                    .filter(value -> value.getParameterCount() == 7).findFirst().orElseThrow()
                    .newInstance(noise, DensityFunctions.constant(0.625), DensityFunctions.constant(-0.375),
                            0.13, -1.25, 0.375, true);
            var captured = DensityFunctionSnapshot.applied(negative);
            assertEquals(negative.minValue(), captured.minValue(), 0.0,
                    "preserve released bounds, including nonzero offset and a negative multiplier");
            assertEquals(negative.maxValue(), captured.maxValue(), 0.0);
        }
    }

    @Test void configuredPresetSurvivesSettingsCodecAndClientNoiseInitialization(@TempDir Path directory) throws Exception {
        try (var loader = releasedLoader(directory)) {
            var parameters = new NormalNoise.NoiseParameters(-3, 1.0, 0.25, 0.5);
            var noise = new DensityFunction.NoiseHolder(Holder.direct(parameters),
                    NormalNoise.create(new XoroshiroRandomSource(78123L), parameters));
            var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise");
            DensityFunction configured;
            if (java.util.Arrays.stream(type.getConstructors()).anyMatch(value -> value.getParameterCount() == 7)) {
                configured = presetNoise(loader, "continents", noise);
            } else {
                configured = (DensityFunction) java.util.Arrays.stream(type.getConstructors())
                        .filter(value -> value.getParameterCount() == 6).findFirst().orElseThrow().newInstance(noise,
                        DensityFunctions.constant(0.625), DensityFunctions.constant(-0.375), 0.13, -1.25, 0.375);
            }
            DensityFunction graph = DensityFunctions.add(DensityFunctions.constant(0.125),
                    new DensityFunctions.HolderHolder(Holder.direct(configured))).clamp(-2, 2);
            var lookup = VanillaRegistries.createLookup();
            var base = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            var components = NoiseRouter.class.getRecordComponents();
            var arguments = new Object[components.length];
            var types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                arguments[i] = components[i].getName().equals("finalDensity") ? graph
                        : components[i].getAccessor().invoke(base.noiseRouter());
                types[i] = components[i].getType();
            }
            var router = NoiseRouter.class.getConstructor(types).newInstance(arguments);
            var source = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(), base.defaultFluid(),
                    router, base.surfaceRule(), base.spawnTarget(), base.seaLevel(), base.disableMobGeneration(),
                    base.aquifersEnabled(), base.oreVeinsEnabled(), base.useLegacyRandomSource());
            var captured = DensityFunctionSnapshot.applied(source);
            var ops = serializationContext(lookup);
            var json = requireCodecSuccess("encode complete noise settings",
                    NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, captured));
            var decoded = requireCodecSuccess("decode complete noise settings",
                    NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json));
            var restored = decoded.noiseRouter().finalDensity().mapAll(new DensityFunction.Visitor() {
                public DensityFunction apply(DensityFunction value) { return value; }
                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) { return noise; }
            });
            for (int[] p : new int[][]{{-17, -96, 31}, {0, 0, 0}, {8191, 128, -8193},
                    {-29_999_999, 383, 29_999_999}}) {
                var point = new DensityFunction.SinglePointContext(p[0], p[1], p[2]);
                assertEquals(Double.doubleToRawLongBits(graph.compute(point)),
                        Double.doubleToRawLongBits(captured.noiseRouter().finalDensity().compute(point)), "settings capture");
                assertEquals(Double.doubleToRawLongBits(graph.compute(point)),
                        Double.doubleToRawLongBits(restored.compute(point)), "settings decode " + java.util.Arrays.toString(p));
            }
        }
    }

    private static <T> T requireCodecSuccess(String stage, DataResult<T> result) {
        return result.result().orElseThrow(() -> new AssertionError(stage + ": "
                + result.error().map(error -> error.message()).orElse("codec returned no value or error")));
    }

    @SuppressWarnings("unchecked")
    private static RegistryOps<JsonElement> serializationContext(HolderLookup.Provider lookup) {
        try {
            // RegistrySetBuilder supplies its own holder owners for the vanilla bootstrap graph.
            // Invoke through the public interface because the implementation class is package-private.
            return (RegistryOps<JsonElement>) HolderLookup.Provider.class
                    .getMethod("createSerializationContext", DynamicOps.class).invoke(lookup, JsonOps.INSTANCE);
        } catch (NoSuchMethodException legacyMinecraft) {
            // Minecraft 1.20.1 predates Provider.createSerializationContext.
            return RegistryOps.create(JsonOps.INSTANCE, lookup);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot create the registry provider's serialization context", failure);
        }
    }

    static void assertReleasedConfiguredNoiseAndOverkillSnapshotsRunInRustWithExactDoubleBits(Path directory) throws Exception {
        try (var loader = TectonicPresetSnapshotTest.releasedLoader(directory)) {
            var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
            var noise = lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE)
                    .getOrThrow(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS);
            var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise");
            var source = new java.util.LinkedHashMap<String, DensityFunction>();
            var seven = java.util.Arrays.stream(type.getConstructors()).filter(value -> value.getParameterCount() == 7).findFirst();
            if (seven.isPresent()) {
                for (boolean smoother : new boolean[]{false, true})
                    source.put("config_" + smoother, (DensityFunction) seven.orElseThrow().newInstance(
                            new DensityFunction.NoiseHolder(noise),
                            DensityFunctions.yClampedGradient(-96, 384, -1.3, 1.1), DensityFunctions.constant(-0.375),
                            0.13, -1.25, 0.375, smoother));
                for (String key : List.of("continents", "erosion"))
                    source.put("overkill_" + key, TectonicPresetSnapshotTest.presetNoise(loader, key,
                            new DensityFunction.NoiseHolder(noise)));
            } else {
                source.put("config_legacy", (DensityFunction) java.util.Arrays.stream(type.getConstructors())
                        .filter(value -> value.getParameterCount() == 6).findFirst().orElseThrow().newInstance(
                                new DensityFunction.NoiseHolder(noise), DensityFunctions.constant(0.625),
                                DensityFunctions.constant(-0.375), 0.13, -1.25, 0.375));
            }
            var doc = LithostitchedNativeTest.document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var ops = serializationContext(lookup);
            for (var entry : source.entrySet()) router.add(entry.getKey(),
                    dev.xantha.vss.testutil.DensityCodecFixtures.CODEC.encodeStart(ops,
                            dev.xantha.vss.common.worldgen.DensityFunctionSnapshot.applied(entry.getValue())).result().orElseThrow());
            var generator = new JsonObject();
            generator.add("settings", doc.get("settings"));
            generator.add("biome_source", doc.get("biome_source"));
            assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, doc),
                    "applied smoother-scaling must remain eligible for Rust");
            int[][] positions = {{-17, -96, 31}, {0, 0, 0}, {8191, 128, -8193},
                    {-29_999_999, 383, 29_999_999}, {1, -1, -1}, {29_999_999, 129, -29_999_999}};
            var points = ByteBuffer.allocateDirect(positions.length * 12).order(ByteOrder.LITTLE_ENDIAN);
            for (int[] point : positions) for (int coordinate : point) points.putInt(coordinate);
            var output = ByteBuffer.allocateDirect(positions.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (long seed : new long[]{0, 78123L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                long world = RustWorldgenBackend.create(seed, 0, doc.toString());
                try {
                    var factory = new net.minecraft.world.level.levelgen.XoroshiroRandomSource(seed).forkPositional();
                    var seededNoise = new DensityFunction.NoiseHolder(noise,
                            net.minecraft.world.level.levelgen.synth.NormalNoise.create(
                                    factory.fromHashOf("minecraft:continentalness"),
                                    noise.value()));
                    for (var entry : source.entrySet()) {
                        var expected = entry.getValue().mapAll(new DensityFunction.Visitor() {
                            public DensityFunction apply(DensityFunction value) { return value; }
                            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) { return seededNoise; }
                        });
                        assertEquals(positions.length, RustWorldgenBackend.density(world, entry.getKey(), points, output,
                                positions.length));
                        for (int i = 0; i < positions.length; i++) {
                            int[] point = positions[i];
                            double value = expected.compute(new DensityFunction.SinglePointContext(point[0], point[1], point[2]));
                            assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(output.getDouble(i * 8)),
                                    entry.getKey() + " seed=" + seed + " pos=" + java.util.Arrays.toString(point));
                        }
                    }
                } finally { RustWorldgenBackend.close(world); }
            }
        }
    }
}
