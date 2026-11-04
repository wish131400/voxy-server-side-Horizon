package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCaptureStageTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void staleNativeOrJavaSamplingStopsBeforeColorDecorationAndPublicationAndCanRetry() throws Exception {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        config.predictionDistanceBlocks = 4096;
        var sampled = new AtomicInteger();
        var colored = new AtomicInteger();
        var invalidated = new AtomicBoolean();
        var current = new AtomicReference<PredictionTileManager>();
        var source = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample samplePreview(int x, int z, int step) {
                sampled.incrementAndGet();
                if (invalidated.compareAndSet(false, true)) current.get().capturedTerrainChanged(0, 0);
                return PredictionVoxelFixtures.sample(120);
            }
            @Override public ClientColumnSample sampleSurface(int x, int z) {
                sampled.incrementAndGet(); return PredictionVoxelFixtures.sample(120);
            }
            @Override int surfaceColorForLod(int x, int y, int z, boolean preview) {
                colored.incrementAndGet(); return 0xff669944;
            }
        };
        var budget = new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, source, budget, null)) {
            var target = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, manager.layout().levelCount() - 1);
            current.set(manager);
            var desired = PredictionTileManager.class.getDeclaredField("desiredKeys");
            desired.setAccessible(true); desired.set(manager, new HashSet<>(Set.of(target)));
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue",
                    PredictionTileManager.PredictionTileKey.class, int.class, int.class, boolean.class, boolean.class);
            enqueue.setAccessible(true); enqueue.invoke(manager, target, 0, 0, false, false);
            awaitIdle(manager);
            assertEquals(1, sampled.get(), "abort before scanning the rest of the grid");
            assertEquals(0, colored.get(), "stale samples must never enter tint/decoration work");
            assertEquals(0, manager.readyCount());
            assertEquals(0, budget.usedBytes(), "aborted work must release its reservation");
            invalidated.set(false);
            enqueue.invoke(manager, target, 0, 0, false, false);
            awaitIdle(manager);
            assertEquals(2, sampled.get());
            PredictionCaptureRefresh refresh = field(manager, "captureRefresh");
            assertTrue(refresh.defer(target, System.nanoTime()),
                    "a cancelled build must not reset the capture burst before the next capture");
            enqueue.invoke(manager, target, 0, 0, false, false);
            awaitIdle(manager);
            assertEquals(1, manager.readyCount(), manager.surfaceDiagnostics());
            assertEquals(0, manager.failedTileCount());
            assertFalse(refresh.defer(target, System.nanoTime()), "only a current publication completes the burst");
        } finally {
            config.predictionDistanceBlocks = distance;
        }
        assertEquals(0, budget.usedBytes());
    }



    @Test void staleDecorationReleasesBeforeStartingAnyFeatureReplay() {
        var source = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE);
        var plants = new PredictionVegetation(source);
        assertThrows(CancellationException.class,
                () -> plants.tile(0, 0, 64, 1, true, (x, z) -> false, () -> false));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void waitingQueueReorderPreservesExecutingDetailButNavigationStillCancelsIt(boolean navigation) throws Exception {
        var current = new AtomicReference<PredictionTileManager>();
        var target = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
        var sampled = new AtomicInteger();
        var changed = new AtomicBoolean();
        var source = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                sampled.incrementAndGet();
                if (changed.compareAndSet(false, true)) {
                    var manager = current.get();
                    synchronized (manager) {
                        try {
                            PredictionCaptureStageTest.<Set<?>>field(manager, "voxyDetailTargets").remove(target);
                            PredictionCaptureStageTest.<Set<?>>field(manager, "desiredKeys").remove(target);
                            if (navigation) {
                                var abandon = PredictionTileManager.class.getDeclaredMethod("abandonOldViewWork");
                                abandon.setAccessible(true); abandon.invoke(manager);
                            }
                        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    }
                }
                return PredictionVoxelFixtures.sample(120);
            }
            @Override public ClientColumnSample sampleSurface(int x, int z) { return sample(x, z); }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
            @Override int surfaceColorForLod(int x, int y, int z, boolean preview) { return 0xff669944; }
            @Override int foliageColorForLod(int x, int y, int z, boolean preview) { return 0xff669944; }
        };
        var budget = new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, source, budget, null,
                new PredictionCpuBudget(() -> 8, System::nanoTime))) {
            current.set(manager);
            var unified = PredictionTileManager.class.getDeclaredField("unifiedVoxy");
            unified.setAccessible(true); unified.setBoolean(manager, true);
            PredictionCaptureStageTest.<Set<PredictionTileManager.PredictionTileKey>>field(manager, "voxyDetailTargets").add(target);
            PredictionCaptureStageTest.<Set<PredictionTileManager.PredictionTileKey>>field(manager, "desiredKeys").add(target);
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue",
                    PredictionTileManager.PredictionTileKey.class, int.class, int.class, boolean.class, boolean.class);
            enqueue.setAccessible(true); enqueue.invoke(manager, target, 0, 0, false, false);
            awaitIdle(manager);
            assertTrue(sampled.get() > 0, "the queue change must happen after sampling starts");
            assertEquals(navigation ? 0 : 1, manager.readyCount(), manager.surfaceDiagnostics());
            if (!navigation) assertEquals(manager.layout().cellAxis(0), manager.readyTiles().iterator().next().cellAxis(),
                    "an executing native detail request retains its full target after admission reorder");
            assertTrue(PredictionCaptureStageTest.<Set<?>>field(manager, "runningVoxyDetails").isEmpty(),
                    "execution pin releases on both completion and cancellation");
            assertEquals(0, manager.failedTileCount());
        }
        assertEquals(0, budget.usedBytes());
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true); return (T) field.get(owner);
    }

    private static void awaitIdle(PredictionTileManager manager) throws Exception {
        var executor = PredictionCaptureStageTest.<ThreadPoolExecutor>field(manager, "executor");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((manager.pendingCount() != 0 || executor.getActiveCount() != 0
                || !executor.getQueue().isEmpty()) && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics());
        assertEquals(0, executor.getActiveCount(), "completion handoffs must also finish");
    }
}
