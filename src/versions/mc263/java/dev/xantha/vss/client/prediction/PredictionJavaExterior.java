package dev.xantha.vss.client.prediction;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Exact partial volumes through 26.3's compiled density samplers and native aquifer. */
final class PredictionJavaExterior {
    private final RandomState random;
    private final NoiseGeneratorSettings settings;
    private final Aquifer.FluidPicker fluids;
    private final LongAdder builds = new LongAdder();
    PredictionJavaExterior(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        this.random = random; this.settings = generator.generatorSettings().value();
        try {
            var field = java.util.Arrays.stream(NoiseBasedChunkGenerator.class.getDeclaredFields())
                    .filter(f -> java.util.function.Supplier.class.isAssignableFrom(f.getType())).findFirst().orElseThrow();
            field.setAccessible(true);
            fluids = (Aquifer.FluidPicker) ((java.util.function.Supplier<?>) field.get(generator)).get();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Minecraft fluid picker",failure); }
    }
    boolean ordered() { return false; } // The new compiler makes cache axes explicit; no iterator-state replay.
    boolean reusesContexts() { return true; } // One sampler set serves the entire requested footprint.
    long contextBuilds() { return builds.sum(); }
    boolean[] sample(int x,int z,int step,int bottom,int top,BooleanSupplier valid) {
        if (top<=bottom) return null;
        if (step!=1 && step!=2 && step!=4) throw new IllegalArgumentException("exterior footprint step");
        check(valid); boolean[] occupied = new boolean[top-bottom]; builds.increment();
        DensityVolume volume = new DensityVolume(step,top-bottom,step,x,bottom,z);
        try (NoiseChunk chunk = new NoiseChunk(random,Beardifier.EMPTY,settings,fluids,Blender.empty(),volume)) {
            DensitySampler.Bound density = chunk.cachingSamplers().get(settings.noiseRouter().finalDensity());
            for (int y=top-1;y>=bottom;y--) {
                check(valid);
                for (int dz=0;dz<step && !occupied[y-bottom];dz++) for (int dx=0;dx<step;dx++) {
                    int xx=x+dx,zz=z+dz;
                    BlockState state = chunk.aquifer().computeSubstance(xx,y,zz,density.sampleValue(xx,y,zz));
                    boolean solid = !(state == null ? settings.defaultBlock() : state).isAir();
                    if (dx==0 && dz==0 && y==top-1 && !solid) return null;
                    if (solid) { occupied[y-bottom]=true; break; }
                }
            }
        }
        return occupied;
    }
    private static void check(BooleanSupplier valid) {
        if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new CancellationException();
    }
}
