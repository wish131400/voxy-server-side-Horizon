package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.MapCodec;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Contracts for Minecraft's float compiler, replacing the pre-26.3 double optimizer. */
class FloatDensityCompilerTest {
    @BeforeAll static void bootstrap() { ModernDensityFixtures.bootstrap(); }

    @Test void cachedScalarAndBulkKeepTheirOwnExactVanillaSemantics() {
        for (var key : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END)) {
            var settings = ModernDensityFixtures.settings(key);
            var random = ModernDensityFixtures.random(settings, -917);
            var router = settings.noiseRouter();
            try (var context = new PredictionDensityContext(random)) {
                for (var root : List.of(router.temperature(), router.vegetation(), router.chunkSurfaceLevel(), router.finalDensity())) {
                    var source = random.getSampler(root);
                    var actual = context.bind(root);
                    for (int[] point : List.of(new int[]{0, 64, 0}, new int[]{-17, -64, 31}, new int[]{100005, 67, -100009})) {
                        assertBits(source.sampleValue(SamplerContext.EMPTY_UNCACHED, point[0], point[1], point[2]),
                                actual.sampleValue(point[0], point[1], point[2]));
                    }
                    for (var volume : List.of(new DensityVolume(4, 32, 4, -17, 49, 31),
                            new DensityVolume(5, 17, 3, 2047, -59, -2097, 3, 2, 5))) {
                        var expected = DensityBuffer.createUnpooled(volume.size());
                        var output = DensityBuffer.createUnpooled(volume.size());
                        source.sampleVolume(SamplerContext.EMPTY_UNCACHED, expected, volume);
                        actual.sampleVolume(output, volume);
                        for (int i = 0; i < volume.size(); i++) assertBits(expected.get(i), output.get(i));
                    }
                }
            }
        }
    }

    @Test void rootsShareTheSameWorkerCacheAndBulkValuesAreReused() {
        var counted = new CountingDensity();
        var shared = DensityFunctions.cache(counted);
        var random = ModernDensityFixtures.random(ModernDensityFixtures.settings(NoiseGeneratorSettings.OVERWORLD), 42);
        try (var context = new PredictionDensityContext(random)) {
            var first = context.bind(shared.add(1));
            var second = context.bind(shared.mul(2));
            float raw = CountingDensity.value(13, 71, -5);
            assertBits(raw + 1, first.sampleValue(13, 71, -5));
            assertBits(raw * 2, second.sampleValue(13, 71, -5));
            assertEquals(1, counted.scalars.get(), "separate roots must reuse their shared explicit cache");
            var volume = new DensityVolume(3, 7, 2, -5, 49, 11);
            first.sampleVolume(DensityBuffer.createUnpooled(volume.size()), volume);
            second.sampleVolume(DensityBuffer.createUnpooled(volume.size()), volume);
            assertEquals(1, counted.volumes.get(), "one worker must materialize a shared volume only once");
            assertBits(CountingDensity.value(-5, 49, 11) * 2, second.sampleValue(-5, 49, 11));
            assertEquals(1, counted.scalars.get(), "scalar reads inside the cached volume use its float results");
        }
    }

    @Test void workersHaveIndependentCachesAndRetirementReleasesEveryWorker() throws Exception {
        var counted = new CountingDensity();
        var random = ModernDensityFixtures.random(ModernDensityFixtures.settings(NoiseGeneratorSettings.OVERWORLD), 42);
        var context = new PredictionDensityContext(random);
        var sampler = context.bind(DensityFunctions.cache(counted));
        var executor = Executors.newFixedThreadPool(4);
        var ready = new CyclicBarrier(4);
        var release = new CyclicBarrier(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) tasks.add(() -> {
                ready.await();
                for (int i = 0; i < 50; i++) assertBits(CountingDensity.value(7, 53, -9), sampler.sampleValue(7, 53, -9));
                release.await();
                return null;
            });
            for (var future : executor.invokeAll(tasks)) future.get();
            assertEquals(4, counted.scalars.get(), "each worker evaluates one independent scalar cache");
            var workers = retainedWorkers(context);
            assertEquals(4, workers.size());
            for (Object worker : workers) assertNotNull(workerContext(worker));
            context.close();
            for (Object worker : workers) assertNull(workerContext(worker), "close clears live worker references, not only the caller's ThreadLocal");
            assertTrue(retainedWorkers(context).isEmpty());
            assertTrue(context.diagnostics().startsWith("state=retired"));
            int before = counted.scalars.get();
            sampler.sampleValue(7, 53, -9);
            sampler.sampleValue(7, 53, -9);
            assertEquals(before + 2, counted.scalars.get(), "a retired sampler cannot recreate retained caches");
        } finally {
            context.close();
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> retainedWorkers(PredictionDensityContext context) throws Exception {
        var field = PredictionDensityContext.class.getDeclaredField("retained");
        field.setAccessible(true);
        var references = (ConcurrentLinkedQueue<WeakReference<?>>) field.get(context);
        var result = new ArrayList<Object>();
        for (var reference : references) if (reference.get() != null) result.add(reference.get());
        return result;
    }

    private static Object workerContext(Object worker) throws Exception {
        var field = worker.getClass().getDeclaredField("context");
        field.setAccessible(true);
        return field.get(worker);
    }

    static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    static final class CountingDensity implements DensityFunction {
        final AtomicInteger scalars = new AtomicInteger();
        final AtomicInteger volumes = new AtomicInteger();
        static float value(int x, int y, int z) { return x * .03125f + y * .015625f - z * .0625f; }
        @Override public DensitySampler compileSampler(CompileContext ignored) {
            return new DensitySampler() {
                @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
                    scalars.incrementAndGet();
                    return value(x, y, z);
                }
                @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
                    volumes.incrementAndGet();
                    for (int z = 0; z < volume.sizeZ(); z++) for (int x = 0; x < volume.sizeX(); x++)
                        for (int y = 0; y < volume.sizeY(); y++) output.set(volume.indexUnchecked(x, y, z),
                                value(volume.blockX(x), volume.blockY(y), volume.blockZ(z)));
                }
            };
        }
        @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
        @Override public Interval range() { return Interval.INFINITE; }
        @Override public int domainAxes() { return ALL_AXES; }
        @Override public MapCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
    }
}

final class ModernDensityFixtures {
    private static HolderLookup.Provider lookup;
    private ModernDensityFixtures() { }
    static synchronized void bootstrap() {
        if (lookup != null) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = VanillaRegistries.createWorldLookup();
    }
    static HolderLookup.Provider lookup() { bootstrap(); return lookup; }
    static NoiseGeneratorSettings settings(net.minecraft.resources.ResourceKey<NoiseGeneratorSettings> key) {
        return lookup().lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
    }
    static RandomState random(NoiseGeneratorSettings settings, long seed) {
        return RandomState.create(lookup().lookupOrThrow(Registries.NOISE), seed, settings);
    }
    static NoiseGeneratorSettings densitySettings(DensityFunction density) {
        var base = settings(NoiseGeneratorSettings.OVERWORLD);
        var zero = DensityFunctions.zero();
        return new NoiseGeneratorSettings(net.minecraft.world.level.levelgen.NoiseSettings.create(-64, 256),
                base.defaultBlock(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, density), base.materialRule(),
                List.of(), -64, true, java.util.Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY);
    }
}
