package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionClimateSamplerTest {
    @BeforeAll static void bootstrap() { ModernDensityFixtures.bootstrap(); }

    private static DensitySampler.Bound[] roots(Climate.Sampler sampler) {
        return new DensitySampler.Bound[]{sampler.temperature(), sampler.humidity(), sampler.continentalness(),
                sampler.erosion(), sampler.depth(), sampler.weirdness()};
    }

    @Test void rawFloatRootsAndQuantizedClimateMatchTheActualCompiler() {
        for (var key : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END)) {
            var settings = ModernDensityFixtures.settings(key);
            var random = ModernDensityFixtures.random(settings, 42);
            var source = random.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
            try (var actual = new PredictionClimateSampler(random, settings.noiseRouter())) {
                assertTrue(actual.diagnostics().contains("compiler=minecraft-26.3,roots=6"));
                var expectedRoots = roots(source);
                var actualRoots = roots(actual.sampler());
                for (int i = 0; i < 1024; i++) {
                    int x = i * 1877 % 16001 - 8000, y = i % 128 - 32, z = i * 547 % 32003 - 16000;
                    assertEquals(source.sample(x, y, z), actual.sampler().sample(x, y, z), key + " climate point " + i);
                    for (int root = 0; root < 6; root++) FloatDensityCompilerTest.assertBits(
                            expectedRoots[root].sampleValue(x * 4, y * 4, z * 4),
                            actualRoots[root].sampleValue(x * 4, y * 4, z * 4));
                }
                // Fabric adds a seeded End-biome hook at runtime; copying must preserve it.
                for (Class<?> api : source.getClass().getInterfaces()) {
                    if (!api.getName().equals("net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks")) continue;
                    try {
                        assertEquals(api.getMethod("fabric_getSeed").invoke(source),
                                api.getMethod("fabric_getSeed").invoke(actual.sampler()));
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                }
            }
        }
    }

    @Test void fourPredictionWorkersKeepIndependentExactClimateCaches() throws Exception {
        var settings = ModernDensityFixtures.settings(NoiseGeneratorSettings.OVERWORLD);
        var random = ModernDensityFixtures.random(settings, 13459);
        var source = random.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        try (var actual = new PredictionClimateSampler(random, settings.noiseRouter())) {
            var executor = Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
                for (int worker = 0; worker < 4; worker++) {
                    int offset = worker * 65537;
                    tasks.add(() -> {
                        for (int i = 0; i < 1024; i++) {
                            int x = offset + i % 32 - 16, y = i % 96 - 16, z = -offset + i / 32;
                            assertEquals(source.sample(x, y, z), actual.sampler().sample(x, y, z));
                        }
                        return null;
                    });
                }
                for (var future : executor.invokeAll(tasks)) future.get();
            } finally { executor.shutdownNow(); }
            actual.close();
            assertTrue(actual.diagnostics().startsWith("state=retired"));
            assertEquals(source.sample(17, -11, 53), actual.sampler().sample(17, -11, 53));
            assertTrue(actual.diagnostics().startsWith("state=retired"));
        }
    }
}
