package dev.xantha.vss.client.prediction;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Uses Minecraft's compiler, with all roots sharing one isolated cache per VSS worker. */
final class PredictionDensityContext implements AutoCloseable {
    private final RandomState random;
    private final AtomicBoolean retired = new AtomicBoolean();
    private final ConcurrentLinkedQueue<WeakReference<Worker>> retained = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Worker> workers = ThreadLocal.withInitial(() -> {
        var worker = new Worker();
        retained.add(new WeakReference<>(worker));
        return worker;
    });
    private int compiled;
    private static final class Worker { volatile SamplerContext context; }
    PredictionDensityContext(RandomState random) { this.random = random; }
    private SamplerContext context() {
        if (retired.get()) return SamplerContext.EMPTY_UNCACHED;
        Worker worker = workers.get();
        var context = worker.context;
        if (context == null) {
            context = SamplerContext.builder().enableCaches().build();
            worker.context = context;
            if (retired.get()) { worker.context = null; return SamplerContext.EMPTY_UNCACHED; }
        }
        return context;
    }
    DensitySampler.Bound bind(DensityFunction function) {
        DensitySampler delegate = random.getSampler(function);
        compiled++;
        return new DensitySampler() {
            @Override public float sampleValue(SamplerContext ignored, int x, int y, int z) {
                return delegate.sampleValue(context(), x, y, z);
            }
            @Override public void sampleVolume(SamplerContext ignored, DensityBuffer output, DensityVolume volume) {
                delegate.sampleVolume(context(), output, volume);
            }
        }.bind(SamplerContext.EMPTY_UNCACHED);
    }
    String diagnostics() { return "state=" + (retired.get() ? "retired" : "compiled")
            + ",compiler=minecraft-26.3,roots=" + compiled + ",workerCaches=isolated"; }
    @Override public void close() {
        retired.set(true);
        for (var reference : retained) { var worker = reference.get(); if (worker != null) worker.context = null; }
        retained.clear();
        workers.remove();
    }
}
