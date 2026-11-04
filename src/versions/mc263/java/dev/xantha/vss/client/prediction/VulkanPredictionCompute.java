package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/** Optional FP32 density offload. GPU completion is awaited only by prediction
 * builders. Queue admission is bounded; saturation immediately uses Rust. */
public final class VulkanPredictionCompute implements AutoCloseable {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { var thread = new Thread(task, "vss-vulkan-compute"); thread.setDaemon(true); return thread; });
    // Shader compilation can take tens of seconds on its first run. It must
    // never hold up a dispatch for another already-initialized world.
    private static final ThreadPoolExecutor INITIALIZER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> { var thread = new Thread(task, "vss-vulkan-init"); thread.setDaemon(true); return thread; });
    // At most eight callers wait for GPU work across all worlds. Other builders
    // continue in Rust; render/main threads never submit work here.
    private static final Semaphore REQUEST_SLOTS = new Semaphore(8);
    // Leave other builders on Rust while a large display request is running.
    // Four callers can populate both 512-column pipeline buffers, while other
    // builders keep running Rust. The global request bound is unchanged.
    private static final Semaphore DISPLAY_SLOTS = new Semaphore(4);
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private boolean draining;
    private final LongAdder submissions = new LongAdder(), queueNanos = new LongAdder(), fenceWaitNanos = new LongAdder();
    private final LongAccumulator largestBatch = new LongAccumulator(Long::max, 0);
    private final LongAccumulator largestInFlight = new LongAccumulator(Long::max, 0);
    private record Request(int[] xyz, int kind, CompletableFuture<int[]> completion, long queuedAt) {}
    private final long world;
    private final int bottom, top;
    private volatile boolean closed, initializing, failed;
    private volatile boolean previewUseful, displayValidated;
    private final LongAdder displayColumns = new LongAdder(), displaySkipped = new LongAdder();
    private final VulkanDisplayPolicy displayPolicy = new VulkanDisplayPolicy();
    private volatile VulkanDensityDevice device;
    private volatile String state = "disabled";
    private final LongAdder batches = new LongAdder(), columns = new LongAdder(), fallback = new LongAdder(), nanos = new LongAdder();
    private final ThreadLocal<ByteBuffer> heights = ThreadLocal.withInitial(() -> direct(64 * 4));
    private final ThreadLocal<ByteBuffer> maskPositions = ThreadLocal.withInitial(() -> direct(64 * 8));

    public static boolean supported() { return true; }
    VulkanPredictionCompute(long world, int bottom, int height) { this.world = world; this.bottom = bottom; this.top = bottom + height - 1; }

    private synchronized void prepare() {
        if (closed || failed || initializing || device != null || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
        initializing = true; state = "initializing";
        try {
            INITIALIZER.execute(() -> {
                VulkanDensityDevice created = null;
                try {
                    if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
                    var program = JsonParser.parseString(RustWorldgenBackend.vulkanProgram(world)).getAsJsonObject();
                    created = new VulkanDensityDevice(program);
                    validate(created);
                    if (!previewUseful && !displayValidated) {
                        synchronized (this) {
                            if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
                            failed = true; state = "cpu-faster";
                        }
                        VSSLogger.info("VSS Vulkan preview keeps Rust: CPU is faster for this density graph");
                        return;
                    }
                    String readyName = created.name;
                    synchronized (this) {
                        if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
                        device = created; state = "ready:" + created.name; created = null;
                    }
                    VSSLogger.info("VSS Vulkan density compute ready: " + readyName);
                } catch (RuntimeException | LinkageError failure) { reject(failure); }
                finally {
                    if (created != null) created.close();
                    initializing = false;
                }
            });
        } catch (RejectedExecutionException busy) { initializing = false; state = "waiting"; }
    }

    /** Runtime check covers negative and large coordinates, scalar interpolation
     * and both sides of cell boundaries before accepting GPU preview heights. */
    private void validate(VulkanDensityDevice candidate) {
        int[] xyz = new int[64 * 3];
        var input = direct(64 * 12); var expected = direct(64 * 8);
        for (int i = 0; i < 64; i++) {
            int x = switch (i % 4) { case 0 -> i * 71 - 600; case 1 -> -29_500_000 + i * 13; case 2 -> 29_500_000 - i * 29; default -> -i * 503; };
            int y = bottom + i * Math.max(1, top - bottom) / 63;
            int z = 311 * i - 8000;
            xyz[i * 3] = x; xyz[i * 3 + 1] = y; xyz[i * 3 + 2] = z;
            input.putInt(x).putInt(y).putInt(z);
        }
        if (RustWorldgenBackend.density(world, "final_density", input, expected, 64) != 64)
            throw new IllegalStateException("Incomplete Rust validation batch");
        int[] values = candidate.dispatch(xyz, 64, false, bottom, top);
        for (int i = 0; i < 64; i++) {
            float a = (float) expected.getDouble(i * 8), b = Float.intBitsToFloat(values[i]);
            if (!Float.isFinite(a) || !Float.isFinite(b) || Math.abs(a - b) > 0.000002f
                    || (a > 0) != (b > 0)) throw new IllegalStateException("Vulkan density validation mismatch at " + i + ": " + a + " / " + b);
        }
        // Compare complete preview records, including the downstream Rust stage.
        var xz = direct(64 * 8); var cpu = direct(64 * 40); var gpu = direct(64 * 40); var hs = direct(64 * 4);
        for (int i = 0; i < 64; i++) xz.putInt(xyz[i * 3]).putInt(xyz[i * 3 + 2]);
        long cpuTime = 0, gpuTime = 0;
        for (int round = 0; round < 4; round++) {
            if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
            long start = System.nanoTime();
            if (RustWorldgenBackend.previewPoints(world, xz, cpu, 64) != 64)
                throw new IllegalStateException("Incomplete Rust preview validation");
            long elapsed = System.nanoTime() - start;
            if (round > 0) cpuTime += elapsed;
            start = System.nanoTime();
            int[] result = candidate.dispatch(xyz, 64, true, bottom, top);
            hs.clear();
            for (int value : result) { if (value == Integer.MIN_VALUE) throw new IllegalStateException("GPU math requires CPU evaluation"); hs.putInt(value); }
            if (RustWorldgenBackend.previewPointsGpu(world, xz, gpu, 64, hs) != 64)
                throw new IllegalStateException("Incomplete GPU preview validation");
            elapsed = System.nanoTime() - start;
            if (round > 0) gpuTime += elapsed;
            for (int i = 0; i < 64 * 10; i++) if (cpu.getInt(i * 4) != gpu.getInt(i * 4))
                throw new IllegalStateException("Vulkan preview validation mismatch at record " + i / 10);
        }
        // Dispatch/copy overhead dominates simple graphs such as vanilla Nether.
        // Backend selection changes no sampling density or visual quality.
        previewUseful = gpuTime < cpuTime;
        // Cell volume semantics and every boundary must agree independently of
        // the display record cache, including empty columns and thin islands.
        for (int round = 0; round < 3; round++) {
            if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
            if (RustWorldgenBackend.displayDensityFloors(world, xz, hs, 64) != 64)
                throw new IllegalStateException("Incomplete Cell boundary validation");
            int[] result = candidate.dispatch(xyz, 64, 2, bottom, top);
            for (int i = 0; i < 64; i++) if (result[i] != hs.getInt(i * 4))
                throw new IllegalStateException("Vulkan display boundary mismatch at " + i + ": " + result[i] + " / " + hs.getInt(i * 4));
        }
        // Also validate the 256-column regular batches used by the sampler.
        // These are correctness oracles, never profitability measurements.
        for (int spacing : new int[]{1,2,4,8,16}) {
            if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
            int[] grid = new int[256 * 3];
            for (int i = 0; i < 256; i++) {
                grid[i*3] = -607+i%16*spacing;
                grid[i*3+2] = -809+i/16*spacing;
            }
            int[] result = candidate.dispatch(grid, 256, 2, bottom, top);
            for (int base = 0; base < 256; base += 64) {
                xz.clear();
                for (int i = base; i < base+64; i++) xz.putInt(grid[i*3]).putInt(grid[i*3+2]);
                if (RustWorldgenBackend.displayDensityFloors(world, xz, hs, 64) != 64)
                    throw new IllegalStateException("Incomplete regular display validation");
                for (int i = 0; i < 64; i++) if (result[base+i] != hs.getInt(i*4))
                    throw new IllegalStateException("Vulkan regular boundary mismatch at spacing " + spacing);
            }
        }
        // Correctness is independent of profitability. Dense CPU requests use
        // adaptive probes, interval proofs and downstream caches, so raw floor
        // timing cannot select the production display backend. The sampler
        // auditions complete cold regions, including queue/copy/material work.
        displayValidated = true;
    }

    /** -1 means use the existing Rust call. Caller-owned buffers are never
     * captured by the worker: positions/results are copied before submission. */
    int preview(ByteBuffer xz, ByteBuffer output, int count) {
        if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return -1;
        prepare();
        var active = device;
        if (active == null || !previewUseful || count < 16 || count > 64 || !REQUEST_SLOTS.tryAcquire()) { fallback.add(count); return -1; }
        int[] xyz = new int[count * 3];
        for (int i = 0; i < count; i++) { xyz[i * 3] = xz.getInt(i * 8); xyz[i * 3 + 2] = xz.getInt(i * 8 + 4); }
        int[] values = await(active, xyz, 1);
        if (values == null) { fallback.add(count); return -1; }
        var buffer = heights.get(); buffer.clear();
        for (int value : values) buffer.putInt(value);
        int written = RustWorldgenBackend.previewPointsGpu(world, xz, output, count, buffer);
        batches.increment(); columns.add(count); return written;
    }

    /** Four adjacent 8x8 windows per caller; only cold columns enter the queue.
     * Returns MIN_VALUE for cached/declined columns, which keep their CPU policy. */
    int[] displayHeights(int[] xz) {
        return displayHeights(xz,16);
    }
    boolean displayReady(int spacing) {
        if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return false;
        prepare();
        return device != null && displayValidated && spacing > 0;
    }
    VulkanDisplayPolicy.Trial beginDisplayRegion(int spacing, boolean fullRegion) {
        return displayPolicy.begin(spacing, fullRegion);
    }
    void finishDisplayRegion(VulkanDisplayPolicy.Trial trial, long elapsed, int coldColumns,
                             boolean attemptedGpu, boolean gpuResult, boolean complete) {
        displayPolicy.finish(trial, elapsed, coldColumns, attemptedGpu, gpuResult, complete);
    }
    /** Preflight only for the three CPU audition regions. It does not populate
     * the native display cache or alter the backend's normal CPU call shape. */
    int coldDisplayColumns(int[] xz) {
        int cold = 0;
        var positions = maskPositions.get();
        for (int base = 0; base < xz.length / 2; base += 64) {
            int size = Math.min(64, xz.length / 2 - base); positions.clear();
            for (int i = 0; i < size; i++) positions.putInt(xz[(base+i)*2]).putInt(xz[(base+i)*2+1]);
            cold += Long.bitCount(RustWorldgenBackend.displayGpuMask(world, positions, size));
        }
        return cold;
    }
    record DisplayHeights(int[] values, int coldColumns, boolean attempted) {}
    int[] displayHeights(int[] xz,int spacing) {
        return displayHeightsDetailed(xz, spacing).values();
    }
    DisplayHeights displayHeightsDetailed(int[] xz,int spacing) {
        return displayHeightsDetailed(xz,spacing,16);
    }
    DisplayHeights displayRegionHeights(int[] xz,int spacing) {
        return displayHeightsDetailed(xz,spacing,VulkanDisplayPolicy.MIN_COLD_COLUMNS);
    }
    private DisplayHeights displayHeightsDetailed(int[] xz,int spacing,int minimumColdColumns) {
        if (closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return new DisplayHeights(null, 0, false);
        prepare();
        var active = device;
        if (active == null || !displayValidated || spacing < 1 || xz.length == 0 || xz.length > 512 || (xz.length & 1) != 0) return new DisplayHeights(null, 0, false);
        int count = xz.length / 2, requested = 0;
        int[] xyz = new int[count * 3], indices = new int[count];
        var positions = maskPositions.get();
        for (int base = 0; base < count; base += 64) {
            int size = Math.min(64, count - base); positions.clear();
            for (int i = 0; i < size; i++) positions.putInt(xz[(base+i)*2]).putInt(xz[(base+i)*2+1]);
            long mask = RustWorldgenBackend.displayGpuMask(world, positions, size);
            for (int i = 0; i < size; i++) if ((mask & (1L << i)) != 0) {
                int index = base + i;
                xyz[requested*3] = xz[index*2]; xyz[requested*3+2] = xz[index*2+1];
                indices[requested++] = index;
            }
        }
        displaySkipped.add(count - requested);
        if (requested < minimumColdColumns || !DISPLAY_SLOTS.tryAcquire()) { fallback.add(requested); return new DisplayHeights(null, requested, false); }
        int[] values;
        try {
            if (!REQUEST_SLOTS.tryAcquire()) { fallback.add(requested); return new DisplayHeights(null, requested, false); }
            values = await(active, Arrays.copyOf(xyz, requested * 3), 2);
        } finally { DISPLAY_SLOTS.release(); }
        if (values == null) { fallback.add(requested); return new DisplayHeights(null, requested, true); }
        int[] result = new int[count]; Arrays.fill(result, Integer.MIN_VALUE);
        for (int i = 0; i < requested; i++) result[indices[i]] = values[i];
        batches.increment(); columns.add(requested); displayColumns.add(requested);
        return new DisplayHeights(result, requested, true);
    }

    /** Caller has acquired one request slot. Only builder threads wait here. */
    private int[] await(VulkanDensityDevice active, int[] xyz, int kind) {
        CompletableFuture<int[]> completion = new CompletableFuture<>();
        synchronized (this) {
            if (closed || device != active || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) {
                REQUEST_SLOTS.release(); return null;
            }
            pending.addLast(new Request(xyz, kind, completion, System.nanoTime()));
            scheduleDrain(active);
        }
        try {
            int[] values = completion.get();
            return closed || !VSSClientConfig.CONFIG.predictionVulkanAcceleration ? null : values;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new CancellationException("Prediction GPU wait interrupted");
        } catch (ExecutionException failure) { return null; }
    }
    /** Caller holds the instance lock. No artificial collection delay: requests
     * arriving during a dispatch form the next batch, in FIFO order. */
    private void scheduleDrain(VulkanDensityDevice active) {
        if (draining || pending.isEmpty()) return;
        draining = true;
        try { WORKER.execute(() -> drain(active)); }
        catch (RejectedExecutionException busy) { draining = false; cancelPending(); }
    }
    private void cancelPending() {
        while (!pending.isEmpty()) finish(pending.removeFirst(), null);
    }
    private static void finish(Request request, int[] values) {
        REQUEST_SLOTS.release();
        request.completion.complete(values);
    }
    private record Flight(java.util.List<Request> requests, VulkanDensityDevice.Submission submission, long started) { }

    private java.util.List<Request> takeBatch(VulkanDensityDevice active) {
        var group = new ArrayList<Request>(8);
        int count = 0;
        synchronized (this) {
            if (closed || device != active || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return group;
            while (!pending.isEmpty() && (group.isEmpty() || pending.peekFirst().kind == group.getFirst().kind)
                    && count + pending.peekFirst().xyz.length / 3 <= VulkanDensityDevice.MAX_COLUMNS) {
                var request = pending.removeFirst(); group.add(request); count += request.xyz.length / 3;
            }
        }
        return group;
    }

    private static void finishBatch(java.util.List<Request> group, int[] result) {
        int offset = 0;
        for (var request : group) {
            int size = request.xyz.length / 3;
            int[] values = result == null ? null : Arrays.copyOfRange(result, offset, offset + size);
            if (values != null) for (int value : values) if (value == Integer.MIN_VALUE) { values = null; break; }
            finish(request, values); offset += size;
        }
    }

    private void drain(VulkanDensityDevice active) {
        var flights = new ArrayDeque<Flight>(VulkanDensityDevice.PIPELINE_DEPTH);
        java.util.List<Request> submitting = null;
        boolean dispose = false;
        int completed = 0;
        try {
            while (true) {
                synchronized (this) {
                    if (closed || device != active || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) return;
                }
                // Fill independent buffers before waiting. A second dispatch can
                // execute while the worker copies/completes the first result.
                // Yield the shared worker after a bounded turn so another
                // world's dispatch or deferred device cleanup can run too.
                while (completed < 4 && active.hasFreeSlot()) {
                    submitting = takeBatch(active);
                    if (submitting.isEmpty()) { submitting = null; break; }
                    int count = submitting.stream().mapToInt(r -> r.xyz.length / 3).sum();
                    int[] joined = new int[count * 3]; int offset = 0;
                    for (var request : submitting) {
                        System.arraycopy(request.xyz, 0, joined, offset, request.xyz.length); offset += request.xyz.length;
                    }
                    long start = System.nanoTime();
                    for (var request : submitting) queueNanos.add(start - request.queuedAt);
                    var submission = active.submit(joined, count, submitting.getFirst().kind, bottom, top);
                    flights.addLast(new Flight(submitting, submission, start)); submitting = null;
                    submissions.increment(); largestBatch.accumulate(count); largestInFlight.accumulate(flights.size());
                }
                if (flights.isEmpty()) return;
                var flight = flights.peekFirst();
                long waitStart = System.nanoTime();
                int[] result = active.await(flight.submission);
                fenceWaitNanos.add(System.nanoTime() - waitStart);
                nanos.add(System.nanoTime() - flight.started);
                flights.removeFirst();
                finishBatch(flight.requests, result);
                completed++;
            }
        } catch (RuntimeException | LinkageError failure) {
            reject(failure);
            synchronized (this) {
                if (device == active) { device = null; dispose = true; }
            }
        } finally {
            if (submitting != null) finishBatch(submitting, null);
            for (var flight : flights) finishBatch(flight.requests, null);
            synchronized (this) {
                draining = false;
                if (closed || device != active || !VSSClientConfig.CONFIG.predictionVulkanAcceleration) cancelPending();
                else scheduleDrain(active);
            }
            // Caller permits are released before potentially slow driver cleanup.
            if (dispose) active.close();
        }
    }
    private void reject(Throwable failure) {
        if (closed) return;
        failed = true; state = "fallback:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        VSSLogger.warn("VSS Vulkan compute unavailable; prediction keeps Rust: " + failure);
    }
    java.util.Map<String,Object> performanceSnapshot() {
        return java.util.Map.ofEntries(java.util.Map.entry("state",state),
                java.util.Map.entry("enabled",VSSClientConfig.CONFIG.predictionVulkanAcceleration),
                java.util.Map.entry("previewUseful",previewUseful),java.util.Map.entry("displayValidated",displayValidated),
                java.util.Map.entry("displayUseful",displayPolicy.minimumGpuSpacing()!=Integer.MAX_VALUE),
                java.util.Map.entry("minDisplaySpacing",displayPolicy.minimumGpuSpacing()),
                java.util.Map.entry("displayRegionPolicy",displayPolicy.snapshot()),
                java.util.Map.entry("submissions",submissions.sum()),java.util.Map.entry("largestBatch",largestBatch.get()),
                java.util.Map.entry("pipelineDepth",VulkanDensityDevice.PIPELINE_DEPTH),
                java.util.Map.entry("largestInFlight",largestInFlight.get()),java.util.Map.entry("fenceWaitNanos",fenceWaitNanos.sum()),
                java.util.Map.entry("requests",batches.sum()),java.util.Map.entry("columns",columns.sum()),
                java.util.Map.entry("displayColumns",displayColumns.sum()),java.util.Map.entry("cacheSkippedColumns",displaySkipped.sum()),
                java.util.Map.entry("fallbackColumns",fallback.sum()),java.util.Map.entry("queueWaitNanos",queueNanos.sum()),
                java.util.Map.entry("dispatchNanos",nanos.sum()));
    }
    String diagnostics() { return "vulkan={state=" + state + ",enabled=" + VSSClientConfig.CONFIG.predictionVulkanAcceleration
            + ",previewUseful=" + previewUseful + ",displayValidated=" + displayValidated
            + ",minDisplaySpacing=" + displayPolicy.minimumGpuSpacing() + ",regionPolicy=" + displayPolicy.snapshot()
            + ",displayColumns=" + displayColumns.sum() + ",displaySkipped=" + displaySkipped.sum()
            + ",submissions=" + submissions.sum() + ",largestBatch=" + largestBatch.get()
            + ",pipelineDepth=" + VulkanDensityDevice.PIPELINE_DEPTH + ",largestInFlight=" + largestInFlight.get()
            + ",queueWaitMs=" + queueNanos.sum() / 1_000_000.0 + ",batches=" + batches.sum() + ",columns=" + columns.sum() + ",fallbackColumns=" + fallback.sum() + ",computeMs=" + nanos.sum() / 1_000_000.0 + "}"; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; state = "closed"; cancelPending();
        var active = device; device = null;
        if (active != null) {
            // Shutdown never waits for a GPU fence on the game thread.
            // Cleanup shares the worker, so it follows every in-flight dispatch.
            try { WORKER.execute(active::close); }
            catch (RejectedExecutionException busy) {
                CompletableFuture.runAsync(() -> {
                    try { WORKER.getQueue().put(active::close); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            }
        }
        heights.remove(); maskPositions.remove();
    }
    private static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
}
