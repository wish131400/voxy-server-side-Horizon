package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import dev.xantha.vss.common.worldgen.TectonicConfigNoise;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** The released float samplers intentionally have different scalar and volume results. */
@EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
class TectonicPresetSnapshotTest {
    private static net.minecraft.core.HolderLookup.Provider worldLookup;

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        dev.xantha.vss.testutil.TectonicCodecFixtures.register();
    }

    static URLClassLoader releasedLoader(Path directory) throws Exception {
        var release = Path.of(System.getProperty("vss.tectonicJar"));
        var urls = new ArrayList<java.net.URL>();
        urls.add(release.toUri().toURL());
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

    static DensityFunction releasedNoise(ClassLoader loader, Holder<NormalNoise> noise, float scale,
            float multiplier, float offset, boolean smoother) throws Exception {
        var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise");
        return (DensityFunction) type.getConstructor(Holder.class, DensityFunction.class, DensityFunction.class,
                float.class, float.class, float.class, boolean.class).newInstance(noise,
                DensityFunctions.yClampedGradient(-96, 384, -1.3f, 1.1f), DensityFunctions.constant(-0.375f),
                scale, multiplier, offset, smoother);
    }

    static DensityFunction presetNoise(ClassLoader loader, String key, Holder<NormalNoise> noise) throws Exception {
        var preset = loader.loadClass("dev.worldgen.tectonic.config.ConfigPresets").getField("OVERKILL").get(null);
        var experimental = preset.getClass().getField("experimental").get(preset);
        assertTrue(experimental.getClass().getField("alternateErosionScaling").getBoolean(experimental));
        assertTrue(experimental.getClass().getField("alternateContinentsScaling").getBoolean(experimental));
        var state = preset.getClass().getMethod("getNoiseState", String.class).invoke(preset, key);
        assertTrue(state.getClass().getField("smootherScaling").getBoolean(state));
        return releasedNoise(loader, noise, state.getClass().getField("scale").getFloat(state),
                state.getClass().getField("multiplier").getFloat(state),
                state.getClass().getField("offset").getFloat(state), true);
    }

    @Test void overkillPresetRetainsExactScalingAtRootAndInsideDensityRecords(@TempDir Path directory) throws Exception {
        try (var loader = releasedLoader(directory)) {
            for (String key : List.of("continents", "erosion")) {
                var noise = registeredNoise(key.equals("continents") ? "continentalness" : "erosion");
                var configured = presetNoise(loader, key, noise);
                for (var graph : List.of(configured, configured.add(0.125f).clamp(-2, 2))) {
                    assertReleasedRoundTrip(graph);
                }
            }
        }
    }

    @Test void normalAndSmoothKeepReleasedFloatScalarAndDoubleVolumeOrdering(@TempDir Path directory) throws Exception {
        try (var loader = releasedLoader(directory)) {
            var noise = registeredNoise("continentalness");
            for (boolean smoother : new boolean[]{false, true}) {
                for (float scale : new float[]{0.13f, -0.017f, 0.0f}) {
                    var configured = releasedNoise(loader, noise, scale, 1.25f, -0.8f, smoother);
                    var captured = (TectonicConfigNoise) DensityFunctionSnapshot.applied(configured);
                    assertEquals(smoother, captured.smootherScaling());
                    assertBits(scale, captured.scale());
                    assertBits(1.25f, captured.multiplier());
                    assertBits(-0.8f, captured.offset());
                    var encoded = DensityFunctions.DIRECT_CODEC.encodeStart(
                            lookup().createSerializationContext(JsonOps.INSTANCE), captured).getOrThrow()
                            .getAsJsonObject();
                    assertEquals("vss:tectonic_config_noise", encoded.get("type").getAsString());
                    assertFalse(encoded.has("key"), "source configuration must not be replayed while decoding");
                    assertReleasedRoundTrip(configured);
                }
            }
        }
    }

    @Test void smoothVolumeRetainsReleasedMissingOffsetWhileScalarKeepsOffset(@TempDir Path directory) throws Exception {
        try (var loader = releasedLoader(directory)) {
            var noise = registeredNoise("continentalness");
            var withOffset = DensityFunctionSnapshot.applied(releasedNoise(loader, noise, 0.13f, 1.25f, -0.8f, true));
            var withoutOffset = DensityFunctionSnapshot.applied(releasedNoise(loader, noise, 0.13f, 1.25f, 0.0f, true));
            var random = random(withOffset, 78123);
            var a = random.getSampler(withOffset);
            var b = random.getSampler(withoutOffset);
            float scalar = b.sampleValue(SamplerContext.EMPTY_UNCACHED, 17, 128, -31);
            assertBits(scalar - 0.8f, a.sampleValue(SamplerContext.EMPTY_UNCACHED, 17, 128, -31));
            var volume = new DensityVolume(3, 7, 2, -17, 49, 31, 3, 2, 5);
            var av = DensityBuffer.createUnpooled(volume.size());
            var bv = DensityBuffer.createUnpooled(volume.size());
            a.sampleVolume(SamplerContext.EMPTY_UNCACHED, av, volume);
            b.sampleVolume(SamplerContext.EMPTY_UNCACHED, bv, volume);
            for (int i = 0; i < volume.size(); i++) assertBits(av.get(i), bv.get(i));
        }
    }

    static RandomState random(DensityFunction function, long seed) {
        var zero = DensityFunctions.zero();
        var router = new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, function);
        var noises = lookup().lookupOrThrow(Registries.NOISE);
        return RandomState.create(noises, seed, false, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 63, router);
    }

    private static void assertReleasedRoundTrip(DensityFunction source) {
        var captured = DensityFunctionSnapshot.applied(source);
        var ops = lookup().createSerializationContext(JsonOps.INSTANCE);
        var encoded = DensityFunctions.DIRECT_CODEC.encodeStart(ops, captured).getOrThrow();
        var restored = DensityFunctions.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        var random = random(source, 78123);
        var expected = random.getSampler(source);
        for (var candidate : List.of(captured, restored)) {
            var actual = random.getSampler(candidate);
            for (int[] point : new int[][]{{-17, -96, 31}, {0, 0, 0}, {8191, 128, -8193},
                    {-29_999_999, 383, 29_999_999}, {1, -1, -1}, {29_999_999, 129, -29_999_999}}) {
                assertBits(expected.sampleValue(SamplerContext.EMPTY_UNCACHED, point[0], point[1], point[2]),
                        actual.sampleValue(SamplerContext.EMPTY_UNCACHED, point[0], point[1], point[2]));
            }
            for (var volume : List.of(new DensityVolume(3, 7, 2, -17, 49, 31, 3, 2, 5),
                    new DensityVolume(2, 4, 3, 29_999_991, -96, -29_999_999, 2, 16, 3))) {
                var expectedValues = DensityBuffer.createUnpooled(volume.size());
                var actualValues = DensityBuffer.createUnpooled(volume.size());
                expected.sampleVolume(SamplerContext.EMPTY_UNCACHED, expectedValues, volume);
                actual.sampleVolume(SamplerContext.EMPTY_UNCACHED, actualValues, volume);
                for (int i = 0; i < volume.size(); i++) assertBits(expectedValues.get(i), actualValues.get(i));
            }
        }
    }

    private static synchronized net.minecraft.core.HolderLookup.Provider lookup() {
        if (worldLookup == null) worldLookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        return worldLookup;
    }

    private static Holder<NormalNoise> registeredNoise(String name) {
        return lookup().lookupOrThrow(Registries.NOISE).getOrThrow(net.minecraft.resources.ResourceKey.create(
                Registries.NOISE, net.minecraft.resources.Identifier.withDefaultNamespace(name)));
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
